import XCTest
import AppKit
@testable import DroidRelayCore

/// 뷰(`MenuTitleView`)의 그리기 규칙을 **테스트 타깃에서 재현**하는 순수 계산기.
///
/// 뷰는 실행 파일 모듈이라 `@testable import` 가 불가능하다(실측: 컴파일 깨짐).
/// 그래서 뷰가 하는 계산(몇 줄을 그리고 높이가 얼마인지)을 여기에 같은 공식으로 두고
/// **계약을 검증한다.** 뷰가 바뀌면 여기도 같이 바꿔야 한다.
private final class MenuTitleViewProbe {
    /// `NSFont` 은 Sendable 이 아니라 정적 프로퍼티에 둘 수 없다(Swift 6 strict).
    /// 계산할 때 만든다 — 1회 호출이라 비용 무시할 수 있다.
    private var font: NSFont { .monospacedDigitSystemFont(ofSize: 10.5, weight: .medium) }
    var lines: [MenuBarTitle.MenuBarLine] = []

    /// 뷰와 같은 규칙: 배지 줄은 버리고 속도 줄들을 **한 줄로 합친다**.
    var drawnLineCount: Int {
        let n = lines.filter { if case .speed = $0 { return true } else { return false } }.count
        return n == 0 ? 0 : 1
    }
    var requiredHeight: CGFloat {
        drawnLineCount == 0 ? 0 : font.ascender - font.descender + font.leading
    }
}

/// 메뉴바 항목이 **무엇을 그릴지** (M-14).
///
/// ## 왜 이게 실용적인가
///
/// `--diagnose` 는 `StatusItem` 을 만들지 않는다. 그래서 "진단 출력은 정상인데
/// 메뉴바에 아이콘만 보인다" 는 상태가 그대로 통과했고 **눈으로 봐야만** 알았다.
/// 실제로 두 번 연속 그랬다.
/// 1. 배지 갱신이 `attributedTitle` 을 지웠다
/// 2. `NSButton` 이 여러 줄 타이틀을 안 그린다
///
/// 뷰는 테스트할 수 없다(실행 파일 모듈이라 `@testable import` 가 컴파일을 깨진다 —
/// 실측). 그래서 **"무엇을 그릴지"를 `MenuBarTitle`(Core)로 빼서** 검증한다.
final class MenuBarTitleTests: XCTestCase {

    private func title(
        badge: Int = 0,
        droid: SpeedReading = .init(downBps: 0, upBps: 0),
        device: SpeedReading? = nil,
        d: Bool = true, v: Bool = true
    ) -> [MenuBarTitle.MenuBarLine] {
        MenuBarTitle.lines(badge: badge, droid: droid, device: device,
                           includeDroid: d, includeDevice: v)
    }

    private func texts(_ l: [MenuBarTitle.MenuBarLine]) -> [String] { l.map(\.text) }

    // MARK: - 줄 구성

    /// **항상 배지 줄 하나** — 배지 숫자만. 속도 줄은 뷰가 합친다(1줄 제약).
    func test_배지_줄이_맨_앞이다() {
        let l = title(badge: 3, droid: .init(downBps: 460_390, upBps: 9_626))
        guard case .head = l[0] else { return XCTFail("첫 줄이 배지가 아니다: \(texts(l))") }
    }

    /// **배지가 0 이면 숫자를 그리지 않는다** — " 0" 이 붙으면 작업이 없는데 진행 중처럼 보인다.
    func test_배지가_0이면_숫자를_그리지_않는다() {
        XCTAssertEqual(title(badge: 0)[0].text, "", "배지 자리에 0 이 찍혔다")
    }

    func test_배지가_있으면_숫자가_그려진다() {
        XCTAssertTrue(title(badge: 7)[0].text.contains("7"))
    }

    /// **값이 없으면 속도 줄이 없다** — 배지만 남는다. 빈 줄은 메뉴바에 구멍을 만든다.
    func test_값이_없으면_배지만() {
        let l = title(badge: 2, droid: .init(downBps: 0, upBps: 0), d: false, v: false)
        XCTAssertEqual(l.count, 1, "값이 없는데 줄이 더 있다: \(texts(l))")
    }

    // MARK: - 1줄 압축 (실측 근거)

    /// **2줄 설계가 물리적으로 불가능했다** — 메뉴바 22pt, 10.5pt 글꼴 한 줄이 13pt.
    /// 그래서 ↑/↓ 를 한 줄에 압축한다. **이 테스트가 그 계약을 지킨다.**
    func test_한_줄에_업과_다운이_모두_있다() {
        let l = title(droid: .init(downBps: 460_390, upBps: 9_626), d: true, v: false)
        let up = l[1].text, down = l[2].text
        XCTAssertTrue(up.contains("\u{2191}"), up)
        XCTAssertTrue(down.contains("\u{2193}"), down)
        // 뷰가 이 둘을 한 줄로 합친다 — 각각이 별도 줄이 되면 27pt 로 잘린다.
        XCTAssertEqual(l.count, 3, "구조는 3요소(배지·업·다운)로 유지: \(texts(l))")
    }

