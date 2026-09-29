import XCTest
import CoreGraphics
@testable import DroidRelayCore

/// **창 위치 되살리기** — `WindowPlacement`.
///
/// ## 왜 이걸 순수 함수로 만들었나
///
/// 화면 좌표 계산은 `NSScreen` 이 필요해 보이지만 **사실 셋이면 충분하다** —
/// 창 크기, 화면별 가시 영역, 저장된 좌표. 이 셋을 인자로만 받으니
/// **디스플레이를 붙였다 떼는 모든 경우를 재현할 수 있다.**
///
/// 특히 이 테스트가 막는 사고는 **실제로 일어난 것**이다 —
/// **창이 화면 밖에 떠서 "설정 메뉴를 눌러도 아무 일도 없다"** 는 상태.
/// 앱은 멀쩡히 돌고 있고 메뉴바 아이콘도 있다. 조용히 실패하는 게 가장 나쁘다.
final class WindowPlacementTests: XCTestCase {

    /// 1440×900 주 화면. 메뉴바(25)·독(80)을 뺀 가시 영역이고 원점은 (0, 0).
    private let main = CGRect(x: 0, y: 0, width: 1440, height: 795)
    private let win = CGSize(width: 460, height: 520)

    /// 화면 하나뿐인 경우 — 대부분 이럴 것이다.
    private func screens(_ extra: [CGRect] = []) -> [CGRect] { [main] + extra }

    // MARK: - 처음엔 가운데

    /// **저장된 위치가 없으면 정중앙** — 사용자의 요청 그 자체.
    func test_저장된_위치가_없으면_가운데다() {
        let o = WindowPlacement.resolve(saved: nil, size: win, visible: screens())
        XCTAssertEqual(o.x, (1440 - 460) / 2, "가로 중앙")
        XCTAssertEqual(o.y, (795 - 520) / 2, "세로 중앙")
    }

    /// **`visibleFrame` 기준의 중심** — 메뉴바·독을 뺀 영역의 가운데.
    ///
    /// `frame` 의 중심을 쓰면 메뉴바 쪽으로 25px 치우쳐 **눈에 보인다.**
    func test_가시영역_기준으로_가운데를_계산한다() {
        let o = WindowPlacement.centered(in: main, size: win)
        XCTAssertEqual(o.x + win.width / 2, 720, "가로 중앙")
        XCTAssertEqual(o.y + win.height / 2, 397.5, "세로는 독을 뺀 가시 영역 중앙")
    }

    /// **화면이 아예 없으면 창을 띄울 자리가 없다** — 그래도 크래시하면 안 된다.
    func test_화면이_없어도_크래시하지_않는다() {
        XCTAssertEqual(WindowPlacement.resolve(saved: nil, size: win, visible: []), .zero)
    }

    // MARK: - 기억한다

    /// **저장된 위치가 잡히면 그자리를 지킨다** — "위치를 기억한다" 의 핵심.
    func test_저장된_위치를_그대로_지킨다() {
        let saved = CGPoint(x: 100, y: 80)
        XCTAssertEqual(WindowPlacement.resolve(saved: saved, size: win, visible: screens()), saved)
    }

    /// **오른쪽 아래 모서리에 붙인 배치도 그대로** — 앱이 사용자를 고쳐버리지 않는다.
    func test_모서리에_붙인_위치도_지킨다() {
        let saved = CGPoint(x: 1440 - 460, y: 0)
        XCTAssertEqual(WindowPlacement.resolve(saved: saved, size: win, visible: screens()), saved)
    }

    /// **일부만 걸친 위치도 그대로 둔다.**
    ///
    /// 이게 "위치를 기억한다" 를 지키는 방식이다. 보이는 걸 억지로 가운데로
    /// 밀어내면 **사용자가 배치한 것을 앱이 맨날 고쳐버리는 것**이 되고,
    /// 요구를 정면으로 배반한다. → **안 보일 때만 고친다.**
    func test_일부만_보이는_위치도_손대지_않는다() {
        let partly = CGPoint(x: 1440 - 100, y: 200)   // 오른쪽에 100px 걸침
        XCTAssertTrue(WindowPlacement.canGrab(partly, size: win, in: screens()))
        XCTAssertEqual(WindowPlacement.resolve(saved: partly, size: win, visible: screens()),
                       partly, "보이는 배치는 그대로 둔다")
    }

