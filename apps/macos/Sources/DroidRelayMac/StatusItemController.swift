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

    private var titleView: MenuTitleView?

    func install() {
        seedPositionOnce()
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem = item

        if let b = item.button {
            // **내용은 비운다.** `image` 를 지정한 `NSStatusBarButton` 은 `title` 을
            // 그리지 않고, `title` 에 줄바꿈을 넣어도 `NSButton` 이 여러 줄을
            // 렌더링하지 않는다. 둘 다 넣으면 결과적으로 **아이콘만 보인다.**
            b.image = nil
            b.title = ""
            b.target = self
            b.action = #selector(clicked(_:))
            b.sendAction(on: [.leftMouseUp, .rightMouseUp])

            // 그림은 서브뷰가 맡는다. autoresizing 으로 붙인다 —
            // Auto Layout 을 걸면 `NSStatusBarButton` 이 높이를 자기 마음대로 잡아
            // 줄이 잘린다(실측).
            let v = MenuTitleView()
            v.translatesAutoresizingMaskIntoConstraints = true
            v.autoresizingMask = [.minYMargin, .maxYMargin]
            b.addSubview(v)
            titleView = v
        }
        updateBadge()

        // 배지 갱신. `PopoverContainer` 가 `badgeCount` 변화를 알리면 받아 그린다.
        // (이 알림을 안 받으면 배지는 `install()` 시점 값으로 영영 고착된다)
        NotificationCenter.default.addObserver(
            forName: .drBadgeChanged, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.updateBadge() }
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
    func updateBadge() {
        guard let v = titleView else { return }
        let src = model.speedSetting
        let d = model.droidSpeed
        let dev = model.deviceSpeedAvailable ? model.deviceSpeed : nil

        // **못 쓰는 출처는 열을 만들지 않는다** — 서버가 값을 안 주는데 0 을 넣으면
        // 사용자는 "고장 났구나" 를 화면에서 읽는다.
        v.setLines(MenuBarTitle.lines(
            badge: model.badgeCount,
            droid: d,
            device: dev,
            includeDroid: src.showDroid,
            includeDevice: src.showDevice
        ))

        if let b = statusItem?.button {
            b.needsLayout = true
            b.layoutSubtreeIfNeeded()
            let s = v.intrinsicContentSize
            v.frame = NSRect(x: 0, y: 0, width: s.width, height: s.height)
            b.frame.size.height = s.height
            statusItem?.length = s.width
        }
        renderTooltip()
    }

    /// 실제로 그리는 줄 — `--title-check` 검증용.
    var debugLines: [String] { titleView?.debugLines ?? [] }

    /// 상태 항목의 실제 프레임 — **요청한 높이를 시스템이 지켰는지** 확인한다.
    ///
    /// **잘림 판정의 기준은 뷰 자신이 아니라 메뉴바 두께다.** 뷰 프레임이
    /// intrinsic 과 같아도 **버튼이 22pt 면 더 큰 뷰는 시스템이 잘라낸다.**
    /// 뷰끼리만 비교하면 "잘림 없음" 이라는 잘못된 안락함을 준다(실제로 그랬다).
    var debugFrames: [String] {
        guard let b = statusItem?.button, let v = titleView else { return [] }
        let need = v.intrinsicContentSize.height
        let room = NSStatusBar.system.thickness
        let shown = min(need, b.frame.height, room)
        let fit = need <= room + 0.5
        return [
            "버튼 프레임 : w=\(Int(b.frame.width)) h=\(Int(b.frame.height))",
            "뷰 프레임   : w=\(Int(v.frame.width)) h=\(Int(v.frame.height))",
            "필요 높이   : \(Int(need))",
            "메뉴바 두께  : \(Int(room))  ← 이게 실제 한계",
            "보이는 줄   : \(v.debugLines.count)줄 중 \(max(0, Int((shown / max(need / CGFloat(v.debugLines.count), 1)).rounded(.down))) )줄",
            "잘림        : " + (fit
                ? "없음 — 전부 들어감"
                : "**있음 — \(Int(need - room))pt 가 잘린다. 마지막 \(Int(ceil((need - room) / 13)))줄 이 배 밖으로 나간다**")
        ]
    }

    /// 값은 상태로 **한 곳에만** 조립한다 — 메뉴바와 툴팁이 같은 값을 쓴다.
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
    @objc private func quit() { NSApp.terminate(nil) }
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
