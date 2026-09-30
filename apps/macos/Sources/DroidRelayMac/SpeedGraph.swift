import SwiftUI
import DroidRelayCore

/// 메뉴바 속도 그래프 — 4계열(출처 2 × 방향 2).
///
/// ## 왜 공유 Y축인가
///
/// Droid 트래픽은 기기 트래픽의 **부분집합**이다. 축을 따로 잡으면 기기선이 항상 위고
/// Droid 선이 항상 아래라서 "둘이 얼마나 차이 나는지" 를 읽을 수 없다. 크기 비교가 목적이면
/// **같은 축**이어야 한다.
///
/// ## 왜 1초 샘플을 균등 재구성하는가
///
/// 폴링이 잠시 멈췄다가 복구되면 샘플 간격이 들쭉날쭉해진다. 그대로 x 좌표를 비율로
/// 놓으면 **과거가 오른쪽으로 몰리고** 그래프가 왜곡된다. 시간 축을 고정폭으로 다시
/// 세운다(`AppModel.graphSeries`).
struct SpeedGraph: View {
    let sources: [SpeedSource]
    let droidDown: [Int]
    let droidUp: [Int]
    let deviceDown: [Int]
    let deviceUp: [Int]
    /// **기기 축 라벨** — 최대값 + "평시" 를 붙인 문자열.
    ///
    /// ## 왜 주입하는가
    ///
    /// 축 라벨 계산은 **Core 의 순수 함수**(`SpeedScale`)이고, 여기에는 문자열만
    /// 넘긴다. 그래프가 계산을 하면 **표시가 규칙을 따르는지 알 방법이 없어진다.**
    let deviceAxisLabel: String

    /// Y축 최대값 — **전체 최대에 최소값**을 둔다. 전부 0 이어도 축이 무너지지 않게.
    private var peak: Int {
        let all = droidDown + droidUp + deviceDown + deviceUp
        return max(all.max() ?? 0, 1024)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // ── 범례 ──
            //
            // **별도 뷰로 뺐다** — 이 한 줄이 깨졌던 곳이고(M-26), **따로 떠 있어야
            // 높이를 재서 "접혔나" 를 판정할 수 있다.** 그래프까지 한 덩어리로 재면
            // `Canvas` 46pt 이 항상 섞여 **두 줄인지 한 줄인지 구분되지 않는다.**
            SpeedLegend(
                sources: sources,
                droid: (down: droidDown.last ?? 0, up: droidUp.last ?? 0),
                device: (down: deviceDown.last ?? 0, up: deviceUp.last ?? 0),
                axisLabel: sources.contains(.device) ? deviceAxisLabel : SpeedFormat.axis(peak)
            )

            // ── 그래프 ──
            Canvas { ctx, size in
                draw(&ctx, size, droidDown, SpeedColor.droid)
                draw(&ctx, size, deviceDown, SpeedColor.device)
                // 업은 얇게 — 다운이 주역이고, 업은 보조.
                draw(&ctx, size, droidUp, SpeedColor.droid.opacity(0.55), width: 1)
                draw(&ctx, size, deviceUp, SpeedColor.device.opacity(0.55), width: 1)
            }
            .frame(height: 46)
            .background(Color.primary.opacity(0.04))
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, _ values: [Int],
                      _ color: Color, width: CGFloat = 1.5) {
        guard values.count >= 2, size.width > 0, size.height > 0 else { return }
        let maxV = CGFloat(peak)
        // 0 이 바닥이 아니라 **바닥에서 살짝 위**여야 0 인 구간도 선으로 보인다.
        let h = size.height
        var path = Path()
        for (i, v) in values.enumerated() {
            let x = size.width * CGFloat(i) / CGFloat(values.count - 1)
            let ratio = CGFloat(min(max(v, 0), peak)) / maxV
            let y = h - ratio * (h - 1) - 0.5      // 아래 여백 1pt
            i == 0 ? path.move(to: CGPoint(x: x, y: y)) : path.addLine(to: CGPoint(x: x, y: y))
        }
        ctx.stroke(path, with: .color(color), lineWidth: width)
    }
}