    // MARK: - 화면 밖 (이게 핵심)

    /// **2인치 모니터를 분리한 경우** — 저장된 좌표가 화면 밖으로 날아간다.
    ///
    /// 사용자가 보는 건 이렇다: 설정 메뉴를 눌렀다 → 아무 일도 없다.
    /// 앱은 정상 실행 중이다(메뉴바 아이콘은 있다). **"설정이 고장났다" 고 결론.**
    /// → **안 보일 때는 가운데로 되돌린다.** 사용자가 다시 볼 수 있다.
    func test_화면_밖으로_날아간_위치는_가운데로_복귀한다() {
        let lost = CGPoint(x: 1920, y: 300)   // 빠진 오른쪽 보조 모니터 위
        XCTAssertFalse(WindowPlacement.canGrab(lost, size: win, in: screens()),
                       "전혀 보이지 않는다")
        XCTAssertEqual(WindowPlacement.resolve(saved: lost, size: win, visible: screens()),
                       WindowPlacement.centered(in: main, size: win),
                       "보이지 않는 자리에 그대로 두면 사용자가 창을 못 찾는다")
    }

    /// **더 작은 화면으로 해상도가 줄어든 경우** — 같은 규칙이 적용된다.
    func test_더_작은_화면에서도_보이는_곳으로_온다() {
        let small = [CGRect(x: 0, y: 0, width: 1024, height: 700)]
        let lost = CGPoint(x: 1600, y: 900)
        let got = WindowPlacement.resolve(saved: lost, size: win, visible: small)
        XCTAssertTrue(WindowPlacement.canGrab(got, size: win, in: small),
                      "해상도가 줄어도 창은 보여야 한다")
    }

    /// **2인치_monitor 가 왼쪽에 있는 사용자** — 주 화면만 보고 판단하면 틀린다.
    ///
    /// 이게 **화면 "목록"을 받는 이유**다. 하나만 보면 "안 보인다"고 판단해
    /// **주 화면 가운데로 튕겨 보낸다.** 그 사용자는 자기 모니터를 그대로 두고
    /// 있는데 창만 갑자기 반대편으로 이동한다.
    func test_왼쪽에_붙은_모니터의_창을_억지로_옮기지_않는다() {
        let left = CGRect(x: -1440, y: 0, width: 1440, height: 795)
        let saved = CGPoint(x: -1400, y: 100)          // 왼쪽 모니터 위
        XCTAssertEqual(WindowPlacement.resolve(saved: saved, size: win, visible: screens([left])),
                       saved, "옮긴 모니터 위에 있는 창은 그대로 둔다")
    }

    /// **모든 화면에서 사라졌을 때만 가운데로.**
    func test_어느_화면에도_없을_때만_가운데로_간다() {
        let both = screens([CGRect(x: -1440, y: 0, width: 1440, height: 795)])
        let nowhere = CGPoint(x: 5000, y: 5000)
        XCTAssertEqual(WindowPlacement.resolve(saved: nowhere, size: win, visible: both),
                       WindowPlacement.centered(in: main, size: win))
    }

    // MARK: - 제목바 판정 (왜 전체 창이 아니라 띠인가)

    /// **맨 아래 2px 만 걸친 창은 "안 보인 것"** — 겹침 넓이는 460×2 로 넓어 보인다.
    ///
    /// 하지만 보이는 건 **창의 맨 아래 2px 다.** 제목바는 화면 반대편에 있어
    /// **잡을 방법이 없다.** 사람 눈에는 "안 뜬 것"이고 그대로 두면 사용자는
    /// **설정이 고장났다** 고 결론내린다.
    func test_맨_아래_2px만_보이면_안_보이는_것이다() {
        let sliver = CGPoint(x: 500, y: main.maxY - 2)
        XCTAssertFalse(WindowPlacement.canGrab(sliver, size: win, in: screens()),
                       "2px 은 사람이 지각할 수 없다 — 끌 방법이 없다")
    }

