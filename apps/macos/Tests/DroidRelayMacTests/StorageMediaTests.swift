import XCTest
@testable import DroidRelayCore

/// **보관함 동영상 ▶ / 받기 주소 조립** — `GET /stream/{경로}` · `GET /dl-file/{경로}`.
///
/// ## 왜 이게 테스트로 필요한가
///
/// `fileURL` 과 같은 함정이다. 실측으로 확인한 두 가지:
///
/// 1. **슬래시가 없으면 `URL` 이 `nil` 이 된다.** `base` 가
///    `http://10.38.120.211:3000` 으로 끝에 슬래시가 없다.
/// 2. **하위 폴더 경로는 하위 폴더 대소문자를 구분한다.** 경로 전체를 한 덩어리로
///    인코딩하면 `M/a.mp4` 가 `M%2Fa.mp4` 가 되고 서버는 404 를 준다(실측).
///    → **슬래시는 구분자로 남기고 조각마다 인코딩해야 한다.**
///
/// 조용히 실패하면 **▶ 를 눌렀는데 아무 일도 안 일어나는** 상태가 된다.
/// 순수 함수이므로 여기서 고정한다.
final class StorageMediaURLTests: XCTestCase {

    private let base = URL(string: "http://10.38.120.211:3000")!

    /// **포트가 있어도 슬래시가 붙어야 한다** — 핵심 회귀 방지.
    func test_포트가_있어도_잘못된_주소가_안_만들어진다() {
        XCTAssertEqual(RelayClient.streamURL(base: base, path: "a.mp4")?.absoluteString,
                       "http://10.38.120.211:3000/stream/a.mp4")
        XCTAssertEqual(RelayClient.downloadURL(base: base, path: "a.mp4")?.absoluteString,
                       "http://10.38.120.211:3000/dl-file/a.mp4")
    }

    /// **하위 폴더는 슬래시를 남겨야 한다** — `%2F` 로 접으면 서버가 404 를 준다(실측).
    func test_하위폴더_슬래시가_구분자로_남는다() {
        let u = RelayClient.streamURL(base: base, path: "M/4k688.com@T38-072.mp4")
        XCTAssertEqual(u?.absoluteString,
                       "http://10.38.120.211:3000/stream/M/4k688.com@T38-072.mp4")
        XCTAssertFalse(u?.absoluteString.contains("%2F") ?? true,
                       "슬래시를 인코딩하면 서버가 하위 폴더를 못 읽는다")
    }

    /// **2단계 + 중국어·괄호·`+` 가 든 실제 파일명** — 실측으로 200 이었던 그 이름.
    func test_중국어_괄호_플러스_파일명() {
        let p = "M/T38-072/18+游戏大全(996gg.cc)-七龍珠H版-三國志H版-三國群淫傳等.mp4"
        let u = RelayClient.streamURL(base: base, path: p)
        let s = u?.absoluteString ?? ""
        XCTAssertTrue(s.hasPrefix("http://10.38.120.211:3000/stream/M/T38-072/18+"),
                      "앞부분이 그대로여야 한다 — 실제: \(s)")
        XCTAssertTrue(s.hasSuffix(".mp4"), "확장자가 보존돼야 한다 — 실제: \(s)")
        XCTAssertNotNil(u, "중국어 파일명이어도 URL 이 만들어져야 한다")
    }

    /// **공백·`&`·`#` 는 인코딩돼야 한다** — 그대로 두면 주소가 잘린다.
    func test_주소에서_잘리는_문자는_인코딩된다() {
        let u = RelayClient.streamURL(base: base, path: "a b#c&d.mp4")
        let s = u?.absoluteString ?? ""
        XCTAssertFalse(s.contains("#"), "프래그먼트로 잘리면 파일이 안 나온다 — 실제: \(s)")
        XCTAssertFalse(s.contains(" "), "공백이 남아 있으면 깨진 URL 이 된다 — 실제: \(s)")
    }

    /// **앞뒤에 슬래시가 붙어 있어도 중복되지 않는다.**
    func test_경로_앞뒤_슬래시() {
        XCTAssertEqual(RelayClient.streamURL(base: base, path: "/M/a.mp4/")?.absoluteString,
                       "http://10.38.120.211:3000/stream/M/a.mp4")
    }

    /// **이게 고장으로 이어졌던 그 방법** — 지금도 `nil` 이 나와야 한다.
    func test_문자열_이어붙이기는_고장이다() {
        XCTAssertNil(URL(string: base.absoluteString + "stream/a.mp4"),
                     "슬래시 없이 이어 붙이면 URL 이 nil 이 된다 — 그래서 streamURL 을 쓴다")
    }
}

