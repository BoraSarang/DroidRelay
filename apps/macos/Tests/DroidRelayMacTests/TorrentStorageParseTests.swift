import XCTest
@testable import DroidRelayCore

/// M3 — 토렌트 · 보관함 파싱.
///
/// M1 에서 실제 버그 5건 중 4건이 **파싱/계산**이었다. 그 전제에 따라
/// 서버 JSON → 모델 변환을 전부 테스트한다. 화면(SwiftUI)은 못 검증하지만
/// "틀린 값을 조용히 보여주는" 이 실패는 여기서 막는다.
final class TorrentStorageParseTests: XCTestCase {

    // ── 숫자 안전 추출 ───────────────────────────────────

    func test_null_은_0_이_된다() {
        XCTAssertEqual(RelayClient.int(nil), 0)
        XCTAssertEqual(RelayClient.int(NSNull()), 0)
    }

    /// 서버는 크기를 **바이트 정수**로 준다. Double 을 섞어 넘겨도 잘려야 하고,
    /// NaN/Infinity 가 섞이면 0 이 되어야 한다(NaN 이 진행률로 새면 바가 깨진다).
    func test_숫자_정규화() {
        XCTAssertEqual(RelayClient.int(1_048_576), 1_048_576)
        XCTAssertEqual(RelayClient.int(1_048_576.9), 1_048_576)
        XCTAssertEqual(RelayClient.int("4096"), 4096)
        XCTAssertEqual(RelayClient.int("파싱불가"), 0)
        XCTAssertEqual(RelayClient.int(Double.nan), 0)
        XCTAssertEqual(RelayClient.int(Double.infinity), 0)
    }

    // ── Torrent ─────────────────────────────────────────

    private func torrentJSON(_ extra: [String: Any] = [:]) -> [String: Any] {
        var o: [String: Any] = [
            "id": "t-1", "name": "T38-072", "state": "DOWNLOADING",
            "progress": 0.42, "downloadSpeed": 161_000, "uploadSpeed": 0,
            "totalSize": 4_394_658_638, "downloadedSize": 1_800_000_000,
            "seeds": 19, "peers": 169, "files": [["a"], ["b"]],
        ]
        for (k, v) in extra { o[k] = v }
        return o
    }

    func test_토렌트_정상_파싱() {
        let t = Torrent(json: torrentJSON())
        XCTAssertNotNil(t)
        XCTAssertEqual(t!.id, "t-1")
        XCTAssertEqual(t!.percent, 42)
        XCTAssertEqual(t!.seeds, 19)
        XCTAssertEqual(t!.fileCount, 2)
        XCTAssertTrue(t!.isActive)
        XCTAssertFalse(t!.isDone)
        XCTAssertEqual(t!.stateLabel, "다운로드 중")
    }

    /// 진행률이 0..1 밖으로 나가면 바(width = w * p) 가 화면 밖으로 나가거나
    /// 음수가 되면 SwiftUI 가 조용히 뭉갠다. 클램프가 방어선이다.
    func test_진행률_클램프() {
        XCTAssertEqual(Torrent(json: torrentJSON(["progress": 1.8]))!.percent, 100)
        XCTAssertEqual(Torrent(json: torrentJSON(["progress": -0.5]))!.percent, 0)
        XCTAssertEqual(Torrent(json: torrentJSON(["progress": Double.nan]))!.percent, 0)
    }

    /// 상태값을 모르면 "UNKNOWN" 으로 뭉개지 말고 **원문을 그대로** 보여준다.
    /// 서버가 새 상태를 추가해도 사용자가 원인을 볼 수 있어야 한다.
    func test_알_수_없는_상태는_원문_유지() {
        let t = Torrent(json: torrentJSON(["state": "BRAND_NEW_STATE"]))!
        XCTAssertEqual(t.stateLabel, "BRAND_NEW_STATE")
    }

    func test_상태_라벨_전체() {
        let pairs: [(String, String)] = [
            ("DOWNLOADING", "다운로드 중"), ("SEEDING", "시딩"), ("DONE", "완료"),
            ("PAUSED", "일시정지"), ("STALLED", "정체"), ("QUEUED", "대기"),
            ("FAILED", "실패"), ("FETCHING_METADATA", "메타데이터"),
        ]
        for (raw, label) in pairs {
            XCTAssertEqual(Torrent(json: torrentJSON(["state": raw]))!.stateLabel, label, "state=\(raw)")
        }
    }

    /// id 가 없으면 목록에 쓸 수 없다 — 조용히 버려야 한다(잘못된 항목이 화면에 나오는 것보다).
    func test_id_없는_항목은_버려진다() {
        var o = torrentJSON(); o.removeValue(forKey: "id")
        XCTAssertNil(Torrent(json: o))
        XCTAssertNil(Torrent(json: "문자열"))
        XCTAssertNil(Torrent(json: ["name": "x"]))
    }

    /// 필드가 빠져도 크래시 나면 안 된다 — 서버 버전에 따라 다를 수 있다.
    func test_필드_누락에_강건하다() {
        let t = Torrent(json: ["id": "only-id"])
        XCTAssertNotNil(t)
        XCTAssertEqual(t!.name, "(이름 없음)")
        XCTAssertEqual(t!.state, "UNKNOWN")
        XCTAssertEqual(t!.totalSize, 0)
        XCTAssertEqual(t!.percent, 0)
    }

