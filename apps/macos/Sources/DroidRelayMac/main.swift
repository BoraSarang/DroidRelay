import AppKit
import ObjectiveC
import SwiftUI
import DroidRelayCore
import ServiceManagement

/// `--watch` 로그 위치
let agentWatchLog = NSString(string: "~/.agent-droidrelay-watch.log").expandingTildeInPath

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
        // **실행 중인 앱이 진짜로 무엇을 그리는지** 파일로 남긴다.
        //
        // `--title-check` 는 별도 프로세스라 상태 항목이 "살아 있는" 앱과 다르다.
        // 사용자가 보는 건 이 프로세스인데, 여기를 안 보면 또 같은 착각을 한다.
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
            // 기기 = 누적 카운터. 속도는 클라이언트가 시간 차로 나눈다.
            if let t = await c.deviceTraffic() {
                print("기기 카운터 : rx=\(t.rxTotal) tx=\(t.txTotal) (누적 — 속도는 클라이언트가 계산)")
            } else {
                print("기기 카운터 : (서버 미지원 — /api/net/speed 없거나 supported=false)")
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
