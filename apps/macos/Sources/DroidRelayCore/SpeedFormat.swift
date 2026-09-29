import Foundation

/// 메뉴바·그래프가 쓰는 속도 값과 표기 규격 (M-14).
///
/// **포맷을 여기서 한 곳에 모은 이유** — 목업 HTML·RelayConsole·웹 대시보드가 각자 다른
/// 포매터를 쓰면 값이 화면마다 다르게 보인다. 서버는 **바이트**를 주므로 단위 변환은
/// 클라이언트가 해야 하고, 그 변환이 세 군데로 흩어지면 곧 어긋난다.
///
/// **`ByteCountFormatter` 를 쓰지 않는 이유** — 그건 macOS 버전마다
/// `"2 MB"` / `"2MB"` / `"2.0 MB"` 를 오가서 **자기 테스트가 부서진다.**
/// (M3 에서 실제로 겪었다) 그래서 규격을 직접 구현하고 테스트로 묶는다.
public enum SpeedFormat {

    /// MB 경계를 512B 앞당긴 값. 정확히 1MiB 에서만 올리면 `1024.0 KB/s` 가 표시된다 —
    /// 나눗셈 경계에서 생기는 어색한 표기. (실측으로 확인)
    private static let mbEdge = 1_048_576.0 - 512

    public static func text(_ bytesPerSec: Int) -> String {
        let b = Double(bytesPerSec)
        guard b > 0, b.isFinite else { return "—" }
        if b >= mbEdge { return String(format: "%.1f MB/s", b / 1_048_576) }
        if b >= 1024 { return String(format: "%.1f KB/s", b / 1024) }
        return "\(Int(b.rounded())) B/s"
    }

    /// **메뉴바용 압축 표기.** 자릿수를 버려 폭을 줄인다.
    ///
    /// **필요한 이유** — 메뉴바 한 줄에 두 값(업/다운)을 넣어야 해서 폭이 부족하다.
    /// `"449.6 KB/s"` 처럼 쓰면 항목이 지나치게 넓어져 다른 메뉴바 항목을 밀어낸다.
    /// 단위를 유지하되 소수점을 없애면 정보는 유지되고 폭은 1/3 이다.
    public static func compact(_ bytesPerSec: Int) -> String {
        let b = Double(bytesPerSec)
        guard b > 0, b.isFinite else { return "\u{2014}" }
        // **G 경계도 필요하다.** M 만 두면 1TiB/s 는 "1024M" 가 되는데,
        // 이는 오차가 아니라 단위 선택 실패다 — 사람이 "1024M" 를 보고 큰 값이라
        // 느낄 수는 있어도 "1G" 가 더 정확하다. (테스트가 잡았다)
        if b >= 1_073_741_824 { return "\(Int((b / 1_073_741_824).rounded()))G" }
        if b >= 1_048_576 { return "\(Int((b / 1_048_576).rounded()))M" }
        if b >= 1024 { return "\(Int((b / 1024).rounded()))K" }
        return "\(Int(b.rounded()))"
    }

    /// 그래프 눈금용 — 자리수가 줄도록 단위를 크게 ("1.2 MB/s").
    public static func axis(_ bytesPerSec: Int) -> String {
        let b = Double(bytesPerSec)
        guard b > 0, b.isFinite else { return "0"
        }
        if b >= 1_073_741_824 { return String(format: "%.1f GB/s", b / 1_073_741_824) }
        if b >= 1_048_576 { return String(format: "%.1f MB/s", b / 1_048_576) }
        if b >= 1024 { return String(format: "%.0f KB/s", b / 1024) }
        return "\(Int(b.rounded())) B/s"
    }
}

/// 속도의 출처 — 메뉴바의 열 하나가 이거나다.
public enum SpeedSource: String, CaseIterable, Identifiable, Sendable {
    case droid   // 이 앱이 씀
    case device  // 폰 전체