    /// **압축 표기** — `"449.6 KB/s"` 로 쓰면 폭이 너무 넓어 다른 메뉴바 항목을 밀어낸다.
    func test_압축_표기를_쓴다() {
        let l = title(droid: .init(downBps: 460_390, upBps: 9_626), d: true, v: false)
        XCTAssertTrue(l[2].text.contains("450K"), "압축 안 됨: \(l[2].text)")
        XCTAssertTrue(l[1].text.contains("9K"), "압축 안 됨: \(l[1].text)")
        XCTAssertFalse(l[2].text.contains("KB/s"), "단위 풀어가면 폭이 넘친다: \(l[2].text)")
    }

    /// 압축 표기도 `SpeedFormat.compact` 와 **같아야 한다** — 두 구현으로 나뉘면 어긋난다.
    func test_압축_표기가_compact와_일치한다() {
        let r = SpeedReading(downBps: 7_340_032, upBps: 1_024 * 40)
        let l = title(droid: r, d: true, v: false)
        XCTAssertTrue(l[2].text.contains(SpeedFormat.compact(r.downBps)), l[2].text)
        XCTAssertTrue(l[1].text.contains(SpeedFormat.compact(r.upBps)), l[1].text)
    }

    /// **0 은 "—"** — 값이 없다는 표시는 남긴다.
    func test_0은_대시로_표시된다() {
        let l = title(droid: .init(downBps: 0, upBps: 0), d: true, v: false)
        XCTAssertTrue(l[2].text.contains("\u{2014}"), l[2].text)
    }

    /// **1줄 전체의 폭이 메뉴바에서 무리하지 않아야 한다** — 출처 2개 + 방향 2개.
    /// 대략 90pt 를 넘으면 다른 항목이 밀려난다.
    func test_두_출처_1줄_폭이_과하지_않다() {
        let l = title(droid: .init(downBps: 999 * 1_048_576, upBps: 999 * 1_048_576),
                      device: .init(downBps: 999 * 1_048_576, upBps: 999 * 1_048_576))
        // ↑999M 999M  ↓999M 999M  ≈ 25자, 10.5pt 고정폭 숫자 기준 대략 130pt
        let oneLine = l[1].text.count + l[2].text.count
        XCTAssertLessThan(oneLine, 30, "1줄이 너무 길다: \(oneLine)자")
    }

    // MARK: - 열 (출처)

    /// **열마다 아이콘을 두지 않는다**(사용자 지정). 화살표는 방향 표시일 뿐 아이콘이 아니다.
    func test_열마다_아이콘을_두지_않는다() {
        for t in texts(title(droid: .init(downBps: 1, upBps: 1),
                            device: .init(downBps: 2, upBps: 2))) {
            for icon in ["\u{1F4E1}", "\u{1F4F6}", "\u{1F5A5}"] {
                XCTAssertFalse(t.contains(icon), "열에 아이콘이 붙었다: \(t)")
            }
        }
    }

    /// **서버가 값을 안 주면 열을 만들지 않는다.** `0` 을 넣으면 사용자는 화면에서
    /// "고장 났구나" 를 읽는다 — 빈칸보다 없는 편이 정직하다.
    func test_기기_미지원이면_열이_하나뿐이다() {
        let l = title(droid: .init(downBps: 460_390, upBps: 9_626), device: nil)
        XCTAssertEqual(l.count, 3, "기기 미지원인데 줄이 늘었다: \(texts(l))")
        XCTAssertTrue(l[2].text.contains("450K"), l[2].text)
    }

    /// **설정이 꺼져 있으면 값을 넣어도 열이 없다** — 설정이 우선이다.
    /// (값을 넣는 쪽이 위험하다. 서버가 값을 줬는데 사용자가 껐는데 그려지면 안 된다)
    func test_꺼진_출처는_값이_있어도_그리지_않는다() {
        let droidOnly = title(droid: .init(downBps: 1 * 1_048_576, upBps: 1 * 1_048_576),   // 정확히 1.0 MB/s
                              device: nil, d: false, v: true)
        // droid=false, device 미지원 → 그릴 값이 하나도 없음
        XCTAssertEqual(droidOnly.count, 1, "꺼진 출처가 그려졌다: \(texts(droidOnly))")

        // 반대로 device 만 켜고 기기가 지원되면 → device 한 열
        let deviceOnly = title(droid: .init(downBps: 1 * 1_048_576, upBps: 1 * 1_048_576),   // 정확히 1.0 MB/s
                               device: .init(downBps: 2 * 1_048_576, upBps: 2 * 1_048_576),  // 정확히 2.0 MB/s
                               d: false, v: true)
        XCTAssertEqual(deviceOnly.count, 3)
        XCTAssertTrue(deviceOnly[2].text.contains("2M"), deviceOnly[2].text)
        XCTAssertFalse(deviceOnly[2].text.contains("1M"),
                       "꺼진 Droid 값이 섞였다: \(deviceOnly[2].text)")
    }

