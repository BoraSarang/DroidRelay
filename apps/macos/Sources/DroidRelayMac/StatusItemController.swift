import AppKit
import DroidRelayCore
import SwiftUI

/// 메뉴바 항목 수명 관리.
///
/// **왜 `MenuBarExtra` 가 아니라 수동 `NSStatusItem` 인가**
/// macOS 26+ 에서 사용자가 "시스템 설정 → 제어 센터 → 메뉴바" 에서 이 앱을 끄면
/// `MenuBarExtra` 씬은 **크래시 로그 없이 프로세스가 조용히 종료**된다 (프레임워크가
/// 표시 가능한 씬이 없다고 판단해 비예외 경로로 나간다). 수동 `NSStatusItem` 은
/// 그 경우 `isVisible = false` 가 될 뿐 프로세스가 살아 있다.
///
/// 또 `NSStatusItem` 은 **강한 참조**를 유지해야 한다. 해제되면 아이콘이 조용히 사라진다.
@MainActor
final class StatusItemController {
    private var statusItem: NSStatusItem?
    private var popover: NSPopover?
    private let model: AppModel

    /// 메뉴바에서 이 앱을 끌 수 있을지 (아이콘 위치 예약) — 아님면 다른 앱의 것도 건드린다.
    private static let positionKey = "NSStatusItem Preferred Position com.borasarang.DroidRelayMac"

    init(model: AppModel) { self.model = model }

    /// 현재 메뉴바에 그려진 줄 (검증용)
    private var drawnLines: [MenuBarTitle.MenuBarLine] = []
    /// 모델 관찰 루프 — 팝오버와 무관하게 메뉴바를 갱신한다.
    private var observe: Task<Void, Never>?
    /// 메뉴바에 붙는 뷰 — `statusItem.view` 로 들어간다.
    private var speedView: MenuBarSpeedView?

