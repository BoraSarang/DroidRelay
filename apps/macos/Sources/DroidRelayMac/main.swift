import AppKit
import ObjectiveC
import SwiftUI
import DroidRelayCore
import ServiceManagement

/// `--watch` 로그 위치
let agentWatchLog = NSString(string: "~/.agent-droidrelay-watch.log").expandingTildeInPath
let popoverLog = NSString(string: "~/.agent-droidrelay-popover.log").expandingTildeInPath
/// 화면에 실제로 그려진 뷰 계층 덤프 (`--ui-dump`)
let uiDumpLog = NSString(string: "~/.agent-droidrelay-ui.log").expandingTildeInPath

/// 로그 파일에 **덧붙인다.** `String.write(atomically:false)` 는 파일을 잘라서
/// 마지막 1줄만 남는다(실제로 그랬다).
func appendLog(_ msg: String, to path: String) {
    if !FileManager.default.fileExists(atPath: path) {
        FileManager.default.createFile(atPath: path, contents: nil)
    }
    guard let fh = FileHandle(forWritingAtPath: path) else { return }
    fh.seekToEndOfFile()
    fh.write(Data(msg.utf8))
    try? fh.close()
}

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
        let delegate = AppDelegate()
        app.delegate = delegate
        // **전역에서 살아 있어야 한다.** 지역 변수로 두면 delegate 가 해제돼서
        // 앱이 아무 응답도 안 하는 프로세스로 남는다(아무 오류 없이).
        objc_setAssociatedObject(app, "drDelegate", delegate, .OBJC_ASSOCIATION_RETAIN)
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
        // **설정 창이 "다시 찾기" 결과를 말하는가** — 실제로 화면에 있는지로 판정한다. (T-1089)
        //
        // ## 왜 전용 모드인가
        //
        // 사용자 보고: "설정 → 다시 찾기 했을때 찾았을때의 반응이 없네".
        // `--diagnose` 는 StatusItem 을 안 만들고, `--settings-check` 는 **창 위치만** 본다.
        // **셋 다 "결과가 화면에 있는지" 를 검증하지 못한다.**
        //
        // → **실제 창을 띄우고, 그 안의 AX 트리에서 문구를 찾는다.**
        // "코드에 `Text` 가 있다" 와 "화면에 보인다" 는 다른 말이다 (M-21 교훈).
        if CommandLine.arguments.contains("--discovery-check") {
            Task { await Diagnostics.discoveryCheck() }
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
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: "serverAddress"))
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
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: "serverAddress"))
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
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: "serverAddress"))
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
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: "serverAddress"))
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
            let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: "serverAddress"))
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

        let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: "serverAddress"))
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

    func applicationShouldTerminateAfterLastWindowClosed(_ s: NSApplication) -> Bool { false }
}

/// `--diagnose` — 메뉴바 앱은 화면이 없으므로 실패 원인을 볼 수단이 이것뿐이다.

