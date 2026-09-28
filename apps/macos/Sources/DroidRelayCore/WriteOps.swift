import Foundation

/// macOS 클라이언트가 **쓰기** 동작을 할 때 받는 결과.
///
/// ## 왜 `Result` 를 쓰는지
///
/// 서버는 실패를 **HTTP 상태 코드 + 본문 텍스트**로 돌려준다.
/// ```
/// 422  E-AND-DOWN-1003: 유효한 http(s) URL이 아닙니다
/// 409  {"error":"E-AND-DOWN-1006: 이미 등록된 다운로드입니다"}
/// 404  없음
/// ```
/// `Data` 를 그대로 던져도 되지만 그러면 UI 가 상태 코드마다 문자열을 다시 만들어야 한다.
/// **서버가 준 사유를 그대로 보여주는** 게 정직하다(사용자가 볼 오류 메시지가
/// 서버 진짜 이유이기 때문에).
public struct WriteResult: Sendable, Equatable {
    public var ok: Bool
    /// 사용자에게 보여줄 사유. 성공이면 빈 문자열.
    public var message: String
    /// 새로 만들어진 항목의 id (있으면). 목록 갱신에 쓴다.
    public var newId: String?

    public static let success = WriteResult(ok: true, message: "", newId: nil)
    public static func fail(_ m: String) -> WriteResult { WriteResult(ok: false, message: m, newId: nil) }

    public init(ok: Bool, message: String, newId: String? = nil) {
        self.ok = ok; self.message = message; self.newId = newId
    }
}

/// 토렌트 상세 — 시드/피어 정보.
///
/// **목록(`/api/torrents`)에는 없는 값**들이 여기 있다. 시드가 몇 명 붙어 있는지,
/// 지금 몇 명이 연결돼 있는지, 어느 트래커를 쓰고 있는지 — 사용자가 "왜 안Sac 내려와"
/// 를 판단하는 근거가 전부 여기 있다.
public struct TorrentDetail: Sendable, Equatable {
    public var id: String
    public var name: String
    public var state: String
    public var progress: Double
    public var infoHash: String
    public var magnet: String
    public var savePath: String
    public var totalSize: Int
    public var downloadedSize: Int
    public var seeds: Int
    public var peers: Int
    /// 지금 **실제로 연결된** 시드/리처 (DB 값이 아니라 현재 세션 기준)
    public var connectedSeeders: Int
    public var connectedLeechers: Int
    public var connectedPeers: [Peer]
    public var currentTracker: String
    public var numConnections: Int
    public var numPieces: Int
    public var listSeeds: Int
    /// 스웜 전체 시드/완전본 수 — `seeds` 는 "트래커가 알려준 값" 이고 이건 "이 토렌트가
    /// 알고 있는 수" 다. 둘이 다르면 트래커가 뒤처진 것이라 **사용자가 알아야 한다.**
    public var numComplete: Int
    public var numIncomplete: Int
    /// 시딩 중인지 — 다운로드 완료 후 업로드만 도는 상태
    public var isSeeding: Bool
    public var downloadSpeed: Int
    public var uploadSpeed: Int
    public var errorMessage: String
    public var files: [TorrentFile]

    public init?(json o: [String: Any]) {
        guard let id = o["id"] as? String else { return nil }
        self.id = id
        self.name = o["name"] as? String ?? "(이름 없음)"
        self.state = o["state"] as? String ?? "UNKNOWN"
        self.progress = (o["progress"] as? NSNumber)?.doubleValue ?? 0
        self.infoHash = o["infoHash"] as? String ?? ""
        self.magnet = o["magnet"] as? String ?? ""
        self.savePath = o["savePath"] as? String ?? ""
        self.totalSize = RelayClient.int(o["totalSize"])
        self.downloadedSize = RelayClient.int(o["downloadedSize"])
        self.seeds = RelayClient.int(o["seeds"])
        self.peers = RelayClient.int(o["peers"])
        self.connectedSeeders = RelayClient.int(o["connectedSeeders"])
        self.connectedLeechers = RelayClient.int(o["connectedLeechers"])
        self.connectedPeers = (o["connectedPeers"] as? [Any])?.compactMap { e in
            (e as? [String: Any]).flatMap(Peer.init(json:))
        } ?? []
        self.currentTracker = o["currentTracker"] as? String ?? ""
        self.numConnections = RelayClient.int(o["numConnections"])
        self.numPieces = RelayClient.int(o["numPieces"])
        self.listSeeds = RelayClient.int(o["listSeeds"])
        self.numComplete = RelayClient.int(o["numComplete"])
        self.numIncomplete = RelayClient.int(o["numIncomplete"])
        self.isSeeding = (o["isSeeding"] as? NSNumber)?.boolValue ?? false
        self.downloadSpeed = RelayClient.int(o["downloadSpeed"])
        self.uploadSpeed = RelayClient.int(o["uploadSpeed"])
        self.errorMessage = o["errorMessage"] as? String ?? ""
        self.files = (o["files"] as? [Any])?.compactMap { e in
            (e as? [String: Any]).flatMap(TorrentFile.init(json:))
        } ?? []
    }
}

/// 연결된 피어 한 명
public struct Peer: Sendable, Equatable, Identifiable {
    public var id: String
    public var address: String
    public var progress: Double
    public var downSpeed: Int
    public var upSpeed: Int
    /// 시더 플래그 — **이게 "시드 정보" 의 핵심** 이다
    public var isSeeder: Bool
    public var client: String

    public init?(json o: [String: Any]) {
        // **서버 키는 `ip` 다** — `address` 로 읽으면 항상 빈 값이 된다(실측).
        // `address` 로도 받아둔다: 서버가 나중에 이름을 바꿔도 안 깨지게.
        let addr = (o["ip"] as? String) ?? (o["address"] as? String) ?? ""
        guard !addr.isEmpty else { return nil }
        self.id = o["id"] as? String ?? "\(addr):\(RelayClient.int(o["port"]))"
        self.address = addr
        self.progress = (o["progress"] as? NSNumber)?.doubleValue ?? 0
        self.downSpeed = RelayClient.int(o["downSpeed"])
        self.upSpeed = RelayClient.int(o["upSpeed"])
        // 서버는 flags 의 bit0 으로 시더를 판별한다 (Android libtorrent 와 동일)
        let flags = (o["flags"] as? NSNumber)?.intValue ?? 0
        self.isSeeder = (flags & 0x1) != 0
        self.client = o["client"] as? String ?? ""
    }
}

/// 토렌트 안의 파일 하나
public struct TorrentFile: Sendable, Equatable, Identifiable {
    public var index: Int
    public var path: String
    public var size: Int
    public var progress: Double
    public var selected: Bool
    public var id: Int { index }

    public init?(json o: [String: Any]) {
        self.index = RelayClient.int(o["index"])
        self.path = o["path"] as? String ?? ""
        self.size = RelayClient.int(o["size"])
        self.progress = (o["progress"] as? NSNumber)?.doubleValue ?? 0
        self.selected = (o["selected"] as? NSNumber)?.boolValue ?? true
    }
}
