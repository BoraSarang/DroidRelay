import XCTest
@testable import DroidRelayCore

/// 추가 입력 파싱이 **웹 1786~1806행과 한 글자도 다르지 않은지** 고정한다.
final class AddInputTests: XCTestCase {

    /// 웹 1788행 — **공백과 쉼표로 나누고 `https?://` 만 통과시킨다.**
    func test_여러_URL을_공백과_쉼표로_나눈다() {
        let r = AddInput.urls("http://a.com/x.tgz https://b.com/y.tgz ,https://c.com/z.tgz")
        XCTAssertEqual(r, ["http://a.com/x.tgz", "https://b.com/y.tgz", "https://c.com/z.tgz"])
    }

    /// **줄바꿈**으로 붙여넣어도 돼야 한다 — 웹 플레이스홀더가 "줄바꿈/공백 구분" 이라 명시한다.
    func test_줄바꿈도_구분자다() {
        let r = AddInput.urls("https://a.com/1\nhttps://b.com/2\thttps://c.com/3")
        XCTAssertEqual(r, ["https://a.com/1", "https://b.com/2", "https://c.com/3"])
    }

    /// **`magnet:` 같은 것은 걸러진다** — 웹 `/^https?:\/\//i` 와 같다.
    ///
    /// 이게 없으면 magnet 를 다운로드로 보내고 422 를 받는다.
    /// 서버도 http/https 만 받는다(JobRoutes 47행) → 규칙이 어긋나면 안 된다.
    func test_http가_아니면_버린다() {
        XCTAssertEqual(AddInput.urls("magnet:?xt=urn:btih:abc"), [])
        XCTAssertEqual(AddInput.urls("ftp://x.com/a"), [])
        XCTAssertEqual(AddInput.urls("javascript:alert(1)"), [])
        XCTAssertEqual(AddInput.urls("file:///etc/passwd"), [])
    }

    /// **대소문자는 무관** — 웹 정규식에 `i` 플래그가 있다.
    func test_대소문자_HTTP를_모두_허용한다() {
        XCTAssertEqual(AddInput.urls("HTTP://A.com/1 HtTpS://B.com/2"), ["HTTP://A.com/1", "HtTpS://B.com/2"])
    }

    /// **구분자가 연속으로 있어도 빈 조각이 새 URL 이 아니다**
    func test_연속구분자와_앞뒤_공백() {
        let r = AddInput.urls("  https://a.com/1 ,,  , https://b.com/2  ")
        XCTAssertEqual(r, ["https://a.com/1", "https://b.com/2"])
    }

    /// 빈 입력 → 빈 배열. **"추가" 를 눌러도 아무 요청도 가지 않아야 한다.**
    func test_빈_입력은_빈_배열() {
        XCTAssertEqual(AddInput.urls(""), [])
        XCTAssertEqual(AddInput.urls("   \n\t "), [])
    }

    /// **URL 이 하나도 없으면 서버를 부르면 안 된다** — 웹 1789행 `alert`.
    ///
    /// 클라이언트도 조용히 아무것도 하지 않는다. **버튼을 못 누르게** 하는 게 낫다.
    func test_유효한_URL이_없으면_추가할_것이_없다() {
        let r = AddInput.urls("magnet:?xt=urn:btih:abc\nftp://a/b")
        XCTAssertTrue(r.isEmpty, "추가할 URL 이 없으면 요청도 없어야 한다")
    }

    /// **`、`(일본어 쉼표)는 구분자가 아니다** — 웹이 그걸로 나누지 않는다.
    ///
    /// 이걸 넣으면 앱에서만 여러 개로 쪼개져 **웹과 다른 요청**이 나간다.
    func test_일본어_쉼표는_구분자가_아니다() {
        let raw = "https://a.com/1、https://b.com/2"
        // 웹으로는 URL 하나(첫째만) — 나머지는 붙어 있는 하나의 잘못된 URL
        XCTAssertEqual(AddInput.urls(raw), ["https://a.com/1、https://b.com/2"])
    }

    /// magnet 는 `magnet:` 로 시작해야 한다 — 웹 1805~1806행은 형식을 서버에 맡기지만,
    /// 클라이언트는 **누르는 순간** 알려주는 게 낫다.
    func test_magnet_형식_검증() {
        XCTAssertTrue(AddInput.isMagnet("magnet:?xt=urn:btih:424c6b085d2125678a1508f78b88ff7b7359dd7f"))
        XCTAssertTrue(AddInput.isMagnet("MAGNET:?xt=urn:btih:abc"), "대소문자 무시")
        XCTAssertFalse(AddInput.isMagnet("https://a.com/x.torrent"))
        XCTAssertFalse(AddInput.isMagnet(""))
    }

    /// **입력 순서가 유지된다** — 서버는 받은 순서대로 큐에 넣는다.
    ///
    /// 순서를 **정렬로** 비교하지 않는다. 정렬해 버리면 "순서가 바뀌었는지" 를 못 보기 때문.
    /// (앞선 실패 교훈: `contains("c")` 로 판별하려다 `"https"` 의 `c` 를 걸렀다.
    ///  값이 아니라 **배열 그 자체** 를 그대로 비교해야 한다.)
    func test_순서가_유지된다() {
        let r = AddInput.urls("https://c.example/1 https://a.example/2 https://b.example/3")
        XCTAssertEqual(r, ["https://c.example/1", "https://a.example/2", "https://b.example/3"])
    }
}
