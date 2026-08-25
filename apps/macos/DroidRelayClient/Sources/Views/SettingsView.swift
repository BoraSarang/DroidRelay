import AppKit
import SwiftUI

/// 설정 — 서버 주소/자동스캔/저장폴더/인증/일반
struct SettingsView: View {
    @Environment(AppState.self) private var st
    @State private var showFolderPicker = false

    var body: some View {
        @Bindable var settings = st.settings

        Form {
            Section("서버") {
                LabeledContent("주소") {
                    TextField("192.168.0.10:8080", text: $settings.manualAddress)
                        .textFieldStyle(.roundedBorder)
                        .frame(maxWidth: 220)
                        .onSubmit { Task { await st.tryConnect(settings.manualAddress) } }
                }
                Toggle("실행 시 자동 스캔", isOn: $settings.autoScan)
                HStack {
                    Button("지금 다시 스캔") {
                        Task { await st.rescan() }
                    }
                    Spacer()
                    if let info = st.info, let v = info.version {
                        Text("서버 v\(v) · 저장 \(Fmt.bytes(info.storageFree ?? 0)) 남음")
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }
            }

            Section("받기") {
                LabeledContent("저장 폴더") {
                    HStack(spacing: 8) {
                        Text(settings.downloadFolder)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Button("변경…") { pickFolder() }
                    }
                }
                Toggle("완료 시 알림", isOn: $settings.notificationsEnabled)
            }

            Section("인증 (Basic Auth)") {
                Toggle("사용", isOn: $settings.authEnabled)
                if settings.authEnabled {
                    LabeledContent("사용자명") {
                        TextField("", text: $settings.authUser)
                            .textFieldStyle(.roundedBorder)
                            .frame(maxWidth: 180)
                    }
                    LabeledContent("비밀번호") {
                        SecureField("", text: $settings.authPassword)
                            .textFieldStyle(.roundedBorder)
                            .frame(maxWidth: 180)
                    }
                    Text("비밀번호는 Keychain에만 저장됩니다.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Section("일반") {
                Toggle("메뉴바에 속도 표시", isOn: $settings.showSpeedInMenuBar)
                Toggle("로그인 시 자동 실행", isOn: $settings.launchAtLogin)
                LabeledContent("앱 버전") {
                    Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 460, height: 480)
    }

    private func pickFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.directoryURL = URL(fileURLWithPath: st.settings.downloadFolder)
        if panel.runModal() == .OK, let url = panel.url {
            st.settings.downloadFolder = url.path
            DebugLog.shared.i("Settings", "저장 폴더 변경: \(url.path)")
        }
    }
}
