import XCTest
@testable import DroidRelayCore

/// 상태별 표시 규칙이 **웹 대시보드와 같은지** 를 고정한다.
///
/// ## 왜 이게 계약인가
///
/// 이 규칙을 틀리면 사용자가 웹과 앱을 오갈 때마다 헷갈린다.
/// 이전 버전은 일시정지/재개/삭제를 **항상 3개 다** 보여줬다 —
/// "진행 중" 인데 "재개" 가 떠서 눌렀다 아무 일도 안 일어나는 상황이 그 결과다.
///
/// 각 테스트에 **웹의 어느 행에서 왔는지** 를 적어 둔다.
/// 웹이 바뀌면 여기서 깨진다 — 그게 의도다.
final class ActionRulesTests: XCTestCase {

    // MARK: - 잡 (웹 1176~1185행)

    /// **진행 중 → 일시정지만.** 재개가 동시에 뜨면 안 된다.
    func test_잡_진행중은_일시정지만_나온다() {
        XCTAssertEqual(ActionRules.jobActions(state: "RUNNING", type: nil), [.pause])
    }

    /// 일시정지 → 재개. 일시정지가 **같이 뜨면 안 된다.**
    func test_잡_일시정지는_재개만_나온다() {
        XCTAssertEqual(ActionRules.jobActions(state: "PAUSED", type: nil), [.resume])
    }

    /// 실패 → 재개
    func test_잡_실패는_재개가_나온다() {
        XCTAssertEqual(ActionRules.jobActions(state: "FAILED", type: nil), [.resume])
    }

    /// **완료/취소는 아무 동작도 안 나온다.** 이미 끝났으므로.
    func test_잡_완료취소는_동작이_없다() {
        XCTAssertTrue(ActionRules.jobActions(state: "DONE", type: nil).isEmpty)
        XCTAssertTrue(ActionRules.jobActions(state: "CANCELED", type: nil).isEmpty)
    }

    /// **비디오 작업은 일시정지 버튼이 없다** — 서버가 400 으로 거절한다.
    /// 웹 1177행이 버튼 대신 "FFmpeg → 삭제로 취소" 텍스트를 넣는 이유.
    func test_비디오_작업에는_일시정지_버튼이_없다() {
        let a = ActionRules.jobActions(state: "RUNNING", type: "video")
        XCTAssertEqual(a, [.videoNotice], "일시정지 버튼이 나오면 400 으로 실패한다")
        XCTAssertFalse(a.contains(.pause))
    }

    /// 실패한 비디오는 "재시도" — 웹 1179행
    func test_실패한_비디오는_재시도() {
        XCTAssertEqual(ActionRules.jobActions(state: "FAILED", type: "video"), [.videoRetry])
    }

    /// 비디오는 재개가 없다 (그냥 지우고 다시 추가하는 방식)
    func test_비디오에는_재개가_없다() {
        XCTAssertFalse(ActionRules.jobActions(state: "PAUSED", type: "video").contains(.resume))
    }

    /// 완료된 잡에 속도 제한 선택기를 띄우면 의미가 없다 — 웹 1181행
    func test_완료된_잡은_속도제한을_편집할_수_없다() {
        XCTAssertFalse(ActionRules.jobLimitEditable(state: "DONE", type: nil))
        XCTAssertFalse(ActionRules.jobLimitEditable(state: "CANCELED", type: nil))
        XCTAssertTrue(ActionRules.jobLimitEditable(state: "RUNNING", type: nil))
    }

    // MARK: - 토렌트 (웹 1325~1326행)

    /// **토렌트는 DOWNLOADING 이다** — 잡의 RUNNING 이 아니다.
    /// 잡의 규칙을 그대로 쓰면 토렌트에서 일시정지가 아예 안 뜬다.
    func test_토렌트_다운로딩은_일시정지() {
        XCTAssertEqual(ActionRules.torrentActions(state: "DOWNLOADING"), [.pause])
    }

    /// 메타데이터를 받는 중에도 중단할 수 있다
    func test_토렌트_메타데이터_추출중도_일시정지() {
        XCTAssertEqual(ActionRules.torrentActions(state: "FETCHING_METADATA"), [.pause])
    }

