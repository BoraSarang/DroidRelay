import AppKit
import DroidRelayCore

/// `AppMenu`(Core) 명세를 실제 `NSApp.mainMenu` 로 옮긴다.
///
/// ## 왜 필요한가
///
/// AppKit 은 `⌘+키` 를 **메인 메뉴의 key equivalent** 로 먼저 푼다. 메인 메뉴가
/// 없으면(=`NSApp.mainMenu == nil`) 표준 편집 단축키가 **어디에도 닿지 않아**
/// 모든 TextField 에서 ⌘C/⌘V/⌘X/⌘A/⌘Z 가 죽는다. 이 앱은 씬 없이
/// `NSApplication` 을 직접 부팅하므로(`main.swift` `Boot`) SwiftUI 가 기본 메뉴를
/// 대신 만들어 주지 않는다 — **직접 만들어야 한다.**
///
/// ## 설치 시점
///
/// **런루프가 돌기 전에** 설치한다(`Boot.start()`). 늦게 넣으면 그 전 키 입력이
/// 메뉴에 닿지 않는다. `--menu-check` 진단이 실제 설치 여부를 확인한다.
@MainActor
enum MainMenu {

    /// 번들 밖(`swift run`)에서도 이름이 있게.
    static var defaultAppName: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String) ?? "DroidRelay"
    }

    static func install(appName: String = defaultAppName) {
        let root = NSMenu()
        for spec in AppMenu.menus(appName: appName) {
            // 최상위 항목은 **제목이 곧 서브메뉴 제목**이라 비워 둔다.
            // (여기에 제목을 넣으면 메뉴바에 "DroidRelay DroidRelay ..." 처럼 중복된다)
            let container = NSMenuItem()
            container.submenu = makeMenu(spec)
            root.addItem(container)
        }
        NSApp.mainMenu = root
    }

    private static func makeMenu(_ spec: AppMenu.Menu) -> NSMenu {
        let menu = NSMenu(title: spec.title)
        for item in spec.items {
            if item.isSeparator {
                menu.addItem(.separator())
                continue
            }
            // `NSMenuItem(title:action:keyEquivalent:)` 는 target 을 **nil** 로 둔다.
            // 그래야 action 이 responder chain(`paste:` → 필드 편집기)으로 흐른다.
            let mi = NSMenuItem(title: item.title, action: Selector(item.selector), keyEquivalent: item.key)
            if !item.key.isEmpty {
                var mask: NSEvent.ModifierFlags = [.command]
                if item.shift { mask.insert(.shift) }
                if item.option { mask.insert(.option) }
                mi.keyEquivalentModifierMask = mask
            }
            menu.addItem(mi)
        }
        return menu
    }
}
