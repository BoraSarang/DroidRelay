import Foundation

// MARK: - Content-Range

/// **서버가 `Content-Range` 로 알려주는 구간과 전체 크기.**
///
/// DroidRelay 서버(`serveFile`)는 **응답 하나를 32MB 로 자른다**(`MAX_SERVE_SLICE`,
/// A90 OOM 수정에서 도입). 잘린 응답에는 항상 `Content-Range: bytes a-b/TOTAL` 이
/// 붙고, 그 `TOTAL` 이 **전체 파일 크기**다.
///
/// ## 이 헤더를 읽지 않으면 무슨 일이 나는가 (T-1128 실측)
///
/// 맥 앱은 `받기` 를 브라우저에 넘겼다(`NSWorkspace.open`). 브라우저 다운로드
/// 매니저는 206 슬라이스를 **"완료" 로 저장하고** `Content-Range` 의 전체 크기를
/// 무시한 채 후속 `Range` 요청을 보내지 않는다. 그래서 18GB 파일이 **32MB 로**
/// 저장되고 화면에는 "완료" 라고 떴다(사용자 제보).
///
/// 자동 이어받기는 `<video>`·WebDAV 클라이언트에서만 성립한다 — 저장(attachment)은
/// 안 된다. 그래서 클라이언트가 **`TOTAL` 을 반드시 읽고 스스로 이어받아야** 한다.
public struct ContentRange: Equatable, Sendable {
    /// 이 응답 본문이 시작하는 파일 내 위치.
    public let start: Int64
    /// 이 응답 본문이 끝나는 파일 내 위치(포함).
    public let end: Int64
    /// **전체 파일 크기.** 서버가 `*` 로 주면(알 수 없음) `nil`.
    public let total: Int64?

    public init(start: Int64, end: Int64, total: Int64?) {
        self.start = start
        self.end = end
        self.total = total
    }

    /// `bytes 0-33554431/19343747520` → `(0, 33554431, 19343747520)`
    ///
    /// ## 왜 실패를 `nil` 로 돌려주나
    ///
    /// 형식이 어긋났을 때 **추측하면 파일이 조용히 어긋난다.** 18GB 짜리 gguf 를
    /// 다 받고 나서야 해시가 다르다는 걸 알게 되는 것이 최악이다. 모르면 모른다고
    /// 하고 **멈춘다**(`DownloadError.missingContentRange`).
    public static func parse(_ raw: String?) -> ContentRange? {
        guard let raw else { return nil }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count > 6, text.lowercased().hasPrefix("bytes ") else { return nil }
        let spec = text.dropFirst("bytes ".count)
        let halves = spec.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        guard halves.count == 2 else { return nil }
        let bounds = halves[0].split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard bounds.count == 2,
              let start = Int64(bounds[0].trimmingCharacters(in: .whitespaces)),
              let end = Int64(bounds[1].trimmingCharacters(in: .whitespaces)),
              end >= start
        else { return nil }

        let totalPart = halves[1].trimmingCharacters(in: .whitespaces)
        if totalPart == "*" { return ContentRange(start: start, end: end, total: nil) }
        guard let total = Int64(totalPart) else { return nil }
        return ContentRange(start: start, end: end, total: total)
    }
}

// MARK: - 진행률

/// **내려받기 진행률** — 화면 문구를 여기서 만든다(테스트로 고정).
///
/// 진행률 계산을 뷰에 두면 "몇 %인가" 를 화면 없이 검증할 수 없다.
public struct DownloadProgress: Equatable, Sendable {
    public let received: Int64
    /// 전체 크기. 서버가 알려주기 전에는 `nil` — **0% 라고 거짓말하지 않는다.**
    public let total: Int64?

    public init(received: Int64, total: Int64?) {
        self.received = received
        self.total = total
    }

    /// 0~1. 전체를 모르면 `nil`.
    public var fraction: Double? {
        guard let total, total > 0 else { return nil }
        return min(1, max(0, Double(received) / Double(total)))
    }

    /// `42%` · 전체를 모르면 받은 양(`1.2 GB`).
    public var percentText: String {
        guard let f = fraction else { return RelayClient.format(bytes: Int(received)) }
        return "\(Int((f * 100).rounded(.down)))%"
    }

    /// `7.6 GB / 18.0 GB`.
    public var detailText: String {
        guard let total, total > 0 else { return RelayClient.format(bytes: Int(received)) }
        return "\(RelayClient.format(bytes: Int(received))) / \(RelayClient.format(bytes: Int(total)))"
    }

