import Foundation

/// DroidRelay 서버 탐색 실행기.
///
/// 계층 전략은 실측에 근거한다 (2026-09-27, `docs/mockups/probe_discovery.py`):
/// 게이트웨이 35ms · /24 스캔 0.11초. 순서와 상관없이 총 0.15초면 끝난다.
/// 사용자가 체감할 수준이 아니라서 mDNS 같은 무거운 탐색은 필요 없다.
public actor ServerDiscovery {

    public struct Result: Sendable, Equatable {
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

    /// **네트워크의 모든 DroidRelay 를 수집한다** — 기기 선택 목록용.
    ///
    /// ## 왜 `discover()` 와 따로인가
    ///
    /// `discover()` 는 **처음 응답한 1대**에서 멈춘다(빠른 연결용).
    /// 폰이 2대(S22·A90)면 먼저 답한 쪽만 보이고 다른 쪽은 영영 안 보인다 —
    /// 사용자가 겪은 그대로다. 목록용은 **전부를 훑어야** 한다.
    ///
    /// 비용이 다르다: 단일 탐색은 0.15초, 전체 수집은 서브넷 전부(254호스트)를
    /// 다 두드려 ~1~2초다. 그래서 **자동 연결에는 쓰지 않고**
    /// 사용자가 "다시 찾기" 를 누를 때·연결 후 백그라운드 1회에만 쓴다.
    public func discoverAll() async -> [Result] {
        await discoverAll(probe: RelayClient.probe)
    }

    /// `probe` 주입 — 테스트에서 네트워크 없이 합치기/정렬을 본다.
    func discoverAll(
        probe: @Sendable @escaping (URL) async -> ServerInfo?
    ) async -> [Result] {
        var out: [Result] = []
        let t0 = Date()
        if let c = cached, let fresh = await probe(c.baseURL) {
            out.append(Result(info: fresh, strategy: .cached,
                              elapsed: Date().timeIntervalSince(t0)))
        }
        let ctx = Self.currentContext()
        if let gw = ctx.gateway {
            for port in PortCandidate.all {
                if let info = await probe(URL(string: "http://\(gw):\(port)")!) {
                    out.append(Result(info: info, strategy: .gateway,
                                      elapsed: Date().timeIntervalSince(t0)))
                    break
                }
            }
        }
        if let ip = ctx.localIPv4, let mask = ctx.netmask {
            let hosts = Subnet.scanHosts(ipv4: ip, netmask: mask)
            if !hosts.isEmpty {
                let first = PortCandidate.all.first ?? 3000
                var found = await scanAll(hosts, ports: [first], probe: probe)
                if found.isEmpty {
                    let rest = Array(PortCandidate.all.dropFirst())
                    if !rest.isEmpty {
                        found = await scanAll(hosts, ports: rest, probe: probe)
                    }
                }
                let elapsed = Date().timeIntervalSince(t0)
                out.append(contentsOf: found.map {
                    Result(info: $0, strategy: .subnetScan, elapsed: elapsed)
                })
            }
        }
        return Self.merged(out)
    }

    /// **중복 제거 + 주소 정렬** — 순수 함수라 OS 없이 검증한다.
    ///
    /// 같은 폰이 cached·gateway·subnet 세 경로에서 **세 번** 잡힌다.
    /// 그대로 목록에 두면 "같은 주소 3줄" 이 된다.
    /// 정렬은 **화면이 매번 같은 순서**로 보이기 위함 — 스캔은 병렬이라
    /// 응답 순서가 실행마다 다르다.
    static func merged(_ results: [Result]) -> [Result] {
        var seen = Set<String>()
        var out: [Result] = []
        for r in results {
            if seen.insert(r.info.displayAddress).inserted { out.append(r) }
        }
        return out.sorted { $0.info.displayAddress < $1.info.displayAddress }
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

    /// **전부를 훑어 성공한 전부를 모은다** — 기기 목록용.
    ///
    /// ## 왜 `scan` 과 따로인가
    ///
    /// `scan` 은 **처음 성공에서 `cancelAll` 로 멈춘다.** 2대째는 한 번도 안 본다.
    /// 목록은 반대다 — 254개를 **끝까지** 다 두드려야 S22·A90 둘 다 보인다.
    /// 파이프라이닝(끝난 자리 채우기)은 `scan` 과 같다 — 앞쪽만 보고 뒤를
    /// 놓치던 버그(2026-10-08)를 여기서 반복하지 않는다.
    func scanAll(
        _ hosts: [String],
        ports: [Int],
        limit: Int = 128,
        probe: @Sendable @escaping (URL) async -> ServerInfo? = RelayClient.probe
    ) async -> [ServerInfo] {
        guard !hosts.isEmpty, !ports.isEmpty else { return [] }
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
            while inFlight < limit, enqueueHost() {}
            var found: [ServerInfo] = []
            while let r = await group.next() {
                inFlight -= 1
                if let r { found.append(r) }
                while inFlight < limit, enqueueHost() {}
                if inFlight == 0 { break }
            }
            return found
        }
    }
}
