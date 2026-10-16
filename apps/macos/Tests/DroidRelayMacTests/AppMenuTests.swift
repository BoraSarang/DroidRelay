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
}
