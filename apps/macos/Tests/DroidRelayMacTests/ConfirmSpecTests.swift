import XCTest
@testable import DroidRelayCore

/// **삭제 컨펌 문구를 고정한다.**
///
/// ## 왜 문구까지 테스트하는가
///
/// 컨펌은 **사람이 실수하지 않기 위한 장치**다. 문구가 틀리면:
///
/// - "되돌릴 수 있습니다" 인데 실제로는 파일을 지운다 → **사용자 데이터 손실**
/// - "파일이 남습니다" 인데 실제로는 지운다 → **오히려 더 위험** (확심하고 누른다)
///
/// 문구와 서버 동작이 어긋나는 걸 **빌드 때** 잡아야 한다.
final class ConfirmSpecTests: XCTestCase {

    // MARK: - 잡

    /// **잡 삭제는 완전 삭제다** — 서버가 `partialFile()`·`doneFile()` 을 둘 다 지운다.
    /// 진행 중이어도, 완료여도 같다. → 반드시 "되돌릴 수 없다" 고 말해야 한다.
    func test_잡_삭제는_되돌릴_수_없다고_말한다() {
        let j = makeJob(state: "DOWNLOADING", name: "영화.mkv")
        let s = ConfirmSpec.job(j)
        XCTAssertTrue(s.isDestructive, "잡 삭제는 파괴 동작이어야 한다")
        XCTAssertTrue(s.body.contains("되돌릴 수 없습니다"),
                      "잡 삭제 본문에 복구 불가 문구가 없다: \(s.body)")
    }

    /// **완료된 잡도 마찬가지** — `doneFile()` 이 지우므로 파일이 사라진다.
    func test_완료된_잡도_파괴_동작이다() {
        let j = makeJob(state: "DONE", name: "완료.zip")
        let s = ConfirmSpec.job(j)
        XCTAssertTrue(s.isDestructive)
        XCTAssertTrue(s.body.contains("삭제"))
    }

    /// **이름이 화면에 보인다** — 무엇을 지우는지 확인 안 하고 지르면 안 된다.
    func test_잡_이름이_본문에_들어간다() {
        let j = makeJob(state: "RUNNING", name: "여름 vacation.mp4")
        XCTAssertTrue(ConfirmSpec.job(j).body.contains("여름 vacation.mp4"))
    }

    /// **이름이 비면 화면이 비어 보이지 않게** — 빈 「」 만 남으면 뭐 지우는지 모른다.
    func test_이름이_비면_그대로_보인다() {
        let j = makeJob(state: "RUNNING", name: "   ")
        let s = ConfirmSpec.job(j)
        XCTAssertTrue(s.body.contains("(이름 없음)"), "빈 이름 대체를 넣지 않았다: \(s.body)")
    }

    // MARK: - 토렌트

    /// **완료 토렌트 삭제 = 목록에서만.** 서버 `if (isComplete)` 분기(830행).
    ///
    /// 이때 "파일이 남습니다" 고 말해야 한다. 반대로 말하면 사용자가
    /// 확신하고 삭제해 **완성 파일을 잃는다.**
    func test_완료_토렌트는_파일이_남는다고_말한다() {
        let t = makeTorrent(state: "DONE", seeding: false, name: "T38-072")
        let s = ConfirmSpec.torrent(t)
        XCTAssertFalse(s.isDestructive, "완료 토렌트 삭제는 되돌릴 수 있다")
        XCTAssertTrue(s.body.contains("그대로 남습니다"),
                      "완료 토렌트에 파일 보존 안내가 없다: \(s.body)")
    }

    /// **시딩 중도 완료로 본다** — 서버는 `DONE || SEEDING` 으로 판정한다(811행).
    func test_시딩_중도_완료와_같게_본다() {
        let t = makeTorrent(state: "SEEDING", seeding: true, name: "시드중")
        XCTAssertFalse(ConfirmSpec.torrent(t).isDestructive,
                       "SEEDING 은 서버도 완료로 친다(complete=true)")
    }