    /// 목록 한 줄·안내 배너에 넣을 문구 — `42% · 7.6 GB / 18.0 GB`.
    public var rowText: String { "\(percentText) · \(detailText)" }

    public var isComplete: Bool {
        guard let total else { return false }
        return received >= total
    }
}

// MARK: - 오류

/// 내려받기 실패 이유 — **무엇이 어긋났는지 화면에 말할 수 있어야 한다.**
public enum DownloadError: Error, Equatable, LocalizedError, Sendable {
    /// 2xx·206·416 이 아닌 응답.
    case badStatus(Int)
    /// 206 인데 `Content-Range` 가 없다 — 이어받을 근거가 없다.
    case missingContentRange(from: Int64)
    /// 요청한 지점과 다른 지점을 보냈다 — 이어 붙이면 파일이 어긋난다.
    case rangeNotHonored(from: Int64, got: ContentRange?)
    /// `Range` 를 아예 무시한다 — 32MB 만 받고 "완료" 로 저장되던 그 상태다.
    case noRangeSupport
    /// 416 — 요청 지점이 파일 범위 밖이다.
    case unsatisfiable(at: Int64)
    /// 다 받았다고 판단했는데 전체 크기와 다르다.
    case incomplete(received: Int64, total: Int64)
    /// 빈 조각이 왔다(진행이 없다).
    case emptySlice(at: Int64)
    /// 만들기/쓰기 실패(디스크 여유 등).
    case fileSystem(String)

    public var errorDescription: String? {
        switch self {
        case .badStatus(let code):
            return "서버가 \(code) 로 응답했습니다"
        case .missingContentRange(let from):
            return "\(from) 바이트 지점 응답에 Content-Range 가 없어 이어받을 수 없습니다"
        case .rangeNotHonored(let from, let got):
            return "서버가 요청한 \(from) 이 아닌 \(got.map { String($0.start) } ?? "알 수 없는") 지점을 보냈습니다"
        case .noRangeSupport:
            return "서버가 Range 이어받기를 지원하지 않습니다 — 32MB 까지만 받고 멈추던 원인입니다"
        case .unsatisfiable(let at):
            return "\(at) 바이트 지점을 받을 수 없습니다(파일 범위 밖)"
        case .incomplete(let received, let total):
            return "전체 \(total) 바이트 중 \(received) 바이트만 받았습니다"
        case .emptySlice(let at):
            return "\(at) 바이트 지점에서 빈 응답이 왔습니다"
        case .fileSystem(let message):
            return message
        }
    }

    /// **취소인가.** 취소는 실패가 아니다 — 재시도하지 않고, 안내도 "실패" 로 하지 않는다.
    ///
    /// `URLSession` 은 취소를 `CancellationError` 가 아니라 `URLError(.cancelled)` 로
    /// 던진다. 둘 다 봐야 한다 — 한쪽만 보면 사용자가 취소했는데 "실패" 배너가 남는다.
    public static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let u = error as? URLError, u.code == .cancelled { return true }
        return false
    }
}

// MARK: - 이름

/// **저장 파일 이름 규칙.**
public enum DownloadNaming {
    /// 파일명이 될 수 없거나 경로를 벗어나는 문자를 지운다.
    ///
    /// `/` 를 남기면 `폴더/이름` 이 되어 **저장 폴더 밖으로 나간다.** 서버 이름은
    /// 보관함 파일명이라 원래 `/` 가 없지만, 방어는 여기서 한다.
    public static func sanitize(_ name: String) -> String {
        var s = name
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .replacingOccurrences(of: "\0", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { s = "download" }
        // `.` `..` 처럼 점만 있는 이름은 디렉터리를 가리킨다.
        if s.trimmingCharacters(in: CharacterSet(charactersIn: ".")).isEmpty { s = "download" }
        return s
    }

    /// 같은 이름이 있으면 **`이름 (1).ext`** 로 비켜 간다 — **덮어쓰지 않는다.**
    ///
    /// ## 왜 덮어쓰지 않나
    ///
    /// 18GB 를 20분 받아 놓고 **이미 있던 파일을 지우면** 되돌릴 방법이 없다.
    /// Finder 관습(` (1)`)을 따른다 — 사용자가 이미 아는 규칙이어야 한다.
    ///
    /// 존재 판정을 주입받아 테스트에서 실제 파일 없이 검증한다.
    public static func unique(_ preferred: String, exists: (String) -> Bool) -> String {
        guard exists(preferred) else { return preferred }
        let ns = preferred as NSString
        let ext = ns.pathExtension
        let base = ns.deletingPathExtension
        for n in 1...10_000 {
            let candidate = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            if !exists(candidate) { return candidate }
        }
        return preferred
    }
}

// MARK: - 슬라이스

/// **Range 슬라이스 한 번의 응답.**
///
/// ## 왜 본문이 `Data` 가 아니라 파일인가
///
/// 18GB 를 `Data` 로 올리면 담을 수 없다(맥 앱 메모리 예산 300MB). `URLSession`
/// 의 `download(for:)` 가 **임시 파일로 떨어뜨린 것**을 그대로 받아, 4MB 씩 읽어
/// 목적 파일에 붙인다 — 메모리 상한이 슬라이스 크기(32MB)로 묶인다.
public struct SliceResponse: Sendable {
    public let statusCode: Int
    public let contentRange: ContentRange?
    /// 헤더가 말하는 본문 길이. 206 이면 **이 조각의** 길이다.
    public let contentLength: Int64?
    /// 본문이 들어 있는 파일(URLSession 임시 파일). 다 쓰면 **지운다.**
    public let bodyFile: URL
    /// 헤더가 아니라 **실제로 디스크에 있는** 바이트 수.
    public let bodyLength: Int64

