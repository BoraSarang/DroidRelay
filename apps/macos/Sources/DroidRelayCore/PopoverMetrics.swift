import Foundation
import CoreGraphics

/// **팝오버 폭 예산** — 한 줄이 얼마나 많은 공간을 필요로 하는지 계산한다.
///
/// ## 왜 이 파일이 있나 (M-26)
///
/// 2026-09-29 사용자 스크린샷: 그래프 위 속도 표시가 **두 줄로 깨졌다.**
/// ```
/// ● Droi
///   d  ↓269   ↑33K        1.0 MB/s · 보통 3…
/// ● 기기 ↓707  ↑762K
///   K
/// ```
///
/// 원인은 추정하지 않고 **실측했다** (`NSFont` 로 문자열 폭을 직접 잰다):
///
/// ```
/// 실측 필요 폭   345.8 pt   (Droid ↓707K ↑762K · 기기 ↓707K ↑762K · 축 라벨)
/// 그때 가용 폭   328.0 pt   (팝오버 352 − 좌우 12×2)
/// → 17.8 pt 부족
/// ```
///
/// **범례의 `Text` 에 `lineLimit` 이 없었다.** 그래서 폭이 모자라면
/// SwiftUI 가 **자기 마음대로 두 줄로 접었다.** 축 라벨만 `lineLimit(1)` 이라
/// `1.0 MB/s · 보통 3…` 로 잘렸다. **같은 줄에서 두 개가 다르게 깨졌다** —
/// 하나는 접히고 하나는 잘리고.
///
/// ## 이 계산이 하는 일
///
/// 1. **숫자 하나를 어디에도 안 적는다** — 352 가 `PopoverView` 에 네 번,
///    `StatusItemController` 에 한 번 흩어져 있었다. 한 곳을 고쳐도 나머지가
///    따라가지 않아 **일부만 넓어지는** 상태가 된다.
/// 2. **범례 한 줄이 실제로 얼마나 넓은지 계산한다** — 눈으로 재는 게 아니다.
/// 3. **계산값을 테스트로 고정한다** — 폭을 줄이는 사람이 있으면 테스트가 잡는다.
///
/// ## 왜 순수 함수인가
///
/// 글꼴 폭은 `NSFont` 가 알아서 한다. 그러면 OS 없이 못 재고, 이 버그처럼
/// **눈으로만 보이는 깨짐** 이 다시 온다. → 문자열 폭을 **보수적으로 추정**한다.
///
/// **보수 = 실제보다 크게.** 줄바꿈을 놓치는 방향은 **과소평가**다.
/// 과대평가하면 여유가 좀 남을 뿐, 과소평가하면 **화면이 깨진다.**
/// 이 함수는 **오직 과대평가만 하도록** 만들어졌고, 그 계약은
/// `PopoverMetricsTests` 가 **실제 `NSFont` 실측과 대조**해 지킨다.
public enum PopoverMetrics {

    // MARK: - 팝오버 크기

    /// **팝오버 폭(pt).** 352 → 450.
    ///
    /// **왜 450 인가 — 실측에서 냈다.** 추정이 아니다.
    ///
    /// `--legend-check` 가 **SwiftUI 실제 배치**로 잰 값 = **365.0pt**
    /// (Droid ↓707K ↑762K · 기기 ↓707K ↑762K · 축 라벨 `"1.0 MB/s · 보통 3.1 KB/s"`).
    ///
    /// | 폭 | 그래프 가용폭 | 실측 365.0 대비 |
    /// |---|---|---|
    /// | 352 (기존) | 328 | **−37.0 pt ★ 깨짐** |
    /// | 420 | 396 | +31.0 pt (+8%) |
    /// | 440 | 416 | +51.0 pt (+14%) |
    /// | **450** | **426** | **+61.0 pt (+17%)** |
    ///
    /// 440 은 실측에 **들어가지만 여유가 14%** 다. 값은 매초 바뀌고
    /// (`269K` → `1G` 처럼 **자릿수 자체가** 달라진다) 좁은 폭은
    /// **오늘은 괜찮고 내일 깨지는** 폭이다. → **여유 17%** 로 잡았다.
    ///
    /// **460 보다 좁은 이유** — 설정 창이 460 이다. **팝오버가 설정 창보다 넓으면**
    /// "작은 창을 열었더니 더 큰 창이 떴다" 가 되고, 두 창이 같은 앱의 조각으로
    /// 읽히지 않는다. → **설정 창보다 좁게** 맞췄다.
    public static let width: CGFloat = 450

