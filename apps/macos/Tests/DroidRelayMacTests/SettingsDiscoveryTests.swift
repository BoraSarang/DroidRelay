import XCTest
@testable import DroidRelayCore

/// **설정 창 "다시 찾기" 결과 표시** (T-1089).
///
/// 사용자 보고: "설정 → 다시 찾기 했을때 찾았을때의 반응이 없네.
/// 설정에서 주소 채워줘야 하고 찾았습니다 또는 실패 했습니다. 등이 있어야 하는데"
///
/// `--diagnose` 실측으로 **탐색은 40ms 에 정상 성공**한다.
/// → 버그가 아니라 **결과를 말하지 않은 것**이 문제였다.
final class SettingsDiscoveryTests: XCTestCase {

    // MARK: - 상태 한 줄

    func test_idle는_아직_안_찾았다고_말한다() {
        let s = SettingsDiscovery.status(.idle, address: nil, version: nil)
        XCTAssertFalse(s.isEmpty, "빈 문자열이 되면 안 된다 — 아무 말도 안 하는 것과 같다")
        XCTAssertTrue(s.contains("찾지 않았"))
    }

    /// 탐색 중에도 **뭘 하고 있는지**를 말한다 — 254개 호스트를 두드리는 동안
    /// 사용자가 "멈췄나" 하고 버튼을 다시 누를 수 있다.
    func test_탐색중은_무엇을_하는지_말한다() {
        let s = SettingsDiscovery.status(.searching, address: nil, version: nil)
        XCTAssertTrue(s.contains("탐색 중"))
    }

    /// ★ **성공해도 어느 폰인지 알 수 없다** — 주소·버전·방법이 함께 있어야 한다.
    func test_성공은_주소_버전_방법을_모두_말한다() {
        let s = SettingsDiscovery.status(.found(.gateway),
                                         address: "10.38.120.211:3000",
                                         version: "0.50.0")
        XCTAssertTrue(s.contains("10.38.120.211:3000"), "주소가 없다 — 내 폰인지 확인할 방법이 없다")
        XCTAssertTrue(s.contains("0.50.0"), "버전이 없다 — 폰 화면과 숫자를 맞춰 볼 수 없다")
        XCTAssertTrue(s.contains("게이트웨이"), "방법이 없다 — 왜 붙었는지 모른다")
    }

    /// 네트워크가 바뀌면 **조용히 다른 폰에 붙을 수 있다.**
    /// 어느 방법이었는지가 드러나야 그것이 보인다.
    func test_저장된_주소로_붙었으면_그렇게_말한다() {
        XCTAssertTrue(SettingsDiscovery.status(.found(.cached),
                                              address: "10.0.0.9:3000", version: "0.50.0")
            .contains("저장된 주소"))
    }

    /// 실패는 **왜인지 → 조치**까지 말해야 한다. "실패" 만으로는 사용자가 못 한다.
    func test_실패는_원인과_조치를_말한다() {
        let s = SettingsDiscovery.status(.failed, address: nil, version: nil)
        XCTAssertTrue(s.contains("실패"))
        XCTAssertTrue(s.contains("확인"), "사용자가 뭘 해야 하는지가 없다")
    }

    /// 주소 없이 "찾았다" 는 **거짓말**이다 — 뭘 확인한지 모른다.
    func test_주소가_없으면_성공이라고_말하지_않는다() {
        XCTAssertFalse(SettingsDiscovery.status(.found(.gateway), address: nil, version: nil)
            .contains("게이트웨이"))
        XCTAssertTrue(SettingsDiscovery.status(.found(.gateway), address: nil, version: nil)
            .contains("실패"))
        XCTAssertTrue(SettingsDiscovery.status(.found(.gateway), address: "", version: nil)
            .contains("실패"))
    }

    // MARK: - 문구를 두 벌 만들지 않는다 (M-27 교훈)

    /// `DiscoveryBadge`(M-22) 와 **같은 규칙을 두 벌 쓰면** 한쪽만 고쳐지고
    /// 테스트는 통과한 채 **화면과 진단이 다른 말을 한다.**
    func test_문구는_팝오버와_동일하다() {
        for strategy in [DiscoveryStrategy.cached, .gateway, .subnetScan, .manual] {
            let badge = DiscoveryBadge.line(strategy: strategy,
                                            address: "10.0.0.9:3000",
                                            version: "0.50.0",
                                            isManual: strategy == .manual)
            let settings = SettingsDiscovery.status(.found(strategy),
                                                   address: "10.0.0.9:3000",
                                                   version: "0.50.0")
            XCTAssertEqual(settings, badge,
                           "\(strategy) — 설정 창과 팝오버가 다른 말을 하면 안 된다")
        }
    }

    func test_실패_문구도_동일하다() {
        XCTAssertEqual(SettingsDiscovery.status(.failed, address: nil, version: nil),
                       DiscoveryBadge.failureLine())
    }

    // MARK: - 주소 칸

    /// **성공하면 칸이 자동으로 채워진다** — 사용자가 요구한 "주소 채워줘야 하고".
    func test_성공하면_주소가_채워진다() {
        XCTAssertEqual(SettingsDiscovery.addressField(current: "", found: "10.38.120.211:3000"),
                       "10.38.120.211:3000")
    }

