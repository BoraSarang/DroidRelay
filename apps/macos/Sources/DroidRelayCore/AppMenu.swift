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

    /// **앱이 직접 구현하는 동작.** `rawValue` 가 그대로 ObjC selector 다.
    ///
    /// ## 왜 `NSApplication` 이 아니라 여기 있는가 (회귀 기록, M-33)
    ///
    /// `설정…` 은 **responder chain 에 구현이 없다** — `paste:` 는 필드 편집기가,
    /// `terminate:` 는 `NSApplication` 이 갖고 있지만 설정을 여는 기본 동작은 없다.
    /// 그래서 항목마다 **명시적 target** 이 필요하고, 그 대상(앱 델리게이트 /
    /// 메뉴바 컨트롤러)이 아래 selector 를 구현한다.
    ///
    /// 이전에는 우클릭 메뉴가 `openSettings` 라는 **스텁**(앱을 활성화만 하고
    /// 창을 열지 않았다)에 걸려 있어 `설정…` 이 **한 번도 동작한 적이 없었다**.
    /// selector 를 데이터로 두어 테스트가 배선을 고정한다.
    public enum Action: String, CaseIterable, Equatable, Sendable {
        /// 설정 창을 연다. **앱 델리게이트와 메뉴바 컨트롤러가 각각 구현한다.**
        case showSettings = "drShowSettings:"
        /// 기본 브라우저로 서버 대시보드를 연다.
        case openDashboard = "drOpenDashboard:"
        /// 앱을 종료한다(관찰 루프 정리 포함 — `terminate:` 를 그냥 쓰지 않는 이유).
        case quit = "drQuit:"
    }

    /// 이 항목의 action 을 **누가 받는가.**
    ///
    /// `target = nil` 이면 AppKit 은 responder chain 을 뒤지고, **끝내 못 찾으면
    /// 그 항목을 비활성(회색)** 으로 만든다. 앱 전용 동작을 `nil` 로 두면
    /// "메뉴가 있는데 눌리지도 않는다" 가 된다.
    public enum Receiver: Equatable, Sendable {
        /// responder chain(`paste:` · `terminate:` · `orderFrontStandardAboutPanel:`).
        case responderChain
        /// **앱이 직접 구현** — glue(AppKit 계층)가 명시적 target 을 건다.
        case appController
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
        /// 누가 이 action 을 받는가.
        public let receiver: Receiver

        public init(title: String, selector: String, key: String = "", shift: Bool = false, option: Bool = false,
                    receiver: Receiver = .responderChain) {
            self.title = title
            self.selector = selector
            self.key = key
            self.shift = shift
            self.option = option
            self.receiver = receiver
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

    /// 앱 메뉴 — 제목은 앱 이름. ⌘Q 와 **⌘, (설정)** 이 여기서 산다.
    ///
    /// macOS 관례 위치다: 정보 → 구분선 → **설정… ⌘,** → 구분선 → 종료.
    /// (M-33 이전에는 정보·종료뿐이라 **⌘, 가 어디에서도 안 먹었다** —
    ///  우클릭 메뉴의 `⌘,` 는 팝업 메뉴라 전역 단축키가 아니었다.)
    public static func appMenu(appName: String) -> Menu {
        Menu(title: appName, items: [
            Item(title: "\(appName) 정보", selector: "orderFrontStandardAboutPanel:"),
            .separator,
            Item(title: "설정…", selector: Action.showSettings.rawValue, key: ",", receiver: .appController),
            .separator,
            Item(title: "\(appName) 종료", selector: "terminate:", key: "q"),
        ])
    }

    /// **메뉴바 아이콘 우클릭 메뉴.**
    ///
    /// 팝오버(좌클릭)와 별개로 뜨는 짧은 메뉴다. 세 항목 모두 앱이 구현하므로
    /// glue 가 **명시적 target**(메뉴바 컨트롤러)을 건다.
    ///
    /// `종료` 는 `terminate:` 를 쓰지 않고 `drQuit:` 를 쓴다 —
    /// 컨트롤러의 관찰 루프(`observe`)를 **먼저 정리**해야 하기 때문이다.
    public static let statusMenu = Menu(title: "DroidRelay", items: [
        Item(title: "대시보드 열기", selector: Action.openDashboard.rawValue, receiver: .appController),
        Item(title: "설정…", selector: Action.showSettings.rawValue, key: ",", receiver: .appController),
        .separator,
        Item(title: "DroidRelay 종료", selector: Action.quit.rawValue, key: "q", receiver: .appController),
    ])

    /// `NSApp.mainMenu` 에 그대로 넣을 순서.
    public static func menus(appName: String) -> [Menu] {
        [appMenu(appName: appName), editMenu]
    }
}
