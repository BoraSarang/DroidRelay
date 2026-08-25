import SwiftUI

/// 디버그 로그 창 — 순환 버퍼 표시 + 전체 복사 (Cmd+Shift+D 대체 진입: 팝오버 하단 '로그')
struct LogsView: View {
    @Environment(AppState.self) private var st
    @State private var lines: [String] = []
    @State private var timer: Timer?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("DebugLogger · 최근 \(lines.count)줄")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    let text = DebugLog.shared.dump()
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                    st.showToast("로그가 클립보드에 복사되었습니다.")
                } label: {
                    Label("전체 복사", systemImage: "doc.on.doc")
                }
                .buttonStyle(.borderless)
            }
            .padding(8)

            Divider()

            ScrollViewReader { proxy in
                ScrollView {
                    Text(lines.joined(separator: "\n"))
                        .font(.system(size: 11, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .padding(10)
                        .background(Color(nsColor: .textBackgroundColor))
                    Color.clear.frame(height: 1).id("BOTTOM")
                }
                .onChange(of: lines.count) { _, _ in
                    proxy.scrollTo("BOTTOM")
                }
            }
        }
        .onAppear {
            lines = DebugLog.shared.lines
            timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
                Task { @MainActor in
                    let fresh = DebugLog.shared.lines
                    if fresh != lines { lines = fresh }
                }
            }
        }
        .onDisappear {
            timer?.invalidate()
            timer = nil
        }
    }
}

extension AppState {
    func copyLogsToClipboard() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(DebugLog.shared.dump(), forType: .string)
    }
}