/// 읽기 태스크가 모은 SSE 이벤트. 타임아웃 태스크와 결과를 공유하지 않는다.
private actor TickBox {
    private(set) var all: [String] = []
    func add(_ e: [String]) { all.append(contentsOf: e) }
}
enum Diagnostics {
    /// **그래프 위 속도 표시가 한 줄로 안 깨지는지** — 실측 전용 (M-26).
    ///
    /// ## 왜 전용 모드인가
    ///
    /// **"한 줄로 나온다" 는 서술이다.** 맞았는지 틀렸는지는 **높이**로 판정한다.
    /// 줄이 하나면 12pt, 둘이면 24pt 다. → **실제로 배치해서 높이를 잰다.**
    ///
    /// ## 왜 계산만으로 부족한가 — M-21 에서 배운 것
    ///
    /// M-21 에서 `WindowPlacement.resolve` 가 **정확한 좌표 (670, 291) 를 냈고
    /// 테스트도 100% 통과했는데 실제 창은 (0, 0) 에 있었다.**
    ///
    /// > **계산이 맞다 ≠ 그 계산이 화면에 간다.**
    ///
    /// `PopoverMetrics` 는 이 버그를 못 재현한다. **글꼴 폭을 계수로 재기 때문**이고,
    /// 무엇보다 **SwiftUI 의 레이아웃 엔진을 한 번도 통과하지 않는다.**
    /// `fixedSize` · `lineLimit` · `truncationMode` 가 실제로 어떻게 배분하는지는
    /// **SwiftUI 만 안다.** → 여기서 **실제 뷰를 `NSHostingView` 에 넣고 잰다.**
    ///
    /// ## 무엇을 확인하나
    ///
    /// 1. **범례가 요구하는 실제 폭** — SwiftUI 가 스스로 말하게 한다.
    /// 2. **그 값을 담을 때의 실제 높이** — **한 줄인지** 의 판정이다.
    /// 3. **계산값과 실제값의 차이** — 둘이 어긋나면 Core 쪽 계수를 고쳐야 한다.
    /// 4. **팝오버 창이 실제로 그 폭인가** — 상수가 아니라 **창에서 읽는다.**
    @MainActor
    static func legendCheck() async {
        print("=== DroidRelay 그래프 범례 한 줄 진단 (M-26) ===")
        // **사용자 스크린샷의 값을 그대로 쓴다.** 실제 서버 값으로 재면
        // "269K" 처럼 자릿수가 짧아 **부족한 순간을 놓친다.**
        let values: [SpeedSource: (down: String, up: String)] = [
            .droid:  ("707K", "762K"),
            .device: ("707K", "762K"),
        ]
        let axis = "1.0 MB/s · 보통 3.1 KB/s"
        let sources: [SpeedSource] = [.droid, .device]

        // ── 1. 계산값 ──
        let 계산 = PopoverMetrics.legendRowWidth(sources: sources, values: values, axisLabel: axis)
        print("계산 필요폭  : \(String(format: "%.1f", 계산))pt")
        print("그래프 가용폭: \(String(format: "%.1f", PopoverMetrics.graphWidth))pt  (팝오버 \(Int(PopoverMetrics.width)) − 여백 \(Int(PopoverMetrics.graphInset))×2)")
        print(PopoverMetrics.describe(sources: sources, values: values, axisLabel: axis))

        // ── 2. 실제 SwiftUI 배치 ──
        // **계산이 아니라 뷰를 실제로 넣는다.** 여기가 이 진단의 전부다.
        //
        // **`SpeedLegend` 만 따로 재는 이유** — 첫 판은 `SpeedGraph` 전체를 재고
        // **62pt 가 나왔다.** 62 = 범례 12 + 간격 4 + 그래프 46 이므로
        // **범례는 이미 한 줄이었는데**, "20pt 미만이어야 한 줄" 이라는 기준과
        // 맞지 않아 **"깨진다" 고 잘못 판정했다.**
        //
        // → **틀린 건 판정 기준이 아니라 판정 대상이었다.** 범례만 떼어 본다.
        let legend = SpeedLegend(
            sources: sources,
            droid: (down: 707_000, up: 762_000),
            device: (down: 707_000, up: 762_000),
            axisLabel: axis
        )
        // **(가) 폭을 준다** — 실제 화면과 같은 조건이어야 접히는 것도 재현된다.
        // **높이는 준다** — 높이가 없으면 두 줄이 되어야 하는데 **잘려서 한 줄처럼 보인다.**
        let 제한된 = NSHostingView(
            rootView: legend.frame(width: PopoverMetrics.graphWidth, alignment: .leading)
        )
        // **(나) 폭을 주지 않는다** — 그러면 **필요한 만큼만** 차지한다.
        // 이게 "SwiftUI 가 스스로 말하는 필요폭" 이다.
        let 자유 = NSHostingView(rootView: legend)

        try? await Task.sleep(for: .milliseconds(300))   // 레이아웃이 끝날 때까지 양보
        제한된.layoutSubtreeIfNeeded()
        자유.layoutSubtreeIfNeeded()

        let 제한크기 = 제한된.fittingSize
        let 자유폭 = 자유.fittingSize.width
        print("실제 필요폭  : \(String(format: "%.1f", 자유폭))pt   ← 폭을 주지 않고 SwiftUI 가 낸 값")
        print("가용폭 넣은 높이: \(String(format: "%.1f", 제한크기.height))pt   ← 이게 한 줄/두 줄의 판정값")
        print("계산과의 차이: \(String(format: "%+.1f", 자유폭 - 계산))pt  "
              + (자유폭 - 계산 < -2 ? "★ Core 계수가 과대 — 실제보다 넓다고 계산했다"
                 : 자유폭 - 계산 > 8 ? "★ Core 계수가 과소 — 접힘을 놓칠 수 있다" : "일치"))

        // ── 3. 한 줄인가 (이게 판정) ──
        // 9.5pt 한 줄은 12pt 안팎, 두 줄이면 23pt 이상이다.
        let 한줄 = 제한크기.height < 20
        print("한 줄 여부   : \(한줄 ? "OK — 접히지 않는다" : "★ 두 줄로 깨진다")  (기준 20pt)")
        if !한줄 {
            print("             → 범례 `Text` 에 `lineLimit(1)`+`fixedSize` 가 없거나,")
            print("               `PopoverMetrics.width` 가 `graphWidth` 보다 좁다.")
        }
        if 자유폭 > PopoverMetrics.graphWidth {
            print("             ★ 필요폭 \(String(format: "%.1f", 자유폭))pt > 가용폭 "
                  + "\(Int(PopoverMetrics.graphWidth))pt — 접힌다")
        }

        // ── 4. 창이 실제로 그 폭인가 (상수가 아니라 창에서 읽는다) ──
        // **팝오버를 먼저 만들어야** `contentSize` 를 읽을 수 있다 —
        // `install()` 은 메뉴바 항목만 만들고 팝오버는 **누를 때** 만든다.
        let c = StatusItemController(model: AppModel())
        c.install()
        c.forceShowPopover()
        try? await Task.sleep(for: .milliseconds(600))
        print("팝오버 폭    : \(c.debugPopoverWidth)")
        print("설정 창 폭   : \(Int(SettingsWindowController.contentSize.width))pt  "
              + "— 팝오버가 이보다 넓으면 두 창이 같은 앱으로 읽히지 않는다")
        print("=== 끝 ===")
        NSApp.terminate(nil)
    }

