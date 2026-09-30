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

    /// **완료 잡의 파일을 내려받는 주소** — `GET /file/{id}` (웹 1174행).
    ///
    /// ## 왜 함수를 따로 떼어냈나
    ///
    /// 처음엔 뷰 쪽에서 문자열을 이어 붙였다.
    /// ```swift
    /// URL(string: base.absoluteString + "file/" + id)   // ← 이게 문제였다
    /// ```
    /// `base` 는 **`http://10.38.120.211:3000` 으로 끝에 슬래시가 없다.**
    /// 이어 붙이면 `http://10.38.120.211:3000file/…` 이 되고,
    /// **호스트가 `10.38.120.211:3000file` 이 되어 `URL` 이 `nil` 이 된다.**
    ///
    /// `nil` 을 조용히 넘기면 **누른 흔적 없이 아무 일도 안 일어난다.**
    /// 실제로 그렇게 고장 났고, 클릭으로 확인해서야 알았다.
    /// 그래서 조립을 **여기**로 옮겨 테스트로 고정한다.
    public static func fileURL(base: URL, id: String) -> URL? {
        guard var c = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        c.path = "/file/\(id)"
        return c.url
    }

    // MARK: - 보관함 파일 주소

    /// **경로 조각 하나만 인코딩할 때 남겨둘 문자** — `/` `%` `?` `#` 는 뺀다.
    ///
    /// `CharacterSet.urlPathAllowed` 에는 `/` 가 **들어 있다.** 그대로 쓰면
    /// 하위 폴더 구분자가 인코딩되어 `M%2Fa.mp4` 가 되고, 서버는 그것을
    /// "폴더가 아니라 이름에 슬래시가 든 파일" 로 읽어 404 를 준다.
    ///
    /// `%` 를 빼는 이유 — 이미 인코딩된 문자열에 다시 걸면 `%` 가 `%25` 가 되어
    /// 이중 인코딩이 된다. `path` 는 서버에서 받은 원본이므로 아직 인코딩 전이다.
    private static let pathSegmentAllowed: CharacterSet = {
        var s = CharacterSet.urlPathAllowed
        s.remove(charactersIn: "/%?#")
        return s
    }()

    /// **보관함 경로를 URL 조각들로 조립한다** — `/` 는 구분자로 남기고 각 조각만 인코딩.
    ///
    /// 실측으로 두 방식을 다 확인했다:
    /// - 슬래시 리터럴 + 조각 인코딩 → `M/4k688.com@T38-072.mp4` **200**
    /// - 조각 안에서 `/` 까지 인코딩 → **404**
    private static func encodedPath(_ path: String) -> String {
        path.split(separator: "/", omittingEmptySubsequences: true)
            .map { $0.addingPercentEncoding(withAllowedCharacters: pathSegmentAllowed) ?? String($0) }
            .joined(separator: "/")
    }

    /// **보관함 동영상을 브라우저로 실시간 재생할 주소** — `GET /stream/{경로}`.
    ///
    /// ## 왜 브라우저로 여는가
    ///
    /// "맥 기본 프로그램으로 열어" 라는 요구를 충돌 없이 만족시키는 유일한 길이다.
    /// 실측(Launch Services):
    /// ```
    /// http://…/stream/x.mp4  →  Safari.app            ← 기본
    /// ~/x.mp4 (로컬 파일)     →  /Applications/IINA.app  ← 사용자 기본 플레이어
    /// ```
    /// **IINA·QuickTime 은 http URL 을 열 수 없다**(IINA 는 후보 목록에 아예 없다).
    /// 그래서 "기본 프로그램"은 **URL 이냐 파일이냐에 따라 갈리고**,
    /// http 로는 **브라우저만 가능하다.** IINA 로 내려받으려면 전체를 받아야 한다.
    ///
    /// 이 엔드포인트는 `Content-Disposition: inline` + **`Range`(206) 지원**이라
    /// 브라우저에서 **탐색(seek)** 이 되고, 언제든 그 자리에서 멈출 수 있다.
    /// (실측: `bytes=0-1023` → `206 Partial Content`)
    public static func streamURL(base: URL, path: String) -> URL? {
        guard var c = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        c.path = "/stream/\(encodedPath(path))"
        return c.url
    }

    /// **보관함 파일을 내려받는 주소** — `GET /dl-file/{경로}`.
    ///
    /// `Content-Disposition: attachment` 라 **저장**이 되고 재생은 안 된다.
    /// 브라우저가 못 재생하는 형식(`.mkv` `.avi` `.dmg` …)은 이쪽이 유일한 길이다.
    public static func downloadURL(base: URL, path: String) -> URL? {
        guard var c = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        c.path = "/dl-file/\(encodedPath(path))"
        return c.url
    }

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
        /// **서버가 원본으로 준 타입** — "video" 등. `isVideo` 는 이 값의 축약형이다.
        public let typeRaw: String
        /// **현재 적용된 속도 제한 (B/s). 0 = 무제한.**
        ///
        /// 웹은 이 값을 프리셋 선택기의 현재값으로 쓴다(1182행 `j.maxDownBps`).
        /// 없으면 **무제한으로 표시하면서 실제로 제한이 걸려 있을 수 있다** —
        /// 사용자가 아무것도 안 고르고 확인하면 틀린 정보를 믿게 된다.
        public let maxDownBps: Int

        /// **아직 끝나지 않은 잡인가** — 배지와 "진행" 숫자를 세는 기준.
        ///
        /// ## 왜 이름이 중요했나
        ///
        /// 원래 이름은 `isListable`("목록에 뜨는가") 이었다. 하지만 **완료 잡도
        /// 목록에 보여야 한다** 고 정해져(웹과 동일) 이 이름이 **거짓말**이 되었다.
        /// 거짓말인 이름은 나중에 "이 필터로 목록을 그린다" 는 코드를 다시 쓴다.
        ///
        /// 그래서 목적을 이름에 그대로 넣었다. **"그릴 것인가" 가 아니라
        /// "끝났는가" 다.**
        public var isUnfinished: Bool { ActionRules.isUnfinished(state) }

        /// **끝난 잡인가** (`DONE`/`CANCELED`) — 목록 아래에 모이는 대상.
        public var isFinishedJob: Bool { ActionRules.isFinished(state) }

        /// **속도 합산에 쓸 판정** — 서버의 상태 문자열을 하드코딩하지 않는다.
        ///
        /// 이전에 `state == "RUNNING"` 으로만 더했다. 그런데 **`/api/jobs` 는 지금 0건**이고,
        /// 진행 중인 것은 토렌트 쪽(`DOWNLOADING`)이었다. 문자열이 하나라도 어긋나면
        /// **조용히 0 이 되고 에러도 없다** — 그래서 몇 시간 동안 놓칠 수 있다.
        ///
        /// 대소문자를 무시하고 진행 상태를 폭넓게 받아, 서버가 말을 바꿔도 값이 0 이
        /// 되지 않게 한다. "완료" 만은 제외한다.
        public var isRunning: Bool {
            let s = state.lowercased()
            guard !s.contains("done") && !s.contains("complete") && !s.contains("fail") else { return false }
            return s.contains("running") || s.contains("download") || s.contains("active")
                || s.contains("stalled") || s.contains("progress")
        }
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

    /// **최상위가 JSON 배열인 응답을 `[[String: Any]]` 로 돌려준다.**
    ///
    /// ## 왜 이게 필요한가 (실측으로 고친 함정)
    ///
    /// `getJSON` 은 **사전(`[String: Any]`) 전용**이다. 서버가
    /// `GET /api/storage/trash` 처럼 **배열**을 주면 `any as? [String: Any]` 가
    /// 실패해서 `nil` 이 된다.
    ///
    /// 그럼 호출부가 `await getJSON(...) as? [[String: Any]]` 로 되돌려도
    /// **이미 nil 을 배열로 캐스팅한 것이라 그대로 nil** 이다.
    /// → 함수는 조용히 `[]` 를 돌려준다. → **휴지통 목록은 항상 비어 보였다.**
    ///
    /// `jobs()`/`torrents()` 는 원래 이렇게 파싱했다. 휴지통만 빠졌다.
    /// `RelayClient+Write` 의 확장에서 도 쓸 수 있게 `fileprivate` 이 아니라
    /// 모듈 내부 공개로 둔다.
    func getJSONArray(_ path: String) async -> [[String: Any]] {
        guard let d = try? await get(path),
              let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let o = any as? [[String: Any]]
        else { return [] }
        return o
    }

    // MARK: - 속도 (M-14)

    /// Droid(앱) 속도 — 잡+토렌트를 **서버가 준 값에서 합산**한다.
    /// 웹 대시보드가 하던 계산과 동일하다(그때도 클라이언트 합산).
    public func droidSpeed() async -> SpeedReading {
        async let j = jobs()
        async let t = torrents()
        let (jobs, torrents) = await (j, t)
        let jobsDown = jobs.filter { $0.state == "RUNNING" }.reduce(0) { $0 + $1.speedBps }
        let active = torrents.filter { $0.isActive }
        return SpeedReading(
            downBps: jobsDown + active.reduce(0) { $0 + $1.downloadBps },
            upBps: active.reduce(0) { $0 + $1.uploadBps }
        )
    }

    /// 기기(폰 전체) 트래픽 **누적 카운터** — 속도가 아니다.
    ///
    /// **누적값을 받는 이유** — 서버가 초당 속도로 나눠 보내면 폴링 주기가 흔들릴 때
    /// 표시가 출렁인다(같은 트래픽이라도 1초 간격과 5초 간격의 값이 다르다).
    /// 누적값을 받고 **클라이언트가 자기 타이머로** 나누면 오차가 0 이다.
    ///
    /// `nil` 은 **서버가 미지원** 이라는 뜻이다. `0` 과 구분해야 한다 — 구분 못 하면
    /// 스위치를 켰는데 항상 0 이 나오는 "고장 난 기능" 이 된다.
    /// **기기 트래픽 누적값** — `nil` 은 **서버가 미지원** 이라는 뜻이다.
    ///
    /// `0` 과 구분해야 한다 — 구분 못 하면 스위치를 켰는데 항상 0 이 나오는
    /// **"고장 난 기능"** 이 된다.
    ///
    /// - Parameter scope: 원하는 범위. **쿼리로 실려 서버가 실제로 고른다.**
    ///   생략하면 서버 기본값(외부만)을 쓴다.
    public func deviceTraffic(scope: TrafficScope? = nil) async -> DeviceTraffic? {
        try? await deviceTrafficChecked(scope: scope)
    }

    /// **`nil` 의 원인을 알려주는 版本** — 진단 전용.
    ///
    /// ## 왜 둘로 나눴나
    ///
    /// `deviceTraffic()` 는 **네트워크 실패와 "서버 미지원" 을 모두 `nil` 로 준다.**
    /// 화면에서는 둘이 똑같이 "값이 안 나온다" 로 보이며 **뭐가 잘못됐는지 알 수 없다.**
    /// (M-14 에서 이미 한 번 겪었다 — "속도가 0" 과 "서버에 값이 없다" 를 못 구분)
    ///
    /// → **진단에서는 원인을 봐야 한다** 그래서 이 메서드가 사유를 던진다.
    public func deviceTrafficChecked(scope: TrafficScope? = nil) async throws -> DeviceTraffic {
        // **★ 쿼리가 붙은 경로다** — `appendingPathComponent` 로 붙이면 `?` 가
        // 경로로 인코딩돼 **서버가 파라미터를 못 본다.**
        // (M-16 에서 슬래시 문제로 이미 한 번 같은 함정을 밟았다)
        // → **URLComponents** 로 직접 만든다.
        var comps = URLComponents(
            url: base.appendingPathComponent("api/net/speed"), resolvingAgainstBaseURL: false
        )
        if let scope {
            comps?.queryItems = [URLQueryItem(name: "scope", value: scope.queryValue)]
        }
        guard let url = comps?.url else { throw URLError(.badURL) }
        guard let o = try? await getJSON(url) else {
            throw URLError(.cannotConnectToHost)   // 네트워크·파싱 실패
        }
        guard (o["supported"] as? Bool) == true else {
            let note = (o["note"] as? String) ?? "사유 없음"
            throw TrafficScopeError.unsupported(note)
        }
        // ## 왜 `int64Value` 인가 — **비유는 틀렸다. 결론은 맞다.**
        //
        // 값이 음수로 나와서 **"32비트 `intValue` 가 좁혔다"** 고 단정했고,
        // 그게 **틀렸다.** 같은 응답을 두 경로로 나눠 재현한 결과:
        //
        // ```
        // JSON          txTotal = 71,693,545,994
        // intValue   →  71,693,545,994     ← 64비트 macOS 에선 안 잘린다
        // 문자열 직접 →  71,693,545,994     ← 동일
        // ```
        //
        // **macOS 는 64비트라 `intValue` 도 64비트**다. 좁히기가 원인이 아니었다.
        // (그래도 `int64Value` 를 쓴다 — **형이 값의 범위를 보장하는 쪽**이 맞고,
        //  someday 32비트 빌드가 생겨도 여기서는 안전하다.)
        return DeviceTraffic(
            rxTotal: DeviceTraffic.clampTotal(o["rxTotal"]),
            txTotal: DeviceTraffic.clampTotal(o["txTotal"]),
            // **서버가 실제로 쓴 범위** — 요구한 것과 다를 수 있다(구버전 서버).
            scope: TrafficScope.parse(o["scope"] as? String)
        )
    }

    /// **서버가 기기 카운터를 지원하지 않는다** — 사유를 그대로 담는다.
    ///
    /// **사유를 버리지 않는다.** M-24 에서 `rmnet*` 못 찾는 경우를
    /// "미지원" 이라는 말 한마디로 소비했다가 **왜 안 되는지 알 수 없었다.**
    public enum TrafficScopeError: LocalizedError {
        case unsupported(String)
        public var errorDescription: String? {
            switch self {
            case .unsupported(let why): "기기 트래픽 미지원 — \(why)"
            }
        }
    }

    private func getJSON(_ path: String) async -> [String: Any]? {
        await getJSON(base.appendingPathComponent(path))
    }

    private func getJSON(_ url: URL) async -> [String: Any]? {
        guard let d = try? await get(url),
              let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let o = any as? [String: Any]
        else { return nil }
        return o
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

    /// 보관함 목록 — `path` 가 비면 루트, 있으면 그 폴더 안.
    ///
    /// ## 쿼리를 문자열로 이어 붙이면 안 되는 이유 (실측으로 발견한 함정)
    ///
    /// 이전엔 이렇게 짰었다:
    /// ```swift
    /// var p = "api/storage"
    /// if !path.isEmpty { p += "?path=" + … }
    /// let d = try? await get(p)          // ← get() 이 appendingPathComponent 로 붙인다
    /// ```
    ///
    /// `appendingPathComponent` 는 `?` 와 `=` 를 **경로의 일부로 퍼센트 인코딩**한다.
    /// → 요청이 `/api/storage%3Fpath=M` 로 가고 서버는 404 를 준다.
    /// → `try?` 가 삼킨다. → **빈 배열.**
    ///
    /// 결과적으로 **하위 폴더 목록은 한 번도 열린 적이 없었다.**
    /// 폴더를 눌러도 항상 빈 화면이었다. "폴더가 비어 있나" 로 오해할 만했다.
    ///
    /// 그래서 **`URLComponents` 로 쿼리를 분리**한다. 인코딩도 그 몫을 한다.
    public func storage(path: String = "") async -> [StorageEntry] {
        let root = base.appendingPathComponent("api/storage")
        var url = root
        if !path.isEmpty {
            if var comps = URLComponents(url: root, resolvingAgainstBaseURL: false) {
                comps.queryItems = [URLQueryItem(name: "path", value: path)]
                url = comps.url ?? root
            }
        }
        let d = try? await get(url)
        guard let d, let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let arr = any as? [Any]
        else { return [] }
        // **경로는 서버가 안 준다 — 여기서 상위 경로와 합쳐 만든다.**
        // (StorageEntry.path 를 주지 않으면 루트에서 열었을 때 하위 폴더의
        //  항목도 이름만 남아 `이름 변경`/`휴지통` 이 전부 실패한다)
        return arr.compactMap { StorageEntry(json: $0, dir: path) }
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
        // 본문은 필요 없다 — 상태 코드만으로 성공 여부를 판정한다
        let (_, r) = (try? await session.data(for: req)) ?? (nil, nil)
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
            isVideo: o["type"] as? String == "video",
            typeRaw: o["type"] as? String ?? "",
            // **서버가 안 보내도 조용히 0 이 되게 두지 않는다** — 값이 없으면 0(무제한)
            maxDownBps: int(o["maxDownBps"])
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

    /// 잡 일시정지/재개 — `POST /api/jobs/{id}/{action}`.
    ///
    /// ## 반환값을 고친 이유
    ///
    /// 원래는 요청 결과와 무관하게 `true` 를 돌려줬다:
    /// ```swift
    /// _ = try? await session.data(for: req)
    /// return true          // ← 네트워크가 끊겨도, 400 이어도 "성공"
    /// ```
    /// 그래서 **서버가 거절했는데 클라이언트는 성공으로 보고했다.**
    /// 사용자는 아무 반응이 없고, 무엇이 틀렸는지 알 방법이 없다.
    /// → 상태 코드를 본다. 연결 실패(0)도 실패다.
    public func control(_ id: String, _ action: String) async -> Bool {
        var req = URLRequest(url: base.appendingPathComponent("api/jobs/\(id)/\(action)"))
        req.httpMethod = "POST"
        req.timeoutInterval = 5
        guard let (_, r) = try? await session.data(for: req),
              let http = r as? HTTPURLResponse else { return false }
        return (200..<300).contains(http.statusCode)
    }

    /// 잡 삭제 — `DELETE /api/jobs/{id}`.
    ///
    /// **`POST /{action}` 에 `cancel` 이 없다는 걸 나중에 알았다.** 서버는
    /// 일시정지/재개만 받는다(JobRoutes 100~109행). 삭제는 이 메서드다.
    public func jobDelete(_ id: String) async -> Bool {
        var req = URLRequest(url: base.appendingPathComponent("api/jobs/\(id)"))
        req.httpMethod = "DELETE"
        req.timeoutInterval = 5
        guard let (_, r) = try? await session.data(for: req),
              let http = r as? HTTPURLResponse else { return false }
        return (200..<300).contains(http.statusCode)
    }

    // MARK: - 내부

    private func get(_ path: String) async throws -> Data {
        try await get(base.appendingPathComponent(path))
    }

    /// **쿼리가 이미 붙은 URL 로 GET.**
    ///
    /// `appendingPathComponent` 로 문자열 경로를 붙이면 `?` 가 경로로 인코딩된다.
    /// 쿼리가 있는 요청은 **반드시 이쪽을 써야 한다.** (보관함 하위 폴더가 그랬다)
    private func get(_ url: URL) async throws -> Data {
        var req = URLRequest(url: url)
        req.timeoutInterval = 5
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let (d, _) = try await session.data(for: req)
        return d
    }

    // MARK: - 쓰기 요청 (RelayClient+Write 에서 쓴다)

    /// POST + JSON 본문.
    ///
    /// **HTTP 상태 코드를 버리지 않는다.** 서버는 200 이 아닌 상태로 **오류 문구를
    /// 본문에 담아** 보내는데, 성공 코드만 보면 "왜 안 되지?" 하고 이유를 잃는다.
    /// → 상태 코드와 본문을 함께 `WriteResult` 에 담아 돌려준다.
    func postBody(_ path: String, _ body: [String: Any]) async -> (status: Int, data: Data) {
        var req = URLRequest(url: base.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.timeoutInterval = 15
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        do {
            let (d, r) = try await session.data(for: req)
            return (Int((r as? HTTPURLResponse)?.statusCode ?? 0), d)
        } catch {
            return (0, Data("\(error.localizedDescription)".utf8))
        }
    }

    /// POST + JSON → `WriteResult`. **서버 사유를 그대로 살려서** 돌려준다.
    func postJSON(_ path: String, _ body: [String: Any]) async -> WriteResult {
        let (status, data) = await postBody(path, body)
        return RelayClient.writeResult(status: status, data: data, body: body)
    }

    /// GET 또는 POST + JSON → 딕셔너리 (공유 링크처럼 응답이 JSON 인 경우).
    func getJSON(_ path: String, method: String = "GET", body: [String: Any]? = nil) async -> [String: Any]? {
        var req = URLRequest(url: base.appendingPathComponent(path))
        req.httpMethod = method
        req.timeoutInterval = 10
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        guard let d = try? await session.data(for: req).0,
              let any = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
        else { return nil }
        return any as? [String: Any]
    }

    /// 상태 코드 + 본문 → `WriteResult`.
    ///
    /// 서버는 오류를 **텍스트**(`"E-AND-DOWN-1003: …"`)나 **JSON**(`{"error":"…"}`)
    /// 둘 다 쓴다. 둘 다 읽어봐야 사유가 나온다.
    static func writeResult(status: Int, data: Data, body: [String: Any]) -> WriteResult {
        guard status != 0 else { return .fail("서버에 연결하지 못했습니다") }
        let raw = String(data: data, encoding: .utf8) ?? ""
        // JSON 이면 error 필드를 우선한다
        if let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let e = o["error"] as? String, !e.isEmpty {
            return WriteResult(ok: false, message: e,
                               newId: o["id"] as? String)
        }
        if (200..<300).contains(status) {
            // 추가 API 는 새 id 를 돌려준다
            let newId = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])??["id"] as? String
            return WriteResult(ok: true, message: "", newId: newId)
        }
        // 본문이 비었으면 상태 코드만으로 말한다 — 사용자에게 422 만 보여주면 무의미
        return .fail(raw.isEmpty ? "요청이 실패했습니다 (HTTP \(status))" : raw)
    }
}
