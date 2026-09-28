import AppKit
import DroidRelayCore

/// 메뉴바 항목의 내용을 **직접 그리는 뷰**.
///
/// ## 왜 `NSStatusItem.button.title` 이 아니라 뷰인가
///
/// `NSStatusItem` 의 버튼은 `NSButton` 이고, `NSButton` 은 **여러 줄 타이틀을 그리지 않는다.**
/// 줄바꿈이 든 문자열을 넣으면 첫 줄만 보이거나 통째로 잘린다.
/// 여기에 `button.image` 를 지정하면 title 은 아예 렌더링되지 않는다 — 실제로
/// 안테나 심볼만 보이고 속도 행이 안 떴다(배지만 `title` 로 대입돼서 그것만 살아남았다).
///
/// 그래서 역할을 나눈다.
/// - **버튼** = 클릭 대상(좌/우클릭) + 툴팁. 내용은 비운다.
/// - **이 뷰** = 실제 그림.
///
/// **무엇을 그릴지는 `MenuBarTitle`(Core)이 정한다.** 여기엔 그리기만 있다.
final class MenuTitleView: NSView {

    /// 그릴 줄. 값이 바뀌면 `needsDisplay` 로 다시 그린다.
    private var lines: [MenuBarTitle.MenuBarLine] = []

    private static let valueFont = NSFont.monospacedDigitSystemFont(ofSize: 10.5, weight: .medium)
    private static let markFont  = NSFont.systemFont(ofSize: 10.5, weight: .semibold)
    private static let badgeFont = NSFont.systemFont(ofSize: 10.5, weight: .bold)
    private static let lineGap: CGFloat = 1

    /// 메뉴바 두께 안에 **몇 줄이 들어가는지** — 여기서 줄 수를 정한다.
    ///
    /// ## 실측이 먼저다 (중요)
    ///
    /// 처음엔 아이콘+업+다운 **3줄**(42pt)을 요구했다. 메뉴바 두께는 **22pt** 이다.
    /// 그래서 20pt 가 잘려 **마지막 2줄(업·다운)이 배 밖으로 나갔다** — 사용자 화면에는
    /// 아이콘만 보인 것이었다(2번 연속 터진 원인 중 두 번째).
    ///
    /// "2줄이 가능하다" 는 가정은 **틀렸다.** 10.5pt 글꼴 한 줄이 이미 13pt 라
    /// 22pt 에는 **1줄만** 들어간다. 이론적 최소 글꼴(9pt → 11pt)도 2줄은 23pt 로 넘친다.
    ///
    /// 그래서 **1줄로 접는다.** 방향 기호와 압축 표기("450K")를 한 줄에 넣는다.
    static var maxLines: Int { max(1, Int(NSStatusBar.system.thickness / 13) - 1) }

    /// 22pt 에 1줄만 들어가므로 **아래 두 개는 그릴 수 없다** — 배지는 툴팁으로,
    /// 앱 아이콘은 이미 `NSStatusBarButton` 가 스스로 그리고 있다.
    private static let fitsTwoLines = NSStatusBar.system.thickness >= 30

    /// 앱 아이콘 — **열마다 아이콘을 두지 않는다**(사용자 지정). 첫 줄에 하나뿐.
    private static let symbolName = "antenna.radiowaves.left.and.right"

    /// 한 줄 = 하나의 attributed 문자열. **줄 단위로 쪼개서 그린다** —
    /// 통째로 `draw(in:)` 에 넘기면 줄 높이를 내가 통제할 수 없어 배치가 틀어진다.
    /// **그릴 줄은 언제나 하나다** — `↑9K ↓450K`.
    ///
    /// 배지 줄은 그리지 않는다(아이콘은 `NSStatusBarButton` 가 스스로 그리고).
    /// 배지까지 넣으면 3줄이 되고 22pt 를 넘겨 아래가 잘린다(실측: 42pt 필요).
    private func attributed() -> [NSAttributedString] {
        let speeds = lines.compactMap { line -> MenuBarTitle.SpeedRow? in
            if case .speed(let r) = line { return r } else { return nil }
        }
        guard !speeds.isEmpty else { return [] }
        let s = NSMutableAttributedString()
        for (i, r) in speeds.enumerated() {
            if i > 0 { s.append(NSAttributedString(string: "  ")) }
            s.append(NSAttributedString(string: "\(r.direction.symbol)",
                                       attributes: [.font: Self.markFont]))
            s.append(NSAttributedString(string: " \(r.values.joined(separator: "  "))",
                                       attributes: [.font: Self.valueFont]))
        }
        return [s]
    }

    func setLines(_ l: [MenuBarTitle.MenuBarLine]) {
        lines = l
        invalidateIntrinsicContentSize()
        needsDisplay = true
    }

    override var intrinsicContentSize: NSSize {
        let sizes = attributed().map { $0.size() }   // key path 로 메서드 못 참조
        let w = sizes.map(\.width).max() ?? 0
        let h = sizes.map(\.height).reduce(0, +)
            + Self.lineGap * CGFloat(max(0, sizes.count - 1))
        return NSSize(width: ceil(w) + 2, height: ceil(h))
    }

    override func draw(_ dirtyRect: NSRect) {
        var y: CGFloat = 0
        for s in attributed() {
            let sz = s.size()
            s.draw(at: NSPoint(x: 0, y: y))
            y += sz.height + Self.lineGap
        }
    }

    /// **클릭은 버튼이 받아야 한다** — 이 뷰가 받으면 메뉴바 클릭이 통째로 죽는다.
    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    /// 검증용 — 실제로 그리는 문자열.
    var debugLines: [String] { lines.map(\.text) }
}