    static func run() async {
        let i = NetworkInfo.currentInterface()
        print("=== DroidRelay 진단 ===")
        if let i {
            print("인터페이스  : \(i.name)")
            print("로컬 IP    : \(i.ipv4) / \(i.netmask) (prefix \(IPv4(i.netmask)?.prefix ?? -1))")
            print("게이트웨이 : \(i.gateway ?? "없음")")
            let hosts = Subnet.scanHosts(ipv4: i.ipv4, netmask: i.netmask)
            print("스캔 대상  : \(hosts.count)개 (앞뒤 \(hosts.first ?? "-") … \(hosts.last ?? "-"))")
        } else {
            print("인터페이스 : 없음 (Wi-Fi 연결 확인)")
        }

        let d = ServerDiscovery()
        let t0 = Date()
        if let r = await d.discover() {
            print("탐색 결과   : \(r.info.displayAddress) v\(r.info.version)")
            print("사용 전략   : \(r.strategy.displayName)  (\(String(format: "%.0f", Date().timeIntervalSince(t0) * 1000))ms)")
            // **팝오버에 실제로 찍히는 문자열** (M-11) — 화면과 같은 함수를 쓴다.
            // 화면 글자를 진단에서 따로 적으면 **화면과 진단이 서로 다른 말을 하게 된다.**
            print("팝오버 표시 : \(DiscoveryBadge.line(strategy: r.strategy, address: r.info.displayAddress, version: r.info.version, isManual: r.strategy == .manual))")
            print("빈 버전 표기 : \"\(DiscoveryBadge.line(strategy: .cached, address: r.info.displayAddress, version: "", isManual: false))\"  ← v 가 붙지 않아야 한다")
            print("수동 입력 표기: \"\(DiscoveryBadge.line(strategy: .manual, address: r.info.displayAddress, version: r.info.version, isManual: true))\"")
            let c = RelayClient(base: r.info.baseURL)
            let jobs = await c.jobs()
            print("다운로드    : \(jobs.count)건")
            for j in jobs.prefix(5) {
                print("  - \(j.name)  \(j.state)  \(j.progress)%  \(j.speedText)")
            }
            // M3 — 탭 3개가 같은 서버를 본다. 탭 데이터가 안 내려오면 그 탭은 그냥 비어 보인다.
            // 메뉴바 앱에 화면이 없으므로 **여기서라도** 확인되어야 원인을 알 수 있다.
            let torrents = await c.torrents()
            print("토렌트      : \(torrents.count)건")
            for t in torrents.prefix(5) {
                print("  - \(t.name)  \(t.stateLabel)  \(t.percent)%  \(t.speedText)  시드\(t.seeds)/피어\(t.peers)")
            }
            let storage = await c.storage()
            print("보관함      : \(storage.count)개 항목 (폴더 \(storage.filter { $0.isDirectory }.count))")
            for e in storage.prefix(5) {
                print("  - \(e.isDirectory ? "📁" : "📄") \(e.name)  \(e.sizeText)  \(e.modifiedText)")
            }
            // 로그인 항목 — **설치본에서만** 성립한다. `swift run` 컨텍스트면 .notFound 가
            // 나오는데 그게 정답이다(경로가 안정적이지 않아 등록 대상이 될 수 없다).
            // 스위치가 왜 안 먹는지 확인할 수단이 여기뿐이라 diagnose 에 노출한다.
            // 속도 — 메뉴바 2줄이 실제로 이런 문자열이 된다
            let droid = await c.droidSpeed()
            print("Droid 속도  : ↓ \(SpeedFormat.text(droid.downBps))  ↑ \(SpeedFormat.text(droid.upBps))")
            // 기기 = 누적 카운터. 속도는 클라이언트가 시간 차로 나눈다 (M-27).
            //
            // **★ 두 모드를 다 찍는다** — 하나만 찍으면 **어느 경로가 문제인지 모른다.**
            // `external` 이 0 이면 서버가 rmnet 을 못 찾는 것이고, `all` 이 0 이면
            // 요청 자체가 실패한 것이다. **"서버 미지원" 이라는 말은 원인을 감춘다.**
            let 설정 = TrafficScopeSetting.load()
            print("기기 범위 설정: \(설정.scope.rawValue) (기본 = \(TrafficScope.default.rawValue))")
            // **설정한 모드만 먼저 찍는다** — 이것이 **실제로 화면에 나오는 값**이다.
            if let t = try? await c.deviceTrafficChecked(scope: 설정.scope) {
                print("기기 카운터 : rx=\(t.rxTotal) tx=\(t.txTotal)"
                      + "  범위=\(t.scope?.rawValue ?? "없음(구버전)")  (누적 — 속도는 클라이언트가 계산)")
            } else {
                print("기기 카운터 : (서버 미지원 — /api/net/speed 없거나 supported=false)")
            }
            // **그다음 나머지 구간** — "왜 이 값인지" 를 비교하기 위해 본다. (M-28: 3구간)
            //
            // **이게 왜 필요한가** — 세 구간이 **서로 다른 값을 본다.**
            // M-28 실측 (동일 구간, 세 값이 각각 다름):
            // ```
            // swlan0   Δrx=28,368  Δtx=60,495   ← 핫스팟 ↔ 클라이언트
            // external Δrx=547     Δtx=443      ← 셀룰러 (거의 0)
            // all      Δrx=25,286  Δtx=63,268   ← ≈ swlan0 합계와 일치 ✓
            // ```
            // **하나만 찍으면 "설정이 안 먹혔다" 는 잘못된 결론을 만든다.**
            for s in TrafficScope.allCases where s != 설정.scope {
                do {
                    let t = try await c.deviceTrafficChecked(scope: s)
                    print("  비교(\(s.rawValue)): rx=\(t.rxTotal) tx=\(t.txTotal)")
                } catch {
                    print("  비교(\(s.rawValue)): 실패 — \(error)")
                }
            }
            // **설정과 서버가 실제로 쓴 값의 일치 여부** — 구버전 서버 대처의 근거.
            if let t = try? await c.deviceTrafficChecked(scope: 설정.scope) {
                if let used = t.scope, used != 설정.scope {
                    print("범위 불일치 : 설정 '\(설정.scope.rawValue)' · 서버 '\(used.rawValue)'")
                } else if t.scope == nil {
                    print("범위        : 서버가 이 필드를 모른다 (구버전) — 설정이 지켜지지 않을 수 있다")
                }
            }
            let st = SMAppService.mainApp.status
            let stName = switch st {
                case .notRegistered: "미등록"
                case .enabled: "켜짐"
                case .requiresApproval: "승인 필요 — 시스템 설정에서 허용하세요"
                case .notFound: "앱 없음"
                @unknown default: "알 수 없음"
            }
            print("로그인 항목  : \(stName)  [status=\(st.rawValue)]"
                  + (LoginItem.isBundled ? "" : "  ← 번들 밖(설치본 아님) — 등록 불가"))
            if let info = await c.serverInfo() {
                print("저장공간    : \(RelayClient.format(bytes: info.storageFree)) 여유 / \(RelayClient.format(bytes: info.storageTotal))")
            }
            await Diagnostics.probeSSE(base: r.info.baseURL)
        } else {
            print("탐색 결과   : 실패 — 설정에서 주소를 직접 입력하세요")
        }
        print("=== 끝 ===")
        await MainActor.run { NSApp.terminate(nil) }
    }

