import SwiftUI

@main
struct DroidRelayClientApp: App {
    @State private var appState = AppState.shared

    var body: some Scene {
        MenuBarExtra {
            PopoverView()
                .environment(appState)
        } label: {
            MenuBarLabel(state: appState)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView()
                .environment(appState)
        }

        Window("디버그 로그", id: "logs") {
            LogsView()
                .environment(appState)
        }
        .defaultSize(width: 560, height: 440)
    }
}

/// 메뉴바 아이콘 — 연결 상태에 따라 안테나/슬래시 + 속도 텍스트
struct MenuBarLabel: View {
    let state: AppState

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: iconName)
            if let speed = state.menuBarSpeedText {
                Text(speed)
                    .font(.system(size: 11, weight: .medium))
            }
        }
    }

    private var iconName: String {
        switch state.connection {
        case .connected:
            return "antenna.radiowaves.left.and.right"
        case .scanning:
            return "antenna.radiowaves.left.and.right"
        case .disconnected:
            return "antenna.radiowaves.left.and.right.slash"
        }
    }
}
