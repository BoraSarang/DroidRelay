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

    /// 계열별 색 — **Droid=주황(따뜻), 기기=파랑(차가움)** 으로 출처를 눈으로 구분한다.
    private static let droidColor = Color(red: 0.95, green: 0.55, blue: 0.15)
    private static let deviceColor = Color(red: 0.20, green: 0.55, blue: 0.95)

    /// Y축 최대값 — **전체 최대에 최소값**을 둔다. 전부 0 이어도 축이 무너지지 않게.
    private var peak: Int {
        let all = droidDown + droidUp + deviceDown + deviceUp
        return max(all.max() ?? 0, 1024)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // ── 범례 ──
            //
            // **범례의 현재값도 필터된 값을 쓴다** — `series.last` 는 원본이므로
            // 스파이크가 걸린 프레임이면 라벨이 "5M" 로 뜬다. 축은 걸러진 값으로
            // 잡혔는데 라벨만 튀면 **같은 화면에서 두 개가 다른 말을 한다.**
            HStack(spacing: 10) {
                if sources.contains(.droid) {
                    legend("Droid", Self.droidColor, down: droidDown.last ?? 0, up: droidUp.last ?? 0)
                }
                if sources.contains(.device) {
                    legend("기기", Self.deviceColor, down: deviceDown.last ?? 0, up: deviceUp.last ?? 0)
                }
                Spacer()
                Text(SpeedFormat.axis(peak))
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(.tertiary)
            }

            // ── 그래프 ──
            Canvas { ctx, size in
                draw(&ctx, size, droidDown, Self.droidColor)
                draw(&ctx, size, deviceDown, Self.deviceColor)
                // 업은 얇게 — 다운이 주역이고, 업은 보조.
                draw(&ctx, size, droidUp, Self.droidColor.opacity(0.55), width: 1)
                draw(&ctx, size, deviceUp, Self.deviceColor.opacity(0.55), width: 1)
            }
            .frame(height: 46)
            .background(Color.primary.opacity(0.04))
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private func legend(_ name: String, _ color: Color, down: Int, up: Int) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(name).font(.system(size: 9.5)).foregroundStyle(.secondary)
            Text("↓\(SpeedFormat.compact(down))")
                .font(.system(size: 9.5, design: .monospaced)).foregroundStyle(color)
            Text("↑\(SpeedFormat.compact(up))")
                .font(.system(size: 9.5, design: .monospaced)).foregroundStyle(color.opacity(0.7))
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
