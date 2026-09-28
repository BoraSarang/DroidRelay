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
    /// **트래커가 보고한 시드 수** (서버 `seeds` = `listSeeds`).
    /// 이 토렌트를 "완전한 사본으로 가진 사람" 수.
    public let seeds: Int
    /// **트래커가 보고한 피어 수** (서버 `peers` = `listPeers`).
    public let peers: Int
    /// **스와밍 전체 시드 수** (서버 `numComplete`).
    ///
    /// ## 왜 `seeds` 와 다른가
    ///
    /// 실측에서 두 값이 **달랐다**: `seeds: 4` 인데 `numComplete: 9` 였다.
    /// - `seeds`/`listSeeds` = 이 토렌트(같은 infohash)를 공유하는 시드
    /// - `numComplete`    = 스와밍에서 완전한 사본 수 (다른 파일셋 포함 가능)
    ///
    /// 웹은 `seeds` 를 쓴다(1313행). **웹과 같은 값을 보여야 하므로 기본값은 `seeds`.**
    /// `numComplete` 는 상세 화면용으로 따로 보관한다.
    public let swarmSeeds: Int
    /// 스와밍 미완료 피어 수 (서버 `numIncomplete`)
    public let swarmPeers: Int
    /// **지금 실제로 연결된 피어 수** (서버 `numConnections`).
    ///
    /// 트래커가 57명을 알려도 실제로 붙은 건 1명일 수 있다.
    /// "57명이나와 연결돼 있다" 고 오해하면 안 되니 따로 둔다.
    public let connections: Int
    /// **완료 상태인가** (서버 `isSeeding`) — progress 100 과는 다르다.
    /// 완료된 파일을 계속 올리고 있으면 `isSeeding: true` 이다.
    public let isSeedingFlag: Bool
    public let fileCount: Int
    /// 첫 파일의 경로 (웹 1317행 `t.files[0].path`)
    public let firstFilePath: String
    /// **현재 적용된 속도 제한 (B/s). 0 = 무제한**
    public let maxDownBps: Int
    /// **서버가 준 오류 메시지** (웹 1320행 `t.errorMessage`).
    ///
    /// 비었으면 "문제없음" 이 아니라 **"아직 실패하지 않음"** 이므로
    /// 비어 있을 땐 화면에 아무것도 그리지 않는다.
    public let errorMessage: String

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
        // **목록 API 에는 스와밍/연결 필드가 없을 수 있다.** 상세에만 있다.
        // 없으면 0 이고, 표시 로직이 "0 0 은 안 그린다" 로 가려 처리한다.
        self.swarmSeeds = RelayClient.int(o["numComplete"])
        self.swarmPeers = RelayClient.int(o["numIncomplete"])
        self.connections = RelayClient.int(o["numConnections"])
        self.isSeedingFlag = (o["isSeeding"] as? Bool) ?? false
        self.maxDownBps = RelayClient.int(o["maxDownBps"])
        self.errorMessage = o["errorMessage"] as? String ?? ""
        let files = o["files"] as? [Any] ?? []
        self.fileCount = files.count
        self.firstFilePath = (files.first as? [String: Any])?["path"] as? String ?? ""
    }

    public var isActive: Bool { state == "DOWNLOADING" || state == "SEEDING" || state == "FETCHING_METADATA" }
    public var isDone: Bool { state == "DONE" }


    /// **진행 중인지** — 서버가 보내는 상태 문자열을 하드코딩하지 않는다.
    ///
    /// 이전에 `"RUNNING"` 으로만 걸렀는데 서버는 `"DOWNLOADING"` 을 보낸다.
    /// 문자열이 하나라도 어긋나면 **조용히 0 이 된다** — 에러도 없다.
    /// 그래서 대소문자를 무시하고 진행 상태들을 폭넓게 받는다.
    public var isRunning: Bool {
        let s = state.lowercased()
        return s.contains("running") || s.contains("download") || s.contains("active")
            || s.contains("progress") || s.contains("start")
    }
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
    /// **보관함 기준 전체 경로** (예: `M/영화/예편.mkv`).
    ///
    /// ## 왜 클라이언트가 만들어야 하는가
    ///
    /// `GET /api/storage` 응답에는 **`path` 키가 없다**(실측). 오직 `name` 뿐이다.
    /// 그런데 `POST /api/storage/rename` 은 **`from` 에 전체 경로**를 받는다.
    ///
    /// 이전 코드는 `o["path"] ?? name` 으로 읽었는데 서버는 그 키를 안 준다.
    /// → **조용히 이름만 보내서** "없는 경로" 오류가 났는데
    /// 사용자는 "이름이 잘못됐나" 하고 이유를 알 수 없었다.
    ///
    /// 그래서 **상위 경로 + 이름**으로 직접 조합한다. 이것이 유일하게 올바른 방법이다.
    public let path: String

    public init?(json: Any, dir: String = "") {
        guard let o = json as? [String: Any], let name = o["name"] as? String else { return nil }
        self.name = name
        // **경로 조합 규칙을 한 곳에 둔다.**
        //
        // 앞뒤 슬래시를 모두 벗겨야 한다. 하나라도 남으면:
        // - 앞쪽 잔여: `/M/a` → 서버 `storageChild` 가 루트 밖으로 보고 거부
        // - 뒤쪽 잔여: `M//a` → 경로가 깨져 "원본 없음" 이 된다
        //
        // 이걸 몰랐을 때 하위 폴더 쓰기가 전부 조용히 실패했다.
        let d = dir.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.path = d.isEmpty ? name : "\(d)/\(name)"
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