    func install() {
        seedPositionOnce()
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem = item

        // ## `statusItem.view` 로 넣는다 — 여기가 다섯 번째 실패의 답
        //
        // `statusItem.button` 의 title·image·서브뷰 로는 **전부 실패했다.**
        // `NSStatusItem` 은 **`.view` 로도** 내용을 가질 수 있고, 여기에 직접 배치한
        // `NSTextField` 는 시스템이 건드릴 일이 없다.
        //
        // (TetherLens 의 검증된 방식. 2줄이 성립하는 이유도 거기서 찾았다 —
        //  **9pt 폰트**는 줄당 11pt, 2줄이 22pt 로 메뉴바 두께에 딱 들어간다.
        //  내 이전 구현은 10.5pt(줄당 13pt)라 26pt 가 필요했고 잘렸다.)
        let v = MenuBarSpeedView(
            onClick: { [weak self] in self?.togglePopover() },
            onRightClick: { [weak self] in self?.showMenu() }
        )
        item.view = v
        speedView = v
        updateBadge()

        // ## 갱신을 누가 시키는가 — 여기가 마지막 함정이었다
        //
        // 이전에는 `PopoverContainer` 의 `.onChange(of: model.badgeCount)` 가
        // `.drBadgeChanged` 를 올리고 그걸 여기서 받았다. 그럼 **팝오버가 살아야** 갱신된다.
        //
        // 그런데 `NSHostingController` 는 **팝오버를 처음 열 때** 만들어진다.
        // → 앱을 켠 직후에는 아무것도 알리지 않는다 → 메뉴바가 `install()` 시점의
        // `—` 로 **영영 고착된다.** 사용자가 클릭해 팝오버를 열기 전까지.
        //
        // `--watch` 로그로 실측: 서버에는 62.8 KB/s 가 흐르는데 메뉴바는 `—` .
        // 서버 문제가 아니라 **갱신 트리거가 팝오버에 묶여 있던 것** 이었다.
        //
        // → **컨트롤러가 모델을 직접 관찰한다.** 팝오버는 그릴 대상일 뿐 아니다.
        observe = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                if Task.isCancelled { break }
                await MainActor.run { self?.updateBadge() }
            }
        }

        // **팝오버를 직접 닫지 않는다.** 바깥 클릭은 `behavior = .transient` 가 처리한다.
        // `NSWindow.didResignKeyNotification` 으로 닫으면 팝오버 안에서 설정 시트를 열었을 때
        // resign key 가 울려 **시트가 열리는 순간 팝오버까지 사라진다**.
    }

    /// 노치가 있는 화면에서 새 항목이 노치 밑에 숨어 보이지 않는 문제가 있다.
    /// macOS 는 생성 시점에 이 값을 한 번만 읽는다. **미설정 시에만** 시드한다 —
    /// 매번 덮어쓰면 사용자의 ⌘-드래그로 정렬한 순서를 파괴한다.
    private func seedPositionOnce() {
        let d = UserDefaults.standard
        if d.object(forKey: Self.positionKey) == nil {
            d.set(0, forKey: Self.positionKey)   // 0 = 우측 끝
        }
    }

    /// 배지와 속도를 **한 번에** 그린다.
    ///
    /// **버튼의 `title` 을 만지지 않는 이유** — `attributedTitle` 을 지우기 때문이다.
    /// 한 번 더 상처를 냈다: 처음엔 `attributedTitle` 에 넣고 배지만 `b.title` 로 갱신했는데,
    /// AppKit 이 `attributedTitle` 을 **삭제**해서 속도가 사라졌다(배지만 남음).
    /// 지금은 버튼에도 뷰에도 대입하지 않고 **뷰 하나만** 갱신한다.
    /// 배지와 속도를 **한 번에** 그린다.
    ///
    /// ## `b.image` 로 넣는다 — 서브뷰는 실패했다
    ///
    /// 커스텀 `NSView` 를 서브뷰로 붙이는 길을 먼저 시도했다. **결과는 아무것도 안
    /// 보였다** — 안테나 아이콘까지 사라졌다. 원인은 `NSStatusBarButton` 이 컨테이너가
    /// 아니라 `NSButton` 이고, `NSStatusBar` 이 레이아웃을 다시 잡을 때 **버튼 프레임을
    /// 자기가 결정한다** — 서브뷰 프레임은 그때마다 버려진다. 넣은 직후 조회하면
    /// "정상" 으로 보이는데 실제로는 그려지지 않는다.
    ///
    /// `b.image` 는 시스템이 **항상** 그리는 자리다(안테나 심볼이 거기서 그려졌다).
    /// 그래서 그림을 이미지로 구워서 이 자리에 넣는다.
    func updateBadge() {
        let src = model.speedSetting
        let drawn = MenuBarTitle.lines(
            badge: model.badgeCount,
            droid: model.droidSpeed,
            // **못 쓰는 출처는 열을 만들지 않는다** — 서버가 값을 안 주는데 0 을 넣으면
            // 사용자는 화면에서 "고장 났구나" 를 읽는다.
            device: model.deviceSpeedAvailable ? model.deviceSpeed : nil,
            includeDroid: src.showDroid,
            includeDevice: src.showDevice
        )
        drawnLines = drawn
        // **버튼이 아니라 `speedView` 를 갱신한다.** `NSStatusItem.view` 로 붙인 뒤
        // 여기서 내용을 채운다 — 시스템이 개입하지 않으므로 값이 유실되지 않는다.
        // 값이 없으면 빈 문자열이 들어가고 앱 아이콘 하나만 남는다.
        speedView?.apply(drawn)
        renderTooltip()
    }

    /// 실제로 그리는 줄 — `--title-check` 검증용.
    var debugLines: [String] { drawnLines.map(\.text) }

    /// 메뉴바에 붙은 실제 프레임 — **잘림 여부를 숫자로 본다.**
    var debugFrames: [String] {
        guard let v = speedView else { return ["speedView    : **nil — install() 이 안 돌았다**"] }
        return ["speedView ok : true"] + v.debugGeometry
    }

    private func renderTooltip() {
        guard let b = statusItem?.button else { return }
        let sources = model.visibleSpeedSources
        b.toolTip = sources.isEmpty ? "DroidRelay" :
            sources.map { "\($0.label) ↓ \(SpeedFormat.text(model.speed(for: $0).downBps))"
                        + " ↑ \(SpeedFormat.text(model.speed(for: $0).upBps))" }
                   .joined(separator: "\n")
    }

    @objc private func clicked(_ sender: NSStatusBarButton) {
        let isRight = NSApp.currentEvent?.type == .rightMouseUp
        if isRight { showMenu() } else { togglePopover() }
    }

    private func ensurePopover() -> NSPopover {
        if let p = popover { return p }
        let p = NSPopover()
        p.behavior = .transient          // 바깥 클릭 시 닫힘
        p.animates = true
        p.contentSize = NSSize(width: 352, height: 420)
        p.contentViewController = NSHostingController(rootView: PopoverContainer(model: model))
        popover = p
        return p
    }

    private func togglePopover() {
        let p = ensurePopover()
        if p.isShown {
            p.performClose(nil)
        } else {
            guard let b = statusItem?.button else { return }
            p.show(relativeTo: b.bounds, of: b, preferredEdge: .minY)
            // 팝오버 안의 컨트롤이 키보드를 받을 수 있게 활성화
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    /// 우클릭 — 시스템 표준 메뉴
    private func showMenu() {
        let m = NSMenu()
        m.addItem(withTitle: "대시보드 열기", action: #selector(openDash), keyEquivalent: "").target = self
        m.addItem(withTitle: "설정…", action: #selector(openSettings), keyEquivalent: ",").target = self
        m.addItem(.separator())
        m.addItem(withTitle: "DroidRelay 종료", action: #selector(quit), keyEquivalent: "q").target = self
        statusItem?.menu = m
        statusItem?.button?.performClick(nil)
        statusItem?.menu = nil     // 좌클릭 동작 복구
    }

    @objc private func openDash() { model.openDashboard() }
    @objc private func openSettings() { NSApp.activate(ignoringOtherApps: true) }
    @objc private func quit() {
        observe?.cancel()
        observe = nil
        NSApp.terminate(nil)
    }
}

/// 설정 창은 M4 범위라 지금은 주소 입력만 최소로 제공한다.
struct PopoverContainer: View {
    @Bindable var model: AppModel
    @State private var showSettings = false
    @State private var address = ""
    /// 로그인 시 자동 실행 — **Launch Services 를 직접 조회**한다(UserDefaults 플래그가 아니라).
    @State private var loginOn = LoginItem.isEnabled
    @State private var showDroid = true
    @State private var showDevice = false
    @State private var loginMsg: String? = nil

    var body: some View {
        PopoverView(
            model: model,
            onSettings: {
                address = model.storedAddress ?? ""
                loginOn = LoginItem.isEnabled   // 시트를 열 때마다 실제 상태로 새로고침
                showDroid = model.speedSetting.showDroid
                showDevice = model.speedSetting.showDevice
                loginMsg = nil
                showSettings = true
            },
            onQuit: { NSApp.terminate(nil) }
        )
        .sheet(isPresented: $showSettings) {
            settingsSheet
        }
        .task { if model.phase == .idle { await model.connect() } }
        .onChange(of: model.badgeCount) { _, _ in
            NotificationCenter.default.post(name: .drBadgeChanged, object: nil)
        }
        .onChange(of: showDroid) { _, _ in
            model.speedSetting.showDroid = showDroid
        }
        .onChange(of: showDevice) { _, _ in
            model.speedSetting.showDevice = showDevice
        }
        .onChange(of: model.droidSpeed) { _, _ in NotificationCenter.default.post(name: .drBadgeChanged, object: nil) }
        .onChange(of: model.deviceSpeed) { _, _ in NotificationCenter.default.post(name: .drBadgeChanged, object: nil) }
    }

    private var settingsSheet: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("DroidRelay 연결").font(.system(size: 14, weight: .bold))
            Text("자동으로 찾지 못하면 주소를 직접 입력하세요.")
                .font(.system(size: 11.5)).foregroundStyle(.secondary)
            HStack {
                TextField("10.0.0.5:3000", text: $address)
                    .textFieldStyle(.roundedBorder)
                Button("연결") { Task { await model.useManualAddress(address) } }
                    .disabled(address.isEmpty)
            }
            Divider()
            HStack {
                Text("자동 검색으로 다시 시도").font(.system(size: 12))
                Spacer()
                Button("다시 찾기") { Task { await model.connect() } }
            }

            Divider()
            Text("메뉴바 속도").font(.system(size: 14, weight: .bold))
            speedToggle("Droid 속도", "이 앱이 쓰는 트래픽", $showDroid)
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("기기 속도").font(.system(size: 12))
                    Text(model.deviceSpeedAvailable
                         ? "폰 전체 트래픽" : "서버 지원 대기 중 — 켜도 값이 나오지 않습니다")
                        .font(.system(size: 10.5))
                        .foregroundStyle(model.deviceSpeedAvailable ? .secondary : Color.orange)
                }
                Spacer()
                Toggle("", isOn: $showDevice)
                    .labelsHidden()
                    .disabled(!model.deviceSpeedAvailable)
            }

            Divider()
            Text("시작").font(.system(size: 14, weight: .bold))
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("로그인 시 자동 실행").font(.system(size: 12))
                    if let loginMsg {
                        Text(loginMsg).font(.system(size: 10.5)).foregroundStyle(.secondary)
                    } else {
                        Text("Mac 에 로그인하면 DroidRelay 가 자동으로 뜹니다")
                            .font(.system(size: 10.5)).foregroundStyle(.tertiary)
                    }
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { loginOn },
                    set: { toggleLogin($0) }
                ))
                .labelsHidden()
            }

            HStack { Spacer(); Button("닫기") { showSettings = false }.keyboardShortcut(.defaultAction) }
        }
        .padding(20)
        .frame(width: 420)
    }

    private func speedToggle(_ t: String, _ h: String, _ binding: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(t).font(.system(size: 12))
                Text(h).font(.system(size: 10.5)).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: binding).labelsHidden()
        }
    }

    /// 실패해도 앱은 죽지 않는다 — 사유를 설정 화면에 남기고 스위치를 원래대로 되돌린다.
    private func toggleLogin(_ want: Bool) {
        let r = LoginItem.set(want)
        loginOn = LoginItem.isEnabled   // 요청한 값이 아니라 **실제** 상태를 따른다
        if case .applied = r { loginMsg = nil } else { loginMsg = r.message }
    }
}

extension Notification.Name { static let drBadgeChanged = Notification.Name("drBadgeChanged") }
