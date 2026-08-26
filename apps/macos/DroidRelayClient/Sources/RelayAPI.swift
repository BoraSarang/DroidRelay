import Foundation

struct APIError: LocalizedError {
    let code: String
    let message: String
    var errorDescription: String? { "[\(code)] \(message)" }
}

/// DroidRelay 서버 REST 클라이언트 (RelayServer.kt 18종 엔드포인트)
final class RelayAPI: Sendable {
    let baseURL: URL
    private let authHeader: String?
    private let session: URLSession

    init(baseURL: URL, authHeader: String? = nil) {
        self.baseURL = baseURL
        self.authHeader = authHeader
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 8
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: cfg)
    }

    // MARK: - URL 빌더 (테스트 가능하도록 static)

    static func url(_ base: URL, _ path: String, query: [String: String] = [:]) -> URL {
        var comps = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return comps.url!
    }

    func fileURL(id: String) -> URL { Self.url(baseURL, "file/\(id)") }
    func dlFileURL(_ namePath: String) -> URL {
        baseURL.appendingPathComponent("dl-file/" + namePath)
    }
    func dashboardURL() -> URL { baseURL }

    // MARK: - 내부 전송 유틸

    private func request(_ url: URL, method: String = "GET", body: [String: Any]? = nil) -> URLRequest {
        var r = URLRequest(url: url)
        r.httpMethod = method
        if let authHeader { r.setValue(authHeader, forHTTPHeaderField: "Authorization") }
        if let body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    private func decode<T: Decodable>(_ type: T.Type, _ req: URLRequest) async throws -> T {
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw APIError(code: "E-MAC-NET-1002", message: "응답 없음")
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw APIError(code: http.statusCode == 401 ? "E-MAC-AUTH-1004" : "E-MAC-NET-1002",
                           message: "HTTP \(http.statusCode)")
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw APIError(code: "E-MAC-NET-1002", message: "응답 해석 실패: \(error.localizedDescription)")
        }
    }

    /// {"ok":true} / {"error":"..."} 형태 처리 (스토리지 연산)
    private func opJSON(_ req: URLRequest) async throws {
        struct OpResult: Decodable {
            let ok: Bool?
            let error: String?
        }
        let result: OpResult = try await decode(OpResult.self, req)
        if let err = result.error {
            throw APIError(code: "E-MAC-STOR-1003", message: err)
        }
    }

    private func okText(_ req: URLRequest) async throws {
        let (_, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw APIError(code: "E-MAC-NET-1002", message: "응답 없음")
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw APIError(code: http.statusCode == 401 ? "E-MAC-AUTH-1004" : "E-MAC-NET-1002",
                           message: "HTTP \(http.statusCode)")
        }
    }

    // MARK: - 서버 정보

    func getInfo() async throws -> ServerInfo {
        try await decode(ServerInfo.self, request(Self.url(baseURL, "api/info")))
    }

    // MARK: - 다운로드 잡

    func jobs() async throws -> [Job] {
        try await decode([Job].self, request(Self.url(baseURL, "api/jobs")))
    }

    func addJob(url urlString: String, sha256: String? = nil) async throws -> String {
        var body: [String: Any] = ["url": urlString]
        if let sha256, !sha256.isEmpty { body["sha256"] = sha256 }
        let r: AddResult = try await decode(AddResult.self,
                                      request(Self.url(baseURL, "api/jobs"), method: "POST",
                                              body: body))
        return r.id
    }

    func jobAction(id: String, _ action: String) async throws {
        try await okText(request(Self.url(baseURL, "api/jobs/\(id)/\(action)"), method: "POST"))
    }

    func deleteJob(id: String) async throws {
        try await okText(request(Self.url(baseURL, "api/jobs/\(id)"), method: "DELETE"))
    }

    // MARK: - 토렌트

    func torrents() async throws -> [Torrent] {
        try await decode([Torrent].self, request(Self.url(baseURL, "api/torrents")))
    }

    func addTorrent(magnet: String) async throws -> String {
        let r: AddResult = try await decode(AddResult.self,
                                      request(Self.url(baseURL, "api/torrents/add"), method: "POST",
                                              body: ["magnet": magnet]))
        return r.id
    }

    func addTorrent(fileData: Data, filename: String) async throws -> String {
        let r: AddResult = try await decode(AddResult.self,
                                      request(Self.url(baseURL, "api/torrents/add"), method: "POST",
                                              body: [
                                                  "torrentFileBase64": fileData.base64EncodedString(),
                                                  "filename": filename
                                              ]))
        return r.id
    }

    func torrentAction(id: String, _ action: String) async throws {
        try await okText(request(Self.url(baseURL, "api/torrents/\(id)/\(action)"), method: "POST"))
    }

    func deleteTorrent(id: String) async throws {
        try await okText(request(Self.url(baseURL, "api/torrents/\(id)"), method: "DELETE"))
    }

    // MARK: - 보관함

    func storageList(path: String) async throws -> [StorageItem] {
        try await decode([StorageItem].self,
                   request(Self.url(baseURL, "api/storage", query: path.isEmpty ? [:] : ["path": path])))
    }

    func storageMkdir(path: String, name: String) async throws {
        try await opJSON(request(Self.url(baseURL, "api/storage/mkdir"), method: "POST",
                                 body: ["path": path, "name": name]))
    }

    func storageRename(from: String, to: String) async throws {
        try await opJSON(request(Self.url(baseURL, "api/storage/rename"), method: "POST",
                                 body: ["from": from, "to": to]))
    }

    func storageDelete(path: String) async throws {
        try await opJSON(request(Self.url(baseURL, "api/storage/delete"), method: "POST",
                                 body: ["path": path]))
    }

    func storageMove(from: String, toDir: String) async throws {
        try await opJSON(request(Self.url(baseURL, "api/storage/move"), method: "POST",
                                 body: ["from": from, "to": toDir]))
    }

    func storageUpload(path: String, name: String, data: Data) async throws {
        try await opJSON(request(Self.url(baseURL, "api/storage/upload"), method: "POST",
                                 body: [
                                     "path": path,
                                     "name": name,
                                     "data": data.base64EncodedString()
                                 ]))
    }

    /// Raw binary 업로드 — multipart 없이 스트리밍 (대용량 파일 지원)
    func storageUploadRaw(path: String, name: String, data: Data,
                          onProgress: ((Int64) -> Void)? = nil) async throws {
        var req = request(Self.url(baseURL, "api/storage/raw-upload"), method: "POST")
        req.setValue(name, forHTTPHeaderField: "X-File-Name")
        req.setValue(path, forHTTPHeaderField: "X-File-Path")
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 600

        let (_, resp) = try await session.upload(for: req, from: data, delegate: nil)
        onProgress?(Int64(data.count))
        guard let http = resp as? HTTPURLResponse else {
            throw APIError(code: "E-MAC-NET-1002", message: "응답 없음")
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw APIError(code: http.statusCode == 401 ? "E-MAC-AUTH-1004" : "E-MAC-NET-1002",
                           message: "HTTP \(http.statusCode)")
        }
    }
}
