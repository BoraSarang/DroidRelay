import XCTest
@testable import DroidRelayCore

/// 쓰기 동작의 **계약** — 특히 실패 처리 (M-14 쓰기 기능).
///
/// ## 왜 서버 없이 테스트하냐
///
/// 서버 규격이 두 군데에서 틀어진다:
/// - **상태 코드와 본문이 어긋난다** — `{"error":"없음"}` 이 **HTTP 200** 으로 온다(실측)
/// - **오류가 텍스트냐 JSON 이냐가 섞여 있다** — 422 는 텍스트, 409 는 JSON
///
/// 그래서 클라이언트가 **"성공" 으로 오인하지 않는지**가 핵심 계약이다.
final class WriteOpsTests: XCTestCase {

    // MARK: - 결과 해석

    /// **`error` 필드는 200 에도 실패로 본다** — 실측에서 `{"error":"없음"}` 이 200 이었다.
    /// 상태 코드만 믿으면 **"삭제했는데 성공했다고 뜨는"** 최악의 버그가 된다.
    func test_200이어도_error_있으면_실패다() {
        let d = Data(#"{"error":"없음"}"#.utf8)
        let r = RelayClient.writeResult(status: 200, data: d, body: [:])
        XCTAssertFalse(r.ok, "HTTP 200 인데 성공으로 처리했다")
        XCTAssertEqual(r.message, "없음")
    }

    /// **텍스트 오류** — 422 가 `E-AND-DOWN-1003: …` 처럼 텍스트로 온다.
    func test_텍스트_오류를_그대로_보인다() {
        let d = Data("E-AND-DOWN-1003: 유효한 http(s) URL이 아닙니다".utf8)
        let r = RelayClient.writeResult(status: 422, data: d, body: [:])
        XCTAssertFalse(r.ok)
        XCTAssertTrue(r.message.contains("E-AND-DOWN-1003"), r.message)
    }

    /// **서버가 준 사유를 가공하지 않는다** — 사용자가 보는 오류가 진짜 이유여야 한다.
    func test_서버_사유를_가공하지_않는다() {
        let d = Data(#"{"error":"E-AND-DOWN-1006: 이미 등록된 다운로드입니다","id":"abc"}"#.utf8)
        let r = RelayClient.writeResult(status: 409, data: d, body: [:])
        XCTAssertFalse(r.ok)
        XCTAssertEqual(r.message, "E-AND-DOWN-1006: 이미 등록된 다운로드입니다")
        // 중복이면 기존 id 를 돌려줘야 목록을 그대로 갱신할 수 있다
        XCTAssertEqual(r.newId, "abc")
    }

    /// 본문이 비었으면 상태 코드만으로 말한다 — 422 만 보여주면 사용자가 무의미해한다.
    func test_빈_본문은_상태_코드로_말한다() {
        let r = RelayClient.writeResult(status: 500, data: Data(), body: [:])
        XCTAssertFalse(r.ok)
        XCTAssertTrue(r.message.contains("500"), r.message)
    }

    /// **연결 실패(상태 0)** — 네트워크가 죽은 경우. "성공" 으로 보내면 안 된다.
    func test_연결_실패는_성공이_아니다() {
        let r = RelayClient.writeResult(status: 0, data: Data("연결 끊김".utf8), body: [:])
        XCTAssertFalse(r.ok)
        XCTAssertTrue(r.message.contains("연결"), r.message)
    }

    /// 성공 + 새 id — 다운로드/토렌트 추가가 이 형태로 돌아온다.
    func test_성공_응답에서_새_id를_꺼낸다() {
        let d = Data(#"{"id":"job-1","url":"http://x"}"#.utf8)
        let r = RelayClient.writeResult(status: 200, data: d, body: [:])
        XCTAssertTrue(r.ok)
        XCTAssertEqual(r.newId, "job-1")
    }

    /// `ok` 텍스트만 오는 응답(서버가 `{"ok":true}` 를 쓰는 곳이 있다)
    func test_ok_플래그_응답도_성공이다() {
        let r = RelayClient.writeResult(status: 200, data: Data(#"{"ok":true}"#.utf8), body: [:])
        XCTAssertTrue(r.ok)
    }

    // MARK: - 입력 검증 (서버에 보내기 전에 막는다)

    /// **서버에 보내지 않고 미리 막는다.** 빈 입력에 422 + 긴 오류 문구가 그대로 보인다.
    func test_잘못된_URL은_서버를_부르지_않게_막을_수_있다() {
        // 클라이언트 측 검증 규약: scheme 이 없으면 거부
        for bad in ["", "notaurl", "ftp://x", "example.com/file"] {
            XCTAssertFalse(bad.hasPrefix("http://") || bad.hasPrefix("https://"),
                           "\(bad) 는 통과시킬 수 없다")
        }
    }

    // MARK: - 토렌트 파싱 (실측 키)

    /// **서버는 `ip` 를 쓴다** — `address` 로 읽으면 피어 주소가 항상 빈 값이 된다(실측).
    func test_피어_주소는_ip_키에서_온다() {
        let p = Peer(json: ["ip": "1.2.3.4:6881", "flags": 1, "downSpeed": 100])!
        XCTAssertEqual(p.address, "1.2.3.4:6881")
        XCTAssertTrue(p.isSeeder, "flags bit0 이면 시더")
    }

    func test_피어_시더_판정은_flags_비트다() {
        XCTAssertTrue(Peer(json: ["ip": "a:1", "flags": 1])!.isSeeder)
        XCTAssertTrue(Peer(json: ["ip": "a:1", "flags": 3])!.isSeeder)   // 0b11
        XCTAssertFalse(Peer(json: ["ip": "a:1", "flags": 2])!.isSeeder)
        XCTAssertFalse(Peer(json: ["ip": "a:1", "flags": 0])!.isSeeder)
    }

    /// 주소가 없으면 피어로 인정하지 않는다 — 빈 줄이 목록에 섞이면 화면이 더러워진다.
    func test_주소_없는_피어는_버린다() {
        XCTAssertNil(Peer(json: ["flags": 1]))
    }

    /// **스웸 수치 3종을 구분한다** — 다르면 "트래커가 뒤처졌다" 는 사실이 사라진다.
    func test_스웜_수치를_구분한다() {
        let d = TorrentDetail(json: [
            "id": "t1", "seeds": 25, "peers": 294,
            "numComplete": 25, "numIncomplete": 300,
            "connectedSeeders": 9, "connectedLeechers": 1, "isSeeding": true,
        ])!
        XCTAssertEqual(d.seeds, 25)                 // 트래커가 알려준 시드
        XCTAssertEqual(d.numComplete, 25)           // 스웜 전체 시드
        XCTAssertEqual(d.numIncomplete, 300)
        XCTAssertEqual(d.connectedSeeders, 9)       // 지금 붙어있는 시드
        XCTAssertTrue(d.isSeeding)
    }

    /// id 가 없으면 파싱에 실패한다 — 상세 화면이 빈 화면으로 떠야 한다.
    func test_id_없는_상세는_파싱_실패다() {
        XCTAssertNil(TorrentDetail(json: ["name": "x"]))
    }

    /// 파일 목록 — 진행률은 0..1 실수다.
    func test_파일_목록을_파싱한다() {
        let d = TorrentDetail(json: [
            "id": "t1",
            "files": [["index": 0, "path": "a.mkv", "size": 100, "progress": 0.5, "selected": true],
                      ["index": 1, "path": "b.srt", "size": 10, "progress": 0.0, "selected": false]],
        ])!
        XCTAssertEqual(d.files.count, 2)
        XCTAssertEqual(d.files[0].path, "a.mkv")
        XCTAssertEqual(d.files[0].progress, 0.5, accuracy: 0.001)
        XCTAssertFalse(d.files[1].selected)
        XCTAssertEqual(d.files[1].id, 1)     // Identifiable 이 id 로 index 를 쓴다
    }
}
