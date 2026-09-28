import Foundation
import SwiftUI
import DroidRelayCore
import AppKit

/// 앱 전역 상태. @Observable 이므로 view 가 자동으로 갱신된다.
@Observable
@MainActor
final class AppModel {
    enum Phase: Equatable {
        case idle
        case discovering
        case connected(DiscoveryStrategy)
        case failed
    }

    /// 팝오버 3탭 (M3). 웹 대시보드의 3탭 구성과 같은 순서다 —
    /// 사용자가 두 UI 를 오갈 때 mental model 이 안 깨지게.
    enum Tab: String, CaseIterable, Identifiable {
        case downloads, torrents, storage
        var id: String { rawValue }
        var title: String {
            switch self {
            case .downloads: return "다운로드"
            case .torrents: return "토렌트"
            case .storage: return "보관함"
            }
        }
        var icon: String {
            switch self {
            case .downloads: return "arrow.down.circle"
            case .torrents: return "arrow.triangle.2.circlepath"
            case .storage: return "tray.full"
            }
        }
    }

    @ObservationIgnored var selectedTab: Tab = .downloads

    var phase: Phase = .idle
    var server: ServerInfo?
    var jobs: [RelayClient.Job] = []
    var torrents: [Torrent] = []
    var storage: [StorageEntry] = []
    var storageFree: Int = 0
    /// Droid(앱) 속도 — 잡+토렌트를 서버가 준 값에서 합산한다(웹 대시보드와 동일 계산)
    var droidSpeed: SpeedReading = .init(downBps: 0, upBps: 0)
    /// 기기(폰 전체) 속도 — 서버의 누적 카운터를 **여기서** 시간 차로 나눈 값.
    var deviceSpeed: SpeedReading = .init(downBps: 0, upBps: 0)
    /// 직전 기기 카운터와 그 시각 — 속도 계산에 쓴다.
    private var lastDeviceTraffic: (t: DeviceTraffic, at: Date)?
    /// 서버가 기기 카운터를 지원하는가 — 스위치를 이 값으로 켜고 끈다.
    var deviceSpeedAvailable = false
    /// 그래프용 이력 — 1초 주기로 스스로 채운다
    let history = SpeedHistory(capacity: 300)
    /// 속도 샘플러 — 서버 tick(가변 간격)으로 부하를 늘리지 않고 1초에 한 번만 뽑는다
    private var speedTask: Task<Void, Never>?
    var storageTotal: Int = 0
    var lastUpdate: Date?

    private var discovery: ServerDiscovery
    private var stream = EventStream()
    private var pollTask: Task<Void, Never>?
    private var stored: String?

    init(storedAddress: String? = nil) {
        if let s = storedAddress {
            let parts = s.split(separator: ":")
            if parts.count == 2, let p = Int(parts[1]) {
                stored = s
                discovery = ServerDiscovery(cached: ServerInfo(host: String(parts[0]), port: p, version: ""))
            } else {
                discovery = ServerDiscovery()
            }
        } else {
            discovery = ServerDiscovery()
        }
    }

    var activeJobs: [RelayClient.Job] { jobs.filter { $0.isActive } }
    var seedingJobs: [RelayClient.Job] { jobs.filter { $0.isSeeding } }
    /// 배지는 **탭과 무관하게** 전체 활성 수를 센다 — 어느 탭에 있든 진행 중임을 알린다.
    var activeTorrents: [Torrent] { torrents.filter { $0.isActive } }
    var badgeCount: Int { activeJobs.count + activeTorrents.count }

    /// 메뉴바에 표시할 출처 — 설정으로 고른다.
    ///
    /// **저장된 값을 로드한다** — 기본값으로 시작하면 토글을 아무리 눌러도
    /// 재실행할 때마다 되돌아가서 "저장이 안 된다" 고 보인다.
    /// (테스트만 저장/로드를 검증하고 실제 앱은 기본값만 쓰고 있었다)
    var speedSetting = SpeedDisplaySetting.load() {
        didSet {
            speedSetting.save()
            NotificationCenter.default.post(name: .drBadgeChanged, object: nil)
        }
    }

