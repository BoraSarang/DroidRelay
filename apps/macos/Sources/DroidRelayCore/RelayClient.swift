import Foundation

/// DroidRelay REST 클라이언트.
///
/// **REST 를 쓴다 — MCP 를 안 쓰는 이유**
/// 1. 실시간: 메뉴바에 진행률이 떠야 하는데 MCP 에는 구독이 없어 매번 폴링해야 한다.
///    `/api/events` 는 이미 SSE 로 상태 변화마다 tick 을 준다.
/// 2. 이 앱은 LLM 이 아니다. MCP 는 "모델이 도구를 고르기 위한 목록"이 좋은 프로토콜이지
///    UI 를 그리는 앱에 필요한 게 아니다.
/// 3. 검증 층 — MCP 는 curl 스펙 확인까지만 했다. 그 위에 UI 를 얹으면
///    미검증 계층이 두 겹이 된다.
/// MCP 12개 도구는 다른 클라이언트를 위해 그대로 남겨 둔다(공존).
public struct RelayClient: Sendable {
    public let base: URL
    private let session: URLSession

    public init(base: URL, session: URLSession = .shared) {
        self.base = base
        self.session = session
    }

    // MARK: - 탐색 프로브

    /// 후보가 DroidRelay 인지 확인한다. 포트만 열려 있는 것과 구분해야 한다.
    public static func probe(_ base: URL) async -> ServerInfo? {
        var req = URLRequest(url: base.appendingPathComponent("api/info"))
        req.timeoutInterval = 0.7
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let resp: (Data, URLResponse)? = try? await URLSession.shared.data(for: req)
        guard let d = resp?.0 else { return nil }
        guard let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let o = any as? [String: Any],
              let port = o["port"] as? Int, let version = o["version"] as? String
        else { return nil }
        let host = base.host() ?? o["ip"] as? String ?? ""
        return ServerInfo(host: host, port: port, version: version)
    }

    /// 바이트 표기 — 화면과 진단 출력 양쪽에서 쓴다
    public static func format(bytes n: Int) -> String {
        let f = ByteCountFormatter()
        f.allowedUnits = [.useGB, .useMB, .useKB]
        f.countStyle = .file
        return f.string(fromByteCount: Int64(n))
    }

    // MARK: - 목록

    public struct Job: Identifiable, Sendable, Equatable {
        public let id: String
        public let name: String
        public let state: String
        public let progress: Int          // 0..100
        public let speedBps: Int
        public let uploadedBps: Int
        public let etaSeconds: Int?
        public let isVideo: Bool

        public var isActive: Bool { state == "RUNNING" || state == "STALLED" }
        public var isSeeding: Bool { state == "SEEDING" || state == "FETCHING_METADATA" }
        public var speedText: String { Self.bps(speedBps) }
        public var upText: String { Self.bps(uploadedBps) }
        public var etaText: String {
            guard let s = etaSeconds, s > 0 else { return "" }
            if s < 60 { return "\(s)초" }
            let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
            return h > 0 ? "\(h)시간 \(m)분" : "\(m)분 \(sec)초"
        }

        static func bps(_ b: Int) -> String {
            guard b > 0 else { return "—" }
            return b >= 1_048_576
                ? String(format: "%.1f MB/s", Double(b) / 1_048_576)
                : "\(b / 1024) KB/s"
        }
    }

    public func jobs() async -> [Job] {
        let d: Data? = try? await get("api/jobs")
        guard let d,
              let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let o = any as? [[String: Any]]
        else { return [] }
        return o.compactMap(Self.job(from:))
    }

    // MARK: - 토렌트 · 보관함 (M3)

    /// JSON 숫자 필드 안전 추출 — 서버가 `null` 이나 문자열을 줘도 0 으로 떨어지게.
    /// `as? Int` 는 NSNumber 가 아니면 **nil** 이라 조용히 0 이 되고, 그게 의도다.
    /// 단위 테스트 대상 — 서버가 값을 바꿔도 화면이 이상한 숫자를 보여주지 않아야 한다.
    static func int(_ any: Any?) -> Int {
        if let n = any as? Int { return n }
        if let d = any as? Double, d.isFinite { return Int(d) }
        if let s = any as? String, let v = Int(s) { return v }
        return 0
    }

