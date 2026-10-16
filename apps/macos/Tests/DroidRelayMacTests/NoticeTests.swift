import XCTest
@testable import DroidRelayCore

/// **결과 안내 규칙 회귀 고정** — "성공은 사라지고 실패는 남는다".
///
/// ## 왜 테스트가 필요한가
///
/// 이전에는 `lastResult: String` 하나라서 성공/실패 구분이 없었다. 그래서
/// **성공 안내도 경고 아이콘이 붙고 영구히 남았다** — 사용자가 "이게 아직
/// 유효한가" 를 알 수 없었고, 성공을 문제처럼 보이게 했다.
///
/// 규칙 자체(무엇이 사라지고 무엇이 남는가)는 순수 계산이라 여기서 고정한다.
/// 타이머(AppKit)는 몇 줄 안 되고, 이 테스트가 **의미** 를 지킨다.
final class NoticeTests: XCTestCase {

    func testSuccessAutoDismissesAndIsNotError() {
        let n = Notice(text: "다운로드 3건 추가", kind: .success)
        XCTAssertTrue(n.autoDismisses, "성공 안내는 스스로 사라져야 한다")
        XCTAssertFalse(n.isError)
    }

    func testInfoAutoDismissesAndIsNotError() {
        let n = Notice(text: "받는 중 — 영상.mkv", kind: .info)
        XCTAssertTrue(n.autoDismisses)
        XCTAssertFalse(n.isError)
    }

    /// ★ 핵심 — **실패는 절대 사라지지 않는다.**
    func testErrorNeverAutoDismisses() {
        let n = Notice(text: "삭제 실패 — 서버가 거절했습니다", kind: .error)
        XCTAssertFalse(n.autoDismisses, "실패가 조용히 사라지면 사용자는 성공으로 믿는다")
        XCTAssertTrue(n.isError, "실패에는 경고 아이콘이 붙어야 한다")
    }

    /// `Bool` 하나로 텍스트와 종류가 **같이** 움직인다(어긋날 여지 없음).
    func testResultHelperKeepsTextAndKindInSync() {
        XCTAssertEqual(Notice.result(ok: true, "완료"), Notice(text: "완료", kind: .success))
        XCTAssertEqual(Notice.result(ok: false, "실패"), Notice(text: "실패", kind: .error))
        XCTAssertFalse(Notice.result(ok: true, "완료").isError)
        XCTAssertTrue(Notice.result(ok: false, "실패").isError)
    }

    func testAutoDismissSecondsIsSane() {
        XCTAssertGreaterThan(Notice.autoDismissSeconds, 1, "너무 짧으면 읽기 전에 사라진다")
        XCTAssertLessThan(Notice.autoDismissSeconds, 10, "너무 길면 방해된다")
    }
}