    /// 메뉴바 항목이 **실제로 무엇을 그리는지** 확인한다.
    ///
    /// **왜 전용 모드인가** — `--diagnose` 는 `StatusItem` 을 만들지 않는다. 그래서
    /// "진단 출력이 정상인데 메뉴바에 아무것도 안 보인다" 는 상태가 계속 터졌고
    /// (앱 아이콘만 보이는 문제), 눈으로 봐야만 알았다. 그 공백을 메운다.
    /// `AppModel` 과 `StatusItemController` 가 둘 다 MainActor 격리라 본문을 통째로 격리한다.
    @MainActor
    static func titleCheck() async {
        let m = AppModel(storedAddress: UserDefaults.standard.string(forKey: "serverAddress"))
        if let s = await m.server { _ = s }
        // 값이 0 이면 "속도가 안 뜬다" 와 "서버에 값이 없다" 를 구분할 수 없다.
        // 그래서 **값이 있는 것처럼 채워서** 문자열이 어떻게 조립되는지 본다.
        m.droidSpeed = SpeedReading(downBps: 460_390, upBps: 9_626)
        let c = StatusItemController(model: m)
        c.install()
        for line in c.debugLines { print("메뉴바 줄   : |\(line)|") }
        print("배지        : \(m.badgeCount)")
        print("열 개수     : \(m.visibleSpeedSources.count)  (설정: droid=\(m.speedSetting.showDroid) device=\(m.speedSetting.showDevice) · 기기지원=\(m.deviceSpeedAvailable))")
        print("           → 기기가 미지원인데 켜져 있어도 **빈 열을 만들지 않는다**")
        print("아이콘 열   : 없음 (사용자 지정 — 열마다 아이콘 두지 않음)")
        for f in c.debugFrames { print("즉시  \(f)") }
        // **런 루프를 실제로 돌린 뒤에 다시 잰다.** 상태 항목이 처음 붙을 때와
        // 윈도우 서버가 레이아웃을 마친 뒤에는 두께가 다를 수 있다.
        // "22pt 라서 2줄이 안 된다" 같은 결론을 내리기 전에 이걸 확인해야 한다.
        for wait in [0.5, 1.5, 3.0] {
            try? await Task.sleep(for: .seconds(wait))
            for f in c.debugFrames { print("\(String(format: "%4.1fs", wait)) \(f)") }
        }
        // **실제 서버 값으로 다시** 그린다 — 앞의 값은 고정한 샘플이었다.
        // 0 이면 "아무것도 흐르지 않는다" 와 "연결이 안 됐다" 를 구분할 수 있으니
        // 상태 문구를 함께 찍는다.
        print("실측 속도   : \(m.droidSpeed.downBps)↓ / \(m.droidSpeed.upBps)↑  (\(m.phaseLabel))")
        print("실제 그리는 줄: \(c.debugLines.joined(separator: " | "))")

        NSApp.terminate(nil)
    }