    /// **미완료 토렌트 삭제 = 받은 조각도 사라진다.**
    /// 서버가 `saveDir/<이름>` 을 `deleteRecursively()`(834행).
    /// → 반드시 "되돌릴 수 없습니다" 고 말해야 한다.
    func test_미완료_토렌트는_조각도_사라진다고_말한다() {
        let t = makeTorrent(state: "DOWNLOADING", seeding: false, name: "MOOC-016")
        let s = ConfirmSpec.torrent(t)
        XCTAssertTrue(s.isDestructive, "미완료 토렌트 삭제는 파괴 동작이어야 한다")
        XCTAssertTrue(s.body.contains("조각도 삭제"))
        XCTAssertTrue(s.body.contains("되돌릴 수 없습니다"))
    }

    /// **대기/메타데이터 추출 중도 미완료다** — 받을 게 없으므로 조각 삭제는 맞지만
    /// 그래도 "파괴" 로 표시해야 사용자가 신중하다.
    func test_대기_중도_파괴로_표시한다() {
        for st in ["QUEUED", "METADATA", "FETCHING_METADATA", "PAUSED"] {
            let t = makeTorrent(state: st, seeding: false, name: "대기중")
            XCTAssertTrue(ConfirmSpec.torrent(t).isDestructive,
                          "\(st) 는 미완료라 파괴 동작이어야 한다")
        }
    }

    /// **토렌트 문구에는 "목록에서만" 이 없다** — 미완료면 목록만 지우는 게 아니다.
    /// 이 문구가 잘못 들어가면 사용자가 파일까지 지워질 걸 모른다.
    func test_미완료_토렌트에_목록에서만_이라는_문구가_없다() {
        let t = makeTorrent(state: "DOWNLOADING", seeding: false, name: "x")
        XCTAssertFalse(ConfirmSpec.torrent(t).body.contains("목록에서만"),
                       "미완료 삭제인데 '목록에서만' 이라고 하면 안 된다")
    }

    // MARK: - 보관함

    /// **보관함 삭제는 휴지통 이동이다** — `trash/restore` 로 되돌린다.
    /// → 파괴 동작이 아니다. 빨간 버튼으로 두면 오히려 경계심을 부른다.
    func test_보관함은_휴지통_이동이고_파괴가_아니다() {
        let e = makeEntry(name: "예편", isDir: true, dir: "")
        let s = ConfirmSpec.storage(e)
        XCTAssertFalse(s.isDestructive, "휴지통 이동은 되돌릴 수 있다")
        XCTAssertTrue(s.body.contains("되돌릴 수 있습니다"))
    }

    /// **휴지통 → 완전 삭제 = 되돌릴 수 없다.** 유일하게 남은 파괴 동작.
    func test_영구_삭제는_되돌릴_수_없다고_말한다() {
        let s = ConfirmSpec.purge("T38-072")
        XCTAssertTrue(s.isDestructive)
        XCTAssertTrue(s.body.contains("되돌릴 수 없게"))
        XCTAssertTrue(s.body.contains("휴지통에도 남지 않습니다"),
                      "휴지통에도 안 남는다고 밝혀야 한다: \(s.body)")
    }

    /// **복원은 되돌릴 수 있는 동작이지만, 위치가 보장되지 않는다.**
    ///
    /// 서버는 휴지통에 원래 위치를 저장하지 않는다(StorageRoutes 252~262행).
    /// 복원하면 **보관함 루트**로 돌아가고, 충돌하면 `이름-2` 가 붙는다.
    /// → "원래 자리로 돌아간다" 고 말하면 **거짓말**이 된다.
    func test_복원은_위치가_바뀔_수_있다고_말한다() {
        let s = ConfirmSpec.restore("T38-072")
        XCTAssertFalse(s.isDestructive, "복원은 되돌릴 수 있다")
        XCTAssertTrue(s.body.contains("맨 위로"),
                      "복원 위치가 바뀔 수 있음을 밝혀야 한다: \(s.body)")
        XCTAssertFalse(s.body.contains("원래 위치로"),
                       "'원래 위치로' 라고 말하면 실제로 다른 곳에 놓인다 — 거짓말")
    }

