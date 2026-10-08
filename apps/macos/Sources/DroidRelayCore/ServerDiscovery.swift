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
        if let c = cached, let fresh = await RelayClient.probe(c.baseURL) {
            return Result(info: fresh, strategy: .cached, elapsed: 0)
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

    /// 병렬 스캔. 측정 기준 동시 128 — 254호스트가 0.11초였다.
    ///
    /// ## 왜 파이프라이닝인가 (2026-10-08 실제 버그)
    ///
    /// 이전 코드는 처음 128개만 태스크로 올리고 `for await` 로 기다렸다.
    /// 128개가 전부 실패하면 그대로 `nil` — 나머지 129~254는 **한 번도 안 두드렸다.**
    /// `.211` 같은 흔한 폰 주소가 뒤쪽이라 집 공유기에서 자동 탐색이 영영 실패했다.
    /// → 끝난 자리마다 다음 호스트를 채워 **전부를** 훑는다. 동시성은 유지된다.
    /// `probe` 를 주입받는 이유 — 테스트에서 네트워크 없이 "전부를 훑는가" 를 본다.
    func scan(
        _ hosts: [String],
        ports: [Int],
        limit: Int = 128,
        probe: @Sendable @escaping (URL) async -> ServerInfo? = RelayClient.probe
    ) async -> ServerInfo? {
        guard !hosts.isEmpty, !ports.isEmpty else { return nil }
        return await withTaskGroup(of: ServerInfo?.self) { group in
            var idx = 0
            var inFlight = 0
            func enqueueHost() -> Bool {
                guard idx < hosts.count else { return false }
                let h = hosts[idx]; idx += 1
                for p in ports {
                    let host = h
                    let port = p
                    group.addTask { await probe(URL(string: "http://\(host):\(port)")!) }
                    inFlight += 1
                }
                return true
            }
            // 처음 `limit` 개 태스크까지 채운다 (포트가 여러 개면 호스트 수는 그만큼 적다).
            while inFlight < limit, enqueueHost() {}
            while let r = await group.next() {
                inFlight -= 1
                if let r { group.cancelAll(); return r }
                while inFlight < limit, enqueueHost() {}
                if inFlight == 0 { break }
            }
            return nil
        }
    }
}
