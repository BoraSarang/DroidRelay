import Foundation

struct DiscoveredServer: Identifiable, Hashable {
    let address: String // "http://192.168.x.x:8080"
    let info: ServerInfo
    var id: String { address }
}

/// LAN /24 서브넷 스캔으로 DroidRelay 서버 발견
enum ServerDiscovery {
    /// 로컬 기기의 IPv4 주소 목록 (루프백 제외)
    static func localAddresses() -> [String] {
        var out: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return out }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let iface = p.pointee
            if let sa = iface.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let r = getnameinfo(sa, socklen_t(sa.pointee.sa_len),
                                    &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                if r == 0 {
                    let ip = String(cString: host)
                    if ip != "127.0.0.1" { out.append(ip) }
                }
            }
            ptr = iface.ifa_next
        }
        return Array(Set(out)).sorted()
    }

    /// 로컬 IP가 속한 /24 대역의 후보 URL 생성 (192.168.0.1 ~ 254)
    static func candidates(for ip: String, port: Int) -> [URL] {
        let parts = ip.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4,
              (0 ... 255).contains(parts[0]),
              (0 ... 255).contains(parts[1]),
              (0 ... 255).contains(parts[2]) else { return [] }
        return (1 ... 254).compactMap { h in
            URL(string: "http://\(parts[0]).\(parts[1]).\(parts[2]).\(h):\(port)")
        }
    }

    /// 단일 주소 프로브 — GET /api/info 성공 시 DroidRelay 서버로 판정
    static func probe(_ base: URL, auth: String?) async -> DiscoveredServer? {
        var req = URLRequest(url: base.appendingPathComponent("api/info"))
        req.timeoutInterval = 0.7
        if let auth { req.setValue(auth, forHTTPHeaderField: "Authorization") }
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse,
              http.statusCode == 200,
              let info = try? JSONDecoder().decode(ServerInfo.self, from: data),
              info.port != nil else { return nil }
        return DiscoveredServer(address: base.absoluteString, info: info)
    }

    /// 서브넷 전체 동시 프로브 (128개씩 배치)
    static func scan(port: Int = 8080, auth: String?) async -> [DiscoveredServer] {
        var urls: [URL] = []
        var seen = Set<String>()
        for ip in localAddresses() {
            for u in candidates(for: ip, port: port) where seen.insert(u.absoluteString).inserted {
                urls.append(u)
            }
        }
        DebugLog.shared.i("Scan", "스캔 시작: 후보 \(urls.count)개 (포트 \(port))")

        var found: [DiscoveredServer] = []
        for chunk in stride(from: 0, to: urls.count, by: 128) {
            let slice = urls[chunk ..< min(chunk + 128, urls.count)]
            let part = await withTaskGroup(of: DiscoveredServer?.self) { group in
                for u in slice { group.addTask { await probe(u, auth: auth) } }
                var list: [DiscoveredServer] = []
                for await s in group where s != nil { list.append(s!) }
                return list
            }
            found.append(contentsOf: part)
        }
        DebugLog.shared.i("Scan", "스캔 완료: 발견 \(found.count)대")
        return found.sorted { $0.address < $1.address }
    }
}