    /// **이동은 "되돌릴 수 없다"고 말하면 안 된다** —
    /// 되돌릴 **방법**은 있다(다시 이동하면 된다). "불가능"과 "수고스럽다"는 다르다.
    func test_이동은_되돌릴_수_있다고_말한다() {
        let e = makeEntry(name: "예편", isDir: true, dir: "M")
        let s = ConfirmSpec.move(e)
        XCTAssertFalse(s.isDestructive)
        XCTAssertTrue(s.body.contains("다시 옮겨야"))
        XCTAssertFalse(s.body.contains("되돌릴 수 없습니다"))
    }

    /// **이름변경은 파일이 그대로** — 그래야 안 겁나게 조작할 수 있다.
    func test_이름변경은_파일이_그대로라고_말한다() {
        let e = makeEntry(name: "a.mkv", isDir: false, dir: "M")
        XCTAssertTrue(ConfirmSpec.rename(e).body.contains("그대로"))
    }

    // MARK: - 공통

    /// **제목은 짧고 분명해야 한다** — 컨펌의 첫 줄에 무엇인지 나와야 한다.
    func test_제목이_종류를_말해준다() {
        let j = makeJob(state: "RUNNING", name: "a")
        let t = makeTorrent(state: "DOWNLOADING", seeding: false, name: "b")
        XCTAssertTrue(ConfirmSpec.job(j).title.contains("다운로드"))
        XCTAssertTrue(ConfirmSpec.torrent(t).title.contains("토렌트"))
        XCTAssertTrue(ConfirmSpec.purge("c").title.contains("영구"))
    }

    /// **이름에 「」 가 들어 있어도 문구가 깨지지 않아야 한다** —
    /// 파일명에 괄호가 있는 건 흔하다(예: `[구독] film.mkv`).
    /// 접두/접미 검사만으로는 검출 못 하지만 최소한 **빈 화면이 되진 않아야 한다.**
    func test_이름에_특수문자가_있어도_본문이_비지_않는다() {
        let j = makeJob(state: "RUNNING", name: "[구독] 1080p.mkv (한글)")
        let body = ConfirmSpec.job(j).body
        XCTAssertFalse(body.isEmpty)
        XCTAssertTrue(body.contains("[구독] 1080p.mkv (한글)"))
    }

    // MARK: - 파일명이 서식으로 먹히면 안 된다 (실제 사고 2건)

    /// **파일명에 `_` 가 있어도 그대로 보여야 한다** — 실측 사고다.
    ///
    /// 마크다운(`**굵게**`)으로 만들던 시절, `AttributedString(markdown:)` 가
    /// `__agent_test__` 의 `__` 를 **이탤릭 기호로 해석**해
    /// 화면에 **`agent_test`** 로 나왔다.
    ///
    /// 이게 위험한 이유는 보기 좋아서가 아니다:
    /// **"무엇을 지우는가" 가 틀려진다.** 같은 이름의 다른 항목이 있을 수 있다.
    func test_이름의_밑줄이_서식으로_먹히지_않는다() {
        let e = makeEntry(name: "__agent_test__", isDir: true, dir: "")
        XCTAssertTrue(ConfirmSpec.storage(e).body.contains("__agent_test__"),
                      "밑줄이 사라지면 무엇을 지우는지 틀려진다")
    }

    /// **파일명에 `*` 가 있어도 그대로여야 한다** — 마크다운의 강조 기호다.
    ///
    /// `a*b.mp4` 같은 이름은 흔하지 않지만, `**` 로 시작하는 이름은
    /// "강조 표시가 빠졌다" 고 오해할 수 있다. 이름은 **원본**이어야 한다.
    func test_이름의_별표가_서식으로_먹히지_않는다() {
        let e = makeEntry(name: "**중요** 파일.zip", isDir: false, dir: "M")
        XCTAssertTrue(ConfirmSpec.storage(e).body.contains("**중요** 파일.zip"))
    }

