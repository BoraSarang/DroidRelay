import Cocoa
import SwiftUI
import WebKit

// MARK: - 웹뷰 + 도크 수직 컨테이너

final class SplitContainer: NSView {
    let webView: NSView
    let dock: NSView
    var dockHeight: CGFloat = DockController.collapsedHeight { didSet { needsLayout = true } }

    init(webView: NSView, dock: NSView) {
        self.webView = webView
        self.dock = dock
        super.init(frame: .zero)
        addSubview(webView)
        addSubview(dock)
    }

    required init?(coder: NSCoder) { fatalError("unsupported") }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        dock.frame = NSRect(x: 0, y: 0, width: bounds.width, height: dockHeight)
        webView.frame = NSRect(x: 0, y: dockHeight, width: bounds.width, height: bounds.height - dockHeight)
        CATransaction.commit()
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKDownloadDelegate, WKUIDelegate {
    private var statusItem: NSStatusItem!
    private var panel: NSPanel?
    private var webView: WKWebView?
    private var container: SplitContainer?
    private let dockCtl = DockController()
    private var appState = AppState.shared

    /// 진행 중인 WKDownload → TransferManager 항목 id 매핑
    private var downloadIds: [ObjectIdentifier: String] = [:]
    private var downloadDests: [ObjectIdentifier: String] = [:]

    func applicationDidFinishLaunching(_ notification: Notification) {
        setupAppMenu()
        setupStatusItem()
        Task { await appState.connectStartup() }
    }

    // MARK: - ⌘Q 활성화 (최소 App 메뉴)

    private func setupAppMenu() {
        let mainMenu = NSMenu()

        // 앱 메뉴
        let appMenuItem = NSMenuItem()
        mainMenu.addItem(appMenuItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "DroidRelay 정보", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "DroidRelay 종료", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appMenuItem.submenu = appMenu

        // 편집 메뉴 — 웹뷰 입력창 Cmd+V/C/X/A/Z 라우팅 필수
        let editMenuItem = NSMenuItem()
        mainMenu.addItem(editMenuItem)
        let editMenu = NSMenu(title: "편집")
        editMenu.addItem(withTitle: "되돌리기", action: Selector(("undo:")), keyEquivalent: "z")
        editMenu.addItem(withTitle: "다시 실행", action: Selector(("redo:")), keyEquivalent: "Z")
        editMenu.addItem(.separator())
        editMenu.addItem(withTitle: "자르기", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editMenu.addItem(withTitle: "복사", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "붙여넣기", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "전체 선택", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editMenuItem.submenu = editMenu

        NSApp.mainMenu = mainMenu
    }

    // MARK: - 상태바

    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let btn = statusItem.button {
            btn.image = Self.makeMenuBarIcon()
            btn.image?.size = NSSize(width: 18, height: 18)
            btn.action = #selector(statusClicked(_:))
            btn.target = self
            btn.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
        // 1초마다 메뉴바 텍스트 업데이트
        Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.updateMenuBarText()
            }
        }
    }

    @MainActor
    private func updateMenuBarText() {
        guard let btn = statusItem.button else { return }
        if let text = appState.menuBarSpeedText {
            btn.title = " " + text
            statusItem.length = NSStatusItem.variableLength
        } else {
            btn.title = ""
            statusItem.length = NSStatusItem.squareLength + 6
        }
    }

    /// 메뉴바 아이콘 — 꽉 찬 굵은 다운로드 화살표 (template, 시스템 틴트 자동)
    private static func makeMenuBarIcon() -> NSImage {
        let img = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { _ in
            let p = NSBezierPath()
            p.lineWidth = 3.0
            p.lineCapStyle = .round
            p.lineJoinStyle = .round
            // 축: 위 → 아래
            p.move(to: NSPoint(x: 9, y: 15.5))
            p.line(to: NSPoint(x: 9, y: 5))
            // 화살표 머리
            p.move(to: NSPoint(x: 3.2, y: 10.3))
            p.line(to: NSPoint(x: 9, y: 4.5))
            p.line(to: NSPoint(x: 14.8, y: 10.3))
            // 받침대
            p.move(to: NSPoint(x: 2.2, y: 1.6))
            p.line(to: NSPoint(x: 15.8, y: 1.6))
            NSColor.black.setStroke()
            p.stroke()
            return true
        }
        img.isTemplate = true
        return img
    }

    @objc private func statusClicked(_ sender: NSStatusBarButton) {
        if let event = NSApp.currentEvent,
           event.type == .rightMouseUp || event.type == .otherMouseUp {
            showStatusMenu()
            return
        }
        // 창 없으면 생성, 가려져 있으면 최상위로 활성화 (토글 아님)
        showDashboard()
    }

    /// 우클릭 메뉴
    private func showStatusMenu() {
        let menu = NSMenu()
        menu.addItem(withTitle: "대시보드 열기",
                     action: #selector(menuOpenDashboard), keyEquivalent: "").target = self
        menu.addItem(withTitle: "📥 다운로드 목록",
                     action: #selector(menuDownloads), keyEquivalent: "").target = self
        menu.addItem(withTitle: "🐞 디버그 패널",
                     action: #selector(menuDebug), keyEquivalent: "").target = self
        menu.addItem(withTitle: "⚙️ 설정",
                     action: #selector(menuSettings), keyEquivalent: "").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "받기 폴더 열기",
                     action: #selector(menuOpenFolder), keyEquivalent: "").target = self

        // 앱 정보
        menu.addItem(.separator())
        let ver = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        let infoItem = NSMenuItem(title: "DroidRelay v\(ver) (\(build))", action: nil, keyEquivalent: "")
        infoItem.isEnabled = false
        menu.addItem(infoItem)

        if let info = appState.info {
            let serverItem = NSMenuItem(
                title: "서버 v\(info.version ?? "?") · 저장 \(Fmt.bytes(info.storageFree ?? 0)) 여유",
                action: nil, keyEquivalent: "")
            serverItem.isEnabled = false
            menu.addItem(serverItem)
        }

        menu.addItem(.separator())
        menu.addItem(withTitle: "종료",
                     action: #selector(NSApplication.terminate(_:)), keyEquivalent: "")
        statusItem.menu = menu
        statusItem.button?.performClick(nil)
        statusItem.menu = nil
    }

    @objc private func menuOpenDashboard() { showDashboard() }

    @objc private func menuDownloads() {
        showDashboard()
        dockCtl.open(.downloads)
    }

    @objc private func menuDebug() {
        showDashboard()
        dockCtl.open(.debug)
    }

    @objc private func menuSettings() {
        showDashboard()
        dockCtl.open(.settings)
    }

    @objc private func menuOpenFolder() {
        let url = URL(fileURLWithPath: appState.settings.downloadFolder)
        NSWorkspace.shared.open(url)
        DebugLog.shared.i("App", "받기 폴더 열기: \(url.path)")
    }

    // MARK: - 대시보드 패널 (웹뷰 + 하단 도크)

    /// 웹 대시보드 가로폭(760px)보다 살짝 큰 고정 크기
    private static let fixedWindowSize = NSSize(width: 800, height: 720)
    private var pinned = false
    private var titlebarAccessory: NSView?

    private func showDashboard() {
        if let p = panel, let wv = webView {
            reloadIfNeeded(wv)
            if !p.isVisible { p.center() }
            p.level = pinned ? .modalPanel : .normal
            p.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }

        let p = NSPanel(
            contentRect: NSRect(origin: .zero, size: Self.fixedWindowSize),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        p.title = "DroidRelay"
        p.isReleasedWhenClosed = false
        p.hidesOnDeactivate = false
        p.minSize = Self.fixedWindowSize
        p.maxSize = Self.fixedWindowSize
        p.center()
        addWindowControls(to: p)

        let config = WKWebViewConfiguration()
        config.preferences.setValue(true, forKey: "developerExtrasEnabled")
        let wv = WKWebView(frame: .zero, configuration: config)
        wv.navigationDelegate = self
        wv.uiDelegate = self

        let hosting = NSHostingView(rootView: DockRootView(ctl: dockCtl))
        let split = SplitContainer(webView: wv, dock: hosting)
        split.dockHeight = DockController.collapsedHeight
        dockCtl.onHeightChange = { [weak split] h in
            split?.dockHeight = h
        }

        p.contentView = split
        self.panel = p
        self.webView = wv
        self.container = split

        if let addr = appState.serverAddress {
            wv.load(URLRequest(url: URL(string: addr + "/")!))
        } else {
            showConnectionDialog(wv: wv)
        }

        p.center()
        p.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// 타이틀바 우측에 [🔄 새로고침] [📌 핀] [✕ 닫기] 액세서리
    private func addWindowControls(to p: NSPanel) {
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 96, height: 22))

        let reload = NSButton(frame: NSRect(x: 0, y: 1, width: 30, height: 20))
        reload.title = "🔄"
        reload.isBordered = false
        reload.toolTip = "대시보드 새로고침"
        reload.target = self
        reload.action = #selector(reloadDashboard(_:))

        let pin = NSButton(frame: NSRect(x: 32, y: 1, width: 32, height: 20))
        pin.title = "📌"
        pin.isBordered = true
        pin.setButtonType(.pushOnPushOff)
        pin.showsBorderOnlyWhileMouseInside = false
        pin.toolTip = "항상 위 고정"
        pin.target = self
        pin.action = #selector(togglePin(_:))

        let close = NSButton(frame: NSRect(x: 66, y: 1, width: 30, height: 20))
        close.title = "✕"
        close.isBordered = false
        close.toolTip = "창 닫기 (메뉴바 아이콘으로 다시 열기)"
        close.target = self
        close.action = #selector(closePanel(_:))

        view.addSubview(reload)
        view.addSubview(pin)
        view.addSubview(close)

        let accessory = NSTitlebarAccessoryViewController()
        accessory.view = view
        accessory.layoutAttribute = .right
        p.addTitlebarAccessoryViewController(accessory)
    }

    @objc private func reloadDashboard(_ sender: NSButton) {
        webView?.reload()
        DebugLog.shared.i("App", "대시보드 새로고침")
    }

    @objc private func togglePin(_ sender: NSButton) {
        pinned.toggle()
        let level: NSWindow.Level = pinned ? .modalPanel : .normal
        panel?.level = level
        sender.title = pinned ? "📌" : "📌"
        sender.contentTintColor = pinned ? .controlAccentColor : nil
        DebugLog.shared.i("App", "항상 위 = \(pinned) (level=\(level.rawValue))")
    }

    @objc private func closePanel(_ sender: NSButton) {
        panel?.orderOut(nil)
    }

    private func reloadIfNeeded(_ wv: WKWebView) {
        if let addr = appState.serverAddress {
            let current = wv.url?.absoluteString ?? ""
            if !current.contains(addr) {
                wv.load(URLRequest(url: URL(string: addr + "/")!))
            }
        }
    }

    // MARK: - WKNavigationDelegate / WKDownloadDelegate
    // <a download> 클릭은 navigationAction 콜백 없이 곧장 WKDownload 생성됨.
    // didBecome에서 delegate 연결 → 목록 항목 생성 → 진행률 → 완료/실패.

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        // 진단: 다운로드 전환(didBecome)이 안 오는 경우 추적용
        if let url = navigationAction.request.url?.path,
           url.hasPrefix("/file/") || url.hasPrefix("/dl-file/") {
            DebugLog.shared.i("Download", "다운로드 링크 네비게이션 감지 \(url)")
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction,
                 didBecome download: WKDownload) {
        download.delegate = self
        let id = "web-\(UUID().uuidString.prefix(8))"
        downloadIds[ObjectIdentifier(download)] = id
        DebugLog.shared.i("Download", "다운로드 시작 감지 id=\(id)")
        Notify.send(title: "DroidRelay", body: "다운로드 요청 했습니다", enabled: true)
        // 도크 자동 펼침 — 목록에 바로 표시
        Task { @MainActor in
            dockCtl.open(.downloads)
        }
    }

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse,
                  suggestedFilename: String,
                  completionHandler: @escaping (URL?) -> Void) {
        let key = ObjectIdentifier(download)
        let name = suggestedFilename.isEmpty ? "download" : suggestedFilename

        // 목록 항목 생성
        if let id = downloadIds[key] {
            Task { @MainActor in
                appState.transfers.beginWeb(id: id, name: name)
            }
        }

        // 설정의 받기 폴더에 자동 저장 (중복 시 (n) 접미)
        let dir = URL(fileURLWithPath: appState.settings.downloadFolder, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = Fmt.safeFilename(name)
        var dest = dir.appendingPathComponent(safe)
        let base = dest.deletingPathExtension().lastPathComponent
        let ext = dest.pathExtension
        var n = 1
        while FileManager.default.fileExists(atPath: dest.path) {
            dest = ext.isEmpty
                ? dir.appendingPathComponent("\(base)(\(n))")
                : dir.appendingPathComponent("\(base)(\(n)).\(ext)")
            n += 1
        }
        downloadDests[key] = dest.path
        DebugLog.shared.i("Download", "저장 위치: \(dest.lastPathComponent) → \(dir.path)")
        completionHandler(dest)
    }

    func download(_ download: WKDownload, didWrite bytesWritten: Int64,
                  totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let key = ObjectIdentifier(download)
        guard let id = downloadIds[key] else { return }
        Task { @MainActor in
            appState.transfers.updateWeb(id: id, totalWritten: totalBytesWritten, expected: totalBytesExpectedToWrite)
        }
    }

    func downloadDidFinish(_ download: WKDownload) {
        let key = ObjectIdentifier(download)
        let id = downloadIds.removeValue(forKey: key)
        let dest = downloadDests.removeValue(forKey: key)
        guard let id else { return }
        let name = dest.map { URL(fileURLWithPath: $0).lastPathComponent } ?? "파일"
        DebugLog.shared.i("Download", "다운로드 완료: \(name)")
        Task { @MainActor in
            appState.transfers.finishWeb(id: id, ok: true, dest: dest ?? "", name: name,
                                         notifyEnabled: appState.settings.notificationsEnabled)
        }
    }

    func download(_ download: WKDownload, didFail error: Error, resumeData: Data?) {
        let key = ObjectIdentifier(download)
        let id = downloadIds.removeValue(forKey: key)
        let dest = downloadDests.removeValue(forKey: key)
        guard let id else { return }
        let name = dest.map { URL(fileURLWithPath: $0).lastPathComponent } ?? "파일"
        DebugLog.shared.e("E-MAC-NET-1002", "다운로드 실패 \(name): \(error.localizedDescription)")
        Task { @MainActor in
            appState.transfers.finishWeb(id: id, ok: false, dest: "", name: name,
                                         notifyEnabled: appState.settings.notificationsEnabled)
        }
    }

    // MARK: - WKUIDelegate: JS alert/confirm/prompt + 파일 선택창
    // WKWebView는 델리게이트 구현 없으면 prompt()/confirm()/input[type=file] 전부 무반응.

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = NSAlert()
        alert.messageText = "DroidRelay"
        alert.informativeText = message
        alert.addButton(withTitle: "확인")
        alert.runModal()
        completionHandler()
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = NSAlert()
        alert.messageText = "DroidRelay"
        alert.informativeText = message
        alert.addButton(withTitle: "확인")
        alert.addButton(withTitle: "취소")
        completionHandler(alert.runModal() == .alertFirstButtonReturn)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (String?) -> Void) {
        let alert = NSAlert()
        alert.messageText = "DroidRelay"
        alert.informativeText = prompt
        let input = NSTextField(frame: NSRect(x: 0, y: 0, width: 280, height: 24))
        input.stringValue = defaultText ?? ""
        alert.accessoryView = input
        alert.addButton(withTitle: "확인")
        alert.addButton(withTitle: "취소")
        let ok = alert.runModal() == .alertFirstButtonReturn
        completionHandler(ok ? input.stringValue : nil)
    }

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        panel.begin { [weak self] resp in
            guard resp == .OK, !panel.urls.isEmpty else {
                completionHandler(nil)
                return
            }
            // 네이티브 업로드: WKWebView에 URL을 반환하지 않고 직접 업로드
            completionHandler(nil)
            Task { @MainActor in
                guard let self else { return }
                for url in panel.urls {
                    self.appState.uploadFile(url)
                }
                self.dockCtl.open(.downloads)
            }
        }
    }

    // MARK: - 연결 다이얼로그

    private func showConnectionDialog(wv: WKWebView) {
        let alert = NSAlert()
        alert.messageText = "DroidRelay 서버 연결"
        alert.informativeText = "서버 주소를 입력해 주세요 (예: 192.168.0.10:8080)"
        alert.addButton(withTitle: "연결")
        alert.addButton(withTitle: "취소")

        let textField = NSTextField(frame: NSRect(x: 0, y: 0, width: 300, height: 24))
        textField.stringValue = appState.settings.manualAddress
        textField.placeholderString = "192.168.0.10:8080"
        alert.accessoryView = textField

        let response = alert.runModal()
        guard response == .alertFirstButtonReturn else { return }

        let addr = AppState.normalize(textField.stringValue)
        guard !addr.isEmpty else { return }

        appState.serverAddress = addr
        Task { @MainActor in
            let ok = await appState.tryConnect(addr)
            if ok {
                wv.load(URLRequest(url: URL(string: addr + "/")!))
            }
        }
    }
}