    public func torrents() async -> [Torrent] {
        let d: Data? = try? await get("api/torrents")
        guard let d, let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let arr = any as? [Any]
        else { return [] }
        return arr.compactMap { Torrent(json: $0) }
    }

    public func storage(path: String = "") async -> [StorageEntry] {
        var p = "api/storage"
        if !path.isEmpty {
            p += "?path=" + (path.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")
        }
        let d: Data? = try? await get(p)
        guard let d, let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let arr = any as? [Any]
        else { return [] }
        return arr.compactMap { StorageEntry(json: $0) }
    }

    /// 토렌트 제어 — 본문 없이 POST. 서버의 Content-Type 가드는 **본문이 있을 때만**
    /// 적용하므로 헤더를 붙일 필요가 없다(붙여도 무방).
    public func torrentControl(_ id: String, _ action: String) async -> Bool {
        var req = URLRequest(url: base.appendingPathComponent("api/torrents/\(id)/\(action)"))
        req.httpMethod = "POST"
        req.timeoutInterval = 5
        _ = try? await session.data(for: req)
        return true
    }

    /// 토렌트 삭제 — `POST /{action}` 이 **아니라** `DELETE /api/torrents/{id}` 다.
    /// 서버는 torrent/{id}/{action} 에 pause·resume·files·limit 만 분기한다.
    /// 완성 torrent 를 지우면 **목록에서만** 빠지고 보관함 파일은 남는다(서버 설계).
    public func torrentDelete(_ id: String) async -> Bool {
        var req = URLRequest(url: base.appendingPathComponent("api/torrents/\(id)"))
        req.httpMethod = "DELETE"
        req.timeoutInterval = 5
        let (d, r) = (try? await session.data(for: req)) ?? (nil, nil)
        guard let http = r as? HTTPURLResponse else { return false }
        return (200..<300).contains(http.statusCode)
    }

    static func job(from o: [String: Any]) -> Job? {
        guard let id = o["id"] as? String else { return nil }
        let pct = (o["progress"] as? Double) ?? 0
        return Job(
            id: id,
            name: o["filename"] as? String ?? o["name"] as? String ?? "(이름 없음)",
            state: o["state"] as? String ?? "UNKNOWN",
            progress: min(100, Int((pct * 100).rounded())),
            speedBps: (o["speedBps"] as? Int) ?? 0,
            uploadedBps: (o["torrentUploadSpeed"] as? Int) ?? (o["uploadSpeed"] as? Int) ?? 0,
            etaSeconds: (o["etaSec"] as? Int) ?? (o["etaSeconds"] as? Int),
            isVideo: o["type"] as? String == "video"
        )
    }

    public func serverInfo() async -> (storageFree: Int, storageTotal: Int, version: String, ip: String)? {
        let d: Data? = try? await get("api/info")
        guard let d,
              let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let o = any as? [String: Any]
        else { return nil }
        return ((o["storageFree"] as? Int) ?? 0, (o["storageTotal"] as? Int) ?? 0,
                o["version"] as? String ?? "?", o["ip"] as? String ?? "")
    }

    // MARK: - 제어 (M1 범위: 일시정지 / 재개 / 취소)

    public func control(_ id: String, _ action: String) async -> Bool {
        var req = URLRequest(url: base.appendingPathComponent("api/jobs/\(id)/\(action)"))
        req.httpMethod = "POST"
        req.timeoutInterval = 5
        _ = try? await session.data(for: req)
        return true
    }

    // MARK: - 내부

    private func get(_ path: String) async throws -> Data {
        var req = URLRequest(url: base.appendingPathComponent(path))
        req.timeoutInterval = 5
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let (d, _) = try await session.data(for: req)
        return d
    }
}