    /// **실제로 그릴 수 있는 출처** — 설정이 켠 것 중 **서버가 값을 주는 것만.**
    ///
    /// `speedSetting.sources` 를 그대로 쓰면 안 된다. 기기 속도는 서버가 아직
    /// 제공하지 않는데 설정은 켜져 있으므로(기본값) 열 자리가 하나 더 생기고
    /// 그 칸은 계속 `—` 로 찬다. 즉 **"고장 난 기능" 이 화면에 그대로 노출된다** —
    /// 내가 처음 설계할 때 경고한 바로 그 상황이다.
    ///
    /// 그래서 `0` 이 아니라 **열 자체를 만들지 않는다.** 못 쓰는 칸을 비워두는 게
    /// 사용자에게는 정직하다(값이 0 인 것과 값을 모르는 것은 다르다).
    var visibleSpeedSources: [SpeedSource] {
        speedSetting.sources.filter { $0 != .device || deviceSpeedAvailable }
    }

    func speed(for source: SpeedSource) -> SpeedReading {
        switch source {
        case .droid: return droidSpeed
        case .device: return deviceSpeed
        }
    }

    /// Droid 속도 = HTTP 잡(다운로드만) + 활성 토렌트(다운/업)
    private func recomputeDroidSpeed() {
        // **잡 상태 문자열을 하드코딩하면 안 된다.** 이전엔 `"RUNNING"` 을 찾았는데
        // 서버는 `"DOWNLOADING"` 을 보낸다 — 그래서 잡 속도는 **영영 0** 이었다.
        // 대소문자·언어를 무시하도록 바꾼다.
        let jobsDown = jobs.filter { $0.isRunning }.reduce(0) { $0 + $1.speedBps }
        let active = torrents.filter { $0.isActive }
        let tDown = active.reduce(0) { $0 + $1.downloadBps }
        let tUp = active.reduce(0) { $0 + $1.uploadBps }
        droidSpeed = SpeedReading(downBps: jobsDown + tDown, upBps: tUp)
        history.push(droidSpeed)
    }

    // MARK: - 탐색

    func connect() async {
        phase = .discovering
        guard let r = await discovery.discover() else {
            phase = .failed
            return
        }
        server = r.info
        stored = r.info.displayAddress
        phase = .connected(r.strategy)
        await refresh()
        subscribe()
    }

    /// 설정 창에서 주소를 직접 넣었을 때.
    func useManualAddress(_ address: String) async {
        let parts = address.split(separator: ":")
        guard parts.count == 2, let p = Int(parts[1]),
              let u = URL(string: "http://\(address)")
        else { phase = .failed; return }
        guard let info = await RelayClient.probe(u) else { phase = .failed; return }
        server = info
        stored = info.displayAddress
        phase = .connected(.manual)
        await refresh()
        subscribe()
    }

    var storedAddress: String? { stored }

    /// 진단 출력용 — 지금 어느 전략으로 붙어 있는지
    var phaseLabel: String {
        switch phase {
        case .connected(let s): return s.displayName
        case .discovering: return "탐색 중"
        case .failed: return "실패"
        case .idle: return "대기"
        }
    }

    // MARK: - 갱신

    func refresh() async {
        guard let s = server else { return }
        let client = RelayClient(base: s.baseURL)
        // 3개 탭이 같은 서버를 본다 — 필요한 것만 한 번에.
        // SSE tick(빠르면 1초)마다 4요청을 쏘면 서버·망이 불필요하게 busy 해진다.
        // **선택된 탭의 것만** 갱신하고 나머지는 그 탭이 열릴 때 당긴다.
        async let j = selectedTab == .downloads ? client.jobs() : jobs
        // **토렌트는 탭이 열려 있지 않아도 반드시 당긴다.** Droid 속도의 대부분을
        // 잡이 아니라 토렌트가 차지하는데, 여기서 조건부로 부르면 탭이 '다운로드' 일 때
        // `torrents` 가 영영 비어 속도가 계속 0 이 된다.
        // (실측: 서버는 10KB/s 를 보내는데 메뉴바는 '—' — 탭 조건이 원인이었다)
        async let t = client.torrents()
        async let f = selectedTab == .storage ? client.storage() : storage
        async let i = client.serverInfo()
        let (jobs, torrents, storage, info) = await (j, t, f, i)
        self.jobs = jobs
        self.torrents = torrents
        self.storage = storage
        recomputeDroidSpeed()
        if let info {
            storageFree = info.storageFree
            storageTotal = info.storageTotal
        }
        lastUpdate = Date()
    }