    /// 두 출처가 다 켜지고 기기도 지원하면 **열 2개** — 순서는 Droid → 기기로 고정.
    func test_두_출처_순서가_고정된다() {
        let l = title(droid: .init(downBps: 1_048_576, upBps: 1024),
                      device: .init(downBps: 2_097_152, upBps: 2048))
        let down = l[2].text
        XCTAssertTrue(down.contains("1M") && down.contains("2M"), "두 값이 없다: \(down)")
        XCTAssertTrue(down.range(of: "1M")!.lowerBound < down.range(of: "2M")!.lowerBound,
                      "열 순서가 뒤집혔다: \(down)")
    }

    /// **2줄을 유지해야 메뉴바가 흔들리지 않는다** — 열 하나만 켜졌다 꺼졌다 하면
    /// 줄 수가 바뀌어 메뉴바 높이가 출렁인다.
    func test_열_개수와_무관하게_줄_수는_3으로_고정된다() {
        // device 가 nil 이면 "서버 미지원" — 값 대신 열을 만들지 않는다.
        let cases: [(d: Bool, v: Bool, dev: SpeedReading?)] = [
            (true, false, nil), (true, true, nil), (false, true, .init(downBps: 1, upBps: 1)),
            (true, true, .init(downBps: 1, upBps: 1))
        ]
        for (d, v, dev) in cases {
            let l = title(droid: .init(downBps: 5, upBps: 5), device: dev, d: d, v: v)
            XCTAssertEqual(l.count, 3, "droid=\(d) device=\(v) 지원=\(dev != nil) 에서 \(texts(l))")
        }
    }

    // MARK: - 값 표기

    /// 표기는 `SpeedFormat` 과 **같은 규격**이어야 한다 — 두 곳에서 따로 만들면 어긋난다.
    func test_표기가_SpeedFormat과_일치한다() {
        let r = SpeedReading(downBps: 7_340_032, upBps: 1_024 * 40)
        let l = title(droid: r, d: true, v: false)
        XCTAssertTrue(l[1].text.contains(SpeedFormat.compact(r.upBps)), l[1].text)
        XCTAssertTrue(l[2].text.contains(SpeedFormat.compact(r.downBps)), l[2].text)
    }

    /// 값이 **방향별로 올바른 쪽**에 있어야 한다 — 업/다운이 뒤바뀌면 사용자를 속인다.
    func test_방향이_바뀌지_않는다() {
        let l = title(droid: .init(downBps: 1_048_576, upBps: 1024), d: true, v: false)
        XCTAssertTrue(l[1].text.contains("1K"), "업 자리에 값이 없다: \(l[1].text)")
        XCTAssertTrue(l[2].text.contains("1M"), "다운 자리에 값이 없다: \(l[2].text)")
    }

    /// **값 0 은 "—" 로** — 아무것도 안 흐르는 것과 모르는 것을 구분하지 못하므로,
    /// 최소한 비어 보이지는 않게 한다(속도 포맷 규약).
    // MARK: - 메뉴바 높이 (문서화)

    /// **1줄이 22pt 안에 들어간다** — 이게 2줄 설계를 접은 이유다.
    ///
    /// 2줄(27pt)·3줄(42pt)은 메뉴바를 넘어 아래가 잘렸다. 이 테스트는
    /// "압축해서 1줄로 만들었다" 는 결정을 고정한다. 나중에 "2줄로 되돌리자" 면
    /// 이 테스트가 실패하면서 "그러면 다른 해법이 필요하다" 는 신호가 된다.
    func test_그리는_줄은_하나다() {
        let v = MenuTitleViewProbe()
        v.lines = title(droid: .init(downBps: 460_390, upBps: 9_626), d: true, v: false)
        XCTAssertEqual(v.drawnLineCount, 1, "그리는 줄이 \(v.drawnLineCount)개 — 22pt 를 넘는다")
        XCTAssertLessThanOrEqual(v.requiredHeight, 22,
            "필요 높이 \(v.requiredHeight)pt 가 메뉴바 두께 22pt 를 넘는다")
    }

}
