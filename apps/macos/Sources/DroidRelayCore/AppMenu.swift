import Foundation

/// **앱 메인 메뉴 명세** — AppKit 뷰가 아니라 **데이터**로 둔다.
///
/// ## 왜 이 파일이 필요한가 (회귀 기록)
///
/// macOS 의 표준 편집 단축키(⌘Z/⌘X/⌘C/⌘V/⌘A)는 **뷰가 아니라 `NSApp.mainMenu`
/// 의 `편집` 메뉴 항목이 처리**한다. AppKit 은 키 이벤트에서 `⌘+키` 조합을
/// 먼저 메뉴의 key equivalent 로 풀고, 그 항목의 action(`paste:` 등)을
/// **first responder**(= 지금 커서가 있는 텍스트 필드)로 보낸다.
/// 메인 메뉴에 그 항목이 없으면 **어떤 TextField 에서도 붙여넣기가 되지 않는다.**
/// 뷰에 paste 핸들러를 붙여도 소용없다 — 키 이벤트가 그 뷰까지 오지 않는다.
///
/// 실제로 v0.7.1 에서 같은 버그를 겪고 커스텀 메인 메뉴에 편집 메뉴를 넣어
/// 고쳤다(`docs/CHANGELOG.md` 0.7.1 "편집 메뉴 누락 수정"). 그 뒤 macOS 앱을
/// 통째로 삭제(`ebfaa27`)하고 메뉴바 앱을 **처음부터 다시** 쓰면서
/// **메뉴를 다시 만들지 않아** 같은 증상이 재발했다.
///
/// → 명세를 Core 에 두어 **테스트로 고정**하고(`AppMenuTests`), AppKit 계층
///   (`DroidRelayMac/MainMenu.swift`)은 이 데이터를 `NSMenu` 로 옮기기만 한다.
public enum AppMenu {

    /// 표준 편집 명령. `rawValue` 가 **그대로 ObjC selector** 다.
    ///
    /// AppKit 은 이 selector 들을 `NSResponder` 의 first responder 체인으로
    /// 보낸다. `NSTextView`(필드 편집기)가 실제 구현을 갖고 있다.
    public enum EditCommand: String, CaseIterable, Equatable, Sendable {
        case undo      = "undo:"
        case redo      = "redo:"
        case cut       = "cut:"
        case copy      = "copy:"
        case paste     = "paste:"
        case selectAll = "selectAll:"

        public var title: String {
            switch self {
            case .undo:      return "실행 취소"
            case .redo:      return "다시 실행"
            case .cut:       return "오려두기"
            case .copy:      return "복사"
            case .paste:     return "붙여넣기"
            case .selectAll: return "전체 선택"
            }
        }

        /// 소문자 한 글자. AppKit 은 소문자 + ⇧ 없음이면 **자동으로 ⌘** 로 본다.
        /// (여기서는 명시적으로 ⌘ 를 붙여 넘긴다 — `MainMenu.makeMenu`)
        public var key: String {
            switch self {
            case .undo:      return "z"
            case .redo:      return "z"
            case .cut:       return "x"
            case .copy:      return "c"
            case .paste:     return "v"
            case .selectAll: return "a"
            }
        }

        /// `다시 실행` 만 ⇧ 를 더한다 (⌘⇧Z).
        public var shift: Bool { self == .redo }
    }

    /// 메뉴 항목 하나. `selector.isEmpty` 면 구분선이다.
    public struct Item: Equatable, Sendable {
        public let title: String
        /// `""` 이면 구분선.
        public let selector: String
        /// `""` 이면 단축키 없음. 소문자 한 글자.
        public let key: String
        public let shift: Bool
        public let option: Bool

        public init(title: String, selector: String, key: String = "", shift: Bool = false, option: Bool = false) {
            self.title = title
            self.selector = selector
            self.key = key
            self.shift = shift
            self.option = option
        }

        public var isSeparator: Bool { selector.isEmpty }

        public static let separator = Item(title: "", selector: "")

        public static func command(_ c: EditCommand) -> Item {
            Item(title: c.title, selector: c.rawValue, key: c.key, shift: c.shift)
        }
    }

    /// 서브메뉴 하나.
    public struct Menu: Equatable, Sendable {
        public let title: String
        public let items: [Item]

        public init(title: String, items: [Item]) {
            self.title = title
            self.items = items
        }
    }

    /// **이 메뉴가 붙여넣기를 살린다.** 없으면 ⌘V 가 죽는다.
    public static let editMenu = Menu(title: "편집", items: [
        .command(.undo),
        .command(.redo),
        .separator,
        .command(.cut),
        .command(.copy),
        .command(.paste),
        .command(.selectAll),
    ])

    /// 앱 메뉴 — 제목은 앱 이름. ⌘Q 가 여기서 산다.
    public static func appMenu(appName: String) -> Menu {
        Menu(title: appName, items: [
            Item(title: "\(appName) 정보", selector: "orderFrontStandardAboutPanel:"),
            .separator,
            Item(title: "\(appName) 종료", selector: "terminate:", key: "q"),
        ])
    }

    /// `NSApp.mainMenu` 에 그대로 넣을 순서.
    public static func menus(appName: String) -> [Menu] {
        [appMenu(appName: appName), editMenu]
    }
}
