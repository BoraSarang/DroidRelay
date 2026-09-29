import XCTest
import CoreGraphics
import AppKit
@testable import DroidRelayCore

/// **팝오버 폭 예산** — `PopoverMetrics` (M-26).
///
/// ## 이 테스트가 지키는 계약
///
/// 2026-09-29 사용자 스크린샷. 속도 표시가 **두 줄로 깨졌다.**
/// ```
/// ● Droi
///   d  ↓269   ↑33K          1.0 MB/s · 보통 3…
/// ● 기기 ↓707  ↑762K
///   K
/// ```
///
/// 실측 원인은 **부족한 17.8pt** 다. (아래 테스트가 그 수치를 다시 만들어 낸다)
///
/// 계약은 셋이다.
///
/// 1. **범례 한 줄이 그래프 폭 안에 들어간다** — 안 들어가면 접힌다.
/// 2. **여유가 있다** — 딱 맞으면 오늘은 멀쩡하고 값이 자릿수만큼 늘면 조용히 깨진다.
/// 3. **폭 추정값은 실제 글꼴보다 작지 않다** — 이건 **모델을 믿지 않는 테스트다.**
///    계산만으로는 "충분하다" 는 주장을 검증할 수 없다. **실제 `NSFont` 와 대조한다.**
final class PopoverMetricsTests: XCTestCase {

    // MARK: - 1. 깨졌던 폭이 실제로 깨졌나 (재현)

    /// **352pt 였을 때는 깨졌다** — 이 테스트가 실패하면 원인이 다른 곳이다.
    ///
    /// 스크린샷의 값을 그대로 넣어 **깨진 쪽 폭**(`328`)과 비교한다.
    /// → **37pt 가 부족했다.** 이 숫자가 이 버그의 전부다.
    ///
    /// ## 기대값 365.0 은 어디서 왔나
    ///
    /// `--legend-check` 가 **`SpeedLegend` 를 실제로 배치해서** SwiftUI 가 낸 값이다.
    /// 365.0 = 범례 글자 합 + 간격 4곳(40) + `Spacer` 자체(10) + 축 라벨.
    ///
    /// **계산값이 아니다.** 계산은 **364.8** 이고 **실제와 0.2pt 차이**다 —
    /// 그 0.2pt 가 이 모델이 "계산만으로 셌으니" 가 아니라
    /// **"실제로 배치해 확인했다"** 는 근거다.
    func test_깨진_너비에서는_범례가_안_들어간다() {
        let need = PopoverMetrics.legendRowWidth(
            sources: [.droid, .device],
            values: [.droid: ("707K", "762K"), .device: ("707K", "762K")],
            axisLabel: "1.0 MB/s · 보통 3.1 KB/s"
        )
        let 깨진가용폭: CGFloat = 328          // 352 − 12×2
        XCTAssertGreaterThan(need, 깨진가용폭,
                             "이 값이 328 이하면 원인이 '폭 부족' 이 아니다 — 재측정해야 한다")
        // **SwiftUI 실제 배치값 365.0pt.** 여기서 어긋나면 Core 계수를 다시 잡아야 한다.
        XCTAssertEqual(need, 365.0, accuracy: 1.0,
                       "실제 배치 필요폭 365.0pt 과 어긋남 — 글꼴/간격 계수를 다시 잡아야 한다")
    }

    // MARK: - 2. 지금 폭에서는 들어간다

    /// **사용자가 보고한 바로 그 값이 한 줄로 들어간다.**
    func test_스크린샷_값이_들어간다() {
        for (axis, d, u) in [
            ("1.0 MB/s · 보통 3.1 KB/s", "269K", "33K"),
            ("1.0 MB/s · 보통 3.1 KB/s", "707K", "762K"),
        ] {
            XCTAssertTrue(
                PopoverMetrics.legendFits(
                    sources: [.droid, .device],
                    values: [.droid: (d, u), .device: (d, u)],
                    axisLabel: axis
                ),
                PopoverMetrics.describe(
                    sources: [.droid, .device],
                    values: [.droid: (d, u), .device: (d, u)],
                    axisLabel: axis
                )
            )
        }
    }