    /// **설정 창이 어디에 뜨는지** — 좌표를 수치로 본다.
    ///
    /// ## 왜 전용 모드인가
    ///
    /// "창이 가운데에 나온다" 는 **서술**이다. 틀렸는지 맞았는지는 좌표로 판정한다.
    /// `--diagnose` 는 `StatusItem` 을 만들지 않고, `--title-check` 는 메뉴바 줄을 본다.
    /// **셋 다 이 창을 건드리지 않는다.** 그래서 전용이 필요하다.
    ///
    /// ## 무엇을 확인하나
    ///
    /// 1. **실제 화면 목록** — 테스트는 1440×900 을 썼지만 이 Mac 은 3024×1964 다.
    /// 2. **실제 창을 띄운 뒤의 좌표** — **계산값이 아니라 진짜 좌표를 읽는다.**
    /// 3. **저장값이 없을 때** — 사용자의 요청("가운데")이 실제로 성립하는가.
    /// 4. **저장값이 화면 밖에 있을 때** — 가운데로 복귀하는가.
    ///
    /// ## 왜 "실제로 띄워서" 보는가 — 이 진단이 놓쳤던 것
    ///
    /// 첫 판에서 이건 **계산한 좌표만** 찍었다. 계산은 (670, 291) 이었고 테스트도 통과했는데
    /// **실제 창은 화면 왼쪽 하단(0, 0)에 떴다.**
    ///
    /// → **계산이 맞다 ≠ 창이 그곳에 간다.** `setFrameOrigin` 이 호출되지 않았거나,
    /// 호출된 뒤 다른 코드가 옮겼거나 둘 중 하나다. **창을 실제로 띄우고 `w.frame` 을
    /// 읽는 것만이 이 둘을 구분한다.**
    @MainActor
    static func settingsCheck() async {
        print("=== DroidRelay 설정 창 위치 진단 ===")
        // **주 화면이 첫째여야 한다** — `visible[0]` 이 "돌아갈 곳"으로 쓰인다.
        var frames = NSScreen.screens.map(\.visibleFrame)
        if let main = NSScreen.main?.visibleFrame {
            frames.removeAll { $0 == main }
            frames.insert(main, at: 0)
        }
        print("화면 수      : \(NSScreen.screens.count)")
        for (i, f) in frames.enumerated() {
            print("  [\(i)] \(i == 0 ? "주 화면 " : "추가     ")\(fmt(f))")
        }
        let savedBefore = WindowPlacement.loadOrigin()
        print("저장된 좌표  : \(savedBefore.map { "(\(Int($0.x)), \(Int($0.y)))" } ?? "없음 — 처음 실행")")

        // 실제 창을 **진짜로 띄운다** — 계산값만으로는 알 수 없다.
        let c = SettingsWindowController(model: AppModel())
        guard let w = c.window else { print("창 생성 실패"); NSApp.terminate(nil); return }
        let size = w.frame.size
        print("창 크기      : \(Int(size.width))×\(Int(size.height))")
        print("띄우기 전    : \(fmt(w.frame))  ← 이것이 (0,0) 이면 init 이 이미 잘못됐다")

        // **계산값만으로는 부족하다 — 실제로 띄워서 좌표를 읽는다.**
        c.applyPlacement()
        try? await Task.sleep(for: .milliseconds(400))
        print("applyPlacement 후 : \(fmt(w.frame))  → \(w.frame.origin == WindowPlacement.centered(in: frames[0], size: w.frame.size) ? "가운데 ✓" : "저장 위치 ✓ (사용자가 옮긴 곳)")")

        c.present()
        try? await Task.sleep(for: .milliseconds(400))
        let real = w.frame
        let centered = WindowPlacement.centered(in: frames[0], size: real.size)
        print("present 후 실제  : \(fmt(real))")
        print("  기대값         : \(fmt(CGRect(origin: centered, size: real.size)))")
        print("  → \(real.origin == centered ? "일치 ✓" : "불일치 ✗ — 창이 다른 곳으로 옮겨졌다")")
        print("제목바 띠   : \(fmt(WindowPlacement.titleBarRect(origin: real.origin, size: real.size)))")
        print("보이는가     : \(WindowPlacement.canGrab(real.origin, size: real.size, in: frames) ? "yes — 사용자가 찾을 수 있다" : "NO — 창을 못 찾는다")")

        // **저장값을 일부러 화면 밖에 두고** 가운데로 돌아오는지 본다 — 이게 핵심 규칙.
        let lost = CGPoint(x: frames[0].maxX + 2000, y: frames[0].maxY)
        let rescued = WindowPlacement.resolve(saved: lost, size: real.size, visible: frames)
        print("화면 밖 복귀 : (\(Int(lost.x)), \(Int(lost.y))) → (\(Int(rescued.x)), \(Int(rescued.y)))  → \(rescued == centered ? "가운데로 복귀 ✓" : "복귀 실패 ✗")")
        // **저장값은 건드리지 않는다** — 진단이 사용자의 배치를 바꾸면 안 된다.
        print("저장된 좌표  : \(WindowPlacement.loadOrigin() == savedBefore ? "변화 없음 ✓" : "바뀜 ✗ — 진단이 배치를 망가뜨렸다")")
        print("=== 끝 ===")
        NSApp.terminate(nil)
    }

