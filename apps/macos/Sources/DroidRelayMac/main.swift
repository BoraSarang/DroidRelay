import AppKit
import SwiftUI
import DroidRelayCore
import ServiceManagement

/// 앱 진입점.
///
/// 메뉴바 전용 앱이므로 Dock 아이콘이 없어야 한다(`.accessory`).
/// `LSUIElement` 와 같은 효과지만 코드로 하므로 Info.plist 없이도 동작하고
/// SPM 빌드로 만든 실행 파일을 바로 쓸 수 있다.
@main
struct DroidRelayMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        Settings { EmptyView() }   // 설정은 팝오버에서 제공 (M1 범위)
        .commands { CommandGroup(replacing: .newItem) {} }
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
        if CommandLine.arguments.contains("--login-item=off") {
            print("[DroidRelay] 로그인 항목 해제 → \(LoginItem.set(false).message)  (실제: \(LoginItem.isEnabled))")
            NSApp.terminate(nil); return
        }
        NSApp.setActivationPolicy(.accessory)   // Dock 아이콘 숨김

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
            let dev = await c.deviceSpeed()
            print("Droid 속도  : ↓ \(SpeedFormat.text(droid.downBps))  ↑ \(SpeedFormat.text(droid.upBps))")
            if dev == nil {
                print("기기 속도   : (서버 미지원 — TrafficStats 엔드포인트 신설 필요)")
            } else {
                let d = dev!
                print("기기 속도   : ↓ \(SpeedFormat.text(d.downBps))  ↑ \(SpeedFormat.text(d.upBps))")
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
