import XCTest
@testable import DroidRelayCore

/// 메뉴바 속도 표기·이력·설정 (M-14).
///
/// 속도 표기는 **눈으로 확인할 수 없다** — 메뉴바에 "449.6 KB/s" 가 떠 있는 걸로
/// "이게 449.6 인지 449.9 인지" 알 수 없다. 그래서 규격을 전부 테스트로 묶는다.
final class SpeedFormatTests: XCTestCase {

    // ── 표기 규격 ──────────────────────────────────────────

    func test_0_과_음수는_대시로_간다() {
        XCTAssertEqual(SpeedFormat.text(0), "—")
        XCTAssertEqual(SpeedFormat.text(-1), "—")
        XCTAssertEqual(SpeedFormat.text(Int.max), String(format: "%.1f MB/s", Double(Int.max) / 1_048_576))
    }

    func test_단위_경계() {
        XCTAssertEqual(SpeedFormat.text(1023), "1023 B/s")
        XCTAssertEqual(SpeedFormat.text(1024), "1.0 KB/s")
        XCTAssertEqual(SpeedFormat.text(1_048_576), "1.0 MB/s")
    }

    /// **"1024.0 KB/s" 가 나오면 안 된다** — 정확히 1MiB 에서만 MB 로 올리면
    /// 1MiB-1 바이트가 "1024.0 KB/s" 로 표시된다. 경계를 512B 앞당겨 막았다.
    func test_1024KB_같은_표기가_나오지_않는다() {
        for b in [1_048_575, 1_048_500, 1_048_100] {
            XCTAssertNotEqual(SpeedFormat.text(b), "1024.0 KB/s", "\(b) B/s 에서 1024.0 KB/s 가 나왔다")
            XCTAssertTrue(SpeedFormat.text(b).hasSuffix("MB/s"), "\(b) → \(SpeedFormat.text(b))")
        }
    }

    /// 첨부 목업에 있던 값
    func test_목업_값() {
        XCTAssertEqual(SpeedFormat.text(460_390), "449.6 KB/s")
        XCTAssertEqual(SpeedFormat.text(9_626), "9.4 KB/s")
        XCTAssertEqual(SpeedFormat.text(7_340_032), "7.0 MB/s")
    }

    /// 표시 문자열을 B/s 로 되돌린다 — 단위까지 붙어 있어야 한다.
    private func toBytesPerSec(_ s: String) -> (value: Double, unitBytes: Double) {
        let parts = s.split(separator: " ")
        let n = Double(parts.first ?? "0") ?? 0
        guard parts.count >= 2 else { return (n, 1) }
        switch parts[1] {
        case "KB/s": return (n, 1024)
        case "MB/s": return (n, 1_048_576)
        case "GB/s": return (n, 1_073_741_824)
        default: return (n, 1)
        }
    }

    /// **값이 커질수록 표기가 작아지면 안 된다.**
    ///
    /// 숫자만 비교하면 역전을 잘못 잡는다 — `1020.8 KB/s` 다음에 `1.0 MB/s` 는
    /// **정상**(단위가 바뀐 것)인데 1.0 < 1020.8 이라 "역전" 으로 보인다.
    /// 그래서 표시를 다시 B/s 로 환산해 **절댓값** 으로 비교한다.
    func test_단위_역전이_없다() {
        var prevBps = 0.0
        for b in stride(from: 1, through: 20_000_000, by: 7_919) {
            let (shown, unit) = toBytesPerSec(SpeedFormat.text(b))
            let back = shown * unit
            // 반올림이므로 실제값을 최대 "표시 자릿값의 절반" 만큼 넘을 수 있다
            let slack = unit * 0.05
            XCTAssertGreaterThanOrEqual(back + slack, prevBps,
                "\(b) B/s 가 \(SpeedFormat.text(b)) 로 표시되며 값이 줄었다 (이전 \(prevBps))")
            XCTAssertLessThanOrEqual(back, Double(b) + slack,
                "\(b) B/s 가 \(SpeedFormat.text(b)) 로 표시되며 반올림 범위를 넘는다")
            prevBps = min(back, Double(b))
        }
    }

    /// 표기가 **반올림 오차 안에서** 원래 값을 복원할 수 있어야 한다 — 그래프 눈금이
    /// 축 라벨로 쓰이므로 이 성질이 있어야 축이 실제 값과 어긋나지 않는다.
    func test_표기가_원래_값을_복원한다() {
        for b in [1024, 460_390, 9_626, 1_048_576, 7_340_032, 1_073_741_824] {
            let s = SpeedFormat.text(b)
            let (n, unit) = toBytesPerSec(s)
            let diff = abs(n * unit - Double(b)) / Double(b)
            XCTAssertLessThan(diff, 0.01, "\(b) → \(s) 이 1% 넘게 어긋난다")
        }
    }