    private static func fmt(_ r: CGRect) -> String {
        "(\(Int(r.minX)), \(Int(r.minY)) \(Int(r.width))×\(Int(r.height)))"
    }

    @MainActor
    /// **설정 창 "다시 찾기" 반응 진단** (T-1089).
    ///
    /// ## 왜 이렇게 검증하나
    ///
    /// 사용자 보고는 "반응이 없다" 였다. `--diagnose` 실측으로는 **탐색이 40ms 에 성공**한다.
    /// 즉 결함은 탐색이 아니라 **결과를 말하지 않은 것**이었고,
    /// 그래서 이건 **탐색이 아니라 표시**의 문제다.
    ///
    /// → 코드의 계산값만으로는 부족하다. **실제 창을 띄우고 그 안의 문구를 찾는다.**
    /// (M-21: "계산이 맞다 ≠ 창이 그곳에 간다" / M-26: "코드에 Text 가 있다 ≠ 화면에 보인다")
    static func discoveryCheck() async {
        print("=== DroidRelay 설정 창 발견 상태 진단 (T-1089) ===")

        // ── 1. 순수 함수 — 네 가지 상태의 문구 ──
        // **화면과 같은 함수**로 찍는다. 따로 적으면 화면과 진단이 다른 말을 한다 (M-22 교훈).
        print("\n[1] 상태별 문구 (SettingsDiscovery.status — 화면과 동일)")
        let cases: [(SettingsDiscovery.State, String?, String?)] = [
            (.idle, nil, nil),
            (.searching, nil, nil),
            (.found(.gateway), "10.38.120.211:3000", "0.50.0"),
            (.found(.subnetScan), "10.38.120.211:3000", "0.50.0"),
            (.failed, nil, nil),
        ]
        for (st, addr, ver) in cases {
            let tone = SettingsDiscovery.Tone(st)
            print("  \(String(describing: st).padding(toLength: 22, withPad: " ", startingAt: 0)) → \(SettingsDiscovery.status(st, address: addr, version: ver))   [tone=\(tone)]")
        }

        // ── 2. 주소 칸 — 실패가 사용자의 입력을 지우지 않는가 ──
        print("\n[2] 주소 칸 (SettingsDiscovery.addressField)")
        let 직접 = "192.168.0.77:8080"
        print("  성공 시 덮어씀 : \"\(SettingsDiscovery.addressField(current: "", found: "10.38.120.211:3000"))\"")
        print("  실패 시 보존   : \"\(SettingsDiscovery.addressField(current: 직접, found: nil))\"  → \(SettingsDiscovery.addressField(current: 직접, found: nil) == 직접 ? "보존 ✓" : "★ 지워졌다 ✗")")
        print("  탐색 중 보존   : \"\(SettingsDiscovery.addressField(current: "10.0.0.", found: nil))\"")
        print("  버튼 활성(탐색중): \(SettingsDiscovery.State.searching.isButtonEnabled ? "활성 ✗ 중복 실행 가능" : "비활성 ✓")")

        // ── 3. ★ 실제 뷰를 그려서 상태 줄이 **높이를 차지하는지** 본다 ──
        //
        // ## 왜 AX 조회가 아니라 높이인가 (M-21 / M-26 교훈)
        //
        // 처음엔 창을 띄우고 AX 트리를 훑어 문구를 찾으려 했다. **문자가 0개** 나왔다.
        // `--ui-dump` 주석이 이미 답을 말해 준다 —
        // "**앱 스스로 덤프하면 자기 자신을 원격 AX 로 질의할 수 없다**".
        //
        // **0개 = 못 찾은 것**이지 "화면에 없다" 는 뜻이 아니다.
        // 그걸 그대로 ✗ 로 보고하면 **과소평가**다 — M-26 이 정확히 그 함정에 빠졌다
        // ("**과소평가는 조용히 못-found 한다**").
        //
        // → **화면이 쓰는 그 뷰(`DiscoveryStatusLine`)를** `NSHostingView` 에 넣어 높이를 잰다.
        print("\n[3] 실제 DiscoveryStatusLine 뷰 — 상태 줄이 실제로 그려지는가")
        print("  ※ 창 안 AX 조회는 0개 — 앱이 자기 AX 를 못 질의한다.")
        print("    '안 보임' 과 '못 찾음' 을 구분 않고 ✗ 로 보고하면 과소평가다 (M-26).")

        var 측정: [String: (h: CGFloat, need: CGFloat)] = [:]
        for (label, st) in [("idle", SettingsDiscovery.State.idle),
                            ("searching", .searching),
                            ("found", .found(.gateway)),
                            ("failed", .failed)] {
            let addr = st.isFoundCase ? "10.38.120.211:3000" : nil
            let line = DiscoveryStatusLine(
                state: st,
                text: SettingsDiscovery.status(st, address: addr,
                                               version: st.isFoundCase ? "0.50.0" : nil))
            // (가) 폭을 준다 — 실제 화면(460)과 같은 조건이어야 접히는 것도 재현된다.
            let 제한 = NSHostingView(rootView: line.frame(width: 420, alignment: .leading))
            // (나) 폭을 주지 않는다 — SwiftUI 가 스스로 말하는 필요폭.
            let 자유 = NSHostingView(rootView: line)
            제한.layoutSubtreeIfNeeded()
            자유.layoutSubtreeIfNeeded()
            측정[label] = (제한.fittingSize.height, 자유.fittingSize.width)
        }

        print("  상태별 실제 높이 (420pt 폭에 넣었을 때):")
        var 기준높이: CGFloat = 0
        for label in ["idle", "searching", "found", "failed"] {
            let (h, need) = 측정[label] ?? (0, 0)
            if label == "idle" { 기준높이 = h }
            let 한줄 = h < 20
            print("    \(label.padding(toLength: 10, withPad: " ", startingAt: 0)) 높이 \(String(format: "%.1f", h))pt · 필요폭 \(String(format: "%.0f", need))pt · \(한줄 ? "한 줄 ✓" : "★ 두 줄로 접힌다")")
        }
        // **높이가 0 이면 그 줄이 자리를 차지하지 않는다** — 화면에 없는 것과 같다.
        let 모두살아있음 = ["idle", "searching", "found", "failed"]
            .allSatisfy { (측정[$0]?.h ?? 0) > 0 }
        print("  → 모든 상태에서 실제로 높이를 차지함: \(모두살아있음 ? "yes ✓" : "NO ✗ — 화면에 그려지지 않는다")")
        print("    (기준 높이 \(String(format: "%.1f", 기준높이))pt — 10.5pt 한 줄이면 12~14pt 이다)")

        // ── 4. 실패 문구 ──
        let failLine = SettingsDiscovery.status(.failed, address: nil, version: nil)
        print("\n[4] 실패 문구")
        print("  \"\(failLine)\"")
        print("  → \"실패\" 가 화면에 나오므로 원인을 안다: \(failLine.contains("실패") ? "yes ✓" : "NO ✗")")

        print("\n=== 끝 ===")
        NSApp.terminate(nil)
    }

