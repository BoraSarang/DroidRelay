import XCTest
@testable import DroidRelayCore

/// **메인 메뉴 회귀 고정** — ⌘V 붙여넣기.
///
/// ## 왜 테스트가 필요한가 (실제로 두 번 났다)
///
/// macOS 의 편집 단축키는 뷰가 아니라 **메인 메뉴(`NSApp.mainMenu`)의 key
/// equivalent** 로 처리된다. 메뉴에서 편집 항목이 사라지면 **코드 어디에도 오류가
/// 없는데** 모든 TextField 의 붙여넣기가 죽는다. v0.7.1 에서 한 번, 메뉴바 앱
/// 재작성 때 또 한 번 같은 증상이 났다.
///
/// 이 테스트는 그 "조용한" 회귀를 **빌드 시점에** 잡는다. AppKit 런타임 없이
/// `AppMenu`(순수 명세)만 검사하므로 어느 환경에서도 안정적으로 돈다.
final class AppMenuTests: XCTestCase {

    // MARK: - 편집 메뉴 (⌘V 가 사는 곳)

    func testEditMenuExists() {
        XCTAssertEqual(AppMenu.editMenu.title, "편집")
        XCTAssertFalse(AppMenu.editMenu.items.isEmpty)
    }

    /// ★ 핵심 — 붙여넣기 항목.
    func testEditMenuHasPasteWithCommandV() {
        let paste = AppMenu.editMenu.items.first { $0.selector == AppMenu.EditCommand.paste.rawValue }
        XCTAssertNotNil(paste, "편집 메뉴에 paste: 항목이 없다 — 모든 TextField 에서 ⌘V 가 죽는다")
        XCTAssertEqual(paste?.key, "v")
        XCTAssertEqual(paste?.shift, false, "⌘V 는 ⇧ 를 쓰지 않는다")
        XCTAssertFalse(paste?.isSeparator ?? true)
    }

    /// 표준 편집 명령 6종이 전부 있어야 한다.
    func testEditMenuHasAllStandardEditingCommands() {
        let selectors = Set(AppMenu.editMenu.items.map(\.selector))
        for c in AppMenu.EditCommand.allCases {
            XCTAssertTrue(selectors.contains(c.rawValue), "편집 메뉴에 \(c.rawValue) 가 없다")
        }
    }

    /// 표준 단축키 문자 매핑 (⌘Z/⌘X/⌘C/⌘V/⌘A, ⌘⇧Z).
    func testEditMenuKeyCharacters() {
        func item(_ c: AppMenu.EditCommand) -> AppMenu.Item? {
            AppMenu.editMenu.items.first { $0.selector == c.rawValue }
        }
        XCTAssertEqual(item(.undo)?.key, "z")
        XCTAssertEqual(item(.redo)?.key, "z")
        XCTAssertEqual(item(.redo)?.shift, true, "다시 실행은 ⌘⇧Z")
        XCTAssertEqual(item(.cut)?.key, "x")
        XCTAssertEqual(item(.copy)?.key, "c")
        XCTAssertEqual(item(.paste)?.key, "v")
        XCTAssertEqual(item(.selectAll)?.key, "a")
    }

    /// **같은 단축키가 두 항목에 붙으면 하나는 영영 안 눌린다.**
    func testEditMenuKeyEquivalentsAreUnique() {
        let combos = AppMenu.editMenu.items
            .filter { !$0.key.isEmpty }
            .map { "\($0.shift ? "⇧" : "")\($0.option ? "⌥" : "")\($0.key)" }
        XCTAssertEqual(Set(combos).count, combos.count, "편집 메뉴에 중복 단축키가 있다: \(combos)")
    }

    /// 항목은 **고정 target 이 아니라 responder chain** 으로 가야 한다 —
    /// selector 문자열이 비어 있으면(구분선 외) 안 된다.
    func testEditCommandsAreSelectorsNotFixedActions() {
        for item in AppMenu.editMenu.items where !item.isSeparator {
            XCTAssertFalse(item.selector.isEmpty, "\(item.title) 에 selector 가 없다")
            XCTAssertTrue(item.selector.hasSuffix(":"), "ObjC action 은 ':' 로 끝난다: \(item.selector)")
        }
    }

    // MARK: - 앱 메뉴 / 순서

    func testAppMenuHasQuitWithCommandQ() {
        let quit = AppMenu.appMenu(appName: "DroidRelay").items.first { $0.selector == "terminate:" }
        XCTAssertNotNil(quit, "앱 메뉴에 종료(⌘Q)가 없다")
        XCTAssertEqual(quit?.key, "q")
    }

    func testMenusStartWithAppMenuAndIncludeEditMenu() {
        let menus = AppMenu.menus(appName: "DroidRelay")
        XCTAssertEqual(menus.first?.title, "DroidRelay", "첫 메뉴는 앱 메뉴여야 한다")
        XCTAssertTrue(menus.contains { $0.title == "편집" }, "메인 메뉴에 편집 메뉴가 반드시 있어야 한다")
    }

