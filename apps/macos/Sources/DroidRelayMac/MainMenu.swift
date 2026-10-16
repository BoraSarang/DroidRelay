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

    /// - Parameter appController: `receiver == .appController` 항목에 걸 **명시적 target**.
    ///   `nil` 이면 그런 항목은 responder chain 으로 가서 **비활성**이 된다.
    static func install(appName: String = defaultAppName, appController: AnyObject? = nil) {
        let root = NSMenu()
        for spec in AppMenu.menus(appName: appName) {
            // 최상위 항목은 **제목이 곧 서브메뉴 제목**이라 비워 둔다.
            // (여기에 제목을 넣으면 메뉴바에 "DroidRelay DroidRelay ..." 처럼 중복된다)
            let container = NSMenuItem()
            container.submenu = makeMenu(spec, target: appController)
            root.addItem(container)
        }
        NSApp.mainMenu = root
    }

    /// 명세(`AppMenu.Menu`)를 `NSMenu` 로 옮긴다.
    ///
    /// **메뉴바 우클릭 메뉴(`AppMenu.statusMenu`)도 이 함수를 쓴다** — 명세 → 메뉴
    /// 규칙이 한 벌이어야 한쪽만 고쳐지는 "반쪽 수정" 이 안 생긴다.
    static func makeMenu(_ spec: AppMenu.Menu, target: AnyObject? = nil) -> NSMenu {
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
            // **responder chain 이 구현하지 않는 앱 전용 동작**은 명시적 target 이
            // 필요하다. 없으면 AppKit 이 항목을 **비활성(회색)** 으로 만든다 —
            // "메뉴는 보이는데 눌리지도 않는다" 가 된다 (M-33).
            if item.receiver == .appController { mi.target = target }
            menu.addItem(mi)
        }
        return menu
    }
}