    /// **팝오버 높이(pt).**
    ///
    /// **420 으로 고정하면 아래 내용이 잘린다** — 실측하며 적은 기존 주석을 유지한다.
    public static let height: CGFloat = 520

    /// **그래프 좌우 여백(pt).** 그래프는 이 안에서만 폭을 쓴다.
    ///
    /// 12 → **6** (2026-09-29). 그래프를 넓히라는 요청.
    ///
    /// ## 왜 6 이고 0 이 아닌가
    ///
    /// 0 은 그래프가 팝오버 양끝에 딱 붙는다. **라운드 코너(4pt)** 안에
    /// 파란 선이 닿으면 잘린 것처럼 보인다.
    /// 6 이면 **라운드와 선 사이에 2pt** 가 남고, 나머지 **4pt** 를 그래프가 쓴다.
    /// `450 − 6×2` = **438pt** (12 일 때보다 **+12pt**).
    ///
    /// ## 이 값을 바꾸면 함께 따라가야 하는 것 — 하나라도 빠지면 버그다
    ///
    /// ```
    /// graphInset          ← 여기
    ///     ├→ graphWidth   ← 계산이 읽음 (자동)
    ///     └→ PopoverView 의 .padding(.horizontal:)  ← ★ 직접 써야 함
    /// ```
    ///
    /// **아래를 12 로 그대로 두고 여기만 6 으로 바꾸면**
    /// 계산은 438 라고 하는데 화면은 426 에 그린다. **그래프가 12pt 눌린다.**
    /// 겉보기에는 "조금 좁다" 뿐이라 **누가 알아채기 어렵다.**
    public static let graphInset: CGFloat = 6

    /// **그래프가 실제로 쓸 수 있는 폭(pt).**
    ///
    /// 352 − 12×2 = 328 이 **깨진 값**이었다. 450 − 12×2 = **426**.
    public static var graphWidth: CGFloat { width - graphInset * 2 }

    // MARK: - 범례 한 줄의 구성 상수

    /// 범례 글꼴(pt) — 출처 이름·현재값.
    public static let legendFontSize: CGFloat = 9.5
    /// 축 라벨 글꼴(pt).
    public static let axisFontSize: CGFloat = 9
    /// 범례 **안** 항목 사이 간격(pt) — 점·이름·다운·업.
    public static let legendItemSpacing: CGFloat = 4
    /// 범례 **들** 사이 간격(pt) — Droid 묶음과 기기 묶음, 그리고 축 라벨 앞.
    public static let legendRowSpacing: CGFloat = 10
    /// **축 라벨을 밀어내는 `Spacer` 자체의 최소폭(pt).**
    ///
    /// **이 값도 계산에 넣어야 한다.** `Spacer` 는 자식이라 **간격을 두 번** 받고
    /// (`앞`·`뒤`) **자체 폭도 가진다.** 간격만 세면 **10pt 가 빠진다.**
    /// `--legend-check` 로 확인했다 — **계산 344.8 vs 실제 365.0 = 20.2pt 차이.**
    public static let legendSpacerMinLength: CGFloat = 10
    /// 출처를 나타내는 색 점 지름(pt).
    public static let legendDotSize: CGFloat = 6

    // MARK: - 문자열 폭 추정