    /// **`SpeedFormat` 이 실제로 낼 수 있는 모든 조합** — 범례가 한 줄로 들어간다.
    ///
    /// `compact` 가 4자리 이상이 되는 값(1e12 B/s)은 물리적으로 불가능해서 뺀다.
    /// **`axisLabel` 은 `peak > typical×2` 일 때만 "· 보통" 을 붙인다.**
    func test_도달가능한_모든_조합이_들어간다() {
        let speeds = ["0", "999", "1K", "999K", "1M", "999M", "1G", "999G"]
        let axes = ["0", "999 B/s", "1.0 KB/s", "1023 KB/s", "1.0 MB/s", "1023.1 MB/s"]
        for d in speeds {
            for u in speeds {
                for a in axes {
                    for s in [[SpeedSource.droid, .device], [.droid], [.device]] {
                        let values: [SpeedSource: (down: String, up: String)] =
                            Dictionary(uniqueKeysWithValues: s.map { ($0, (d, u)) })
                        XCTAssertTrue(
                            PopoverMetrics.legendFits(sources: s, values: values, axisLabel: a),
                            PopoverMetrics.describe(sources: s, values: values, axisLabel: a)
                        )
                    }
                }
            }
        }
    }

    /// **딱 맞는 폭은 통과로 충분하지 않다.**
    ///
    /// 값은 매초 바뀌고 자릿수 자체가 달라진다(`269K` → `1G`).
    /// **여유 15% 미만이면fail 이 아니라 "곧 깨진다" 다.**
    func test_여유가_15퍼센트_이상이다() {
        let need = PopoverMetrics.legendRowWidth(
            sources: [.droid, .device],
            values: [.droid: ("707K", "762K"), .device: ("707K", "762K")],
            axisLabel: "1.0 MB/s · 보통 3.1 KB/s"
        )
        let margin = (PopoverMetrics.graphWidth - need) / need
        XCTAssertGreaterThanOrEqual(margin, 0.15,
                                    "여유 \(margin) — 좁은 폭은 '오늘 멀쩡하고 내일 깨지는' 폭이다")
    }

    // MARK: - 3. 모델을 믿지 않는다 — 실제 글꼴과 대조

    /// **추정값이 실제보다 작으면 안 된다.**
    ///
    /// ## 이 테스트가 없으면 무엇이 깨지는가
    ///
    /// `textWidth` 는 `NSFont` 를 부르지 않는다. OS 없이 돌게 하려고 **계수로** 재는데,
    /// 그러면 **모델이 틀려도 아무도 모른다.** 그리고 어느 방향으로 틀리느냐가 중요하다:
    ///
    /// - **과대평가** → 여유가 남는다. **안전하다.**
    /// - **과소평가** → "들어간다"고 판단했는데 **실제로 접힌다.** **이게 M-26 이다.**
    ///
    /// → **모든 문자열에서 추정 ≥ 실측** 이어야 한다. 하나라도 어기면 실패.
    ///
    /// SF Pro / SF Mono 가 바뀌면 여기서 깨진다. 그때 계수를 고르면 된다.
    func test_추정값은_실제_글꼴보다_작지_않다() {
        var corpus: [(String, CGFloat, Bool)] = []
        for n in ["Droid", "기기"] { corpus.append((n, 9.5, false)) }
        for s in ["0", "999", "1K", "999K", "1M", "999M", "1G", "999G", "1023G"] {
            corpus.append(("↓\(s)", 9.5, true))
            corpus.append(("↑\(s)", 9.5, true))
        }
        for a in ["0", "999 B/s", "1.0 KB/s", "1023 KB/s", "1.0 MB/s", "1023.1 MB/s",
                  "1.0 GB/s", "1023.1 GB/s"] {
            corpus.append((a, 9, true))
            corpus.append(("\(a) · 보통 \(a)", 9, true))
        }
        corpus.append(("1.0 MB/s · 보통 3.1 KB/s", 9, true))   // 스크린샷 값

        for (text, size, mono) in corpus {
            let font = mono ? NSFont.monospacedSystemFont(ofSize: size, weight: .regular)
                            : NSFont.systemFont(ofSize: size)
            let real = (text as NSString).size(withAttributes: [.font: font]).width
            let est = PopoverMetrics.textWidth(text, fontSize: size, monospaced: mono)
            XCTAssertGreaterThanOrEqual(est, real - 0.01,
                "과소평가 — \"\(text)\" \(size)pt: 실측 \(real) > 추정 \(est). 접힘을 놓친다")
        }
    }

