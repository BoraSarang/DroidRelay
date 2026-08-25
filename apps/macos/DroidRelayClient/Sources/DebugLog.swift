import Foundation
import os

enum LogLevel: String {
    case info = "INFO"
    case warn = "WARN"
    case error = "ERROR"
}

/// 구조화 로거 — os.Logger 래핑 + 디버그 패널용 순환 버퍼
final class DebugLog: @unchecked Sendable {
    static let shared = DebugLog()
    static let maxLines = 500

    private let logger = Logger(subsystem: "com.borasarang.DroidRelayClient", category: "app")
    private let queue = DispatchQueue(label: "debuglog.buffer")
    private var buffer: [String] = []
    private let formatter: DateFormatter

    private init() {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        formatter = f
    }

    func i(_ tag: String, _ msg: String) { write(.info, tag, msg) }
    func w(_ tag: String, _ msg: String) { write(.warn, tag, msg) }
    func e(_ code: String, _ msg: String) { write(.error, code, msg) }

    private func write(_ level: LogLevel, _ tag: String, _ msg: String) {
        let line = "[\(formatter.string(from: Date()))] [\(level.rawValue)] [\(tag)] \(msg)"
        logger.log("\(line, privacy: .public)")
        queue.sync {
            buffer.append(line)
            if buffer.count > Self.maxLines {
                buffer.removeFirst(buffer.count - Self.maxLines)
            }
        }
    }

    /// 최근 로그 전체 (디버그 패널 표시/복사용)
    func dump() -> String {
        queue.sync { buffer.joined(separator: "\n") }
    }

    var lines: [String] { queue.sync { buffer } }
}
