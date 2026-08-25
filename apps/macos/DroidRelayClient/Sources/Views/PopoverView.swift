import AppKit
import SwiftUI

/// 팝오버 본체 — 연결바 + 3탭 + 하단 도구모음
struct PopoverView: View {
    @Environment(AppState.self) private var st
    @State private var tab = 0

    var body: some View {
        VStack(spacing: 0) {
            ConnectionBar()
            Divider()
            Picker("", selection: $tab) {
                Text("다운로드").tag(0)
                Text("토렌트").tag(1)
                Text("보관함").tag(2)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            Group {
                switch tab {
                case 0: DownloadsTab()
                case 1: TorrentsTab()
                default: StorageTab()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Divider()
            FooterBar()
        }
        .frame(width: 420)
        .frame(minHeight: 480, maxHeight: 600)
        .overlay(alignment: .bottom) {
            if let msg = st.toast {
                ToastView(message: msg)
                    .padding(.bottom, 44)
            }
        }
    }
}

/// 연결 상태 바 — 상태점 + 주소/안내 + 재스캔 + 수동입력
struct ConnectionBar: View {
    @Environment(AppState.self) private var st
    @Environment(\.openWindow) private var openWindow
    @State private var manualInput = ""

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 9, height: 9)
                Text(st.connection.label)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if st.isConnected, let info = st.info {
                    Text(Fmt.bytes(info.storageFree ?? 0) + " 남음")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button {
                    Task { await st.rescan() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .help("네트워크 다시 검색")
            }

            if case .disconnected = st.connection {
                HStack(spacing: 6) {
                    TextField("서버 주소 (예: 192.168.0.10:8080)", text: $manualInput)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(size: 12))
                        .onSubmit { Task { await connectManual() } }
                    Button("연결") {
                        Task { await connectManual() }
                    }
                    .disabled(manualInput.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var statusColor: Color {
        switch st.connection {
        case .connected: return .green
        case .scanning: return .orange
        case .disconnected: return .red
        }
    }

    private func connectManual() async {
        guard await st.tryConnect(manualInput) else {
            st.showToast("연결할 수 없습니다. 서버가 켜져 있는지 확인해 주세요.")
            return
        }
    }
}

/// 하단 도구 모음
struct FooterBar: View {
    @Environment(AppState.self) private var st
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        HStack(spacing: 10) {
            Button {
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            } label: {
                Label("설정", systemImage: "gearshape")
            }
            .buttonStyle(.borderless)

            Button {
                openWindow(id: "logs")
            } label: {
                Label("로그", systemImage: "doc.text.below.search")
            }
            .buttonStyle(.borderless)

            Spacer()

            if st.isConnected {
                Button {
                    st.openDashboard()
                } label: {
                    Label("웹 보기", systemImage: "safari")
                }
                .buttonStyle(.borderless)
            }

            if !st.transfers.actives.isEmpty {
                Button {
                    st.transfers.clearFinished()
                } label: {
                    Text("완료 정리 \(st.transfers.actives.filter { $0.finished || $0.failed }.count)")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .font(.system(size: 12))
    }
}

struct ToastView: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.system(size: 12))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.regularMaterial, in: Capsule())
            .shadow(radius: 4)
    }
}