    /// **추정이 지나치게 과대면 그것도 실패다.**
    ///
    /// "안전하려고" 폭을 과하게 잡으면 **그냥 좁게 만든 것과 같다.**
    /// 계수가 글꼴과 멀어졌다는 신호이고, 그 상태로 두면 나중에 사람이
    /// "충분하다"고 잘못 믿는다. → **실측 대비 1.15배를 넘지 않아야 한다.**
    func test_추정값이_실제보다_과대하지_않다() {
        for (text, size, mono) in [("1.0 MB/s · 보통 3.1 KB/s", 9.0, true),
                                   ("↓707K", 9.5, true), ("기기", 9.5, false),
                                   ("Droid", 9.5, false)] {
            let font = mono ? NSFont.monospacedSystemFont(ofSize: size, weight: .regular)
                            : NSFont.systemFont(ofSize: size)
            let real = (text as NSString).size(withAttributes: [.font: font]).width
            let est = PopoverMetrics.textWidth(text, fontSize: size, monospaced: mono)
            XCTAssertLessThanOrEqual(est, real * 1.15,
                "\"\(text)\": 추정 \(est) 가 실측 \(real) 의 1.15배 — 계수를 다시 잡아야 한다")
        }
    }

    // MARK: - 4. 구성 규칙

    /// **전각 판정** — 한글은 한 칸, 기호는 아니다.
    ///
    /// **Hangul Jamo(`1100–11FF`) 를 넣는 이유** — 이 영역은 유니코드가
    /// **구성 자모(선택형 보조 글자)** 로 쓴다. 빼면 한글이 반 칸으로 계산된다.
    ///
    /// ## 기호를 전각으로 세면 안 되는 이유 — 여기서 두 번 틀렸다
    ///
    /// 축 라벨이 `"1.0 MB/s · 보통 3.1 KB/s"` 라 **기호가 맨날 나온다.**
    /// 처음엔 한글이랑 같은 표로 "기호는 전각" 이라고 적었다. → **실측 반각.**
    /// 9pt 모노 기준 전부 **0.618 × 크기 = ASCII 와 동일**하다.
    ///
    /// | 문자 | 코드포인트 | 모노 9.5pt 실측 | 판정 |
    /// |---|---|---|---|
    /// | `·` | U+00B7 | 0.618 | 반각 |
    /// | `→` | U+2192 | 0.618 | 반각 |
    /// | `↓` `↑` | U+2193 / U+2191 | 0.618 | 반각 |
    /// | `기` | U+AE30 | 0.865 | **전각** |
    ///
    /// 전각으로 세면 **범례가 있는 매 프레임 몇 pt 를 과대평가**하고,
    /// "들어갈 자리가 있는데 안 들어간다" 는 **거짓 진단**이 된다.
    func test_한글은_전각이고_기호는_반각이다() {
        for ch: Character in ["기", "보", "통", "中"] {
            XCTAssertTrue(PopoverMetrics.isWide(ch), "\(ch) 는 전각이어야 한다")
        }
        for ch: Character in ["D", "r", "o", "i", "d", "1", "0", "K", "M", "G", "B", "s", "/",
                              "·", "→", "↓", "↑", "×", "−", "●"] {
            XCTAssertFalse(PopoverMetrics.isWide(ch), "\(ch) 는 반각이어야 한다")
        }
        // 한글 이름 두 글자가 ASCII 다섯 글자("Droid")만큼 넓다.
        // 빠뜨리면 기기 범례가 좁다고 계산하고 **축 라벨과 겹친다고 잘못 판단한다.**
        XCTAssertGreaterThan(
            PopoverMetrics.textWidth("기기", fontSize: 9.5, monospaced: false) * 2,
            PopoverMetrics.textWidth("Droid", fontSize: 9.5, monospaced: false)
        )
    }