    /// SSE 생존 확인 — 메뉴바 앱의 실시간 갱신이 실제로 통하는지 본다.
    ///
    /// **주의**: v0.40 부터 서버는 상태가 **바뀔 때만** tick 을 보내고 15초 beat 으로만
    /// 갱신한다. 아무 변화가 없으면 tick 이 올 이유가 없다. 서버가 계속 살아 있다는
    /// 증거 자체가 "_tick 이 온다" 이므로, HTTP 200 + Content-Type 만 확인한다.
    /// (루프를 데드라인 없이 돌리면 beat 이 계속 들어와 영영 끝나지 않는다 — 실제로 그랬다)
    static func probeSSE(base: URL, seconds: Int = 20) async {
        var req = URLRequest(url: base.appendingPathComponent("api/events"))
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        req.timeoutInterval = .infinity
        do {
            let (bytes, resp) = try await URLSession.shared.bytes(for: req)
            let http = resp as? HTTPURLResponse
            let ct = http?.value(forHTTPHeaderField: "Content-Type") ?? "?"
            print("SSE 연결      : HTTP \(http?.statusCode ?? -1) · \(ct)")

            let t0 = Date()
            // 주의: `group.next()` 로 결과를 받지 않는다 — 먼저 끝난 쪽이 반환되므로
            // 타임아웃(20초) 이 이기고, **읽기 태스크가 모은 tick 이 통째로 버려진다.**
            // (실제로 이 버그로 beat 을 못 잡았다) 수집은 별도 actor 에 쌓는다.
            let box = TickBox()
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    var parser = SSEParser()
                    do {
                        for try await b in bytes {
                            let got = parser.consumeByte(b)
                            if !got.isEmpty { await box.add(got) }
                        }
                    } catch { }
                }
                group.addTask {
                    try? await Task.sleep(for: .seconds(seconds))
                }
                _ = await group.next()
                group.cancelAll()
            }
            let ticks = await box.all
            let dt = String(format: "%.1f", Date().timeIntervalSince(t0))
            if ticks.isEmpty {
                print("SSE tick      : \(seconds)초 동안 0건 — **정상**(상태 변화 없음)")
                print("               진행률 변경이나 beat 은 다음 갱신 때 반영됩니다")
            } else {
                print("SSE tick      : \(ticks.count)건 / \(dt)초  ✓ 실시간 갱신 동작")
                for (i, t) in ticks.prefix(3).enumerated() { print("               #\(i + 1) \(t.prefix(60))") }
            }
        } catch {
            print("SSE 오류      : \(error.localizedDescription) — 10초 백업 폴링으로 동작")
        }
    }
}