    /// **강조는 조각 구조로만 표현된다** — 문자열 안에 마크다운 기호가 남지 않는다.
    ///
    /// 이게 깨지면 화면이 `**` 를 그대로 그린다(실제로 그랬다).
    func test_본문에_마크다운_기호가_남지_않는다() {
        let j = makeJob(state: "RUNNING", name: "a.mkv")
        let t = makeTorrent(state: "DOWNLOADING", seeding: false, name: "b")
        let e = makeEntry(name: "c", isDir: true, dir: "")
        for spec in [ConfirmSpec.job(j), ConfirmSpec.torrent(t),
                     ConfirmSpec.storage(e), ConfirmSpec.purge("d"),
                     ConfirmSpec.restore("d"), ConfirmSpec.move(e),
                     ConfirmSpec.rename(e)] {
            XCTAssertFalse(spec.body.contains("**"),
                           "마크다운 기호가 남았다: \(spec.body)")
        }
    }

    /// **강조 조각은 반드시 하나 이상 있다** — 위험한 부분을 안 알려주면
    /// "설명창이 있네" 하고 넘어가 버린다. 강조가 사라지면 의미가 없어진다.
    func test_강조_조각이_있다() {
        let j = makeJob(state: "RUNNING", name: "a.mkv")
        let e = makeEntry(name: "c", isDir: true, dir: "")
        for spec in [ConfirmSpec.job(j), ConfirmSpec.storage(e), ConfirmSpec.purge("d"),
                     ConfirmSpec.restore("d"), ConfirmSpec.move(e), ConfirmSpec.rename(e)] {
            XCTAssertTrue(spec.parts.contains { $0.strong },
                          "강조 조각이 없다 — 위험 지점을 못 알려준다: \(spec.title)")
        }
    }

    /// **`attributedBody` 로 이어 붙여도 텍스트가 보존된다** —
    /// `AttributedString` 결합이 조각을 흘려보내지 않아야 한다(화면이 빈 게 된다).
    func test_attributedBody가_텍스트를_보존한다() {
        let j = makeJob(state: "RUNNING", name: "여름 vacation.mkv")
        let spec = ConfirmSpec.job(j)
        XCTAssertEqual(String(spec.attributedBody.characters), spec.body,
                       "AttributedString 변환에서 글자가 사라졌다")
        XCTAssertTrue(String(spec.attributedBody.characters).contains("여름 vacation.mkv"))
    }

    // MARK: - 도우미

    /// `Job` 은 **멤버 파라미터 초기화만** 있다 (`init(json:)` 없음).
    /// → 서버 파싱을 타지 않고 상태값만 직접 넣는다.
    private func makeJob(state: String, name: String) -> RelayClient.Job {
        RelayClient.Job(
            id: "job-1", name: name, state: state, progress: 50,
            speedBps: 0, uploadedBps: 0, etaSeconds: nil,
            isVideo: false, typeRaw: "http", maxDownBps: 0
        )
    }

    /// `Torrent` 도 `init?(json:)` 이므로 **강제 해제한다.**
    ///
    /// 이 테스트에서 `nil` 이 나오면 파싱이 깨진 것인데, 그건 테스트 실패로
    /// 드러나야 한다 — `!` 가 정확히 그렇게 만든다 (조용히 0 을 넣지 않는다).
    private func makeTorrent(state: String, seeding: Bool, name: String) -> Torrent {
        let o: [String: Any] = [
            "id": "t-1", "name": name, "state": state, "progress": 0.5,
            "isSeeding": seeding, "maxDownBps": 0,
        ]
        return Torrent(json: o)!
    }

    /// `StorageEntry` 는 `init?(json:dir:)` 뿐이라 **서버 응답 형태**로 만든다.
    /// (진짜와 같은 길로 만들어야 경로 조합 규칙까지 검증된다)
    private func makeEntry(name: String, isDir: Bool, dir: String) -> StorageEntry {
        let o: [String: Any] = ["name": name, "type": isDir ? "dir" : "file",
                                "size": 1024, "count": 1, "modified": 0]
        return StorageEntry(json: o, dir: dir)!
    }
}