    /// **메뉴바 압축 표기** — 폭이 우선이라 소수점을 버린다.
    ///
    /// 단위는 유지한다: `450K` 와 `450` 는 사람이 읽을 때 전혀 다르다.
    func test_압축_표기() {
        XCTAssertEqual(SpeedFormat.compact(0), "—")
        XCTAssertEqual(SpeedFormat.compact(-1), "—")
        XCTAssertEqual(SpeedFormat.compact(512), "512")
        XCTAssertEqual(SpeedFormat.compact(1024), "1K")
        XCTAssertEqual(SpeedFormat.compact(460_390), "450K")
        XCTAssertEqual(SpeedFormat.compact(1_048_576), "1M")
        XCTAssertEqual(SpeedFormat.compact(1_073_741_824), "1G")
    }

    /// **압축해도 값이 뒤집히면 안 된다** — `1020K` 다음 `1M` 이 순서대로 와야 한다.
    /// 숫자만 비교하면(1020 vs 1) 역전으로 오판하므로 환산해서 비교한다.
    func test_압축_표기도_단위가_역전되지_않는다() {
        func bytes(_ s: String) -> Double {
            let n = Double(s.dropLast()) ?? 0
            switch s.last {
            case "K": return n * 1024
            case "M": return n * 1_048_576
            case "G": return n * 1_073_741_824
            default: return n
            }
        }
        var prev = 0.0
        for b in stride(from: 1, through: 40_000_000, by: 9_973) {
            let shown = SpeedFormat.compact(b)
            let back = bytes(shown)
            XCTAssertGreaterThanOrEqual(back, prev, "\(b) → \(shown) 로 값이 줄었다 (이전 \(prev))")
            prev = back
        }
    }

    /// **표시가 실제보다 클 수 있는 오차는 그 단위의 절반 이하여야 한다.**
    ///
    /// **B 단위는 예외다** — 1023B 를 "1K" 로 올리면 1024배 오차가 된다.
    /// 그래서 1024 **미만은 바이트로 그대로** 쓴다(테스트가 처음 이걸 잡았다).
    func test_압축_표기의_오차가_단위의_절반_이내() {
        for b in [1024, 1025, 1_047_000, 1_048_575, 1_048_576, 999_999_999, 1_073_741_824] {
            let s = SpeedFormat.compact(b)
            let n = Double(s.dropLast()) ?? 0
            let unit: Double = s.hasSuffix("K") ? 1024 : (s.hasSuffix("M") ? 1_048_576
                : (s.hasSuffix("G") ? 1_073_741_824 : 1))
            // 절댓값으로 비교한다. 부호를 나눠 비교하면 **아래쪽(내림) 오차와
            // 위쪽(올림) 오차의 허용치가 뒤집혀서** 정상적인 1.0K 같은 값이 실패한다.
            // (테스트를 두 번 잘못 썼다 — 두 번째가 이거였다)
            let err = abs(n * unit - Double(b))
            XCTAssertLessThanOrEqual(err, unit / 2, "\(b) → \(s) : 반올림이 단위의 절반을 넘었다")
        }
    }

    /// **1023B 를 "1K" 로 올리면 1024배 오차** — 이게 실제로 터졌던 케이스다.
    func test_1KiB_미만은_바이트로_그린다() {
        for b in [1, 512, 1023] {
            XCTAssertFalse(SpeedFormat.compact(b).hasSuffix("K"), "\(b)B 를 K 로 올렸다")
            XCTAssertEqual(SpeedFormat.compact(b), "\(b)")
        }
    }

    func test_눈금_형식은_자리수를_줄인다() {
        XCTAssertEqual(SpeedFormat.axis(1_073_741_824), "1.0 GB/s")
        XCTAssertEqual(SpeedFormat.axis(1_048_576), "1.0 MB/s")
        XCTAssertEqual(SpeedFormat.axis(1024 * 40), "40 KB/s")
        XCTAssertEqual(SpeedFormat.axis(0), "0")
    }

    // ── SpeedReading ───────────────────────────────────────

    func test_음수_읽기는_0_으로_떨어뜨린다() {
        let r = SpeedReading(downBps: -5, upBps: -1)
        XCTAssertEqual(r.downBps, 0)
        XCTAssertEqual(r.upBps, 0)
        XCTAssertTrue(r.isIdle)
    }

    // ── SpeedHistory ───────────────────────────────────────

    /// **첫 샘플은 속도가 아니다** — 과거가 없으므로 나누어 계산할 분모가 없다.
    /// 어길 그대로 읽으면 첫 프레임에 존재하지 않는 속도가 그려진다.
    func test_첫_샘플은_과거값을_그대로_반환한다() {
        let h = SpeedHistory()
        let r = SpeedReading(downBps: 1_000_000, upBps: 500_000)
        let out = h.push(r, now: Date(timeIntervalSince1970: 1000))
        XCTAssertEqual(out.downBps, 1_000_000)
        XCTAssertEqual(h.count, 1)
    }