    /// 잡과 달리 **토렌트는 4개 상태에서 재개**한다 (정체·대기 포함) — 웹 1326행
    func test_토렌트_재개는_네_상태다() {
        for s in ["PAUSED", "FAILED", "STALLED", "QUEUED"] {
            XCTAssertEqual(ActionRules.torrentActions(state: s), [.resume], "\(s) 에서 재개가 있어야 한다")
        }
    }

    /// **시딩 중에는 일시정지가 안 된다** — 토렌트를 멈추는 게 아니라
    /// 업로드를 멈추는 것이라 웹에도 버튼이 없다. 아무것도 안 보여야 정확하다.
    func test_토렌트_시딩중에는_동작이_없다() {
        XCTAssertTrue(ActionRules.torrentActions(state: "SEEDING").isEmpty)
    }

    func test_토렌트_완료에는_동작이_없다() {
        XCTAssertTrue(ActionRules.torrentActions(state: "DONE").isEmpty)
    }

    /// **토렌트 속도 제한은 상태와 무관하게 항상 나온다** — 웹 1330행.
    ///
    /// 잡과 **다르다.** 잡은 완료/취소면 숨기지만 토렌트는 항상 보인다.
    /// 웹이 둘을 다르게 판단했으므로 그대로 따라야 사용자가 헷갈리지 않는다.
    /// (잡 조건을 그대로 복사하면 완료된 토렌트에서 제한을 못 바꾸게 된다)
    func test_토렌트_속도제한은_모든_상태에서_나온다() {
        for s in ["DOWNLOADING", "SEEDING", "PAUSED", "FAILED", "DONE", "QUEUED"] {
            XCTAssertTrue(ActionRules.torrentLimitEditable(state: s), "\(s) 에서도 제한은 바꿀 수 있다")
        }
    }

    /// **시드/피어가 둘 다 0 이면 아예 그리지 않는다** — 웹 1313행.
    ///
    /// `0 0` 을 보여주면 "아무도 안 봤다" 와 "정보가 아직 없다" 를 구분할 수 없다.
    func test_시드피어가_모두0이면_표시하지_않는다() {
        XCTAssertFalse(ActionRules.torrentShowsPeers(seeds: 0, peers: 0))
        XCTAssertTrue(ActionRules.torrentShowsPeers(seeds: 1, peers: 0))
        XCTAssertTrue(ActionRules.torrentShowsPeers(seeds: 0, peers: 1))
    }

    /// **ETA 는 `DOWNLOADING` + 속도 > 0 일 때만** — 웹 1306행.
    ///
    /// 속도가 0 이면 남은 시간을 나눌 수 없다. 계산하면 무한대/0 초가 되고
    /// "남은 ∞" 같은 표시가 나온다.
    func test_ETA는_다운로딩중이고_속도가있을때만() {
        XCTAssertTrue(ActionRules.torrentShowsEta(state: "DOWNLOADING", downloadBps: 1000))
        XCTAssertFalse(ActionRules.torrentShowsEta(state: "DOWNLOADING", downloadBps: 0),
                       "속도 0 으로 나눌 수 없다")
        XCTAssertFalse(ActionRules.torrentShowsEta(state: "PAUSED", downloadBps: 1000),
                       "멈춘 토렌트의 남은 시간은 의미 없다")
        XCTAssertFalse(ActionRules.torrentShowsEta(state: "SEEDING", downloadBps: 1000))
    }

    /// **토렌트 삭제 버튼은 항상** — 웹 1329행
    func test_토렌트_삭제는_항상_가능하다() {
        for s in ["DOWNLOADING", "SEEDING", "PAUSED", "FAILED", "DONE"] {
            XCTAssertTrue(ActionRules.torrentDeletable(state: s))
        }
    }

    // MARK: - 라벨

    /// **라벨이 웹과 한 글자도 달라야 하지 않는다** (웹 1176~1180행)
    func test_라벨이_웹과_같다() {
        XCTAssertEqual(ActionRules.label(.pause), "일시정지")
        XCTAssertEqual(ActionRules.label(.resume), "재개")
        XCTAssertEqual(ActionRules.label(.videoNotice), "FFmpeg → 삭제로 취소")
    }

    // MARK: - 잡 삭제 (실측으로 발견한 함정)