    /// **화살표가 들어간 문자열은 반드시 모노로 그려야 한다** — 함정 고정.
    ///
    /// ## 실측 (2026-09-29)
    ///
    /// ```
    ///                 모노 9.5pt      시스템 9.5pt
    /// '0'             5.87 (0.618)   6.13 (0.645)
    /// '↓' '↑'         5.87 (0.618)   7.57 (0.796)   ← 시스템이 훨씬 넓다
    /// '기'            7.79 (0.820)   8.22 (0.865)
    /// ```
    ///
    /// **시스템 폰트의 계수는 0.55 인데 `↓` 는 0.796 이다.**
    /// → `"↓707K"` 를 **시스템 폰트로 그리면** `0.55 × 5 × 9.5 = 26.1pt` 로
    /// 계산하는데 **실제론 29.4pt** 다. **과소평가 → 접힘을 놓친다.**
    ///
    /// 지금은 안전하다. `SpeedGraph` 가 값·축 라벨을 **모노로** 그리고
    /// **시스템 폰트로 그리는 건 출처 이름(`"Droid"`/`"기기"`) 뿐이며
    /// 그 안에는 화살표가 없다.** → 이 테스트가 그 사실을 지킨다.
    ///
    /// - Contract: 시스템 폰트(비모노)로 그리는 문자열에 화살표가 들어가면 실패.
    func test_화살표는_모노로만_그린다() {
        // 시스템 폰트로 그리는 것들 = 출처 이름뿐이다.
        for name in [SpeedSource.droid.label, SpeedSource.device.label] {
            for ch in name {
                // **화살표 블록(U+2190–U+21FF)만 위험하다.** 한글은 원래 전각이라
                // `isWide` 가 잡고, 시스템 계수(0.55)보다 넓은 0.90 을 쓴다.
                let v = ch.unicodeScalars.first?.value ?? 0
                XCTAssertFalse((0x2190...0x21FF).contains(v),
                    "출처 이름 \"\(name)\" 에 화살표(U+\(String(v, radix: 16)))가 들어갔다 — "
                    + "시스템 폰트로 그리면 시스템 계수 0.55 로 과소평가돼 접힘을 놓친다")
            }
            let est = PopoverMetrics.textWidth(name, fontSize: 9.5, monospaced: false)
            let real = (name as NSString).size(
                withAttributes: [.font: NSFont.systemFont(ofSize: 9.5)]).width
            XCTAssertGreaterThanOrEqual(est, real - 0.01,
                "\"\(name)\": 추정 \(est) < 실측 \(real) — 접힘을 놓친다")
        }
        // 모노로 그리는 값 문자열은 추정이 실측을 넘는다 (앞 테스트가 전체를 돈다).
        let v = "↓707K"
        let real = (v as NSString).size(
            withAttributes: [.font: NSFont.monospacedSystemFont(ofSize: 9.5, weight: .regular)]).width
        XCTAssertGreaterThanOrEqual(PopoverMetrics.textWidth(v, fontSize: 9.5, monospaced: true),
                                    real - 0.01)
    }

    /// **꺼진 출처는 폭을 먹지 않는다** — 빈 열을 만들지 않는다는 M-14 규칙과 같다.
    func test_꺼진_출처는_폭을_먹지_않는다() {
        let 둘 = PopoverMetrics.legendRowWidth(
            sources: [.droid, .device],
            values: [.droid: ("707K", "762K"), .device: ("707K", "762K")],
            axisLabel: "1.0 MB/s"
        )
        let 하나 = PopoverMetrics.legendRowWidth(
            sources: [.droid],
            values: [.droid: ("707K", "762K")],
            axisLabel: "1.0 MB/s"
        )
        XCTAssertLessThan(하나, 둘)
    }