/// **출처별 색 — 한 곳에만 있다.**
///
/// **Droid=주황(따뜻), 기기=파랑(차가움)** 으로 출처를 눈으로 구분한다.
///
/// ## 왜 이것만 두는가
///
/// 처음엔 `SpeedGraph` 와 범례에 **같은 색을 두 번** 적었다. 그래프의 선과
/// 범례의 점이 **다른 주황** 이 되면 **같은 출처인데 달라 보인다** — 그리고
/// 어느 쪽이 맞는 색인지 알 방법이 없다. → **한 곳에서 뽑아 쓴다.**
///
/// **하나만 골랐을 때의 손해는 그대로다** — 두 출처를 아무리 비슷하게 지어도
/// 색만으로 구분할 수는 없다. **이름이 같이 붙으므로 그건 이름이 하는 일**이고,
/// 색은 "어느 계열이 더 굵은가" 를 보조하는 것이다.
enum SpeedColor {
    static let droid = Color(red: 0.95, green: 0.55, blue: 0.15)
    static let device = Color(red: 0.20, green: 0.55, blue: 0.95)
}

/// **속도 범례 한 줄** — 출처별 현재값 + 축 참고치.
///
/// ## 왜 `SpeedGraph` 밖으로 뺐나 (M-26)
///
/// **이 한 줄이 두 줄로 접혔던 곳이다.** 그리고 그것을 확인하려면
/// **이 줄만 따로 재야 한다.** 그래프까지 한 덩어리로 재면
/// `Canvas` 의 46pt 이 항상 섞여 **"한 줄인지 두 줄인지" 가 구분되지 않는다.**
///
/// 실제로 이 진단을 처음 짰을 때 **그래프 전체를 재어서 62pt 가 나왔다.**
/// 62 = 범례 12 + 간격 4 + 그래프 46 이므로 **범례는 한 줄이었는데**
/// 높이를 `20pt 미만` 으로 재려다 **"깨진다" 고 잘못 판정했다.**
/// → **잘못된 게 진단이었다.** 판정 대상이 잘못돼 있었다.
///
/// ## 왜 값이 `Int` 로 넘어오나
///
/// `SpeedFormat.compact` 를 **여기서만 부른다.** 포맷을 두 군데서 부르면
/// **화면과 진단이 서로 다른 말을 하게 된다** — M-11 에서 이미 한 번 겪었다.
struct SpeedLegend: View {
    let sources: [SpeedSource]
    let droid: (down: Int, up: Int)
    let device: (down: Int, up: Int)
    let axisLabel: String

    var body: some View {
        HStack(spacing: PopoverMetrics.legendRowSpacing) {
            // **범례의 현재값도 필터된 값을 쓴다** — 원본을 쓰면 라벨만 튀어서
            // **같은 화면에서 축과 범례가 다른 말을 하게 된다.**
            if sources.contains(.droid) {
                item(SpeedSource.droid.label, SpeedColor.droid, down: droid.down, up: droid.up)
            }
            // **값이 없는 출처는 자리를 만들지 않는다** — 빈 열은 정보가 아니라 빈칸이다.
            if sources.contains(.device) {
                item(SpeedSource.device.label, SpeedColor.device, down: device.down, up: device.up)
            }
            Spacer(minLength: PopoverMetrics.legendSpacerMinLength)
            Text(axisLabel)
                .font(.system(size: PopoverMetrics.axisFontSize, design: .monospaced))
                .foregroundStyle(.tertiary)
                .lineLimit(1)
                // **줄바꿈이 아니라 잘림으로.** 이 텍스트가 **여백을 다 먹는 쪽**이다.
                //
                // 폭이 모자라면 SwiftUI 는 기본적으로 **접는다**(두 줄).
                // `lineLimit(1)` 이 없으면 "1.0 MB/s" / "· 보통 3.1 KB/s" 로
                // 갈라져 그래프가 위로 밀린다. (2026-09-29 실측)
                .truncationMode(.tail)
        }
        // **우선순위 수정은 필요 없다 — 이유가 있다.**
        //
        // HStack 이 모자란 폭을 나눌 때 **고정폭 뷰는 안 줄어들고, 줄 수를 줄일 수
        // 있는 뷰만 줄어든다.** 범례는 `fixedSize(horizontal: true)` 라 줄지 않고,
        // `Spacer` 는 0 까지 줄어들고, **축 라벨만 잘린다.**
        //
        // → 우선순위를 손질하면 **줄어들 수 있는 뷰가 늘어나버려**
        // "붙잡아야 할 하나" 가 사라진다. **구조로 정하고, 우선순위로 덮지 않는다.**
    }

