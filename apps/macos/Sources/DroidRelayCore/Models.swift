import Foundation

/// `/api/torrents` 항목 — 웹 대시보드와 **같은** 필드를 쓴다.
///
/// 파싱을 `init?(json:)` 순수 함수로 분리한 이유: 상태값·숫자 단위의 잘못된 해석을
/// OS 없이 검증하기 위해서다. M1 에서 SSE 파서 버그 4건이 이렇게 잡혔듯,
/// 클라이언트가 조용히 틀린 값을 화면에 보여주는 게 가장 나쁜 실패다.
public struct Torrent: Identifiable, Sendable, Equatable {
    public let id: String
    public let name: String
    public let state: String
    public let progress: Double          // 0...1
    public let downloadBps: Int
    public let uploadBps: Int
    public let totalSize: Int
    public let downloadedSize: Int
    public let seeds: Int
    public let peers: Int
    public let fileCount: Int

    public init?(json: Any) {
        guard let o = json as? [String: Any], let id = o["id"] as? String else { return nil }
        self.id = id
        self.name = o["name"] as? String ?? "(이름 없음)"
        self.state = o["state"] as? String ?? "UNKNOWN"
        let p = (o["progress"] as? NSNumber)?.doubleValue ?? 0
        self.progress = p.isFinite ? min(max(p, 0), 1) : 0
        self.downloadBps = RelayClient.int(o["downloadSpeed"])
        self.uploadBps = RelayClient.int(o["uploadSpeed"])
        self.totalSize = RelayClient.int(o["totalSize"])
        self.downloadedSize = RelayClient.int(o["downloadedSize"])
        self.seeds = RelayClient.int(o["seeds"])
        self.peers = RelayClient.int(o["peers"])
        self.fileCount = (o["files"] as? [Any])?.count ?? 0
    }

    public var isActive: Bool { state == "DOWNLOADING" || state == "SEEDING" || state == "FETCHING_METADATA" }
    public var isDone: Bool { state == "DONE" }
    public var percent: Int { Int((progress * 100).rounded()) }

    public var speedText: String { Self.bps(downloadBps) }
    public var upText: String { Self.bps(uploadBps) }
    public var sizeText: String {
        totalSize > 0 ? "\(RelayClient.format(bytes: downloadedSize)) / \(RelayClient.format(bytes: totalSize))"
                      : RelayClient.format(bytes: downloadedSize)
    }

    /// 서버가 보내는 상태값을 사람이 읽을 말로. 알 수 없는 값은 **원문 유지** —
    /// 새 상태가 서버에 추가돼도 "UNKNOWN" 으로 뭉개지지 않게.
    public var stateLabel: String {
        switch state {
        case "DOWNLOADING": return "다운로드 중"
        case "SEEDING": return "시딩"
        case "DONE": return "완료"
        case "PAUSED": return "일시정지"
        case "STALLED": return "정체"
        case "QUEUED": return "대기"
        case "FAILED": return "실패"
        case "FETCHING_METADATA": return "메타데이터"
        default: return state
        }
    }

    static func bps(_ b: Int) -> String { b > 0 ? ByteCountFormatter.string(fromByteCount: Int64(b), countStyle: .binary) : "—" }
}

/// `/api/storage?path=` 항목 — 보관함(=`/sdcard/Download/DroidRelay`) 내용물.
public struct StorageEntry: Identifiable, Sendable, Equatable {
    public var id: String { name }        // 같은 폴더 안에서는 이름이 유일
    public let name: String
    public let isDirectory: Bool
    public let size: Int
    public let fileCount: Int
    public let modified: Date?

    public init?(json: Any) {
        guard let o = json as? [String: Any], let name = o["name"] as? String else { return nil }
        self.name = name
        let type = o["type"] as? String ?? "file"
        self.isDirectory = type == "dir"
        self.size = RelayClient.int(o["size"])
        self.fileCount = RelayClient.int(o["count"])
        let ms = (o["modified"] as? NSNumber)?.doubleValue ?? 0
        self.modified = ms > 0 ? Date(timeIntervalSince1970: ms / 1000) : nil
    }

    public var sizeText: String { isDirectory ? "\(fileCount)개 항목" : RelayClient.format(bytes: size) }
    public var modifiedText: String {
        guard let m = modified else { return "" }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: m)
    }
}
