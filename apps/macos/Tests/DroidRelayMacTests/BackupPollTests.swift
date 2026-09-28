import XCTest
@testable import DroidRelayCore

/// **안전망 폴링 주기 규칙** — `BackupPoll`.
///
/// ## 왜 이걸 테스트로 두나
///
/// 설정값이 **`UserDefaults` 를 거쳐 돌아온다.** 화면에서 고를 수 있는 값만
/// 오게 하면 안전하지만, `UserDefaults` 는 손으로 편집할 수 있고 **옛 버전이 남긴
/// 값일 수도 있다.** 그래서 "화면 밖에서 들어온 값"을 막는 경로를 검증한다.
final class BackupPollTests: XCTestCase {

    // MARK: - 클램프

    /// **하한 아래는 하한으로** — 0 은 즉시 0ms 루프가 되어 이벤트 루프를 태운다.
    func test_0초는_하한으로_올린다() {
        XCTAssertEqual(BackupPoll.clamp(0), BackupPoll.minSeconds,
                       "0 초는 CPU 를 태우는 경로다 — 하한으로 밀어 올린다")
    }

    func test_음수는_하한으로_올린다() {
        XCTAssertEqual(BackupPoll.clamp(-999), BackupPoll.minSeconds)
    }

    /// **상한 위는 상한으로** — 2분마다 오는 안전망은 없는 셈이다.
    func test_매우_큰값은_상한으로_내린다() {
        XCTAssertEqual(BackupPoll.clamp(999_999), BackupPoll.maxSeconds)
    }

    /// **범위 안 값은 그대로** — 정상 경로가 보존되어야 한다.
    func test_범위_안_값은_그대로_간다() {
        for s in [5, 10, 15, 30, 60] {
            XCTAssertEqual(BackupPoll.clamp(s), s, "\(s) 초는 그대로여야 한다")
        }
    }

    /// **경계값도 살아있다** — `clamp` 가 경계를 잘라먹지 않는가.
    func test_경계값은_그대로_간다() {
        XCTAssertEqual(BackupPoll.clamp(BackupPoll.minSeconds), BackupPoll.minSeconds)
        XCTAssertEqual(BackupPoll.clamp(BackupPoll.maxSeconds), BackupPoll.maxSeconds)
    }

    /// **클램프는 언제나 허용 구간 안에만 놓는다** — 이게 진짜 목적이므로 한 번 더.
    func test_어떤_입력도_구간을_벗어나지_않는다() {
        for raw in [-100, -1, 0, 1, 4, 5, 7, 10, 59, 60, 61, 1000, Int.max, Int.min] {
            let got = BackupPoll.clamp(raw)
            XCTAssertGreaterThanOrEqual(got, BackupPoll.minSeconds, "raw=\(raw)")
            XCTAssertLessThanOrEqual(got, BackupPoll.maxSeconds, "raw=\(raw)")
        }
    }

    // MARK: - 선택지

    /// **기본값이 선택지 안에 있다** — 고를 수 없는 기본값은 버그다.
    func test_기본값은_선택할_수_있다() {
        XCTAssertTrue(BackupPoll.defaultIsSelectable,
                      "기본값 \(BackupPoll.defaultSeconds) 초가 목록에 없다")
    }

    /// **선택지가 모두 허용 구간 안** — 1초가 목록에 있으면 규칙이 깨진 것이다.
    func test_모든_선택지가_허용_구간_안이다() {
        for c in BackupPoll.choices {
            XCTAssertGreaterThanOrEqual(c, BackupPoll.minSeconds, "\(c) 초가 하한 아래")
            XCTAssertLessThanOrEqual(c, BackupPoll.maxSeconds, "\(c) 초가 상한 위")
        }
    }

    /// **선택지에 1초가 없다** — 안전망을 주 폴링으로 바꾸는 선택지는 없어야 한다.
    func test_선택지에_1초가_없다() {
        XCTAssertFalse(BackupPoll.choices.contains(1),
                       "1 초는 안전망이 아니라 주 폴링이다 — 고를 수 있게 하면 안 된다")
    }

    /// **선택지가 오름차순** — Picker 가 이상하게 나열되지 않게.
    func test_선택지는_오름차순이다() {
        XCTAssertEqual(BackupPoll.choices, BackupPoll.choices.sorted())
    }
}