    /// totalSize 가 0 인 진행 중 torrent — "0 B / 0 B" 로 나눗셈 예외가 나지 않아야 한다.
    func test_크기_표시_0_안_나눗셈() {
        let t = Torrent(json: torrentJSON(["totalSize": 0, "downloadedSize": 0]))
        XCTAssertNotNil(t?.sizeText)
    }

    // ── StorageEntry ────────────────────────────────────

    func test_보관함_폴더_파싱() {
        let e = StorageEntry(json: [
            "name": "AI", "type": "dir", "size": 0, "count": 12,
            "modified": 1_790_558_468_810,
        ])
        XCTAssertEqual(e?.name, "AI")
        XCTAssertTrue(e!.isDirectory)
        XCTAssertEqual(e!.fileCount, 12)
        XCTAssertEqual(e!.sizeText, "12개 항목")
        XCTAssertNotNil(e!.modified)
    }

    func test_보관함_파일_파싱() {
        let e = StorageEntry(json: [
            "name": "a.mp4", "type": "file", "size": 2_001_226, "count": 0,
            "modified": 1_790_558_468_810,
        ])
        XCTAssertFalse(e!.isDirectory)
        XCTAssertEqual(e!.size, 2_001_226)
        // ByteCountFormatter 의 정확한 문자열은 **OS 버전마다 달라진다**
        // ("2 MB" / "2MB" / "2.0 MB"). 하드코딩하면 macOS 업그레이드마다 깨진다.
        // 계약은 "같은 바이트면 같은 표시" 다.
        XCTAssertEqual(e!.sizeText, RelayClient.format(bytes: 2_001_226))
        XCTAssertFalse(e!.sizeText.isEmpty)
        XCTAssertFalse(e!.sizeText.contains("0 B"), "0 B 로 표기되면 파싱이 깨진 것")
    }

    /// modified 가 0 / 누락이면 날짜를 아예hide 해야 한다 — 1970년이 뜨면 버그처럼 보인다.
    func test_보관함_날짜_없음() {
        XCTAssertEqual(StorageEntry(json: ["name": "x", "type": "file"])!.modifiedText, "")
        XCTAssertEqual(StorageEntry(json: ["name": "x", "type": "file", "modified": 0])!.modifiedText, "")
    }

    func test_보관함_이름_없으면_버려진다() {
        XCTAssertNil(StorageEntry(json: ["type": "file"]))
        XCTAssertNil(StorageEntry(json: 42))
    }

    /// 보관함 항목의 id 는 이름 — 같은 폴더 안에서 이름은 유일하므로 형식상 안전하다.
    /// 그래도 id 계약이 실제로 성립하는지 고정한다(SwiftUI ForEach 가 이걸 쓴다).
    func test_보관함_id_는_이름() {
        let a = StorageEntry(json: ["name": "같은", "type": "file"])!
        let b = StorageEntry(json: ["name": "같은", "type": "dir"])!
        XCTAssertEqual(a.id, b.id)
    }

    // MARK: - 경로 조합 (어제 틀렸던 가정의 교정)

    /// **`GET /api/storage` 에는 `path` 키가 없다** (실측). 오직 `name` 뿐이다.
    ///
    /// 그런데 `POST /api/storage/rename` 은 `from` 에 **전체 경로**를 받는다.
    /// 이전 코드는 `o["path"] ?? name` 이라 **항상 이름만** 보냈고,
    /// 하위 폴더 항목에 대한 모든 쓰기가 실패했다.
    /// 이 테스트가 그 가정을 되돌린다 — 서버가 `path` 를 **안 준다** 는 걸 고정.
    func test_서버는_path_키를_안준다() {
        let e = StorageEntry(json: ["name": "예편.mkv", "type": "file"], dir: "M/영화")!
        // 서버 응답에 path 가 있었으면 이렇게 됐어야 한다
        XCTAssertEqual(e.name, "예편.mkv")
        // 그런데 path 는 **상위 경로 + 이름**으로 만들어졌다
        XCTAssertEqual(e.path, "M/영화/예편.mkv")
    }

    /// 루트(빈 경로)에서는 경로가 이름 그대로다
    func test_루트에서는_경로가_이름이다() {
        let e = StorageEntry(json: ["name": "M", "type": "dir"], dir: "")!
        XCTAssertEqual(e.path, "M")
    }

    /// **앞에 `/` 가 붙은 상위 경로도 처리한다** — 규칙이 두 갈래로 갈라지지 않게.
    func test_앞에슬래시가_붙은_상위경로() {
        XCTAssertEqual(StorageEntry(json: ["name": "a", "type": "file"], dir: "/M")!.path, "M/a")
        XCTAssertEqual(StorageEntry(json: ["name": "a", "type": "file"], dir: "/M/영화")!.path, "M/영화/a")
    }

    /// **`//` 가 생기면 안 된다** — 서버의 `storageChild` 가 거부한다.
    func test_슬래시가_두번_생기지_않는다() {
        let e = StorageEntry(json: ["name": "a", "type": "file"], dir: "M/")!
        XCTAssertEqual(e.path, "M/a")
        XCTAssertFalse(e.path.contains("//"), "중복 슬래시는 서버가 거부한다")
    }
}
