import Foundation
import Observation
import ServiceManagement
import SwiftUI

/// 앱 설정 저장소 — UserDefaults + Keychain(비밀번호)
@Observable
final class SettingsStore {
    private let d = UserDefaults.standard

    init() {
        manualAddress = d.string(forKey: "server.address") ?? ""
        autoScan = d.object(forKey: "scan.auto") as? Bool ?? true
        authEnabled = d.bool(forKey: "auth.enabled")
        authUser = d.string(forKey: "auth.user") ?? ""
        authPassword = KeychainStore.get(account: "auth.password") ?? ""
        showSpeedInMenuBar = d.object(forKey: "ui.showSpeed") as? Bool ?? true
        notificationsEnabled = d.object(forKey: "notify.enabled") as? Bool ?? true
        launchAtLogin = d.bool(forKey: "general.launchAtLogin")
        let savedTheme = d.string(forKey: "ui.theme") ?? "system"
        themeMode = ["system", "light", "dark"].contains(savedTheme) ? savedTheme : "system"

        if let p = d.string(forKey: "download.folder") {
            downloadFolder = p
        } else {
            let url = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("DroidRelay", isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            downloadFolder = url.path
        }
    }

    /// 수동 서버 주소 ("192.168.0.10:8080" 형태, 스킴 생략 가능)
    var manualAddress: String {
        didSet { d.set(manualAddress, forKey: "server.address") }
    }

    /// 실행 시 서브넷 자동 스캔
    var autoScan: Bool {
        didSet { d.set(autoScan, forKey: "scan.auto") }
    }

    /// Basic Auth 사용 여부 (서버 접속 범위가 ANY_WITH_PASSWORD일 때)
    var authEnabled: Bool {
        didSet { d.set(authEnabled, forKey: "auth.enabled") }
    }

    var authUser: String {
        didSet { d.set(authUser, forKey: "auth.user") }
    }

    var authPassword: String {
        didSet { KeychainStore.set(authPassword.isEmpty ? nil : authPassword, account: "auth.password") }
    }

    /// ⬇ 받기 저장 폴더
    var downloadFolder: String {
        didSet { d.set(downloadFolder, forKey: "download.folder") }
    }

    /// 메뉴바에 총 속도 표시 ("↓ 2.5 MB/s")
    var showSpeedInMenuBar: Bool {
        didSet { d.set(showSpeedInMenuBar, forKey: "ui.showSpeed") }
    }

    /// 받기 완료 시 macOS 알림
    var notificationsEnabled: Bool {
        didSet { d.set(notificationsEnabled, forKey: "notify.enabled") }
    }

    /// 앱 테마 — "system" | "light" | "dark"
    var themeMode: String {
        didSet { d.set(themeMode, forKey: "ui.theme") }
    }

    var colorScheme: ColorScheme? {
        switch themeMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    /// 로그인 시 자동 실행 (SMAppService)
    var launchAtLogin: Bool {
        didSet {
            d.set(launchAtLogin, forKey: "general.launchAtLogin")
            applyLaunchAtLogin()
        }
    }

    func basicAuthHeader() -> String? {
        guard authEnabled else { return nil }
        let raw = "\(authUser):\(authPassword)"
        return "Basic " + Data(raw.utf8).base64EncodedString()
    }

    private func applyLaunchAtLogin() {
        do {
            if launchAtLogin {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
            DebugLog.shared.i("Settings", "로그인 시 실행 = \(launchAtLogin)")
        } catch {
            DebugLog.shared.w("Settings", "로그인 실행 설정 실패: \(error.localizedDescription)")
        }
    }
}