/// **▶ 를 띄워도 되는 확장자 판정** — `ActionRules.isBrowserPlayableVideo`.
///
/// ## 왜 이게 중요한가
///
/// `.mkv` 에 ▶ 를 띄우면 사용자가 눌렀을 때 브라우저가 검은 화면을 낸다.
/// **"누를 수 있는데 안 되는 버튼"** 이다. 그래서 판정을 Core 에 고정한다.
final class BrowserPlayableRuleTests: XCTestCase {

    /// **실제 보관함에 있는 것들** — 전부 mp4 라 ▶ 가 떠야 한다.
    func test_보관함의_mp4는_재생된다() {
        XCTAssertTrue(ActionRules.isBrowserPlayableVideo("4k688.com@T38-072.mp4"))
        XCTAssertTrue(ActionRules.isBrowserPlayableVideo("예편.m4v"))
        XCTAssertTrue(ActionRules.isBrowserPlayableVideo("홈영상.MOV"),
                      "대문자 확장자도 같아야 한다")
    }

    /// **브라우저가 못 재생하는 형식에는 ▶ 가 없어야 한다.**
    func test_브라우저가_못_재생하는_형식은_제외된다() {
        for name in ["영화.mkv", "옛날영상.avi", "방송.ts", "화면.flv", "dmg파일.dmg"] {
            XCTAssertFalse(ActionRules.isBrowserPlayableVideo(name),
                           "\(name) 은 브라우저가 못 재생한다 — ▶ 를 띄우면 안 된다")
        }
    }

    /// **`ogv` 는 Chrome 이 되지만 Safari 가 안 된다** — 이 Mac 의 기본은 Safari 다.
    ///
    /// 브라우저가 "하나라도 되면" 으로 고르면 사용자의 기본 브라우저에서 깨진다.
    /// **모두 되는 것만** 넣는 이유다.
    func test_safari가_못_하는_형식은_제외된다() {
        XCTAssertFalse(ActionRules.isBrowserPlayableVideo("영상.ogv"))
    }

    /// **폴더 이름에 점이 있어도 확장자로 오해하지 않는다.**
    ///
    /// `꾹.새 폴더` 처럼 이름에만 점이 있는 **폴더** 는 `pathExtension` 이 빈 문자열이라
    /// 자동으로 걸러진다. 그래도 디렉토리는 애초에 판정을 부르지 않는다.
    func test_확장자가_없으면_재생이_아니다() {
        XCTAssertFalse(ActionRules.isBrowserPlayableVideo("M"))
        XCTAssertFalse(ActionRules.isBrowserPlayableVideo("제목.없는.확장자"))
    }

    /// **받기가 playback 판정과 무관하게 항상 있다** — 이게 이번 단계에서 고친 실제 결함이다.
    ///
    /// ## 무엇이 잘못됐나
    ///
    /// 처음엔 재생 가능한 파일에 `▶` 만 뒀다. 그래서 이런 상태가 됐다:
    /// ```
    /// 4k688.com@T38-072.mp4   →  ▶ 만      ← 스트리밍은 되는데 못 받는다
    /// 영화.mkv              →  받기       ← 재생은 못 하는데 받는다
    /// ```
    /// **재생 못하는 파일만 받을 수 있다** 는 모순이다. 사용자가 그대로 물었다.
    ///
    /// → **받기는 모든 파일에 있다.** 판단 대상이 아니다.
    func test_받기는_재생_판정과_무관하다() {
        // 재생 가능 → ▶ + 받기 (둘 다)
        let mp4 = "4k688.com@T38-072.mp4"
        XCTAssertTrue(ActionRules.isBrowserPlayableVideo(mp4), "mp4 는 재생된다")
        // 재생 불가 → 받기만
        let mkv = "영화.mkv"
        XCTAssertFalse(ActionRules.isBrowserPlayableVideo(mkv), "mkv 는 재생 안 된다")

        // 두 경우 모두 **경로는 만든다** — 판정이 어쨌든 받는 길은 있어야 한다.
        let base = URL(string: "http://10.38.120.211:3000")!
        for p in [mp4, mkv] {
            XCTAssertNotNil(RelayClient.downloadURL(base: base, path: p),
                            "\(p) 는 받아야 한다 — 재생과 무관하다")
        }
    }
}