    public init(statusCode: Int,
                contentRange: ContentRange?,
                contentLength: Int64?,
                bodyFile: URL,
                bodyLength: Int64) {
        self.statusCode = statusCode
        self.contentRange = contentRange
        self.contentLength = contentLength
        self.bodyFile = bodyFile
        self.bodyLength = bodyLength
    }
}

/// **슬라이스를 가져오는 방법.** 테스트는 가짜를 끼워 네트워크 없이 돌린다.
public protocol SliceFetching: Sendable {
    /// `offset` 부터 끝까지(`Range: bytes=offset-`) 요청한다.
    func slice(from offset: Int64) async throws -> SliceResponse
}

// MARK: - 조립 (순수 로직)

/// **32MB 슬라이스를 끝까지 이어 붙인다.**
///
/// 서버·브라우저와 무관하게 **여기서만** 이어받기를 판단한다 — 그래서 순수 로직으로
/// 떼어 두고 가짜 fetcher 로 검증한다.
public enum RangeDownloader {
    public struct Outcome: Equatable, Sendable {
        /// 실제로 디스크에 쓴 바이트 수.
        public let bytes: Int64
        /// 서버가 알려준 전체 크기(`Content-Range`). 모르면 `nil`.
        public let total: Int64?

        public init(bytes: Int64, total: Int64?) {
            self.bytes = bytes
            self.total = total
        }
    }

    /// 한 조각이 실패했을 때 **같은 자리에서** 다시 시도하는 횟수.
    ///
    /// ## 왜 재시도가 필요한가 (실측)
    ///
    /// 2026-10-18 S22 실측: 18GB 이어받기 중 **폰 화면이 꺼져 Doze 로 들어가자**
    /// 전송이 2.7GB 지점에서 멈췄다(로그 오프셋 고정). 화면을 켜자 같은 offset 으로
    /// 다시 요청해 이어졌다. 재시도가 없으면 사용자는 20분짜리 전송을 처음부터
    /// 다시 해야 한다.
    public static let defaultRetries = 3

    /// 재시도 사이의 대기(초).
    public static let retryDelaySeconds = 2

