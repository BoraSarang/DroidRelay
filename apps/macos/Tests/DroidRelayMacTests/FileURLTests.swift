import XCTest
@testable import DroidRelayCore

/// **`GET /file/{id}` 주소 조립** — 완료 잡의 `📥 받기` 가 여는 주소.
///
/// ## 왜 이게 테스트로 필요한가
///
/// 처음에 뷰에서 이렇게 썼다.
/// ```swift
/// URL(string: base.absoluteString + "file/" + id)
/// ```
/// `base` 는 `http://10.38.120.211:3000` 으로 **끝에 슬래시가 없다.**
/// 이어 붙이면 `http://10.38.120.211:3000file/…` 이 되고,
/// **호스트가 `10.38.120.211:3000file` 이 되어 `URL` 이 `nil` 이 된다.**
///
/// 그리고 `guard … else { return }` 이 조용히 끝내므로 **화면에서는 아무 일도
/// 안 일어난 것처럼 보인다.** 실제로 그랬다.
///
/// **버튼이 눌리는데 아무 파일도 안 오는 버그**는 빌드로도 테스트로도 안 잡힌다.
/// 그래도 주소 조립 자체는 순수 함수라 **여기서 고정한다.**
final class FileURLTests: XCTestCase {

    /// **포트 뒤에 경로가 붙어도 슬래시가 생겨야 한다** — 이게 핵심이다.
    func test_포트가_있어도_잘못된_주소가_안_만들어진다() {
        let base = URL(string: "http://10.38.120.211:3000")!
        let u = RelayClient.fileURL(base: base, id: "abc-123")
        XCTAssertEqual(u?.absoluteString, "http://10.38.120.211:3000/file/abc-123")
    }

    /// **홈 경로로 끝나는 기본 주소** — 슬래시가 이미 있는 경우.
    func test_슬래시가_이미_있어도_중복되지_않는다() {
        let base = URL(string: "http://10.38.120.211:3000/")!
        XCTAssertEqual(RelayClient.fileURL(base: base, id: "x")?.absoluteString,
                       "http://10.38.120.211:3000/file/x")
    }

    /// **`id` 에 특수문자가 있어도 깨지지 않는다** — 그대로 인코딩된다.
    ///
    /// 잡 `id` 는 UUID 라 현실적으로 안 나오지만, **인코딩을 URL 에 맡기면
    /// 빠뜨릴 일도 없다.** 문자열을 직접 이어 붙이는 방법과 결과가 다르다.
    ///
    /// `URL.path` 는 **디코딩된 값**이다(`/file/a b&c`). 인코딩을 보려면
    /// `absoluteString` 을 봐야 한다 — 이것도 헷갈리기 쉬운 자리다.
    func test_특수문자가_들어가도_URL이다() {
        let base = URL(string: "http://10.38.120.211:3000")!
        let u = RelayClient.fileURL(base: base, id: "a b&c")
        XCTAssertEqual(u?.absoluteString, "http://10.38.120.211:3000/file/a%20b&c")
    }

    /// **이게 고장으로 이어졌던 그 방법** — 지금도 `nil` 이 나와야 한다.
    ///
    /// 나중에 이 테스트를 지우면서 같이 되돌리지 않도록 **함정인 걸 고정한다.**
    func test_문자열_이어붙이기는_고장이다() {
        let base = URL(string: "http://10.38.120.211:3000")!
        XCTAssertNil(URL(string: base.absoluteString + "file/abc-123"),
                     "슬래시 없이 이어 붙이면 URL 이 nil 이 된다 — 그래서 fileURL 을 쓴다")
    }
}
