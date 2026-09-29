import XCTest
@testable import DroidRelayCore

/// **"요구한 범위" 와 "서버가 실제로 쓴 범위" 의 불일치** — `scopeNote` 규칙 (M-27).
///
/// ## 이 테스트가 지키는 계약
///
/// **세 경우가 있고, 셋 중 하나만 사용자에게 말한다.**
///
/// | 서버가 쓴 값 | 의미 | 말하는가 |
/// |---|---|---|
/// | 요청과 같음 | 정상 | **아무 말도 하지 않는다** |
/// | `nil` (구버전) | 서버가 이 필드를 모름 | **"전체로 셉니다"** |
/// | 요청과 다름 | **설정이 안 먹혔다** | **둘 다 말해 준다** |
///
/// ## 왜 "아무 말도 하지 않는다" 를 계약으로 두는가
///
/// 이 프로젝트의 규칙: **문제가 생겼을 때만 눈에 띄게 남긴다.**
/// 평범한 상태에 경고를 붙이면 **사용자가 경고를 무시하게 된다.**
final class DeviceScopeNoteTests: XCTestCase {

    // MARK: - 정상

    /// **정상 상태에서는 `nil`** — 화면에 아무것도 붙지 않는다.
    func test_일치하면_아무_말도_하지_않는다() {
        XCTAssertNil(note(requested: .external, used: .external))
        XCTAssertNil(note(requested: .all, used: .all))
    }

    // MARK: - 구버전 서버

    /// **`nil` = 서버가 이 필드를 모른다** — "전체로 셉니다" 고 말해야 한다.
    ///
    /// **"외부만" 으로 설정했는데 "전체" 라고 가정하면 안 된다** —
    /// 사용자는 "설정이 안 먹혔다" 고 생각하고 앱을 의심한다.
    /// 실제로는 **서버가 예전 버전**인 거다. 둘은 다른 문제다.
    func test_구버전_서버는_전체로_셉니다라고_말한다() {
        let n = note(requested: .external, used: nil)
        let s = n ?? ""
        XCTAssertNotNil(n, "서버가 범위를 모르면 조용히 넘어가지 않는다")
        XCTAssertTrue(s.contains("전체"), "무엇으로 계산되고 있는지 말해야 한다: \(s)")
        XCTAssertTrue(s.contains("지원하지 않"), "왜 그런지(서버 문제)도 말해야 한다: \(s)")
    }

    // MARK: - 불일치

    /// **서버가 다른 걸로 골랐다면 둘 다 말한다** — 무엇을 골랐는지 + 무엇을 원했는지.
    ///
    /// **한쪽만 말하면 안 된다.** "서버는 '전체' 로 셉니다" 만으로는
    /// "내가 '외부만' 골랐는데 왜 전체로 나왔지" 에 답하지 못한다.
    func test_불일치하면_양쪽_모두_말한다() {
        let s = note(requested: .external, used: .all) ?? ""
        XCTAssertTrue(s.contains("외부만"), "원한 범위가 있어야 한다: \(s)")
        XCTAssertTrue(s.contains("전체"), "실제로 쓰인 범위가 있어야 한다: \(s)")
    }

    func test_반대_방향_불일치도_같다() {
        let s = note(requested: .all, used: .external) ?? ""
        XCTAssertTrue(s.contains("전체"), "원한 범위: \(s)")
        XCTAssertTrue(s.contains("외부만"), "실제 범위: \(s)")
    }

    /// **모두 세 경우가 구분된다** — 이게 요약.
    func test_세_경우가_구분된다() {
        XCTAssertNil(note(requested: .external, used: .external))          // 정상
        XCTAssertNotNil(note(requested: .external, used: nil))            // 구버전
        XCTAssertNotNil(note(requested: .external, used: .all))           // 불일치
        // **구버전 설명과 불일치 설명은 달라야 한다** — 다른 문제다.
        XCTAssertNotEqual(note(requested: .external, used: nil),
                          note(requested: .external, used: .all))
    }

    // MARK: - 헬퍼

    /// **여기서 규칙을 다시 적지 않는다.**
    ///
    /// ## 처음엔 여기서 문장 세 개를 복사해서 돌렸다
    ///
    /// `AppModel.scopeNote` 가 `@MainActor` 객체 안에 있어서 테스트하기 어려워
    /// **같은 문자열을 이 테스트에 다시 적었다.** 그런데 규칙은 **Core 의 순수 함수**
    /// 로 옮길 수 있었다 — `AppModel` 의 상태에 아무것도 의존하지 않으니까.
    ///
    /// → **규칙은 `TrafficScope.mismatchNote` 한 곳**이고, 여기서는 **그 함수를 부른다.**
    ///
    /// ## 복사본이 위험한 이유 — 실제로 그렇게 될 뻔했다
    ///
    /// 규칙이 두 벌이면 **한쪽만 고쳐지고 테스트는 통과한 채로 화면이 틀어진다.**
    /// "테스트가 통과한다" 는 사실이 **구현이 맞다** 는 증거가 아니게 된다.
    /// → **테스트는 규칙의 복사본이 아니라 규칙 자신을 검증해야 한다.**
    private func note(requested: TrafficScope, used: TrafficScope?) -> String? {
        TrafficScope.mismatchNote(requested: requested, used: used)
    }
}