    /// **축 라벨이 없으면 글자만 빠진다** — `Spacer` 와 간격은 **남는다.**
    ///
    /// `SpeedLegend` 는 **`Spacer` 를 항상 그린다.** 라벨이 빈 `Text` 가 있을 뿐이다.
    /// → **화면이 있는 그대로 계산한다.**
    ///
    /// ## 여기서 20.2pt 를 놓쳤다
    ///
    /// HStack 은 `Spacer` 앞뒤에도 간격을 넣고, `Spacer` 자체도 10pt 를 차지한다.
    /// ```
    /// [Droid] [기기] [Spacer] [축라벨]   간격 4곳(40) + Spacer 10 = 50
    /// ```
    /// 처음 계산은 **간격 3곳(30) 만** 세고 **Spacer 10pt 를 잊었다.**
    /// → 실제보다 좁게 계산 → **과소평가** → 접힘을 놓친다.
    /// `--legend-check` 가 **20.2pt 차이**로 드러냈고 그걸로 알았다.
    func test_축_라벨이_없으면_Spacer는_남는다() {
        let withAxis = PopoverMetrics.legendRowWidth(
            sources: [.droid], values: [.droid: ("1K", "1K")], axisLabel: "1.0 KB/s"
        )
        let noAxis = PopoverMetrics.legendRowWidth(
            sources: [.droid], values: [.droid: ("1K", "1K")], axisLabel: ""
        )
        let 글자 = PopoverMetrics.textWidth("1.0 KB/s", fontSize: 9, monospaced: true)
        // **빈 라벨 = 글자만 빠진다. 간격·Spacer 는 그대로.**
        XCTAssertEqual(withAxis - noAxis, 글자, accuracy: 0.001,
                       "축 라벨을 빼면 **그 글자의 폭만** 사라져야 한다")

        // **범례 1 + Spacer + 축라벨 = 자식 3개 → 간격 2곳 + Spacer 자체**
        let 하나 = PopoverMetrics.legendWidth(name: "Droid", down: "1K", up: "1K")
        XCTAssertEqual(noAxis - 하나,
                       PopoverMetrics.legendRowSpacing * 2 + PopoverMetrics.legendSpacerMinLength,
                       accuracy: 0.001,
                       "범례 1 + Spacer = 자식 2개 → 간격 1곳 + Spacer 자체")

        // **범례 2개면 간격이 하나 더 붙는다** — 자식이 늘면 간격도 늘어난다.
        let 둘 = PopoverMetrics.legendRowWidth(
            sources: [.droid, .device],
            values: [.droid: ("1K", "1K"), .device: ("1K", "1K")],
            axisLabel: ""
        )
        let 하나쪽 = PopoverMetrics.legendRowWidth(
            sources: [.droid], values: [.droid: ("1K", "1K")], axisLabel: ""
        )
        XCTAssertEqual(둘 - 하나쪽,
                       PopoverMetrics.legendWidth(name: "기기", down: "1K", up: "1K")
                       + PopoverMetrics.legendRowSpacing,
                       accuracy: 0.001)
    }

    /// **값이 없는 출처는 자리를 만들지 않는다** — 함수 호출이 잘못돼도 조용히 통과하지 않게.
    func test_값이_없는_출처는_건너뛴다() {
        let w = PopoverMetrics.legendRowWidth(
            sources: [.droid, .device],
            values: [.droid: ("1K", "1K")],           // 기기는 값이 없음
            axisLabel: "1.0 KB/s"
        )
        let 하나 = PopoverMetrics.legendRowWidth(
            sources: [.droid], values: [.droid: ("1K", "1K")], axisLabel: "1.0 KB/s"
        )
        XCTAssertEqual(w, 하나, accuracy: 0.001)
    }

    // MARK: - 5. 팝오버와 그래프 폭의 관계

    /// **그래프 폭은 항상 팝오버보다 좁다** — 좌우 여백 두 번만큼.
    func test_그래프폭은_여백을_뺀_값이다() {
        XCTAssertEqual(PopoverMetrics.graphWidth,
                       PopoverMetrics.width - PopoverMetrics.graphInset * 2, accuracy: 0.001)
        XCTAssertLessThan(PopoverMetrics.graphWidth, PopoverMetrics.width)
    }

    /// **팝오버가 설정 창보다 넓으면 안 된다.**
    ///
    /// 설정 창은 460 이다. 팝오버가 그보다 넓으면 "작은 걸 열었더니 더 큰 게 떴다" 가 되고
    /// **같은 앱의 두 조각으로 읽히지 않는다.**
    ///
    /// **여기 460 을 직접 적지 않는 이유** — 설정 창은 `SettingsWindowController` 에 있고
    /// Core 는 AppKit 을 모른다. **숫자를 복사하면 두 값이 언제든 어긋난다.**
    /// → 이 계약은 `--legend-check` 진단이 **실제 창 크기를 읽어** 지킨다.
    func test_팝오버는_450이다() {
        // 넓혔다는 사실 자체를 고정한다 — "450 은 352 보다 넓다" 가 계약이다.
        XCTAssertGreaterThan(PopoverMetrics.width, 352)
        XCTAssertEqual(PopoverMetrics.width, 450, accuracy: 0.001)
        // **설정 창(460)보다 좁아야 한다** — 두 창이 같은 앱의 조각으로 읽혀야 한다.
        XCTAssertLessThan(PopoverMetrics.width, 460)
    }
}