    /// 탭을 바꾸면 그 탭 데이터는 즉시 당긴다 — 10초 백업 폴링을 기다리지 않는다.
    func selectTab(_ tab: Tab) async {
        guard tab != selectedTab else { return }
        selectedTab = tab
        await refresh()
    }

    func control(_ id: String, _ action: String) {
        guard let s = server else { return }
        Task {
            await RelayClient(base: s.baseURL).control(id, action)
            await refresh()
        }
    }

    func torrentControl(_ id: String, _ action: String) {
        guard let s = server else { return }
        Task {
            await RelayClient(base: s.baseURL).torrentControl(id, action)
            await refresh()
        }
    }

    func torrentDelete(_ id: String) {
        guard let s = server else { return }
        Task {
            await RelayClient(base: s.baseURL).torrentDelete(id)
            await refresh()
        }
    }

    // MARK: - 실시간

    private func subscribe() {
        guard let s = server else { return }
        Task { await stream.run(base: s.baseURL) { @Sendable [weak self] in
            await self?.refresh()
        } }
        startSpeedSampling()
        // SSE 가 조용히 죽어도 진행률이 멈추면 안 된다 — 10초 백업 폴링
        // (웹 대시보드가 쓰는 것과 동일한 계약)
        pollTask?.cancel()
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(10))
                if !Task.isCancelled { await refresh() }
            }
        }
    }

    /// 속도 샘플러 — **1초 주기로 스스로 시작한다.**
    ///
    /// 서버 tick 으로 부르지 않는 이유: 서버는 상태가 바뀔 때만 tick 을 보내고 평소엔
    /// 15초 beat 이다. 그걸로 그래프를 그리면 평소엔 직선이 되어 첨부 목업처럼
    /// 촘촘한 곡선이 나오지 않는다. 1초 폴링 1회로 클라이언트가 직접 쌓는다.
    private func startSpeedSampling() {
        speedTask?.cancel()
        speedTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { break }
                await refresh()
                await sampleDeviceTraffic()
            }
        }
    }

    /// 기기 트래픽을 읽어 속도로 바꾼다.
    ///
    /// **서버가 값을 안 주면 `deviceSpeedAvailable = false` 로 두고 열을 만들지 않는다.**
    /// `0` 으로 대체하면 "지원하지만 지금 0" 과 "지원 안 함" 이 구분되지 않아,
    /// 스위치를 켜놓고 항상 0 이 보이는 **고장 난 기능** 이 된다.
    private func sampleDeviceTraffic() async {
        guard let s = server else { return }
        let cur = await RelayClient(base: s.baseURL).deviceTraffic()
        guard let cur else {
            if deviceSpeedAvailable { deviceSpeedAvailable = false; deviceSpeed = .init(downBps: 0, upBps: 0) }
            return
        }
        deviceSpeedAvailable = true
        let now = Date()
        let dt = now.timeIntervalSince(lastDeviceTraffic?.at ?? now)
        deviceSpeed = DeviceTrafficRate.rate(
            previous: lastDeviceTraffic?.t, current: cur, elapsed: dt
        )
        lastDeviceTraffic = (cur, now)
        history.push(deviceSpeed)
    }

    func disconnect() {
        Task { await stream.stop() }
        pollTask?.cancel()
        pollTask = nil
        speedTask?.cancel()
        speedTask = nil
    }

    // MARK: - 표시용

        func openDashboard() {
        guard let s = server else { return }
        NSWorkspace.shared.open(s.baseURL)
    }
}