    /// 구분선은 selector 가 비어 있어야 한다(그래야 AppKit 계층이 `.separator()` 로 옮긴다).
    func testSeparatorIsMarkedByEmptySelector() {
        let sep = AppMenu.Item.separator
        XCTAssertTrue(sep.isSeparator)
        XCTAssertTrue(sep.selector.isEmpty)
        XCTAssertFalse(AppMenu.Item.command(.paste).isSeparator)
    }

    // MARK: - 앱 메뉴의 설정 (⌘,) — M-33

    /// ★ **⌘, 가 사는 곳.** 우클릭 메뉴의 `⌘,` 는 팝업 메뉴라 **전역 단축키가 아니다.**
    func testAppMenuHasSettingsWithCommandComma() {
        let s = AppMenu.appMenu(appName: "DroidRelay").items
            .first { $0.selector == AppMenu.Action.showSettings.rawValue }
        XCTAssertNotNil(s, "앱 메뉴에 설정 항목이 없다 — ⌘, 가 어디에서도 안 먹는다")
        XCTAssertEqual(s?.key, ",")
        XCTAssertEqual(s?.shift, false, "⌘, 는 ⇧ 를 쓰지 않는다")
    }

    /// ★ **핵심 회귀** — `설정…` 은 responder chain 에 구현이 없다.
    /// target 이 `nil` 이면 AppKit 이 그 항목을 **비활성(회색)** 으로 만든다 →
    /// "메뉴는 보이는데 눌리지도 않는다".
    func testAppMenuSettingsUsesExplicitTarget() {
        let s = AppMenu.appMenu(appName: "DroidRelay").items
            .first { $0.selector == AppMenu.Action.showSettings.rawValue }
        XCTAssertEqual(s?.receiver, .appController,
                       "설정 항목에는 명시적 target 이 필요하다 — responderChain 이면 비활성이 된다")
    }

    /// 앱 메뉴 순서는 macOS 관례 — 정보 / 구분선 / 설정 / 구분선 / 종료.
    func testAppMenuOrderFollowsConvention() {
        let titles = AppMenu.appMenu(appName: "DroidRelay").items.map { $0.isSeparator ? "—" : $0.title }
        XCTAssertEqual(titles, ["DroidRelay 정보", "—", "설정…", "—", "DroidRelay 종료"])
    }

    // MARK: - 우클릭(메뉴바) 메뉴 — M-33

    /// 우클릭 메뉴의 세 항목. 이 중 `설정…` 이 **스텁에 걸려** 동작하지 않았다.
    func testStatusMenuHasDashboardSettingsQuit() {
        let selectors = Set(AppMenu.statusMenu.items.map(\.selector))
        XCTAssertTrue(selectors.contains(AppMenu.Action.openDashboard.rawValue), "대시보드 열기 없음")
        XCTAssertTrue(selectors.contains(AppMenu.Action.showSettings.rawValue), "설정 없음")
        XCTAssertTrue(selectors.contains(AppMenu.Action.quit.rawValue), "종료 없음")
    }

    /// ⚠ **팝업 메뉴에는 responder chain 이 없다.** 항목이 `.responderChain` 이면
    /// target 이 nil 로 가 **비활성**이 된다 — 우클릭 메뉴는 전 항목이 앱 동작이어야 한다.
    func testStatusMenuItemsAllUseExplicitTarget() {
        for item in AppMenu.statusMenu.items where !item.isSeparator {
            XCTAssertEqual(item.receiver, .appController, "\(item.title) 이 비활성으로 뜬다 — target 이 없다")
        }
    }

    /// **두 진입점이 같은 selector 를 쓴다** — 한쪽만 바뀌면 "되던 설정이 안 되는" 회귀가 된다.
    func testSettingsSelectorIsSharedBetweenMenus() {
        let app = AppMenu.appMenu(appName: "DroidRelay").items.first { $0.title == "설정…" }?.selector
        let status = AppMenu.statusMenu.items.first { $0.title == "설정…" }?.selector
        XCTAssertEqual(app, AppMenu.Action.showSettings.rawValue)
        XCTAssertEqual(status, AppMenu.Action.showSettings.rawValue)
        XCTAssertEqual(app, status)
    }

    /// 우클릭 메뉴의 구분선 위치 — 대시보드·설정 / 구분선 / 종료.
    func testStatusMenuSeparatorBeforeQuit() {
        let items = AppMenu.statusMenu.items
        XCTAssertEqual(items.firstIndex(where: \.isSeparator), 2, "구분선은 종료 직전이어야 한다")
        XCTAssertEqual(items.last?.selector, AppMenu.Action.quit.rawValue)
    }

    /// 앱 동작 selector 는 ObjC 규칙을 따른다 — `:` 로 끝나고 비어 있지 않으며 중복이 없다.
    func testAppActionsAreObjCSelectors() {
        for a in AppMenu.Action.allCases {
            XCTAssertTrue(a.rawValue.hasSuffix(":"), "\(a.rawValue) 는 ObjC action 이 아니다")
            XCTAssertFalse(a.rawValue.contains(" "), "selector 에 공백이 있으면 안 된다: \(a.rawValue)")
        }
        XCTAssertEqual(Set(AppMenu.Action.allCases.map(\.rawValue)).count, AppMenu.Action.allCases.count)
    }
}
