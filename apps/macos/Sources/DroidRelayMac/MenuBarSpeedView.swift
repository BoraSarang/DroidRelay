import AppKit
import DroidRelayCore

/// 메뉴바 항목의 내용 — **`statusItem.view` 로 직접 교체**.
///
/// # 왜 이 방식인가 (다섯 번 실패한 뒤에야 알았다)
///
/// ## 실패 1 — `button.title` / `attributedTitle`
/// `NSStatusBarButton` 은 `NSButton` 이라 **여러 줄 타이틀을 그리지 않는다.**
/// `image` 를 지정하면 title 은 아예 렌더링되지 않는다.
///
/// ## 실패 2 — 버튼의 서브뷰로 커스텀 NSView
/// `b.addSubview(v)` 로 붙였다. **아무것도 안 보였다** — 아이콘까지 사라졌다.
/// `NSStatusBar` 이 레이아웃을 다시 잡을 때 **버튼 프레임을 자기가 결정**해서
/// 서브뷰 프레임이 버려진다. 넣은 직후 조회하면 "정상" 으로 보이는데 그려지지 않는다.
///
/// ## 실패 3 — 이미지로 굽기
/// 그림을 `NSImage` 로 만들어 `b.image` 에 넣었다. **파일 크기만 있고 픽셀이 없는
/// 투명 이미지**였다. `NSImage(size:flipped:drawingHandler:)` 는 지연 렌더링이라
/// 시스템이 다시 그릴 때 빈 상태가 된다. `NSBitmapImageRep` 에 직접 그리면 해결되긴
/// 했지만, 이미지는 **텍스트 접근성·선택·색상 변경을 모두 잃는다.**
///
/// ## 정답 — `statusItem.view`
/// `NSStatusItem` 은 **버튼이 아니라.view 로도** 내용을 가질 수 있다.
/// 여기에 `NSTextField` 를 **직접 배치**하면 시스템이 개입할 여지가 없다.
///
/// ```
/// statusItem.view = MenuBarSpeedView()   ← 버튼도 이미지도 거치지 않는다
/// ```
///
/// **2줄이 되는 이유** — 9pt 폰트는 줄 높이가 11pt, 2줄은 22pt.
/// 메뉴바 두께가 정확히 22pt 라 **딱 들어간다.** 10.5pt(13pt/줄)를 쓴 내 이전 구현은
/// 26pt 가 필요해 잘렸다. 폰트 크기가 결정적이었다.
final class MenuBarSpeedView: NSView {

    // MARK: - 구성요소

    private let upField = NSTextField(labelWithString: "")
    private let downField = NSTextField(labelWithString: "")
    /// 앱 아이콘 — **열마다 아이콘을 두지 않는다**(사용자 지정). 한 줄에 하나.
    private let iconView = NSImageView()

    private var onClick: (() -> Void)?
    private var onRightClick: (() -> Void)?

    // MARK: - 규격

    /// **9pt 가 핵심이다.** 10.5pt 면 2줄이 26pt 가 되어 메뉴바(22pt)를 넘고 잘린다.
    /// 9pt × 2줄 = 22pt — 딱 맞는다. (TetherLens 의 검증된 수치)
    private static let fontSize: CGFloat = 9
    private static let valueFont = NSFont.monospacedDigitSystemFont(ofSize: fontSize, weight: .regular)
    private static let iconSize: CGFloat = 11
    private static let gap: CGFloat = 3

    // MARK: - 초기화

    init(onClick: @escaping () -> Void, onRightClick: @escaping () -> Void) {
        self.onClick = onClick
        self.onRightClick = onRightClick
        super.init(frame: .zero)

        if let img = NSImage(systemSymbolName: "antenna.radiowaves.left.and.right",
                             accessibilityDescription: "DroidRelay")?
            .withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: Self.fontSize, weight: .semibold)) {
            img.isTemplate = true
            iconView.image = img
        }
        iconView.imageScaling = .scaleProportionallyDown
        addSubview(iconView)

        for f in [upField, downField] {
            f.isEditable = false
            f.isSelectable = false
            f.isBordered = false
            f.backgroundColor = .clear
            f.alignment = .left
            addSubview(f)
        }
    }

    required init?(coder: NSCoder) { nil }

    // MARK: - 갱신

    /// 그릴 내용을 채운다. `MenuBarTitle` 가 만든 줄을 그대로 쓴다 — 검증된 경로와 동일.
    func apply(_ lines: [MenuBarTitle.MenuBarLine]) {
        var up = "", down = ""
        for line in lines {
            guard case .speed(let r) = line else { continue }
            if r.direction == .up { up = r.values.joined(separator: "  ") }
            else { down = r.values.joined(separator: "  ") }
        }
        set(upField, up)
        set(downField, down)
        needsLayout = true
        layout()
    }

    private func set(_ f: NSTextField, _ v: String) {
        // **같은 값이면 안 건드린다.** 매초 대입하면 레이아웃이 흔들린다.
        if f.attributedStringValue.string != v {
            f.attributedStringValue = NSAttributedString(
                string: v, attributes: [.font: Self.valueFont, .foregroundColor: NSColor.labelColor]
            )
        }
    }

    // MARK: - 레이아웃

    override func layout() {
        super.layout()
        let h = NSStatusBar.system.thickness
        // **줄 높이는 폰트의 실제 ascent+descent 로 잰다.** 상수로 두면 시스템 폰트에
        // 따라 2줄이 넘친다 — 이것이 내 이전 구현이 잘린 이유였다.
        let lineH = Self.valueFont.ascender - Self.valueFont.descender + Self.valueFont.leading
        let totalH = lineH * 2
        let baseY = (h - totalH) / 2

        let upW = upField.attributedStringValue.size().width
        let downW = downField.attributedStringValue.size().width
        let valW = ceil(max(upW, downW)) + 1
        let x = Self.iconSize + Self.gap

        iconView.frame = NSRect(x: 0, y: baseY + lineH, width: Self.iconSize, height: lineH)
        upField.frame   = NSRect(x: x, y: baseY + lineH, width: valW, height: lineH)
        downField.frame = NSRect(x: x, y: baseY,        width: valW, height: lineH)

        frame.size = NSSize(width: x + valW + 1, height: h)
    }

    // MARK: - 클릭

    /// `statusItem.view` 로 쓰면 **버튼이 없어서** 클릭을 직접 받는다.
    /// 이전처럼 `hitTest` 을 nil 로 돌리면 아무도 클릭을 못 받는다.
    override func mouseDown(with event: NSEvent) { onClick?() }
    override func rightMouseDown(with event: NSEvent) { onRightClick?() }

    // MARK: - 검증

    /// 실제로 메뉴바에 붙은 프레임 — 잘림 여부를 숫자로 본다.
    var debugGeometry: [String] {
        let h = NSStatusBar.system.thickness
        let lineH = Self.valueFont.ascender - Self.valueFont.descender + Self.valueFont.leading
        let need = lineH * 2
        return [
            "뷰 프레임   : w=\(Int(self.frame.width)) h=\(Int(self.frame.height))",
            "필요 높이   : \(Int(need)) (줄당 \(Int(lineH)) × 2)",
            "메뉴바 두께  : \(Int(h))",
            "잘림        : " + (need <= h + 0.5 ? "없음 — 전부 들어감"
                : "**있음 — \(Int(need - h))pt 잘림**"),
            "글자        : ↑ \(upField.attributedStringValue.string)  ↓ \(downField.attributedStringValue.string)",
        ]
    }
}
