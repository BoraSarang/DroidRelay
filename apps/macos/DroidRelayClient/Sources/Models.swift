import Foundation

// MARK: - 서버 응답 모델 (RelayServer.kt API 스키마)

struct ServerInfo: Codable, Hashable {
    var ip: String?
    var port: Int?
    var version: String?
    var storageFree: Int64?
    var storageTotal: Int64?
    var running: Int?
    var speedTotalBps: Int64?
}

struct Job: Codable, Identifiable, Hashable {
    var id: String
    var url: String
    var filename: String
    var state: String
    var progress: Double
    var downloadedBytes: Int64
    var totalBytes: Int64
    var speedBps: Int64
    var errorMessage: String?

    var isRunning: Bool { state == "RUNNING" || state == "QUEUED" }
    var isDone: Bool { state == "DONE" }
}

struct TorrentFile: Codable, Hashable {
    var index: Int
    var path: String
    var size: Int64
    var progress: Double
    var selected: Bool
}

struct Torrent: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var state: String
    var progress: Double
    var downloadSpeed: Int64
    var uploadSpeed: Int64
    var totalSize: Int64
    var downloadedSize: Int64
    var seeds: Int
    var peers: Int
    var files: [TorrentFile]?

    var isActiveState: Bool { state == "DOWNLOADING" || state == "CHECKING" || state == "METADATA" }
}

struct StorageItem: Codable, Identifiable, Hashable {
    var name: String
    var type: String // "dir" | "file"
    var size: Int64
    var count: Int

    var id: String { "\(type):\(name)" }
    var isDir: Bool { type == "dir" }
}

struct AddResult: Codable {
    var id: String
}

// MARK: - 표시 포맷

enum Fmt {
    static func bytes(_ b: Int64) -> String {
        guard b > 0 else { return "0 B" }
        let units = ["B", "KB", "MB", "GB", "TB"]
        var v = Double(b)
        var i = 0
        while v >= 1024 && i < units.count - 1 { v /= 1024; i += 1 }
        return i == 0 ? String(format: "%.0f %@", v, units[i]) : String(format: "%.1f %@", v, units[i])
    }

    static func speed(_ bps: Int64) -> String { bytes(bps) + "/s" }

    static func pct(_ p: Double) -> String { "\(Int((p * 100).rounded()))%" }

    /// 스토리지 경로 결합 (빈 path 허용)
    static func joinPath(_ base: String, _ name: String) -> String {
        base.isEmpty ? name : base + "/" + name
    }

    /// 파일명 안전화 (경로 구분자 제거)
    static func safeFilename(_ s: String) -> String {
        s.replacingOccurrences(of: "/", with: "_")
    }
}
