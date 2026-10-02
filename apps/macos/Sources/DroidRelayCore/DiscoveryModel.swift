import Foundation

/// 서버 주소 후보에서 DroidRelay 응답을 확인한다.
///
/// 프로토콜 근거: `/api/info` 응답은 `port` 와 `version` 을 반드시 담는다.
/// 포트만 열린 게 아니라 실제 DroidRelay 인지 확인하기 위한 최소 신호다.
public struct ServerInfo: Equatable, Sendable {
    public let host: String
    public let port: Int
    public let version: String

    public init(host: String, port: Int, version: String) {
        self.host = host; self.port = port; self.version = version
    }

    public var baseURL: URL { URL(string: "http://\(host):\(port)")! }
    public var displayAddress: String { "\(host):\(port)" }
}

/// IPv4 주소 — 발견 로직은 `String` 분기 대신 이 타입으로 한다(테스트 가능).
public struct IPv4: Equatable, Sendable {
    public let parts: [Int]

    public init(parts: [Int]) { self.parts = parts }
    public init(intValue: Int) {
        parts = [(intValue >> 24) & 0xFF, (intValue >> 16) & 0xFF, (intValue >> 8) & 0xFF, intValue & 0xFF]
    }
    public init?(_ s: String) {
        let p = s.split(separator: ".").compactMap { Int($0) }
        guard p.count == 4, p.allSatisfy({ (0...255).contains($0) }) else { return nil }
        parts = p
    }

    /// 넷마스크의 연속 1 비트 수. `255.255.255.0` → 24, `255.255.0.0` → 16
    public var prefix: Int {
        var n = 0
        for b in parts {
            for i in (0...7).reversed() where (b >> i) & 1 == 1 { n += 1 }
        }
        return n
    }

    public var intValue: Int { parts[0] << 24 | parts[1] << 16 | parts[2] << 8 | parts[3] }

    /// 접두사가 `newPrefix` 인 새 마스크를 만든다.
    ///
    /// **기존 값과 무관해야 한다** — 이 메서드는 넷마스크에 대해 호출하는데,
    /// `masked(by:)` 로 구현하면 `255.255.0.0` 이 `255.255.255.0` 과 AND 가 되어
    /// 여전히 `/16` 이 남는다(테스트가 잡아낸 실제 버그).
    public func withPrefix(_ newPrefix: Int) -> IPv4 {
        guard newPrefix > 0 else { return IPv4(intValue: 0) }
        let m = Int(UInt32.max) << UInt32(32 - newPrefix)
        return IPv4(intValue: m)
    }

    public func masked(by mask: IPv4) -> IPv4 {
        IPv4(parts: zip(parts, mask.parts).map { $0 & $1 })
    }

    public var description: String { parts.map(String.init).joined(separator: ".") }
}

/// 로컬 IP 와 넷마스크에서 스캔 대상 서브넷을 만든다.
///
/// **왜 넷마스크가 필요한가** — 게이트웨이 주소만으로는 스캔 범위를 알 수 없다.
/// `/16` 에 붙은 Mac 은 그 서브넷만으로 6만 5천 개 호스트가 된다.
/// 그래서 넷마스크로 실제 서브넷을 계산하고 큰 서브넷은 `/24` 로 **캡**한다.
/// 캡하지 않으면 우연히 수백 개 IP 를 두드린다 — 큰 가정 절약.
public enum Subnet {
    public static let scanCapBits = 24

    /// `/24` 로 캡된 스캔 대상 호스트 목록. 네트워크/브로드캐스트는 제외한다.
    ///
    /// **캡은 `max` 다** — 접두사 숫자가 클수록 서브넷이 **작아진다**.
    /// `min(prefix, 24)` 로 쓰면 `/16` 이 그대로 남아 65,534개를 스캔한다
    /// (테스트가 잡아낸 실제 버그). `/24` 는 `max(prefix, 24)` 로 만들어야 254개가 된다.
    public static func scanHosts(ipv4: String, netmask: String) -> [String] {
        guard let ip = IPv4(ipv4), let mask = IPv4(netmask) else { return [] }
        let effective = mask.withPrefix(max(mask.prefix, scanCapBits))
        let base = ip.masked(by: effective).intValue
        let size = 1 << (32 - effective.prefix)
        guard size > 2 else { return [] }
        return (1..<(size - 1)).map { IPv4(intValue: base + $0).description }
    }
}

/// 탐색 전략 — 실측에 근거한다 (2026-09-27, `docs/mockups/probe_discovery.py`).
///
/// | 전략 | 실측 | 성립 조건 |
/// |---|---|---|
/// | `beacon` | 즉시 | 폰이 **주기적으로 자기를 알린다** (T-1090) |
/// | `cached` | 0ms | 이전에 찾은 주소 |
/// | `gateway` | 35ms | **폰이 핫스팟** — 게이트웨이가 곧 폰 |
/// | `subnetScan` | 0.11초 | 폰과 Mac 이 같은 공유기 아래 |
/// | `manual` | — | 위가 전부 실패 |
///
/// 게이트웨이만 해서는 부족하다. 폰이 공유기를 단 말단으로 쓰면(phone=10.38.120.147,
/// gateway=10.38.120.1) 게이트웨이 조회에 폰이 없다. 그래서 계층이 필요하다.
///
/// ## `beacon` 이 가장 앞에 온 이유 — T-1090
///
/// 폰이 **주기적으로 자기를 알린다**면 Mac 은 **아무것도 하지 않고** 주소만 받으면 된다.
/// **254개를 두드릴 이유가 없어진다.**
///
/// ★ **이 전략이 실패해도 나머지가 그대로 돈다** — 안전망이 아니라 **가속 장치**다.
/// 그래서 **먼저** 시도한다. 가장 빠른 경로를 굳이 뒤로 미룰 이유가 없다.
public enum DiscoveryStrategy: String, Sendable, CaseIterable {
    case beacon
    case cached
    case gateway
    case subnetScan
    case manual

    public var displayName: String {
        switch self {
        case .beacon: return "폰 알림"
        case .cached: return "저장된 주소"
        case .gateway: return "게이트웨이"
        case .subnetScan: return "네트워크 검색"
        case .manual: return "수동 입력"
        }
    }
}

/// 포트 후보. DroidRelay 은 포트를 바꿀 수 있다(랜덤 포트 설정) — 3000 만 보면 못 찾는다.
public enum PortCandidate {
    public static let all: [Int] = [3000, 8080, 8000, 8443]
}

public struct DiscoveryContext: Sendable {
    public let localIPv4: String?
    public let netmask: String?
    public let gateway: String?

    public init(localIPv4: String?, netmask: String?, gateway: String?) {
        self.localIPv4 = localIPv4
        self.netmask = netmask
        self.gateway = gateway
    }
}
