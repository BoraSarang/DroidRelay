import Foundation

/// 네트워크 인터페이스 정보.
///
/// **게이트웨이는 `getifaddrs` 로 얻을 수 없다** (macOS 의 ifaddrs 에는 기본 경로가 없다).
/// 그래서 `route -n get default` 를 쓴다 — 앱 시작 시 1회뿐이라 프로세스 비용은 무시할 수 있다.
/// `SCNetworkReachability` 계열은 C 인터롭이 불안정하고(availability 제한, 메모리 관리 강제)
/// 여기다 얻는 인터페이스 이름조차 신뢰하기 어렵다.
public enum NetworkInfo {

    public struct Interface: Sendable {
        public let name: String
        public let ipv4: String
        public let netmask: String
        public let gateway: String?

        public init(name: String, ipv4: String, netmask: String, gateway: String?) {
            self.name = name; self.ipv4 = ipv4; self.netmask = netmask; self.gateway = gateway
        }
    }

    /// 로컬 IP + 넷마스크를 읽는다.
    public static func localIPv4(interface: String) -> (ip: String, mask: String)? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard let sa = ptr.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            var nameBuf = [CChar](repeating: 0, count: Int(IFNAMSIZ))
            strncpy(&nameBuf, ptr.pointee.ifa_name, Int(IFNAMSIZ) - 1)
            guard String(cString: nameBuf) == interface else { continue }
            guard let maskSA = ptr.pointee.ifa_netmask else { return nil }

            // `sa` 는 이미 포인터다. `withUnsafePointer(to: sa)` 를 한 번 더 씌우면
            // **포인터 변수 자체의 주소**를 넘겨 sin_addr 가 포인터 값(0x7700…)을 읽고
            // 로컬 IP 가 119.0.0.0 처럼 깨진다 (진단 모드가 잡아낸 실제 버그).
            var addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            var mask = maskSA.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            var abuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &addr, &abuf, socklen_t(INET_ADDRSTRLEN))
            var mbuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &mask, &mbuf, socklen_t(INET_ADDRSTRLEN))
            return (String(cString: abuf), String(cString: mbuf))
        }
        return nil
    }

    /// `route -n get default` 출력에서 게이트웨이 / 인터페이스를 뽑는다.
    ///
    /// ```
    ///   route to: default
    ///   destination: default
    ///   gateway: 10.38.120.211
    ///   interface: en0
    /// ```
    public static func defaultRoute() -> (gateway: String?, interface: String?)? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/sbin/route")
        p.arguments = ["-n", "get", "default"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = Pipe()
        do { try p.run() } catch { return nil }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        let text = String(data: data, encoding: .utf8) ?? ""
        return (
            gateway: field("gateway", in: text),
            interface: field("interface", in: text)
        )
    }

    /// 게이트웨이 IP (핫스팟이면 이것이 곧 폰).
    public static func defaultGateway() -> String? { defaultRoute()?.gateway }

    /// 실제 경로 인터페이스를 쓰고 Wi-Fi 로 가정한다 (en*).
    ///
    /// VPN 터널이나 USB 네트워킹이 켜져 있으면 en0 이 아닌 곳이 기본 경로가 된다.
    /// 게이트웨이 조회·스캔은 **모두 Wi-Fi 기준**이어야 하므로 en 접두로 걸러낸다.
    public static func wifiInterface() -> String? {
        let ifn = defaultRoute()?.interface
        if let ifn, ifn.hasPrefix("en") { return ifn }
        return enumerateNames().first { $0.hasPrefix("en") }
    }

    /// 탐색에 필요한 모든 것을 한 번에.
    public static func currentInterface() -> Interface? {
        let route = defaultRoute()
        let ifn = (route?.interface?.hasPrefix("en") == true) ? route!.interface! : wifiInterface()
        guard let ifn, let loc = localIPv4(interface: ifn) else { return nil }
        return Interface(name: ifn, ipv4: loc.ip, netmask: loc.mask, gateway: route?.gateway)
    }

    // MARK: - 내부

    private static func field(_ key: String, in text: String) -> String? {
        for line in text.split(separator: "\n") {
            let t = line.trimmingCharacters(in: .whitespaces)
            guard t.hasPrefix(key + ":") else { continue }
            return String(t.dropFirst(key.count + 1)).trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    private static func enumerateNames() -> [String] {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        var out: [String] = []
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            var b = [CChar](repeating: 0, count: Int(IFNAMSIZ))
            strncpy(&b, ptr.pointee.ifa_name, Int(IFNAMSIZ) - 1)
            out.append(String(cString: b))
        }
        return out
    }
}