    /// **제목바 띠는 창의 맨 위** — AppKit 좌표는 아래에서 위로다.
    ///
    /// 이걸 빼먹으면 맨 아래 띠를 검사한다. **테스트는 통과하는데 실제 화면에서
    /// "잡을 수 있는데 안 잡힌다" 고 판단하는** 조용한 오류가 된다.
    func test_제목바는_창의_맨_위에_있다() {
        let o = CGPoint(x: 100, y: 200)
        let band = WindowPlacement.titleBarRect(origin: o, size: win)
        XCTAssertEqual(band.maxY, o.y + win.height, "맨 위가 y + height 다")
        XCTAssertEqual(band.minY, o.y + win.height - WindowPlacement.titleBarHeight)
    }

    /// **아주 좁게만 보이는 경우도 잡을 수 없다** — 꾹 눌러야 하니까.
    func test_아주_좁게만_보이면_잡을_수_없다() {
        let hairline = CGPoint(x: main.maxX - 10, y: 200)   // 10px 만 걸침
        XCTAssertFalse(WindowPlacement.canGrab(hairline, size: win, in: screens()),
                       "10px 은 목표를 누를 수 없다")
    }

    /// **창 전체가 화면 위일 때는 당연히 잡힌다.**
    func test_창이_화면_안에_있으면_잡을_수_있다() {
        XCTAssertTrue(WindowPlacement.canGrab(CGPoint(x: 300, y: 100), size: win, in: screens()))
    }

    // MARK: - 창 생성 위치 (왼쪽 하단 버그)

    /// **창을 만들 때부터 가운데여야 한다** — 이게 왼쪽 하단 버그의 직접 재현이다.
    ///
    /// 원래 `NSWindow(contentRect: NSRect(x: 0, y: 0, …))` 로 만들었다.
    /// **`y: 0` 은 화면 아래쪽**이고 사용자가 실제로 본 게 그거였다.
    ///
    /// "나중에 `applyPlacement()` 로 옮기니까 괜찮지 않나" — 아니다.
    /// **창이 뜨는 첫 순간이 (0,0) 이고**, 그 사이에 사용자는 화면을 본다.
    func test_창을_만들_때부터_가운데여야_한다() {
        let o = WindowPlacement.initialContentOrigin(in: main, contentSize: win)
        XCTAssertGreaterThan(o.y, 0, "y 가 0 이면 화면 아래쪽이다 — 이게 버그였다")
        XCTAssertGreaterThan(o.x, 0, "x 가 0 이면 화면 왼쪽이다 — 이것도 버그였다")
    }

    /// **제목바까지 붙인 최종 프레임이 화면 안에 완전히 들어온다.**
    ///
    /// 실제 초기 프레임은 `(0, -28)` 이었다. `contentRect` 는 **내용물** 영역이라
    /// 위로 제목바가 붙고, **그만큼 화면 밖으로 나갔다.** 이 테스트가 그걸 막는다.
    func test_제목바까지_붙인_프레임이_화면_안에_온다() {
        let o = WindowPlacement.initialContentOrigin(in: main, contentSize: win)
        let f = WindowPlacement.frameAfterTitleBar(contentOrigin: o, contentSize: win)
        XCTAssertTrue(main.contains(f), "창 전체가 화면 안이어야 한다 — 실제: \(f)")
        XCTAssertGreaterThanOrEqual(f.minY, 0, "제목바가 화면 위로 삐져나가지 않는다")
    }

    /// **내용물을 가운데에 넣으면 창 전체도 가운데다** — 제목바가 위쪽에 붙으므로.
    func test_내용물_가운데면_창_전체도_가운데다() {
        let o = WindowPlacement.initialContentOrigin(in: main, contentSize: win)
        let f = WindowPlacement.frameAfterTitleBar(contentOrigin: o, contentSize: win)
        // 창 전체의 중심이 화면 중심과 일치해야 — 수직으로 정확히
        XCTAssertEqual(f.midX, main.midX, accuracy: 0.001, "가로 중앙")
        XCTAssertEqual(f.midY, main.midY, accuracy: 0.001, "세로 중앙")
    }

