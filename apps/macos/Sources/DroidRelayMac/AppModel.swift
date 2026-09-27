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

    var phase: Phase = .idle
    var server: ServerInfo?
    var jobs: [RelayClient.Job] = []
    var storageFree: Int = 0
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
    var badgeCount: Int { activeJobs.count }

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
        async let j = client.jobs()
        async let i = client.serverInfo()
        let (jobs, info) = await (j, i)
        self.jobs = jobs
        if let info {
            storageFree = info.storageFree
            storageTotal = info.storageTotal
        }
        lastUpdate = Date()
    }

    func control(_ id: String, _ action: String) {
        guard let s = server else { return }
        Task {
            await RelayClient(base: s.baseURL).control(id, action)
            await refresh()
        }
    }

    // MARK: - 실시간

    private func subscribe() {
        guard let s = server else { return }
        Task { await stream.run(base: s.baseURL) { @Sendable [weak self] in
            await self?.refresh()
        } }
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

    func disconnect() {
        Task { await stream.stop() }
        pollTask?.cancel()
        pollTask = nil
    }

    // MARK: - 표시용

        func openDashboard() {
        guard let s = server else { return }
        NSWorkspace.shared.open(s.baseURL)
    }
}