    /// 직전 값과의 차이를 **실제 경과 시간**으로 나눈다.
    /// 폴링 주기가 일정하지 않으므로(5초 폴링 + 10초 백업 + SSE tick) 샘플 개수로 나누면
    /// 그래프가 왜곡된다.
    func test_경과_시간으로_나눈다() {
        let h = SpeedHistory()
        h.push(SpeedReading(downBps: 0, upBps: 0), now: Date(timeIntervalSince1970: 0))
        // 1초 뒤 2048 바이트 증가 → 2048 B/s
        let out = h.push(SpeedReading(downBps: 2048, upBps: 0), now: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(out.downBps, 2048)
    }

    func test_간격이_달라도_정확하다() {
        let h = SpeedHistory()
        h.push(SpeedReading(downBps: 0, upBps: 0), now: Date(timeIntervalSince1970: 0))
        // 10초 뒤 20480 증가 → 2048 B/s (샘플 개수가 아니라 시간으로 나눠야 한다)
        let out = h.push(SpeedReading(downBps: 20480, upBps: 0), now: Date(timeIntervalSince1970: 10))
        XCTAssertEqual(out.downBps, 2048)
    }

    /// **카운터 되감김을 방어한다** — 기기 재시작·인터페이스 변경으로 값이 줄면
    /// 음수 속도가 나간다. 방어하지 않으면 그래프가 바닥 아래로 뚫린다.
    func test_카운터_되감김_방어() {
        let h = SpeedHistory()
        h.push(SpeedReading(downBps: 5_000_000, upBps: 0), now: Date(timeIntervalSince1970: 0))
        let out = h.push(SpeedReading(downBps: 100, upBps: 0), now: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(out.downBps, 0, "카운터가 줄었는데 양(+) 속도가 나왔다")
    }

    /// 같은 시각(경과 0) — 0 으로 나눠야 한다. `dt > 0` 가드가 없으면 inf/NaN 이 나온다.
    func test_경과_0초는_0으로_나눈다() {
        let h = SpeedHistory()
        let t = Date(timeIntervalSince1970: 500)
        h.push(SpeedReading(downBps: 1000, upBps: 0), now: t)
        let out = h.push(SpeedReading(downBps: 99_000, upBps: 0), now: t)
        XCTAssertEqual(out.downBps, 0)
        XCTAssertFalse(SpeedFormat.text(out.downBps) == "—" ? false : true, "표기가 이상해지지 않아야 한다")
    }

    func test_용량_초과하면_오래된_것부터_버린다() {
        let h = SpeedHistory(capacity: 10)
        for i in 0..<50 {
            h.push(SpeedReading(downBps: i * 100, upBps: 0), now: Date(timeIntervalSince1970: Double(i)))
        }
        XCTAssertEqual(h.count, 10)
        XCTAssertEqual(h.series(true).last, 49 * 100, "가장 최근 값이 남아야 한다")
    }

    /// Y축이 무너지지 않도록 최소값이 있어야 한다 — 전부 0 이어도 축은 살아 있어야 한다.
    func test_축_최솟값이_0_이_아니다() {
        let h = SpeedHistory()
        h.push(SpeedReading(downBps: 0, upBps: 0))
        XCTAssertGreaterThan(h.peak(true), 0)
    }

    // ── DeviceTrafficRate (기기 누적 카운터 → 속도) ──────────

    private func t(_ rx: Int, _ tx: Int) -> DeviceTraffic { .init(rxTotal: rx, txTotal: tx) }

    /// **첫 샘플은 속도가 아니다** — 직전 값이 없으니 나눌 분모가 없다.
    /// 0 을 내는 게 정답이다. 값이 큰 첫 샘플을 그대로 속도로 쓰면 존재하지 않는
    /// 속도가 메뉴바에 뜬다.
    func test_첫_카운터는_속도가_아니다() {
        let r = DeviceTrafficRate.rate(previous: nil, current: t(16_000_000_000, 33_000_000_000),
                                       elapsed: 1)
        XCTAssertEqual(r.downBps, 0)
        XCTAssertEqual(r.upBps, 0)
    }

    /// 누적값의 **차이**를 경과 시간으로 나눈다 — 절대값이 아니라.
    func test_차이를_경과_시간으로_나눈다() {
        let prev = t(1_000_000, 500_000)
        let cur = t(1_030_400, 500_000)     // rx 가 30,400 증가
        // 30,400 / 2초 = 15,200 B/s
        let r = DeviceTrafficRate.rate(previous: prev, current: cur, elapsed: 2)
        XCTAssertEqual(r.downBps, 15_200)
        XCTAssertEqual(r.upBps, 0)
    }

    /// **간격이 달라도 같은 값이어야 한다** — 30,400 바이트가 흐르는 동안
    /// 1초로 재면 30,400, 4초로 재면 7,600. 실제 속도는 둘 다 같다.
    func test_간격이_달라도_실제_속도는_같다() {
        let prev = t(0, 0)
        let cur = t(40_000, 0)
        XCTAssertEqual(DeviceTrafficRate.rate(previous: prev, current: cur, elapsed: 1).downBps, 40_000)
        XCTAssertEqual(DeviceTrafficRate.rate(previous: prev, current: cur, elapsed: 4).downBps, 10_000)
    }

    /// **경과 0초는 0 으로 나눠야 한다** — `elapsed > 0` 가드가 없으면 `Double` 나눗셈이
    /// `inf`/`NaN` 이 되고, `Int(NaN)` 변환이 예외를 던지거나 임의값이 된다.
    ///
    /// 속도 0 은 "아무것도 흐르지 않음" 이고 `SpeedFormat` 은 그걸 `—` 로 보여준다.
    /// 그래서 표기가 `—` 인 **것이 정상** — NaN 이면 표기도 `—` 로 같아 보이지만
    /// `downBps` 가 0 이 아니므로 그것으로 구분한다.
    func test_경과_0초는_0이다() {
        let r = DeviceTrafficRate.rate(previous: t(0, 0), current: t(99_000, 0), elapsed: 0)
        XCTAssertEqual(r.downBps, 0, "0 이 아니면 NaN/inf 가 들어갔다")
        XCTAssertTrue(r.isIdle, "휴지 상태여야 한다")
    }

    /// **카운터 되감김을 방어한다** — 기기 재부팅·SIM 교체로 값이 줄면 음수 속도가 나온다.
    /// 방어하지 않으면 그래프가 바닥 아래로 뚫린다.
    /// (`SpeedHistory` 의 같은 이름 테스트와 구분하려고 접미어를 붙였다)
    func test_기기_카운터_되감김_방어() {
        let r = DeviceTrafficRate.rate(previous: t(50_000_000, 0), current: t(100, 0), elapsed: 1)
        XCTAssertEqual(r.downBps, 0, "카운터가 줄었는데 양(+) 속도가 나왔다")
    }

    /// 음수 카운터가 들어와도 0 으로 떨어뜨린다.
    func test_음수_카운터는_0으로_떨어뜨린다() {
        XCTAssertEqual(DeviceTraffic(rxTotal: -5, txTotal: -1).rxTotal, 0)
    }

    // ── 설정 ───────────────────────────────────────────────

    func test_출처_토글에_따라_열_개수가_달라진다() {
        XCTAssertEqual(SpeedDisplaySetting(showDroid: true, showDevice: true).sources.count, 2)
        XCTAssertEqual(SpeedDisplaySetting(showDroid: true, showDevice: false).sources, [.droid])
        XCTAssertEqual(SpeedDisplaySetting(showDroid: false, showDevice: true).sources, [.device])
        XCTAssertTrue(SpeedDisplaySetting(showDroid: false, showDevice: false).isOff)
    }

    /// **저장 안 한 사용자는 켜져 있어야 한다.** `bool(forKey:)` 는 키가 없으면 false 라서
    /// 그대로 쓰면 첫 실행부터 꺼진 상태가 된다 — 그래서 `object(forKey:)` 로 구분한다.
    func test_저장_안_했으면_기본값은_켜짐() {
        let d = UserDefaults(suiteName: "test.speed.\(UUID().uuidString)")!
        d.removePersistentDomain(forName: d.volatileDomainNames.first ?? "")
        let s = SpeedDisplaySetting.load(d)
        XCTAssertTrue(s.showDroid)
        XCTAssertTrue(s.showDevice)
    }

    func test_저장하고_다시_읽으면_같다() {
        let name = "test.speed.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        defer { d.removePersistentDomain(forName: name) }
        let s = SpeedDisplaySetting(showDroid: false, showDevice: true)
        s.save(d)
        let back = SpeedDisplaySetting.load(d)
        XCTAssertEqual(back, s)
    }

    /// 꺼진 출처는 다시 켜도 저장돼야 한다 — false 를 "키 없음"으로 오인하면 resurrect 된다.
    func test_꺼짐_상태가_저장된다() {
        let name = "test.speed.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        defer { d.removePersistentDomain(forName: name) }
        let s = SpeedDisplaySetting(showDroid: true, showDevice: false)
        s.save(d)
        XCTAssertFalse(SpeedDisplaySetting.load(d).showDevice)
    }
}