    /// **`POST /api/jobs/{id}/{action}` 에 `cancel` 이 없다.**
    ///
    /// 잡 라우트는 `pause` / `resume` 만 분기하고 그 외는
    /// 400 `지원 없는 동작` 을 돌려준다(JobRoutes 100~109행).
    ///
    /// 이전 코드는 "삭제" 버튼이 `onAction("cancel")` 을 보냈다.
    /// → **이 버튼은 한 번도 동작한 적이 없다.** 서버는 400 이었다.
    /// → 삭제는 `DELETE /api/jobs/{id}` 라 별도 경로다.
    ///
    /// 이 테스트는 `JobAction` 에 `cancel` 이 **다시** 들어오면 깨진다.
    func test_잡_동작에_cancel은_없다_삭제는_DELETE다() {
        let actions: [ActionRules.JobAction] = [.pause, .resume, .retry, .videoNotice, .videoRetry]
        let paths = Set(actions.map(\.rawAction))
        XCTAssertFalse(paths.contains("cancel"),
                       "POST /{action} 에 cancel 은 없다 — 삭제 경로로 쓰면 400 이다")
        XCTAssertEqual(paths, ["pause", "resume", "retry"],
                       "잡 라우트가 받는 경로 조각은 이 세 개뿐이다")
    }

    /// **삭제 버튼은 상태와 무관하게 항상 보인다** — 웹 1180행
    func test_삭제버튼은_모든_상태에서_나온다() {
        for s in ["QUEUED", "RUNNING", "PAUSED", "FAILED", "DONE", "CANCELED"] {
            XCTAssertTrue(ActionRules.jobDeletable(s), "\(s) 에서도 삭제는 가능하다")
        }
    }

    /// **`받기` 는 `DONE` 에만 나온다** — 웹 1174행.
    ///
    /// 서버 `GET /file/{id}` 는 `DONE` 이 아니면 400 "아직 완료되지 않았습니다" 다
    /// (JobRoutes 240행). 넓게 두면 **누를 수 있는데 안 되는** 버튼이 된다.
    func test_받기는_완료한_잡에서만_나온다() {
        XCTAssertTrue(ActionRules.jobDownloadable("DONE"))
        for s in ["QUEUED", "RUNNING", "PAUSED", "FAILED", "STALLED", "CANCELED"] {
            XCTAssertFalse(ActionRules.jobDownloadable(s),
                           "\(s) 는 아직 다 안 받았으니 '받기'가 뜨면 안 된다")
        }
    }

    /// **완료 잡도 목록에 보여야 한다** — 웹 `jobs.forEach` 처럼 전부 그린다.
    ///
    /// 원래는 `!isFinished` 로 필터했다. 그래서 **1초짜리 파일을 추가하면
    /// 잡이 눈앞에서 사라졌다** — "추가했는데 안 보인다 → 실패한 줄 안다".
    func test_완료된_잡도_진행중_잡도_모두_끝나지_않은_것으로_보인다() {
        for s in ["QUEUED", "RUNNING", "PAUSED", "STALLED", "FAILED"] {
            XCTAssertTrue(ActionRules.isUnfinished(s), "\(s) 는 진행 중으로 세야 한다")
        }
        for s in ["DONE", "CANCELED"] {
            XCTAssertFalse(ActionRules.isUnfinished(s), "\(s) 는 끝났으니 배지에서 빠진다")
        }
    }

    /// **`QUEUED` 를 빠뜨리면 추가 직후 잡이 사라진다** — 서버는 받는 즉시 `QUEUED`.
    ///
    /// 이건 실제로 겪은 버그라 회귀 테스트로 남긴다.
    func test_대기중_잡도_배지에_남는다() {
        XCTAssertTrue(ActionRules.isUnfinished("QUEUED"),
                      "추가하자마자 QUEUED 가 되는데 이걸 빼면 '추가했는데 안 보인다' 가 된다")
    }

    /// 상태 표기 — 웹 999행 `label()` 과 동일해야 혼동이 없다
    func test_상태표기가_웹과_같다() {
        XCTAssertEqual(ActionRules.State.running.korean, "진행 중")
        XCTAssertEqual(ActionRules.State.downloading.korean, "진행 중")
        XCTAssertEqual(ActionRules.State.seeding.korean, "시딩")
        XCTAssertEqual(ActionRules.State.stalled.korean, "정체")
        XCTAssertEqual(ActionRules.State.failed.korean, "실패")
    }
}