    /// ★★ **실패하면 사용자가 직접 친 주소가 지워지지 않는다** — 이게 이 함수의 핵심.
    ///
    /// 흔한 구현("탐색이 끝나면 결과로 덮어쓴다")은 실패할 때
    /// 사용자의 유일한 단서를 지워서 **왜 안 되지? 의 이유를 잃게 한다.**
    func test_실패하면_사용자가_친_주소를_지키는다() {
        let 직접입력 = "192.168.0.77:8080"
        XCTAssertEqual(SettingsDiscovery.addressField(current: 직접입력, found: nil), 직접입력)
    }

    /// 탐색 중에도 칸은 **사용자가 편집 중인 값**이다 — 지우면 타이핑이 끊긴다.
    func test_탐색중에도_입력중인_값을_지키는다() {
        XCTAssertEqual(SettingsDiscovery.addressField(current: "10.0.0.", found: nil), "10.0.0.")
    }

    /// 빈 주소는 "찾았다" 가 아니다 — 빈 값으로 덮어쓰면 채워진 칸이 갑자기 비어 보인다.
    func test_빈_주소는_덮어쓰지_않는다() {
        XCTAssertEqual(SettingsDiscovery.addressField(current: "10.0.0.5:3000", found: ""),
                       "10.0.0.5:3000")
    }

    func test_처음_실행에서_아무것도_못_찾으면_빈_칸이다() {
        XCTAssertEqual(SettingsDiscovery.addressField(current: "", found: nil), "")
    }

    /// 재탐색에 성공하면 **새 주소로 갱신**된다 — 옛 값이 남으면 "저장 안 된다" 고 보인다.
    func test_재탐색_성공은_새_주소로_갱신한다() {
        XCTAssertEqual(SettingsDiscovery.addressField(current: "10.0.0.5:3000",
                                                     found: "10.38.120.211:3000"),
                       "10.38.120.211:3000")
    }

    // MARK: - 버튼

    /// 탐색 중에 다시 누르면 **두 번 돈다.** 막아야 한다.
    func test_탐색중에는_버튼이_비활성이다() {
        XCTAssertFalse(SettingsDiscovery.State.searching.isButtonEnabled)
    }

    func test_탐색이_끝나면_버튼이_활성이다() {
        XCTAssertTrue(SettingsDiscovery.State.idle.isButtonEnabled)
        XCTAssertTrue(SettingsDiscovery.State.found(.gateway).isButtonEnabled)
        XCTAssertTrue(SettingsDiscovery.State.failed.isButtonEnabled,
                      "실패한 뒤에는 **다시 누를 수 있어야 한다** — 유일한 다음 행동이다")
    }

    // MARK: - 색

    /// "연결됐다" 고 초록인데 주황이면 **거짓말로 읽힌다** (M-22 교훈).
    func test_찾았을_때는_주황이_아니다() {
        XCTAssertEqual(SettingsDiscovery.Tone(.found(.gateway)), .ok)
    }

    func test_실패만_눈에_띈다() {
        XCTAssertEqual(SettingsDiscovery.Tone(.failed), .bad)
    }

    /// 탐색 중엔 **아직 결과가 없는데 색부터 말하면 거짓말**이다.
    func test_탐색중은_중립이다() {
        XCTAssertEqual(SettingsDiscovery.Tone(.searching), .working)
        XCTAssertNotEqual(SettingsDiscovery.Tone(.searching), SettingsDiscovery.Tone.bad)
        XCTAssertNotEqual(SettingsDiscovery.Tone(.searching), SettingsDiscovery.Tone.ok)
    }

    func test_아무것도_안_했으면_중립이다() {
        XCTAssertEqual(SettingsDiscovery.Tone(.idle), .neutral)
    }

    // MARK: - 붙은 상태인가

    /// **"찾았다" 는 말은 주소가 있어야 성립한다.** 주소·버전을 붙여도 되는 상태인지 가린다.
    func test_주소를_붙일_수_있는_상태는_찾은_것뿐이다() {
        XCTAssertTrue(SettingsDiscovery.State.found(.gateway).isFoundCase)
        XCTAssertTrue(SettingsDiscovery.State.found(.subnetScan).isFoundCase)
        XCTAssertTrue(SettingsDiscovery.State.found(.cached).isFoundCase)
        XCTAssertTrue(SettingsDiscovery.State.found(.manual).isFoundCase)

        XCTAssertFalse(SettingsDiscovery.State.idle.isFoundCase)
        XCTAssertFalse(SettingsDiscovery.State.searching.isFoundCase,
                       "탐색 중인데 붙었다고 말하면 거짓말이다")
        XCTAssertFalse(SettingsDiscovery.State.failed.isFoundCase)
    }

    /// 탐색 중·실패에 주소를 **강제로 붙여도** 문구는 실패로 말해야 한다 —
    /// 주소가 있다고 해서 붙은 것이 아니다.
    func test_붙지_않은_상태에_주소를_강제로_넣어도_성공이라_말하지_않는다() {
        let line = SettingsDiscovery.status(.searching,
                                            address: "10.0.0.9:3000",
                                            version: "0.50.0")
        XCTAssertTrue(line.contains("탐색 중"), "주소가 있어도 아직 붙은 게 아니다: \(line)")
    }
}
