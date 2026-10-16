import AppKit
import ObjectiveC
import SwiftUI
import DroidRelayCore
import ServiceManagement

/// 프로세스 진입점 — `@main` 대신 직접 부팅한다(씬 없이).
///
/// **`NSApplicationMain` 을 쓰면 안 된다** — 그 경로는 `Info.plist` 의 씬 설정을
/// 다시 읽어 "설정 창"을 되살린다. 여기서는 빈 설정을 박아 부팅한다.
let app = Boot.start()
app.run()

/// 앱 진입점.
///
/// **씬을 두지 않는 이유** — `Settings { EmptyView() }` 씬 하나만 있던 앱은
/// **활성화될 때 그 창이 자동으로 열린다**(실측: 실행하자마자 "DroidRelay Settings" 창이 떴다).
/// 내용이 `EmptyView()` 라도 **창이 뜨는** 이유라 빈 화면으로 숨겨도 해결되지 않는다.
///
/// 씬이 아예 없는 `App` 은 유효하지 않다. 그래서 SwiftUI `App` 수명주기를 버리고
/// `NSApplication` 을 직접 부팅한다. 메뉴바 앱은 씬이 필요 없다 —
/// `NSStatusItem` + `NSPopover` 로 전부 구성한다(아래 `main()`).
@MainActor
enum Boot {
    static func start() -> NSApplication {
        let app = NSApplication.shared
        // Dock 아이콘 숨김. 씬이 없으므로 여기서 반드시 해줘야 한다 —
        // `applicationDidFinishLaunching` 에서 하면 첫 프레임에 Dock 아이콘이 보인다.
        app.setActivationPolicy(.accessory)
        // ★ 메인 메뉴를 **런루프 전에** 설치한다.
        //
        // AppKit 은 `⌘+키` 를 메인 메뉴의 key equivalent 로 먼저 푼다. 씬 없이
        // `NSApplication` 을 직접 부팅하면 SwiftUI 가 기본 메뉴를 만들어 주지 않으므로
        // **없으면 모든 TextField 에서 ⌘C/⌘V/⌘X/⌘A/⌘Z 가 죽는다.**
        // (v0.7.1 에서 같은 버그를 고쳤다가 메뉴바 앱 재작성 때 메뉴를 잃었다 —
        //  `AppMenu`/`MainMenu` 와 `AppMenuTests` 가 재발을 막는다)
        // **델리게이트를 먼저 만든다 — 메뉴가 그걸 target 으로 쓴다.**
        //
        // 메뉴 명세의 앱 전용 동작(`설정… ⌘,`)은 responder chain 에 구현이 없다.
        // 그래서 **명시적 target** 이 필요하다(`AppMenu.Receiver.appController`).
        // target 없이 두면 AppKit 이 그 항목을 **비활성(회색)** 으로 만든다.
        let delegate = AppDelegate()
        app.delegate = delegate
        // **전역에서 살아 있어야 한다.** 지역 변수로 두면 delegate 가 해제돼서
        // 앱이 아무 응답도 안 하는 프로세스로 남는다(아무 오류 없이).
        objc_setAssociatedObject(app, "drDelegate", delegate, .OBJC_ASSOCIATION_RETAIN)
        MainMenu.install(appController: delegate)
        return app
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var controller: StatusItemController?
    private var model: AppModel?

    func applicationDidFinishLaunching(_ n: Notification) {
        // 메뉴바 앱은 화면이 없으므로 진단 출력을 직접 낸다.
        // (NSLog 는 LSUIElement 환경에서 unified log 에 잘 안 도달한다)

        // **메인 메뉴가 실제로 설치됐는지** — ⌘V 회귀를 눈이 아니라 수치로 본다.
        // `--diagnose` 는 메뉴를 안 보고, 다른 검증 모드도 전부 메뉴를 못 본다.
        if CommandLine.arguments.contains("--menu-check") {
            Task { await Diagnostics.menuCheck() }
            return
        }
        if CommandLine.arguments.contains("--diagnose") {
            Task { await Diagnostics.run() }
            return
        }
        // 로그인 항목 등록/해제 — UI 는 메뉴바 안에 숨어 있어 자동화로 누르기 어렵고,
        // 이 기능은 **설치본에서만** 성립하므로 유일한 검증 수단이다.
        if CommandLine.arguments.contains("--login-item=on") {
            print("[DroidRelay] 로그인 항목 등록 → \(LoginItem.set(true).message)  (실제: \(LoginItem.isEnabled))")
            NSApp.terminate(nil); return
        }
        // 메뉴바 회귀는 `--diagnose` 로 검증할 수 없다(진단은 StatusItem 을 만들지 않는다).
        // 상태 항목을 실제로 만들어 **무엇이 그려지는지 문자열로** 확인하는 전용 모드.
        if CommandLine.arguments.contains("--title-check") {
            Task { await Diagnostics.titleCheck() }
            return
        }
        // 설정 창 위치는 **눈으로만 판단하면 안 된다.**
        // "중앙에 뜬다" 는 서술이고, 실제 좌표·화면 목록·복원 여부가 수치다.
        // `--diagnose` 는 StatusItem 을 안 만들고 `--title-check` 는 메뉴바를 본다 —
        // **셋 다 이 창을 검증하지 못한다.** 전용 모드가 필요하다.
        if CommandLine.arguments.contains("--settings-check") {
            Task { await Diagnostics.settingsCheck() }
            return
        }
        // **설정 메뉴가 실제로 창을 여는가** — 코드를 읽어서는 알 수 없다.
        //
        // `--settings-check` 는 `SettingsWindowController` 를 **직접** 만들어
        // 띄우므로 **메뉴 배선은 못 본다.** 그래서 우클릭 메뉴의 `설정…` 이
        // 스텁(앱만 활성화)이어도 진단이 통과했다 — 사용자가 그 버그를 겪는 동안에도.
        // 이 모드는 메뉴 항목을 **실제로 실행**하고 창이 떴는지 본다 (M-33).
        if CommandLine.arguments.contains("--settings-menu-check") {
            Task { await Diagnostics.settingsMenuCheck() }
            return
        }
        // **속도 표시가 두 줄로 깨지지 않는지** — 높이로 판정한다.
        //
        // ## 왜 전용 모드인가
        //
        // "한 줄로 나온다" 는 **서술**이다. 맞았는지 틀렸는지는 **숫자**로 판정해야 한다.
        // 줄이 하나면 12pt, 둘이면 23pt 다.
        //
        // `--title-check` 는 메뉴바 줄을 보고 `--popover-check` 는 팝오버가
        // **뜨는**지 본다. **셋 다 "줄이 깨지는지" 를 못 본다.**
        // `PopoverMetrics` 계산만으로도 못 본다 — **SwiftUI 레이아웃을 통과하지 않기 때문.**
        //
        // → **실제 뷰를 `NSHostingView` 에 넣고 높이를 잰다.** (M-21 교훈)
        if CommandLine.arguments.contains("--legend-check") {
            Task { await Diagnostics.legendCheck() }
            return
        }
        // **실행 중인 앱이 진짜로 무엇을 그리는지** 파일로 남긴다.
        //
        // `--title-check` 는 별도 프로세스라 상태 항목이 "살아 있는" 앱과 다르다.
        // 사용자가 보는 건 이 프로세스인데, 여기를 안 보면 또 같은 착각을 한다.
        // **팝오버가 실제로 뜨는지** 클릭 없이 확인한다.
        //
        // "클릭했는데 아무것도 안 뜬다" 는 종류의 버그가 diagnose 로는 잡히지 않는다 —
        // diagnose 는 StatusItem 을 만들지 않으므로. `statusItem.view` 로 전환하면서
        // `statusItem.button` 이 nil 이 된 걸 놓친 것이 딱 이 경고 사례다.
        if CommandLine.arguments.contains("--popover-check") {
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: AppModel.serverAddressKey))
            let c = StatusItemController(model: m)
            model = m; controller = c
            c.install()
            Task {
                await m.connect()
                await MainActor.run { c.showPopoverForCheck() }
                // 팝오버 애니메이션이 끝날 때까지 기다린다 — 바로 재면 isShown 이 false 다.
                for wait in [0.3, 0.8, 1.5] {
                    try? await Task.sleep(for: .seconds(wait))
                    await MainActor.run {
                        let msg = "\(String(format: "%4.1fs", wait)) "
                            + c.debugPopover.joined(separator: " / ") + "\n"
                        appendLog(msg, to: popoverLog)
                    }
                }
                await MainActor.run { NSApp.terminate(nil) }
            }
            return
        }
        // **화면에 실제로 그려진 것을 텍스트로 남긴다.**
        //
        // "코드에 버튼이 있다" 와 "화면에 버튼이 보인다" 는 다른 말이다.
        // 이 모드는 후자를 근거로 만든다 — 뷰 계층을 그대로 훑어
        // 라벨과 프레임을 파일로 dumping 한다.
        // **팝오버를 열고 대기** — 밖에서 `Tools/DumpAX.swift` 로 화면 내용을 읽는다.
        // 앱 스스로 덤프하면 자기 자신을 원격 AX 로 질의할 수 없어(창 0개) 무의미하다.
        if CommandLine.arguments.contains("--ui-hold") {
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: AppModel.serverAddressKey))
            let c = StatusItemController(model: m)
            model = m; controller = c
            c.install()
            Task {
                await m.connect()
                try? await Task.sleep(for: .seconds(1.2))
                await MainActor.run { c.holdPopoverOpen() }
                // 종료하지 않는다 — 덤프가 끝날 때까지 열린 상태를 유지한다
            }
            return
        }
        if CommandLine.arguments.contains("--ui-settings") {
            // **설정 창을 곧장 열어 둔다** — `--ui-hold` 의 설정 판.
            //
            // 왜 필요한가: 설정은 이제 **별도 NSWindow** 다. 팝오버와 달리
            // "메뉴바를 눌러서 팝오버를 열고 그 안에서 설정을 눌러야" 도달했는데,
            // 그 경로는 **사람 손이 두 번 필요**하고 **AX 로는 메뉴바를 못 건드린다.**
            // 플래그가 없으면 이 창은 눈으로만 검증된다 — 그러면 "뜨나 안 뜨나" 를
            // 추측으로만 말하게 되고, 실제로 그렇게 실패했다.
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: AppModel.serverAddressKey))
            let c = StatusItemController(model: m)
            model = m; controller = c
            c.install()
            Task {
                await m.connect()
                try? await Task.sleep(for: .seconds(1.2))
                await MainActor.run { c.showSettingsWindow() }
                // 종료하지 않는다 — 덤프/타이핑 확인이 끝날 때까지 열린 상태를 유지한다
            }
            return
        }
        if CommandLine.arguments.contains("--ui-dump") {
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: AppModel.serverAddressKey))
            let c = StatusItemController(model: m)
            model = m; controller = c
            c.install()
            Task {
                await m.connect()
                // **한 번만 연다.** 이전처럼 루프에서 토글하면 마지막에 닫힌다 —
                // 그럼 "표시 중: false" 가 찍혀 화면이 안 뜬 것처럼 보인다(실제로 그랬다).
                try? await Task.sleep(for: .seconds(1.0))
                await MainActor.run { c.forceShowPopover() }
                // 렌더가 끝날 때까지 기다린다. 3.5초 — 서버 응답 + 그래프 첫 샘플 포함
                try? await Task.sleep(for: .seconds(3.5))
                await MainActor.run {
                    // **원격 AX 가 진짜 답이다.** 로컬 뷰 계층은 SwiftUI 텍스트를
                    // 안 주고(AXUnknown), 프로세스 간 질의는 VoiceOver 경로라 값을 준다.
                    appendLog(c.debugRemoteAX().joined(separator: "\n") + "\n", to: uiDumpLog)
                    NSApp.terminate(nil)
                }
            }
            return
        }
        if CommandLine.arguments.contains("--watch") {
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: AppModel.serverAddressKey))
            let c = StatusItemController(model: m)
            model = m; controller = c
            c.install()
            Task {
                await m.connect()
                while true {
                    try? await Task.sleep(for: .seconds(1))
                    await MainActor.run {
                        let f = c.debugFrames.joined(separator: " / ")
                        let l = c.debugLines.joined(separator: " | ")
                        let msg = "[\(Date().timeIntervalSince1970.formatted(.number.precision(.fractionLength(0))))] \(l)  —  \(f)  [\(m.phaseLabel)]\n"
                        // **덧붙여야 한다.** `String.write(toFile:atomically:false)` 는
                        // 파일을 **자른다** — 그래서 20초 관찰해도 마지막 1줄만 남았다.
                        // (이 버그 때문에 "갱신이 안 된다" 고 잘못 읽을 뻔했다)
                        if !FileManager.default.fileExists(atPath: agentWatchLog) {
                            FileManager.default.createFile(atPath: agentWatchLog, contents: nil)
                        }
                        if let fh = FileHandle(forWritingAtPath: agentWatchLog) {
                            fh.seekToEndOfFile()
                            fh.write(Data(msg.utf8))
                            try? fh.close()
                        }
                    }
                }
            }
            return
        }
        if CommandLine.arguments.contains("--login-item=off") {
            print("[DroidRelay] 로그인 항목 해제 → \(LoginItem.set(false).message)  (실제: \(LoginItem.isEnabled))")
            NSApp.terminate(nil); return
        }
        // Dock 아이콘 숨김은 `Boot.start()` 에서 이미 했다.

        let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: AppModel.serverAddressKey))
        model = m
        let c = StatusItemController(model: m)
        controller = c
        c.install()

        Task {
            await m.connect()
            if let s = m.server {
                print("[DroidRelay] 연결됨 \(s.displayAddress) v\(s.version) (\(m.phaseLabel))")
            } else {
                print("[DroidRelay] 서버를 찾지 못했습니다 — 설정에서 주소를 입력하세요")
            }
        }
    }

    /// **앱 메뉴의 `설정…` (⌘,)** — `AppMenu.Action.showSettings`.
    ///
    /// responder chain 에는 "설정을 연다" 는 기본 동작이 없다. 그래서
    /// `MainMenu.install(appController:)` 로 **이 델리게이트가 target** 이 되고
    /// 여기서 컨트롤러로 넘긴다.
    ///
    /// 컨트롤러에 직접 걸지 않는 이유: 메뉴는 **런루프 전**(`Boot.start`)에,
    /// 컨트롤러는 `applicationDidFinishLaunching` 에서 만들어진다 — 순서가 맞지
    /// 않는다. 델리게이트가 그 사이를 잇는다.
    @objc func drShowSettings(_ sender: Any?) {
        controller?.showSettingsWindow()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ s: NSApplication) -> Bool { false }
}