    /// **출처 하나 — 색 점 + 이름 + 현재 다운/업.**
    ///
    /// ## 왜 각 글자에 `fixedSize` 인가 (M-26)
    ///
    /// 2026-09-29 실측. 폭이 모자라서 이렇게 접혔다:
    /// ```
    /// ● Droi
    ///   d  ↓269   ↑33K
    /// ```
    ///
    /// SwiftUI 의 `Text` 는 **기본적으로 여러 줄을 허용한다.** 폭이 모자라면
    /// **물어보지 않고 접는다** — `"Droid"` 라는 한 단어도 통째로 안 들어가면
    /// **글자 중간에서 자른다.** 그래서 고장 났는데 **어떤 경고도 없다.**
    ///
    /// → **`.fixedSize(horizontal: true, vertical: false)`** 로 **접히지 않게** 만든다.
    /// 그러면 폭이 모자라도 **범례는 접히지 않고**, 대신 **축 라벨이 잘린다.**
    /// **잘린 축 참고치와 접힌 속도값 중 어느 쪽이 덜 나쁜가** 를 고른 것이다.
    ///
    /// ## 이것만으로 충분하지 않은 이유
    ///
    /// **접지 않게 만드는 것은 안전장치이고, 근본은 폭이다.**
    /// 접히지 않아도 **잘린 글자**는 잘린 글자다. `PopoverMetrics` 가
    /// **실제로 들어가는지 계산하고**, `--legend-check` 가 **실제 배치로 잰다.**
    private func item(_ name: String, _ color: Color, down: Int, up: Int) -> some View {
        HStack(spacing: PopoverMetrics.legendItemSpacing) {
            Circle().fill(color)
                .frame(width: PopoverMetrics.legendDotSize, height: PopoverMetrics.legendDotSize)
            noWrap(name, monospaced: false, color: .secondary)
            noWrap("↓\(SpeedFormat.compact(down))", monospaced: true, color: color)
            noWrap("↑\(SpeedFormat.compact(up))", monospaced: true, color: color.opacity(0.7))
        }
    }

    /// **접히지 않는 글자** — `lineLimit(1)` + `fixedSize`.
    ///
    /// **둘을 같이 써야 한다.** `lineLimit(1)` 만 주면 **한 단어가 통째로 안 들어가면
    /// 그것까지 자른다**(위 스크린샷의 `Droi` / `d`). `fixedSize` 는 **늘어날 수는
    /// 있어도 줄어들지 않는다** — 배분받아야 할 폭이 줄어도 원래 크기를 유지한다.
    ///
    /// **글꼴 크기는 `PopoverMetrics` 의 값을 쓴다.** 여기서 9.5 를 따로 적으면
    /// **계산은 9.5 로 하고 화면은 다른 크기로 그린다** — 폭 예산이 거짓말을 하게 된다.
    private func noWrap(_ text: String, monospaced: Bool, color: Color) -> some View {
        Text(text)
            .font(.system(size: PopoverMetrics.legendFontSize,
                          design: monospaced ? .monospaced : .default))
            .foregroundStyle(color)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
    }
}