    /// 목적 파일에 **이어서** 쓴다. 이미 있는 크기가 곧 시작점이다(앱을 껐다 켜도 이어받는다).
    ///
    /// - Returns: 쓴 바이트 수와 서버가 알려준 전체 크기.
    @discardableResult
    public static func run(
        fetcher: SliceFetching,
        partURL: URL,
        retries: Int = RangeDownloader.defaultRetries,
        retryDelay: Duration = .seconds(RangeDownloader.retryDelaySeconds),
        progress: @Sendable (Int64, Int64?) -> Void = { _, _ in },
        onRetry: @Sendable (Int) -> Void = { _ in }
    ) async throws -> Outcome {
        let fm = FileManager.default
        if !fm.fileExists(atPath: partURL.path) {
            guard fm.createFile(atPath: partURL.path, contents: nil) else {
                throw DownloadError.fileSystem("임시 파일을 만들지 못했습니다 — \(partURL.path)")
            }
        }
        let handle: FileHandle
        do {
            handle = try FileHandle(forWritingTo: partURL)
        } catch {
            throw DownloadError.fileSystem("임시 파일을 열지 못했습니다 — \(error.localizedDescription)")
        }
        defer { try? handle.close() }

        var received = Int64(handle.seekToEndOfFile())
        progress(received, nil)
        var total: Int64?
        /// `Range` 를 무시한 200 응답을 이미 본 적이 있는가.
        var sawWholeBody = false
        /// 직전 조각 길이 — 전체 크기를 안 알려주는 서버에서 **끝을 판단**하는 근거.
        var lastSliceLength: Int64?

        while true {
            // 취소하면 `.part` 가 남는다 → 다음에 **그 지점부터** 이어받는다.
            try Task.checkCancellation()

            let res = try await fetch(
                fetcher: fetcher,
                from: received,
                retries: retries,
                retryDelay: retryDelay,
                onRetry: onRetry
            )
            // 임시 파일은 즉시 지운다 — 18GB 를 32MB 조각으로 받으면 임시 파일이 쌓여
            // 디스크가 두 배로 든다(그리고 세션을 닫지 않으면 아무도 안 지운다).
            defer { try? fm.removeItem(at: res.bodyFile) }

            switch res.statusCode {
            case 206:
                guard let cr = res.contentRange else {
                    throw DownloadError.missingContentRange(from: received)
                }
                guard cr.start == received else {
                    throw DownloadError.rangeNotHonored(from: received, got: cr)
                }
                if let t = cr.total { total = t }

            case 200:
                // 서버가 Range 를 무시하고 처음부터 준다.
                if sawWholeBody {
                    // 두 번 연속 200 = Range 지원 없음. **여기서 자르지 않고 실패를 말한다.**
                    // 32MB 만 저장하고 "완료" 라 하던 것이 원래 고장이었다.
                    throw DownloadError.noRangeSupport
                }
                sawWholeBody = true
                try handle.truncate(atOffset: 0)
                try handle.seek(toOffset: 0)
                received = 0
                if let t = res.contentRange?.total ?? res.contentLength, t > 0 { total = t }

            case 416:
                // 이미 끝까지 받은 뒤에만 정상이다.
                if let t = res.contentRange?.total, received >= t {
                    return Outcome(bytes: received, total: t)
                }
                throw DownloadError.unsatisfiable(at: received)

            default:
                throw DownloadError.badStatus(res.statusCode)
            }

            let wrote = try append(res.bodyFile, to: handle)
            received += wrote
            progress(received, total)

            if let t = total, received >= t { return Outcome(bytes: received, total: t) }
            if wrote == 0 { throw DownloadError.emptySlice(at: received) }
            if total == nil, let prev = lastSliceLength, res.bodyLength < prev {
                // 전체 크기를 안 알려주는 서버 — **짧아진 조각이 마지막**이다.
                return Outcome(bytes: received, total: nil)
            }
            if wrote > 0 { lastSliceLength = wrote }
        }
    }

    /// 슬라이스 하나를 가져온다. **실패하면 같은 자리에서** 다시 시도한다.
    ///
    /// `received` 는 성공한 조각만큼만 늘어나므로 재시도는 항상 **그 지점부터**다.
    private static func fetch(
        fetcher: SliceFetching,
        from offset: Int64,
        retries: Int,
        retryDelay: Duration,
        onRetry: @Sendable (Int) -> Void
    ) async throws -> SliceResponse {
        var attempt = 0
        while true {
            do {
                return try await fetcher.slice(from: offset)
            } catch {
                // 취소는 재시도 대상이 아니다 — 사용자가 그만두라고 했다.
                if DownloadError.isCancellation(error) { throw error }
                attempt += 1
                if attempt > retries { throw error }
                onRetry(attempt)
                try await Task.sleep(for: retryDelay)
            }
        }
    }

    /// 조각 파일을 목적 파일 끝에 붙인다. **4MB 씩** 읽어 메모리를 묶어 둔다.
    private static let appendChunk = 4 * 1024 * 1024

    private static func append(_ source: URL, to handle: FileHandle) throws -> Int64 {
        let inHandle: FileHandle
        do {
            inHandle = try FileHandle(forReadingFrom: source)
        } catch {
            throw DownloadError.fileSystem("받은 조각을 열지 못했습니다 — \(error.localizedDescription)")
        }
        defer { try? inHandle.close() }

        var written: Int64 = 0
        while true {
            let data: Data
            do {
                data = try inHandle.read(upToCount: appendChunk) ?? Data()
            } catch {
                throw DownloadError.fileSystem("받은 조각을 읽지 못했습니다 — \(error.localizedDescription)")
            }
            if data.isEmpty { break }
            do {
                try handle.write(contentsOf: data)
            } catch {
                throw DownloadError.fileSystem("저장하지 못했습니다(디스크 여유를 확인하세요) — \(error.localizedDescription)")
            }
            written += Int64(data.count)
        }
        return written
    }
}

// MARK: - 실제 네트워크

/// **`URLSession` 으로 슬라이스를 받는다.**
public struct URLSessionSliceFetcher: SliceFetching {
    public let url: URL
    private let session: URLSession

