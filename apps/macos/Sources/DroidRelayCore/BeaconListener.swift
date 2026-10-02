import Foundation

/// **폰이 보낸 "나 여기있소" 신호** (T-1090).
///
/// 안드로이드 `DiscoveryBeacon` 이 UDP 브로드캐스트로 보내는 한 줄 JSON.
/// Mac 은 이걸 받아 **스캔하지 않고** 바로 붙는다.
///
/// ## 왜 브로드캐스트인데 `ip` 를 실어 보내나
///
/// 브로드캐스트 패킷에는 **발신자 주소가 없다.** 맥은 "누가 보냈는가" 를 알 수 없다.
/// → **폰이 자기 IP 를 직접 넣어야 한다.** 이걸 빼면 맥이 자기 주소로 보내고
/// 자기 패킷을 받아 **자기 폰을 자기 폰으로 찾는** 참사가 난다.
public struct BeaconSignal: Equatable, Sendable {
    public let host: String
    public let port: Int
    public let version: String

    public init(host: String, port: Int, version: String) {
        self.host = host; self.port = port; self.version = version
    }

    public var info: ServerInfo { ServerInfo(host: host, port: port, version: version) }

    /// 포맷 상수 — 안드로이드 `DiscoveryBeacon` 과 **반드시 같아야 한다.**
    ///
    /// 두 벌 쓰면 한쪽만 고쳐지고 **조용히 안 통한다.** 값이 다르면 *조용히* 무시된다.
    public static let appID = "DroidRelay"
    public static let port: UInt16 = 45454

    /// **신호 한 줄을 해석한다.**
    ///
    /// - Returns: 해석되면 시그널. **아니면 `nil`** — 절대 임의 값을 만들지 않는다.
    ///
    /// ## 왜 이렇게 보수적으로 거르나
    ///
    /// 45454 는 임의로 고른 포트라 **다른 프로그램의 패킷이 섞일 수 있다.**
    /// 그런데 "알았다" 하고 **엉뚱한 주소를 붙이면** 사용자는
    /// "왜 갑자기 옛날 자료가 나오지" 하고 원인을 알 수 없다.
    ///
    /// → **확신할 수 없으면 버린다.** 신호는 **가속 장치**일 뿐이라
    /// 버려도 기존 3단계 탐색이 그대로 돈다(M-22 교훈 — 없는 값을 지어내지 않는다).
    public static func parse(_ line: String) -> BeaconSignal? {
        // **공백·개행으로 정리한다.** UDP 라우팅 때문에 뒤에 개행이 붙을 수 있다.
        let text = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.hasPrefix("{") else { return nil }
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        // **식별자가 다르면 남의 패킷이다.**
        guard obj["app"] as? String == appID else { return nil }

        // **IP 가 유효한지 직접 본다** — "10.0.0.999" 를 그대로 쓰면 무의미한 프로브가 된다.
        guard let ip = obj["ip"] as? String, IPv4(ip) != nil else { return nil }
        guard let p = obj["port"] as? Int, (1...65535).contains(p) else { return nil }
        // **버전이 없어도 된다** — 새 키가 추가돼도 구버전 파서가 죽지 않게 하는 것이 목적이다.
        // 표시에는 주소와 방법이 먼저다(`DiscoveryBadge`).
        let v = (obj["v"] as? String) ?? ""

        return BeaconSignal(host: ip, port: p, version: v)
    }
}

/// **UDP 신호 수신기** — 프로세스당 **딱 하나**만 떠 있어야 한다.
///
/// ## ★ 왜 "딱 하나" 가 규칙인가
///
/// `ServerDiscovery.discover()` 는 **재탐색마다** 불린다(사용자가 "다시 찾기" 를 누를 때마다).
/// 리스너를 거기서 새로 만들면 **리스너가 쌓이고 포트를 못 연다**(누가 이미 쥐고 있으니).
/// 결국 **작동하지 않게 된다.**
///
/// → **한 번만 만들고 재사용**한다. 상태는 [lastSignal] 하나뿐이다.
///
/// ## ★ 실패해도 탐색은 돈다
///
/// 소켓을 못 열어도(방화벽·포트 사용 중) 신호 없이 넘어갈 뿐이다.
/// `ServerDiscovery` 는 그 뒤 `cached` → `gateway` → `subnetScan` 을 그대로 시도한다.
public actor BeaconListener {

    public static let shared = BeaconListener()

    private var fd: Int32 = -1
    private var started = false
    private var pump: Thread?
    /// **마지막으로 받은 유효 신호** — 탐색이 여기서 읽는다.
    public private(set) var lastSignal: BeaconSignal?

    public init() {}

    /// 수신 대기를 시작한다 — **이미 떠 있으면 아무것도 하지 않는다.**
    @discardableResult
    public func start() -> Bool {
        guard !started else { return true }
        started = true
        // **UDP 소켓** — `SOCK_DGRAM` 은 **열기만 하고 안 묶는다**(bind 없음).
        // → **폰이 죽어도 즉시 `ECONNREFUSED` 가 온다**(TCP 는 안 그러므로 TCP 로 하면 안 된다).
        // 이 앱은 **주기 발신만** 하므로 열린 포트가 없다. 안드로이드도 같은 이유로 UDP 다.
        let s = socket(AF_INET, SOCK_DGRAM, 0)
        guard s >= 0 else {
            NSLog("[DroidRelay] 폰 알림 소켓 생성 실패: \(errno)")
            return false
        }
        var one: Int32 = 1
        // **재시작 직후 바로 다시 듣는다** — 이전 프로세스가 쥔 포트를 넘겨받는다.
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        // **브로드캐스트 수신을 허용한다.** 이게 없으면 발신은 되는데 수신이 0건이 된다.
        setsockopt(s, SOL_SOCKET, SO_BROADCAST, &one, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = BeaconSignal.port.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)
        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bindResult == 0 else {
            NSLog("[DroidRelay] 폰 알림 바인드 실패(무시하고 기존 탐색으로): \(errno)")
            close(s)
            return false
        }
        fd = s
        // **루프는 소켓 fd 를 값으로 받아간다.** 액터 격리 상태를 건드리지 않는다 —
        // `recv` 는 **멈추는 호출**이라 액터 안에서 돌리면 **그 액터 전체가 잠긴다.**
        let t = Thread { [weak self] in self?.loop(fd: s) }
        t.name = "DroidRelay-Beacon"
        t.stackSize = 64 * 1024
        pump = t
        t.start()
        return true
    }

    /// **수신 루프** — 포트가 닫히면 끝난다.
    private nonisolated func loop(fd: Int32) {
        var buf = [UInt8](repeating: 0, count: 2048)
        while true {
            let n = recv(fd, &buf, buf.count, 0)
            // **다른 패킷은 조용히 버린다** — 루프는 절대 죽지 않는다.
            guard n > 0 else {
                if n == 0 { return }
                if errno == EINTR { continue }
                return   // 소켓이 닫혔거나 회선 오류 — 더 이상 받을 수 없다
            }
            let line = String(decoding: buf[0..<n], as: UTF8.self)
            guard let sig = BeaconSignal.parse(line) else { continue }
            Task { await self.receive(sig) }
        }
    }

    private func receive(_ sig: BeaconSignal) { lastSignal = sig }

    /// 진단용 — 지금 신호가 들어왔는가.
    public func debugSignal() -> String {
        lastSignal.map { "\($0.host):\($0.port) v\($0.version)" } ?? "없음"
    }
}
