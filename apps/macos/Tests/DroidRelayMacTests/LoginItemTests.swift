import XCTest
import ServiceManagement
@testable import DroidRelayCore

/// 로그인 시 자동 실행 (M-14).
///
/// **이 테스트는 상태를 바꾸지 않는다.**
/// `set(true)` 을 호출하면 **개발자 컴퓨터의 실제 로그인 항목이 등록**되고,
/// `swift test` 를 반복할 때마다 쌓인다(실제로 한 번 겪었다 — 테스트가 사용자 환경을
/// 오염시켰다). 그래서 여기서는 조회와 순수 함수만 검증하고,
/// 실제 등록/해제는 <b>설치본에서 사람이 스위치를 눌러</b> 확인할 수밖에 없는 영역이다.
/// 그 경계를 코드에 남기는 것이 이 파일의 목적이다.
final class LoginItemTests: XCTestCase {

    /// 상태 조회가 크래시 없이 Bool 을 준다 — 값은 환경에 달려 있으므로 고정하지 않는다.
    func test_상태_조회는_크래시_안_난다() {
        let v = LoginItem.isEnabled
        XCTAssertTrue(v == true || v == false)
    }

    /// 번들 판정은 자기 자신에 대해 정확해야 한다 — 스위치를 켜기 전에
    /// "이게 설정이 아닌데" 를 아는 게 첫 단계다.
    func test_번들_판단은_자기_경로에_대해_정확하다() {
        let ext = Bundle.main.bundleURL.pathExtension
        XCTAssertEqual(LoginItem.isBundled, ext == "app",
                       "번들 판정이 실제 경로와 어긋났다 (ext=\(ext))")
    }

    /// `swift test`/`swift run` 은 .app 이 아니다 — 이 가드가 없으면
    /// 사용자가 "켰는데 왜 안 되지" 를 사유 없이 마주한다.
    func test_테스트_환경은_번들_바깥이다() {
        // SPM 테스트 번들은 .xctest 다. .app 이 아니어야 이 테스트의 전제가 성립한다.
        XCTAssertNotEqual(Bundle.main.bundleURL.pathExtension, "app",
                          ".app 에서 테스트가 돌면 아래 '오염시키지 않는다' 전제가 깨진다")
    }

    /// 실패 사유가 **빈 문자열이 아니다** — 사용자에게 아무 말 없이 실패하면 안 된다.
    /// (실제로 걸린 계약 공백: `.failed("")` 가 빈 메시지를 그대로 흘려보내고 있었다)
    func test_실패_사유가_비어있지_않다() {
        XCTAssertEqual(LoginItem.Result.applied(enabled: true).message, "켜짐")
        XCTAssertEqual(LoginItem.Result.applied(enabled: false).message, "꺼짐",
                       "해제 성공인데 '켜짐' 이라 말하면 안 된다 — 실제로 그 버그가 났었다")
        XCTAssertFalse(LoginItem.Result.failed("").message.isEmpty)
        XCTAssertFalse(LoginItem.Result.failed("   \n ").message.isEmpty)
        XCTAssertFalse(LoginItem.Result.notBundled.message.isEmpty)
    }

    /// 원래 사유가 있으면 그걸 보여준다 — 정규화는 빈 값 교체만 한다.
    func test_사유는_원문_을_보존한다() {
        XCTAssertEqual(LoginItem.Result.failed("서명이 필요합니다").message, "서명이 필요합니다")
    }

    /// UI 는 "이전 값으로 되돌리기" 를 하려면 Result 가 Equatable 이어야 한다.
    func test_Result_는_Equatable_이다() {
        XCTAssertEqual(LoginItem.Result.applied(enabled: true), LoginItem.Result.applied(enabled: true))
        XCTAssertNotEqual(LoginItem.Result.applied(enabled: true), LoginItem.Result.applied(enabled: false))
        XCTAssertNotEqual(LoginItem.Result.applied(enabled: true), LoginItem.Result.notBundled)
        XCTAssertNotEqual(LoginItem.Result.failed("a"), LoginItem.Result.failed("b"))
    }
}
