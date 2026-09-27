import Foundation

/// SSE (``text/event-stream``) 파서.
///
/// **바이트 단위로 처리한다 — `String` 의 Character 로 하면 CRLF 에서 깨진다.**
/// Swift 는 `\r\n` 을 하나의 그래프eme cluster 로 취급하므로 `"data: x\r\n\r\n"` 에서
/// `buffer.firstIndex(of: "\n")` 이 **nil** 을 준다(테스트가 잡아낸 실제 버그).
/// SSE 프레임은 바이트 개행(0x0A) 기준이라 UTF-8 바이트로 쪼개는 게 맞다.
public struct SSEParser {
    private static let LF: UInt8 = 0x0A
    private static let CR: UInt8 = 0x0D
    private static let COLON: UInt8 = 0x3A

    /// 아직 개행이 오지 않은 바이트
    private var buffer: [UInt8] = []
    /// 진행 중인 이벤트의 `data:` 값들 (스펙: 여러 줄이면 개행으로 연결)
    private var dataLines: [String] = []

    public init() {}

    /// 바이트 하나를 먹는다 — `URLSession.AsyncBytes` 를 직접 순회할 때 쓴다.
    ///
    /// **`bytes.lines` 를 쓰면 안 된다.** `AsyncLineSequence` 는 **빈 줄을 버린다.**
    /// SSE 프레임은 "data: 값" 다음에 **빈 줄**로 끝나므로, lines 로 읽으면
    /// 종료 신호가 사라져 이벤트가 영영 완성되지 않는다. 실제로 curl 은
    /// `data: tick` 를 주는데 Swift 는 0건이었다(진단 모드가 잡은 버그).
    public mutating func consumeByte(_ b: UInt8) -> [String] {
        buffer.append(b)
        var out: [String] = []
        while let idx = buffer.firstIndex(of: 0x0A) {
            let line = Array(buffer[0..<idx])
            buffer.removeSubrange(0...idx)
            process(line, into: &out)
        }
        return out
    }

    public mutating func consume(_ chunk: Data) -> [String] {
        buffer.append(contentsOf: chunk)
        var out: [String] = []
        while let idx = buffer.firstIndex(of: Self.LF) {
            let line = Array(buffer[0..<idx])
            buffer.removeSubrange(0...idx)
            process(line, into: &out)
        }
        return out
    }

    private mutating func process(_ raw: [UInt8], into out: inout [String]) {
        var b = raw
        if b.last == Self.CR { b.removeLast() }              // CRLF
        if b.isEmpty {                                        // 이벤트 끝
            if !dataLines.isEmpty {
                out.append(dataLines.joined(separator: "\n"))
                dataLines = []
            }
            return
        }
        if b.first == Self.COLON { return }                  // 주석(keep-alive)
        let s = String(decoding: b, as: UTF8.self)
        guard s.hasPrefix("data:") else { return }           // id:/retry:/event: 는 버린다
        dataLines.append(String(s.dropFirst(5)).trimmingCharacters(in: .whitespaces))
    }

    /// 스트림 종료 시 미완성 줄을 처리하고 남은 이벤트를 회수한다.
    /// 개행 없이 끊기면 마지막 이벤트가 버퍼에 묶여 있으므로 flush 없이는 놓친다.
    public mutating func flush() -> [String] {
        var out: [String] = []
        if !buffer.isEmpty {
            let line = buffer
            buffer = []
            process(line, into: &out)
        }
        if !dataLines.isEmpty {
            out.append(dataLines.joined(separator: "\n"))
            dataLines = []
        }
        return out
    }
}

/// `/api/events` 구독 — 서버 상태가 **바뀔 때만** tick 이 온다(2026-09 부하 최적화).
/// 변화가 없으면 beat 으로만 오므로, 폴링 대비 요청량이 크게 적다.
public actor EventStream {
    public private(set) var isRunning = false
    private var task: Task<Void, Never>?

    public init() {}

    /// 연결 루프. 재연결은 지수 백오프(1s → 2s → 4s … 최대 30s).
    public func run(base: URL, onTick: @Sendable @escaping () async -> Void) {
        guard !isRunning else { return }
        isRunning = true
        task = Task {
            var backoff = 1
            while !Task.isCancelled {
                do {
                    try await connect(base: base) { await onTick() }
                    backoff = 1
                } catch {
                    try? await Task.sleep(for: .seconds(backoff))
                    backoff = min(backoff * 2, 30)
                }
            }
            isRunning = false
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
        isRunning = false
    }

    private func connect(base: URL, onTick: @Sendable @escaping () async -> Void) async throws {
        var req = URLRequest(url: base.appendingPathComponent("api/events"))
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        req.timeoutInterval = .infinity

        let (bytes, response) = try await URLSession.shared.bytes(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        var parser = SSEParser()
        for try await b in bytes {
            try Task.checkCancellation()
            if !parser.consumeByte(b).isEmpty { await onTick() }
        }
    }
}
