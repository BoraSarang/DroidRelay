import Foundation
import os

enum LogLevel: String {
    case info = "INFO"
    case warn = "WARN"
    case error = "ERROR"
}

/// 구조화 로거 — os.Logger 래핑 + 파일 로깅
final class DebugLog: @unchecked Sendable {
    static let shared = DebugLog()
    static let maxLines = 500

    struct Line: Identifiable, Equatable {
        let id: Int
        let text: String
    }

    private let logger = Logger(subsystem: "com.borasarang.DroidRelayClient", category: "app")
    private let queue = DispatchQueue(label: "debuglog.buffer")
    private var buffer: [String] = []
    private var tagged: [Line] = []
    private var seq = 0
    private let formatter: DateFormatter
    private let fileHandle: FileHandle?
    private let logFileURL: URL?

    private init() {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        formatter = f

        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DroidRelay")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let logFile = dir.appendingPathComponent("debug.log")
        logFileURL = logFile
        if FileManager.default.fileExists(atPath: logFile.path) {
            try? FileManager.default.removeItem(at: logFile)
        }
        FileManager.default.createFile(atPath: logFile.path, contents: nil)
        fileHandle = try? FileHandle(forWritingTo: logFile)

        let line = "[\(f.string(from: Date()))] [INIT] 앱 기동"
        writeRaw(line)
    }

    func i(_ tag: String, _ msg: String) { write(.info, tag, msg) }
    func w(_ tag: String, _ msg: String) { write(.warn, tag, msg) }
    func e(_ code: String, _ msg: String) { write(.error, code, msg) }

    private func write(_ level: LogLevel, _ tag: String, _ msg: String) {
        let line = "[\(formatter.string(from: Date()))] [\(level.rawValue)] [\(tag)] \(msg)"
        logger.log("\(line, privacy: .public)")
        queue.sync {
            buffer.append(line)
            seq += 1
            tagged.append(Line(id: seq, text: line))
            if buffer.count > Self.maxLines {
                buffer.removeFirst(buffer.count - Self.maxLines)
            }
            if tagged.count > Self.maxLines {
                tagged.removeFirst(tagged.count - Self.maxLines)
            }
        }
        writeRaw(line)
    }

    private func writeRaw(_ line: String) {
        if let data = (line + "\n").data(using: .utf8) {
            fileHandle?.write(data)
        }
    }

    /// 최근 로그 전체
    func dump() -> String {
        queue.sync { buffer.joined(separator: "\n") }
    }

    var lines: [String] { queue.sync { buffer } }

    /// List 바인딩용 — 고유 id 부여된 라인
    var taggedLines: [Line] { queue.sync { tagged } }

    /// 버퍼 비우기
    func clear() {
        queue.sync {
            buffer.removeAll()
            tagged.removeAll()
        }
    }
}
