import Foundation

/// DroidRelay 서버 탐색 실행기.
///
/// 계층 전략은 실측에 근거한다 (2026-09-27, `docs/mockups/probe_discovery.py`):
/// 게이트웨이 35ms · /24 스캔 0.11초. 순서와 상관없이 총 0.15초면 끝난다.
/// 사용자가 체감할 수준이 아니라서 mDNS 같은 무거운 탐색은 필요 없다.
public actor ServerDiscovery {

    public struct Result: Sendable {
        public let info: ServerInfo
        public let strategy: DiscoveryStrategy
        public let elapsed: TimeInterval
    }

    /// 저장된 주소 (UserDefaults). 순서와 무관하게 가장 빠르다.
    public var cached: ServerInfo?
    public init(cached: ServerInfo? = nil) { self.cached = cached }

    // MARK: - 탐색

    public func discover() async -> Result? {
        if let c = cached, await RelayClient.probe(c.baseURL) != nil {
            return Result(info: c, strategy: .cached, elapsed: 0)
        }
        let ctx = Self.currentContext()
        if let r = await tryGateway(ctx) { return r }
        if let r = await trySubnetScan(ctx) { return r }
        return nil
    }

    public static func currentContext() -> DiscoveryContext {
        let i = NetworkInfo.currentInterface()
        return DiscoveryContext(localIPv4: i?.ipv4, netmask: i?.netmask, gateway: i?.gateway)
    }

    /// 2순위 — 게이트웨이 조회. **핫스팟일 때 폰이 곧 게이트웨이**라 이것으로 끝난다.
    private func tryGateway(_ ctx: DiscoveryContext) async -> Result? {
        guard let gw = ctx.gateway else { return nil }
        let t0 = Date()
        for port in PortCandidate.all {
            let base = URL(string: "http://\(gw):\(port)")!
            if let info = await RelayClient.probe(base) {
                return Result(info: info, strategy: .gateway, elapsed: Date().timeIntervalSince(t0))
            }
        }
        return nil
    }

    /// 3순위 — /24 스캔. 폰과 Mac 이 같은 공유기 아래일 때.
    private func trySubnetScan(_ ctx: DiscoveryContext) async -> Result? {
        guard let ip = ctx.localIPv4, let mask = ctx.netmask else { return nil }
        let hosts = Subnet.scanHosts(ipv4: ip, netmask: mask)
        guard !hosts.isEmpty else { return nil }
        let t0 = Date()

        // 1차 패스 — 각 호스트의 기본 포트만 (대부분 1회로 끝난다)
        let first = PortCandidate.all.first ?? 3000
        if let info = await scan(hosts, ports: [first]) {
            return Result(info: info, strategy: .subnetScan, elapsed: Date().timeIntervalSince(t0))
        }
        // 2차 패스 — 포트를 바꿔 쓴 경우
        let rest = Array(PortCandidate.all.dropFirst())
        if let info = await scan(hosts, ports: rest) {
            return Result(info: info, strategy: .subnetScan, elapsed: Date().timeIntervalSince(t0))
        }
        return nil
    }

    /// 병렬 스캔. 측정 기준 병렬 128 — 254호스트가 0.11초였다.
    private func scan(_ hosts: [String], ports: [Int], limit: Int = 128) async -> ServerInfo? {
        await withTaskGroup(of: ServerInfo?.self) { group in
            var it = hosts.makeIterator()
            var running = 0
            while running < limit, let h = it.next() {
                for p in ports {
                    group.addTask { await RelayClient.probe(URL(string: "http://\(h):\(p)")!) }
                }
                running += 1
            }
            for await r in group {
                if let r { group.cancelAll(); return r }
            }
            return nil
        }
    }
}