    public var id: String { rawValue }
    public var label: String { self == .droid ? "Droid" : "기기" }
}

/// 기기(폰 전체) 트래픽 **누적 카운터**.
///
/// **속도가 아니라 누적값**이라는 점이 중요하다. 서버가 나눠 보내면 폴링 주기가
/// 흔들릴 때 표시가 출렁인다 — 클라이언트가 `SpeedHistory` 로 시간 차를 재야 한다.
public struct DeviceTraffic: Equatable, Sendable {
    public var rxTotal: Int
    public var txTotal: Int
    public init(rxTotal: Int, txTotal: Int) {
        self.rxTotal = max(0, rxTotal)
        self.txTotal = max(0, txTotal)
    }
}

/// (출처, 방향) 한 쌍 — 메뉴바의 4칸과 그래프의 4계열이 이 값을 쓴다.
public struct SpeedReading: Equatable, Sendable {
    public var downBps: Int
    public var upBps: Int
    public init(downBps: Int, upBps: Int) {
        self.downBps = max(0, downBps)
        self.upBps = max(0, upBps)
    }
    public var isIdle: Bool { downBps == 0 && upBps == 0 }
}

/// 시간대별 속도 이력 — 그래프용.
///
/// **상자에서 시간을 따로 관리하지 않는다** — 폴링 주기(5초)와 SSE tick 은 고정 간격이
/// 아니고, 백업 폴링(10초)만 있다. 그러면 샘플 간격이 들쭉날쭉해 그래프가
/// "빠른 구간/느린 구간" 처럼 왜곡되어 보인다. 그래서 직전 시각을 **직전 재고**
/// 그 간격으로 나눈다.
public final class SpeedHistory: @unchecked Sendable {
    private var samples: [SpeedReading] = []
    private var last: (t: Date, r: SpeedReading)?
    private let capacity: Int
    private let lock = NSLock()

    public init(capacity: Int = 300) {
        self.capacity = max(2, capacity)
    }

    /// 값을 넣는다. **반환값은 이번 샘플의 실측 속도** — 직전 값과의 차이를 실제 경과
    /// 시간으로 나눈 값이다. 호출자는 이 값을 쓰고, 저장은 엔진이 알아서 한다.
    @discardableResult
    public func push(_ reading: SpeedReading, now: Date = Date()) -> SpeedReading {
        lock.lock(); defer { lock.unlock() }
        defer {
            samples.append(reading)
            if samples.count > capacity { samples.removeFirst(samples.count - capacity) }
            last = (now, reading)
        }
        guard let prev = last else { return reading }   // 첫 샘플은 과거가 없다
        let dt = now.timeIntervalSince(prev.t)
        // 카운터 되감김(기기 재시작·인터페이스 변경)을 방어한다 — 음수 속도가 나오면 안 된다.
        guard dt > 0 else { return SpeedReading(downBps: 0, upBps: 0) }
        let dn = max(0, reading.downBps - prev.r.downBps)
        let up = max(0, reading.upBps - prev.r.upBps)
        return SpeedReading(downBps: Int(Double(dn) / dt), upBps: Int(Double(up) / dt))
    }

    // MARK: - 축 표시

    /// 방향 하나의 이력 — 그래프가 한 계열씩 그린다.
    public func series(_ down: Bool) -> [Int] {
        lock.lock(); defer { lock.unlock() }
        return samples.map { down ? $0.downBps : $0.upBps }
    }

    public var count: Int {
        lock.lock(); defer { lock.unlock() }
        return samples.count
    }
    public var isEmpty: Bool { count == 0 }
    public func clear() { lock.lock(); samples.removeAll(); last = nil; lock.unlock() }

    /// **그래프 Y축 최대값** — 원본 기준. 0 이면 축이 무너지므로 최소값을 건다.
    public func peak(_ down: Bool) -> Int {
        max(series(down).max() ?? 0, 1024)
    }