    /// **제목바는 창 위쪽에 붙는다** — 이걸 빼먹으면 (0, -28) 이 그대로 통과한다.
    func test_제목바는_창_위쪽에_붙는다() {
        let o = CGPoint(x: 100, y: 200)
        let f = WindowPlacement.frameAfterTitleBar(contentOrigin: o, contentSize: win)
        XCTAssertEqual(f.minY, o.y - WindowPlacement.titleBarHeight,
                       "제목바는 위쪽 — 이게 틀리면 창이 위로 삐져나간다")
        XCTAssertEqual(f.maxY, o.y + win.height)
    }

    // MARK: - 저장소

    /// **키 쌍이 다 있어야 좌표로 인정한다** — x 만 있고 y 가 없으면 쓰레기다.
    ///
    /// `UserDefaults` 는 손으로 편집할 수 있다. `BackupPoll` 이 `0` 으로 이벤트 루프를
    /// 태우는 사고와 같은 종류 — **저장된 값이 화면 밖에서 들어오는 순간을 막아야 한다.**
    func test_좌표가_반_쪽만_저장돼_있으면_없다고_본다() {
        let d = UserDefaults.standard
        d.set(123.0, forKey: "\(WindowPlacement.defaultsKey).x")
        d.removeObject(forKey: "\(WindowPlacement.defaultsKey).y")
        XCTAssertNil(WindowPlacement.loadOrigin(), "y 없이 x 만 있으면 좌표가 아니다")
        d.removeObject(forKey: "\(WindowPlacement.defaultsKey).x")
    }

    /// **저장 → 읽기 → 지우기** — 실제 계약.
    func test_저장한_좌표를_다시_읽는다() {
        let p = CGPoint(x: 321, y: 654)
        WindowPlacement.saveOrigin(p)
        XCTAssertEqual(WindowPlacement.loadOrigin(), p)
        WindowPlacement.clearOrigin()
        XCTAssertNil(WindowPlacement.loadOrigin(), "지우면 다음에 가운데로 뜬다")
    }

    /// **저장 키는 앱 이름으로 시작한다** — 다른 앱 값과 섞이면 창이 엉뚱한 곳에서 열린다.
    func test_저장키가_앱_이름으로_시작한다() {
        XCTAssertTrue(WindowPlacement.defaultsKey.hasPrefix("com.borasarang.DroidRelay"))
    }

    // MARK: - 왕복 (저장 → 복원)이 실제로 성립하는가

    /// **저장한 좌표가 보이면 그대로 돌아온다** — 위치 기억의 전체 계약.
    func test_저장한_위치가_다음에_열_때_그대로_복원된다() {
        let user = CGPoint(x: 260, y: 140)          // 사용자가 옮긴 자리
        WindowPlacement.saveOrigin(user)
        defer { WindowPlacement.clearOrigin() }

        let got = WindowPlacement.resolve(saved: WindowPlacement.loadOrigin(),
                                          size: win, visible: screens())
        XCTAssertEqual(got, user, "보이는 자리면 반드시 그 자리에 돌아온다")
    }

    /// **저장한 좌표가 화면 밖이면 가운데로** — "아무 일도 없다" 를 막는다.
    func test_저장한_위치가_화면_밖이면_가운데로_복원된다() {
        WindowPlacement.saveOrigin(CGPoint(x: 1920, y: 300))   // 사라진 모니터
        defer { WindowPlacement.clearOrigin() }

        let got = WindowPlacement.resolve(saved: WindowPlacement.loadOrigin(),
                                          size: win, visible: screens())
        XCTAssertEqual(got, WindowPlacement.centered(in: main, size: win),
                       "보이지 않는 자리에 두면 사용자가 창을 못 찾는다")
    }
}