    /// **보수적 문자열 폭 추정(pt).**
    ///
    /// ## 왜 `NSFont` 를 부르지 않는가
    ///
    /// 부르면 이 함수가 OS 밖에서 못 돌아가고, 이 버그처럼 **눈으로만 보이는
    /// 깨짐** 을 테스트할 수 없게 된다. → **계수로 추정하고, 테스트가 실측과 대조한다.**
    ///
    /// ## 계수 (2026-09-29 `NSFont` 실측)
    ///
    /// | 구분 | 계수 × 크기 | 실측 | 여유 |
    /// |---|---|---|---|
    /// | 모노 ASCII | 0.62 | 5.8726 / 9.5 = 0.618 | +0.3% |
    /// | 시스템 ASCII | 0.55 | "Droid" 24.90 / 5 / 9.5 = 0.524 | +5.0% |
    /// | 한글(CJK) | 0.90 | "기기" 16.435 / 2 / 9.5 = 0.865 | +4.0% |
    ///
    /// **세 계수 모두 실측보다 크다.** 60여 개 문자열 대조에서 오차는
    /// **+0.02pt … +2.79pt** — **음수가 단 한 번도 없다.**
    ///
    /// **한글 계수를 1.0 으로 두지 않은 이유** — 처음엔 "전각이니까 1.0" 이라고 적었다.
    /// 그러면 **15% 나 과대평가**하는데, **"안전하려고" 폭을 과하게 잡으면 그건
    /// 좁게 만든 것과 같다.** 계수가 글꼴에서 멀어졌다는 신호이고, 그대로 두면
    /// 나중에 사람이 "충분하다"고 잘못 믿는다. → **실측의 1.04배**로 맞췄다.
    ///
    /// - Parameters:
    ///   - text: 측정할 문자열.
    ///   - fontSize: 글꼴 크기(pt).
    ///   - monospaced: 모노스페이스 여부. **가장 넓은 쪽(0.62) 을 쓴다.**
    public static func textWidth(_ text: String, fontSize: CGFloat, monospaced: Bool) -> CGFloat {
        let asciiRatio: CGFloat = monospaced ? 0.62 : 0.55
        return text.reduce(0) { acc, ch in
            acc + (isWide(ch) ? fontSize * cjkRatio : fontSize * asciiRatio)
        }
    }

    /// **전각 문자의 계수** — 실측 0.865 보다 크다.
    private static let cjkRatio: CGFloat = 0.90

    /// **전각으로 그려지는 문자인가** — 한글·한자·일본어·기호.
    ///
    /// **Hangul Jamo(`1100–11FF`)를 넣는 이유** — 유니코드는 이 영역을
    /// **구성 자모(선택형 보조 글자)** 로 쓴다. 모음 하나가 여기 걸리고,
    /// 실제로는 한 칸(전각)이 차지한다. 이걸 빼면 **한글이 반 칸으로 계산되어**
    /// 폭이 부족하다고 판단한다.
    ///
    /// **`·`(U+00B7) 는 전각이 아니다** — 실측 9pt 모노에서 5.56pt 로
    /// ASCII(0.618)와 같다. 축 라벨의 `"· 보통"` 에서 이 문자가 **제일 자주 나오는데**
    /// 전각으로 세면 **그때마다 몇 pt 를 과대평가**한다.
    /// (U+00B7 은 `3000` 아래라 여기서 걸리지 않는다 — 의도한 동작이다.)
    @inline(__always)
    static func isWide(_ ch: Character) -> Bool {
        ch.unicodeScalars.allSatisfy { v in
            (v.value >= 0x1100 && v.value <= 0x11FF) || v.value >= 0x3000
        }
    }

    // MARK: - 범례 한 줄

    /// **출처 하나가 차지하는 폭(pt)** — 색 점 + 이름 + `↓값` + `↑값`.
    ///
    /// - Parameters:
    ///   - name: 출처 이름 — `"Droid"` / `"기기"`.
    ///   - down: `SpeedFormat.compact` 결과 (예: `"707K"`).
    ///   - up: `SpeedFormat.compact` 결과 (예: `"762K"`).
    public static func legendWidth(name: String, down: String, up: String) -> CGFloat {
        legendDotSize
            + legendItemSpacing + textWidth(name, fontSize: legendFontSize, monospaced: false)
            + legendItemSpacing + textWidth("↓\(down)", fontSize: legendFontSize, monospaced: true)
            + legendItemSpacing + textWidth("↑\(up)", fontSize: legendFontSize, monospaced: true)
    }