    /// **"평소 수준" 의 대표값** — 축 라벨에 참고로 병기한다.
    ///
    /// ## 왜 필요한가 — 2026-09-29 실측
    ///
    /// 기기(폰 전체) 카운터는 **주기적으로 보충**된다(지연 정산). 실측:
    /// ```
    /// 정상 구간   1 ~ 5 KB/s
    /// 보충 구간   2 ~ 5 MB/s     ← 1000배 튀었다가 바로 내려옴
    /// ```
    /// 원인은 **앱에서 제거할 수 없다.** 세 가지를 시도하고 실측했다:
    ///
    /// 1. `NetworkStatsManager` — `SecurityException: Network stats history of uid -1
    ///    is forbidden` (시스템 권한 필요, 일반 앱 불가)
    /// 2. `/proc/net/dev` · `/sys/class/net/...` — SELinux denied
    /// 3. 서버가 1초마다 직접 폴링 — **정산은 네트워크 계층이라 무의미**(0.05초로도
    ///    5,002,000 B/s 가 그대로 전달됨)
    ///
    /// → **값을 지워서 대신 고치는 쪽이 아니다.** 실제 전송량(3초짜리 다운로드 포함)을
    /// 지우면 **더 나쁘다.** 원본을 그리고 **축 라벨에 "평소 이만큼" 을 함께 보여준다.**
    ///
    /// ## 왜 중앙값인가
    ///
    /// 평균은 스파이크 하나에 **함께 끌려간다.** 중앙값은 소수 구간이 스파이크에
    /// 휘둘리지 않는다.
    ///
    /// - Returns: 평소 속도. 표본이 없으면 `nil` — **값이 없는데 지어내지 않는다.**
    public func typical(_ down: Bool) -> Int? {
        SpeedScale.typical(series(down))
    }
}

/// **축 라벨 계산** — 순수 함수라서 OS 없이 모든 경우를 검증할 수 있다.
public enum SpeedScale {

    /// **중앙값.** 정렬이 필요하고 표본이 없으면 `nil`.
    public static func median(_ values: [Int]) -> Int? {
        guard !values.isEmpty else { return nil }
        let s = values.sorted()
        let m = s.count / 2
        return s.count % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2
    }

    /// **"평소 수준" 의 대표값** — 중앙값.
    ///
    /// **값을 지우지 않는다** — 이건 **보조 수치**고, 그래프는 원본을 그대로 그린다.
    /// 지우는 필터와 본질이 다르다.
    public static func typical(_ values: [Int]) -> Int? {
        median(values)
    }

