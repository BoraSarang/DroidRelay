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
    /// 기기(폰 전체) 속도 — 서버가 `TrafficStats` 로 잰다. 없으면 0 유지.
    var deviceSpeed: SpeedReading = .init(downBps: 0, upBps: 0)
    /// 그래프용 이력 — 1초 주기로 스스로 채운다
    let history = SpeedHistory(capacity: 300)
    /// 속도 샘플러 — 서버 tick(가변 간격)으로 부하를 늘리지 않고 1초에 한 번만 뽑는다
    private var speedTask: Task<Void, Never>?
    /// 기기 속도를 제공할지 여부 — 서버에 엔드포인트가 생기기 전엔 항상 false
    var deviceSpeedAvailable = false
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

    /// 메뉴바에 표시할 출처 — 설정으로 고른다
    var speedSetting = SpeedDisplaySetting.default {
        didSet { speedSetting.save() }
    }

    func speed(for source: SpeedSource) -> SpeedReading {
        switch source {
        case .droid: return droidSpeed
        case .device: return deviceSpeed
        }
    }

    /// Droid 속도 = HTTP 잡(다운로드만) + 활성 토렌트(다운/업)
    private func recomputeDroidSpeed() {
        let jobsDown = jobs.filter { $0.state == "RUNNING" }.reduce(0) { $0 + $1.speedBps }
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
        async let t = selectedTab == .torrents ? client.torrents() : torrents
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
            }
        }
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