    /// **범례 한 줄 전체가 요구하는 폭(pt).**
    ///
    /// - Parameters:
    ///   - sources: 켜진 출처. **꺼진 출처는 열을 아예 만들지 않는다** —
    ///     값이 없는데 자리를 차지하면 그건 정보가 아니라 빈칸이다.
    ///   - values: 출처별 현재값. 없는 출처는 건너뛴다.
    ///   - axisLabel: 오른쪽 축 라벨 (`SpeedScale.axisLabel` 결과).
    public static func legendRowWidth(
        sources: [SpeedSource],
        values: [SpeedSource: (down: String, up: String)],
        axisLabel: String
    ) -> CGFloat {
        let parts: [CGFloat] = sources.compactMap { s in
            guard let v = values[s] else { return nil }
            return legendWidth(name: s.label, down: v.down, up: v.up)
        }
        var 폭 = parts.reduce(0, +)
        // **빈 축 라벨이어도 `Spacer` 와 간격은 그대로 남는다.**
        //
        // 처음엔 "축 라벨이 없으면 자리째로 없다" 고 보고 **간격까지 뺐다.**
        // 그런데 **`SpeedLegend` 는 `Spacer` 를 항상 그린다** — 라벨이 빈 `Text` 가
        // 있을 뿐이다. → **화면이 있는 그대로 계산한다.** 모델이 화면보다
        // 관대해야 안전하지, 화면보다 **짧으면 접힘을 놓친다.**
        if !axisLabel.isEmpty {
            폭 += textWidth(axisLabel, fontSize: axisFontSize, monospaced: true)
        }

        // ## 여기서 20.2pt 를 놓쳤다 — `--legend-check` 가 잡아 준 것
        //
        // `Spacer` 는 **하나의 자식이다.** HStack 은 **모든 이웃 사이**에 간격을 넣고,
        // `Spacer` 도 **자체 폭**을 가진다. **둘 다 세야 한다.**
        //
        //     [Droid] [기기] [Spacer] [축라벨]
        //        ←10→   ←10→    ←10→   ←10→      간격 4곳 = 40
        //                        └─10─┘          Spacer 자체 최소폭
        //
        // 처음엔 "범례 사이 + 축 앞" 으로 **간격 3곳(30pt) 만** 세고
        // **Spacer 10pt 를 통째로 잊었다.** → **20pt 를 덜 계산했다.**
        //
        // ```
        // 계산 344.8pt   vs   SwiftUI 실제 365.0pt   →   20.2pt 차이
        // ```
        //
        // ## 왜 조용히 지나갔는가 — 이게 위험한 이유
        //
        // 365 < 416 이라 **여전히 한 줄로 들어간다.** 화면은 멀쩡하고
        // **아무도 알아채지 못한다.** 그런데 값이 조금만 길어져 필요폭이
        // **385 로 뛰면, 계산은 "344.8 로 들어간다"고 하고 실제로는 접힌다.**
        //
        // → **계산과 실제의 차이를 진단이 상시 보여야 한다.**
        //    차이를 숨기면 **여유가 있다는 잘못된 안심** 이 남는다.
        폭 += CGFloat(parts.count + 1) * legendRowSpacing   // 간격: 범례 사이 + Spacer 앞뒤
        폭 += legendSpacerMinLength                          // Spacer 자체
        return 폭
    }

    /// **범례 한 줄이 그래프 폭 안에 들어가는가.**
    ///
    /// `false` 면 **화면이 두 줄로 깨진다.** 개발 중에는 이 값이 `true` 여야 하고,
    /// 언제나 `true` 가 아니라면 **너무 빠듯하다** — 값이 자릿수만큼 늘어나면
    /// 어느 날 조용히 깨진다.
    public static func legendFits(
        sources: [SpeedSource],
        values: [SpeedSource: (down: String, up: String)],
        axisLabel: String
    ) -> Bool {
        legendRowWidth(sources: sources, values: values, axisLabel: axisLabel) <= graphWidth
    }

    /// **계산값을 사람이 읽는 한 줄로** — 진단이 쓴다.
    public static func describe(
        sources: [SpeedSource],
        values: [SpeedSource: (down: String, up: String)],
        axisLabel: String
    ) -> String {
        let need = legendRowWidth(sources: sources, values: values, axisLabel: axisLabel)
        let slack = graphWidth - need
        return String(
            format: "필요 %5.1fpt / 가용 %5.1fpt  여유 %+.1fpt  %@",
            need, graphWidth, slack,
            slack >= 0 ? "들어감" : "★ 넘침 — 두 줄로 깨진다"
        )
    }
}