    public init(url: URL, session: URLSession = .shared) {
        self.url = url
        self.session = session
    }

    public func slice(from offset: Int64) async throws -> SliceResponse {
        var req = URLRequest(url: url)
        req.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
        // 206 을 캐시하면 다음 요청이 **옛 조각**을 받아 파일이 조용히 어긋난다.
        req.cachePolicy = .reloadIgnoringLocalCacheData
        // **이 값은 "데이터가 끊긴 채 이만큼 기다리면 실패"** 다.
        // 실측(2026-10-18): 폰이 Doze 로 들어가면 조각이 그대로 멈춘다.
        // 그래서 짧게 잡고 재시도로 버틴다 — 한없이 매달려 있으면 진행률이 얼어붙는다.
        req.timeoutInterval = 120
        let (file, response) = try await session.download(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw DownloadError.badStatus(-1)
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0
        return SliceResponse(
            statusCode: http.statusCode,
            contentRange: ContentRange.parse(http.value(forHTTPHeaderField: "Content-Range")),
            contentLength: http.expectedContentLength >= 0 ? http.expectedContentLength : nil,
            bodyFile: file,
            bodyLength: size
        )
    }
}

// MARK: - 파일 하나 받기

/// **파일 하나를 끝까지 받아 저장 폴더에 놓는다.**
public enum RangeDownload {
    /// `.part` 에 먼저 쓰고, **전체 크기를 다 받은 뒤에만** 이름을 바꾼다.
    ///
    /// ## 왜 `.part` 인가
    ///
    /// - 중간에 끊기면 `.part` 가 남고, 다시 누르면 **그 지점부터** 이어받는다.
    /// - 완성된 이름의 파일은 **항상 온전하다.** 반쪽 파일이 완성본처럼 보이면
    ///   사용자는 그걸 열어 보고 나서야 안다("32MB 로 완료" 가 정확히 그 고장이었다).
    @discardableResult
    public static func download(
        url: URL,
        into folder: URL,
        fileName: String,
        session: URLSession = .shared,
        progress: @Sendable (Int64, Int64?) -> Void = { _, _ in },
        onRetry: @Sendable (Int) -> Void = { _ in }
    ) async throws -> URL {
        try await download(
            fetcher: URLSessionSliceFetcher(url: url, session: session),
            into: folder,
            fileName: fileName,
            progress: progress,
            onRetry: onRetry
        )
    }

    /// **가져오는 방법을 주입받는 본체** — 테스트가 진짜 서버 없이 이름 규칙·`.part`
    /// 처리·크기 검증까지 확인한다.
    @discardableResult
    public static func download(
        fetcher: SliceFetching,
        into folder: URL,
        fileName: String,
        progress: @Sendable (Int64, Int64?) -> Void = { _, _ in },
        onRetry: @Sendable (Int) -> Void = { _ in }
    ) async throws -> URL {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            throw DownloadError.fileSystem("저장 폴더를 만들지 못했습니다 — \(error.localizedDescription)")
        }

        let safeName = DownloadNaming.sanitize(fileName)
        let finalName = DownloadNaming.unique(safeName) { candidate in
            fm.fileExists(atPath: folder.appendingPathComponent(candidate).path)
        }
        let finalURL = folder.appendingPathComponent(finalName)
        let partURL = folder.appendingPathComponent(finalName + ".part")

        let outcome = try await RangeDownloader.run(
            fetcher: fetcher,
            partURL: partURL,
            progress: progress,
            onRetry: onRetry
        )

        // **전체 크기와 다르면 완성본으로 바꾸지 않는다.** 잘린 파일이 "받기 완료" 로
        // 남는 것이 정확히 사용자가 겪은 고장이다.
        if let t = outcome.total, outcome.bytes != t {
            throw DownloadError.incomplete(received: outcome.bytes, total: t)
        }

        do {
            if fm.fileExists(atPath: finalURL.path) { try fm.removeItem(at: finalURL) }
            try fm.moveItem(at: partURL, to: finalURL)
        } catch {
            throw DownloadError.fileSystem("완성 파일로 옮기지 못했습니다 — \(error.localizedDescription)")
        }
        return finalURL
    }
}