    /// **축 라벨** — 최대값과 평소값을 함께 보여준다.
    ///
    /// ```
    /// 스파이크 없음 : "1.2 MB/s"
    /// 스파이크 있음 : "5.3 MB/s · 보통 17 KB/s"
    /// ```
    ///
    /// ## 왜 둘을 함께 보여주는가
    ///
    /// 축 하나만 있으면 **사용자가 "내 트래픽이 5.3 MB/s 다" 고 읽는다.**
    /// 실제로는 평소 17 KB/s 이고 5.3 MB/s 는 **정산 묶음**이다. 둘을 붙여서 보여주면
    /// **"지금 급증한 것" 과 "평소" 를 한눈에 구분**할 수 있다.
    ///
    /// **숨기지 않는다** — 스파이크가 있었다는 사실을 지우면, 사용자는
    /// **그래프가 뭔가 고장 난 걸로** 읽는다.
    ///
    /// - Parameters:
    ///   - peak: 축 최대값(원본).
    ///   - typical: 평소 대표값. `nil` 이면 표본이 없다.
    /// - Returns: 표시 문자열. **빈 문자열이 되면 안 된다.**
    public static func axisLabel(peak: Int, typical typicalBps: Int?) -> String {
        let top = SpeedFormat.axis(peak)
        guard let t = typicalBps, t > 0 else { return top }
        // **축과 평소가 비슷하면 둘을 붙일 필요가 없다** — "1.2 MB/s · 보통 1.1 MB/s" 는
        // **같은 말을 두 번 하는 것**이고, 읽는 사람이 더 좋은 답을 얻지 못한다.
        guard peak > t * 2 else { return top }
        return "\(top) · 보통 \(SpeedFormat.axis(t))"
    }
}
/// 누적 카운터 → 속도. **클라이언트가 직접 나눠야 한다.**
///
/// ## 왜 서버가 나눠서 안 보내는가
///
/// 서버가 초당 속도로 보내면 **폴링 주기가 흔들릴 때 표시가 출렁인다.** 같은 트래픽이라도
/// 1초 간격으로 물으면 A 라고, 5초 간격으로 물으면 B 라고 나온다. 무엇이 맞는 답인지
/// 정할 수 없다.
///
/// 누적값을 주고 **여기서** 나누면, 간격이 얼마든 실측 속도와 일치한다. 간격이 0 이면
/// 0 으로 나눠야 한다(0 나눗셈).
public enum DeviceTrafficRate {
    /// 직전 값과 현재 값의 차이를 경과 시간으로 나눈다.
    public static func rate(
        previous: DeviceTraffic?,
        current: DeviceTraffic,
        elapsed: TimeInterval
    ) -> SpeedReading {
        guard let prev = previous, elapsed > 0 else {
            // **첫 샘플은 속도가 아니다** — 나눌 분모가 없다. 존재하지 않는 속도를
            // 그려내지 않고 0 을 낸다.
            return SpeedReading(downBps: 0, upBps: 0)
        }
        // **카운터 되감김을 방어한다** — 기기 재부팅·SIM 교체로 값이 줄면 음수 속도가
        // 나온다. 방어하지 않으면 그래프가 바닥 아래로 뚫린다.
        let dn = max(0, current.rxTotal - prev.rxTotal)
        let up = max(0, current.txTotal - prev.txTotal)
        return SpeedReading(downBps: Int(Double(dn) / elapsed), upBps: Int(Double(up) / elapsed))
    }
}
/// 메뉴바에 무엇을 보여줄지 — `UserDefaults` 에 저장되는 설정.
public struct SpeedDisplaySetting: Equatable, Sendable {
    public var showDroid: Bool
    public var showDevice: Bool

    public static let `default` = SpeedDisplaySetting(showDroid: true, showDevice: true)

    public init(showDroid: Bool, showDevice: Bool) {
        self.showDroid = showDroid
        self.showDevice = showDevice
    }

    /// 켜진 출처 — 메뉴바의 열 개수이자 그래프의 계열 개수다.
    public var sources: [SpeedSource] {
        SpeedSource.allCases.filter { self[$0] }
    }
    public var isOff: Bool { sources.isEmpty }

    private subscript(_ s: SpeedSource) -> Bool {
        s == .droid ? showDroid : showDevice
    }

    // MARK: - 영속화

    private enum Key {
        static let droid = "speed.showDroid"
        static let device = "speed.showDevice"
    }

    public static func load(_ defaults: UserDefaults = .standard) -> SpeedDisplaySetting {
        // 키가 없으면 기본값. `bool(forKey:)` 는 "없으면 false" 라서
        // 저장소를 안 써본 사용자까지 꺼진 상태가 된다 — 그래서 object(forKey:) 로 구분한다.
        let d = defaults.object(forKey: Key.droid) == nil ? true : defaults.bool(forKey: Key.droid)
        let v = defaults.object(forKey: Key.device) == nil ? true : defaults.bool(forKey: Key.device)
        return SpeedDisplaySetting(showDroid: d, showDevice: v)
    }

    public func save(_ defaults: UserDefaults = .standard) {
        defaults.set(showDroid, forKey: Key.droid)
        defaults.set(showDevice, forKey: Key.device)
    }
}
