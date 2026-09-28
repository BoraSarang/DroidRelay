import AppKit
import DroidRelayCore

/// 메뉴바 항목 — **`statusItem.view` 로 직접 교체**.
///
/// # 왜 이 방식인가 (네 번 실패한 뒤에야 알았다)
///
/// `statusItem.button` 의 title·image·서브뷰는 **전부 실패**했다.
/// `NSStatusItem` 은 **`.view` 로도** 내용을 가질 수 있고, 거기에 직접 배치한
/// `NSTextField` 는 시스템이 개입하지 않는다.
///
/// # 열 배치가 왜 이렇게 생겼나
///
/// **값마다 열 폭이 달라지면 메뉴바가 출렁인다.** `"9K"` 와 `"128K"` 의 폭이 다른데,
/// 폭을 값에 맞춰 잡으면 숫자가 바뀔 때마다 열이 움직인다. 눈으로 추적할 수 없다.
///
/// → **샘플 문자열로 고정폭을 정한다** (`"999M"`). 값이 몇 자리든 열이 안 움직인다.
/// 같은 이유로 **오른쪽 정렬** — 단위가(M/K/G) 붙는 자리만 움직여야 읽는다.
///
/// # 2줄이 성립하는 이유
///
/// **9pt 가 핵심.** 줄당 10pt, 2줄이 21pt 로 메뉴바 두께 22pt 안에 들어간다.
/// 10.5pt 면 26pt 가 필요해 잘린다. (TetherLens 의 검증된 수치)
final class MenuBarSpeedView: NSView {

    // MARK: - 구성요소

    /// 앱 아이콘 — **열마다 아이콘을 두지 않는다**(사용자 지정).
    private let appIcon = NSImageView()
    private let upArrow = NSImageView()
    private let downArrow = NSImageView()

    /// 출처별 텍스트 필드 — **열 하나에 하나.** 4개라 2줄 × 2열이 된다.
    private let droidUp = NSTextField(labelWithString: "")
    private let droidDown = NSTextField(labelWithString: "")
    private let devUp = NSTextField(labelWithString: "")
    private let devDown = NSTextField(labelWithString: "")

    private var onClick: (() -> Void)?
    private var onRightClick: (() -> Void)?

    /// **열 개수** — 기기 미지원이면 1, 지원하면 2. 열이 없으면 그 폭도 0 이 된다.
    private var columns = 1

    // MARK: - 규격

    private static let fontSize: CGFloat = 9
    private static let valueFont = NSFont.monospacedDigitSystemFont(ofSize: fontSize, weight: .medium)
    private static let iconSize: CGFloat = 11
    private static let arrowSize: CGFloat = 9
    private static let gap: CGFloat = 2

    /// 열 폭을 정하는 샘플 — **값과 무관하게 고정**한다.
    /// 실제 최댓값(`"999M"`)으로 잡으면 자리수가 늘 때 열이 밀린다.
    private static let widthSample = "888M"
    private static var colWidth: CGFloat {
        ceil(NSString(string: widthSample).size(withAttributes: [.font: valueFont]).width) + 2
    }

    // MARK: - 초기화

    init(onClick: @escaping () -> Void, onRightClick: @escaping () -> Void) {
        self.onClick = onClick
        self.onRightClick = onRightClick
        super.init(frame: .zero)

        if let img = NSImage(systemSymbolName: "antenna.radiowaves.left.and.right",
                             accessibilityDescription: "DroidRelay")?
            .withSymbolConfiguration(.init(pointSize: Self.fontSize, weight: .semibold)) {
            img.isTemplate = true
            appIcon.image = img
        }
        for (v, name) in [(appIcon, "antenna.radiowaves.left.and.right"),
                          (upArrow, "arrow.up"), (downArrow, "arrow.down")] {
            if let img = NSImage(systemSymbolName: name, accessibilityDescription: nil)?
                .withSymbolConfiguration(.init(pointSize: Self.arrowSize, weight: .semibold)) {
                img.isTemplate = true     // 다크/라이트에서 OS 가 자동 착색
                v.image = img
            }
            v.imageScaling = .scaleProportionallyDown
            addSubview(v)
        }

        for f in [droidUp, droidDown, devUp, devDown] {
            f.isEditable = false
            f.isSelectable = false
            f.isBordered = false
            f.backgroundColor = .clear
            f.alignment = .right          // 속도 열 오른쪽 정렬 (사용자 지정)
            addSubview(f)
        }
    }

    required init?(coder: NSCoder) { nil }

    // MARK: - 갱신

    /// `MenuBarTitle` 가 만든 줄을 **열 단위로** 배치한다.
    ///
    /// `SpeedRow.values` 는 출처별 배열이므로 그대로 열에 넣으면 된다.
    /// 합쳐서 한 줄 문자열로 만들면 열 정렬이 불가능해진다 — 그랬더니 기기 값이 잘렸다.
    func apply(_ lines: [MenuBarTitle.MenuBarLine]) {
        var upVals: [String] = []
        var downVals: [String] = []
        for line in lines {
            guard case .speed(let r) = line else { continue }
            if r.direction == .up { upVals = r.values } else { downVals = r.values }
        }
        columns = max(1, min(2, max(upVals.count, downVals.count)))
        set(droidUp, upVals.first ?? "")
        set(droidDown, downVals.first ?? "")
        set(devUp, upVals.count > 1 ? upVals[1] : "")
        set(devDown, downVals.count > 1 ? downVals[1] : "")
        needsLayout = true
        layout()
    }

    private func set(_ f: NSTextField, _ v: String) {
        // 같은 값이면 건드리지 않는다 — 매초 대입하면 레이아웃이 흔들린다.
        if f.attributedStringValue.string != v {
            f.attributedStringValue = NSAttributedString(
                string: v, attributes: [.font: Self.valueFont, .foregroundColor: NSColor.labelColor]
            )
        }
    }

    // MARK: - 레이아웃

    /// ```
    ///  ((|))  ↑     32K    31K
    ///        ↓    212K   128K
    ///        └─ 고정폭 열, 오른쪽 정렬
    /// ```
    override func layout() {
        super.layout()
        let h = NSStatusBar.system.thickness
        // **줄 높이는 폰트 실제값으로 잰다.** 상수로 두면 시스템 폰트에 따라 넘친다.
        let lineH = Self.valueFont.ascender - Self.valueFont.descender + Self.valueFont.leading
        let baseY = (h - lineH * 2) / 2

        let iconX: CGFloat = 0
        let arrowX = iconX + Self.iconSize + Self.gap
        let col1X = arrowX + Self.arrowSize + Self.gap
        let cw = Self.colWidth

        // 1줄: 앱 아이콘 + 위 방향. 2줄: 빈칸(아이콘 자리) + 아래 방향.
        //     아이콘은 1줄에만 둔다 — 두 줄에 반복하면 앱 아이콘이 2개로 보인다.
        appIcon.frame = NSRect(x: iconX, y: baseY + lineH, width: Self.iconSize, height: lineH)
        upArrow.frame = NSRect(x: arrowX, y: baseY + lineH, width: Self.arrowSize, height: lineH)
        downArrow.frame = NSRect(x: arrowX, y: baseY, width: Self.arrowSize, height: lineH)

        droidUp.frame   = NSRect(x: col1X, y: baseY + lineH, width: cw, height: lineH)
        droidDown.frame = NSRect(x: col1X, y: baseY,        width: cw, height: lineH)

        // 2열은 기기가 **지원될 때만** 만든다. 미지원이면 폭 0 → 열 자체가 없다.
        let col2X = col1X + cw + Self.gap
        let show2 = columns > 1
        let totalW = show2 ? col2X + cw + 1 : col1X + cw + 1
        devUp.frame   = NSRect(x: col2X, y: baseY + lineH, width: show2 ? cw : 0, height: lineH)
        devDown.frame = NSRect(x: col2X, y: baseY,        width: show2 ? cw : 0, height: lineH)

        frame.size = NSSize(width: totalW, height: h)
    }

    // MARK: - 클릭

    /// `statusItem.view` 로 쓰면 **버튼이 없어서** 클릭을 직접 받는다.
    override func mouseDown(with event: NSEvent) { onClick?() }
    override func rightMouseDown(with event: NSEvent) { onRightClick?() }

    // MARK: - 검증

    /// 각 필드가 **자기 폭 안에 값이 들어가는지** — 잘림을 숫자로 본다.
    var debugGeometry: [String] {
        let h = NSStatusBar.system.thickness
        let lineH = Self.valueFont.ascender - Self.valueFont.descender + Self.valueFont.leading
        let need = lineH * 2
        func fit(_ f: NSTextField) -> String {
            let w = f.attributedStringValue.size().width
            if f.attributedStringValue.string.isEmpty { return "빈칸" }
            return f.frame.width + 1 < w ? "**잘림** (w=\(Int(f.frame.width)) < \(Int(w)))" : "ok"
        }
        return [
            "뷰 프레임   : w=\(Int(frame.width)) h=\(Int(frame.height))",
            "필요 높이   : \(Int(need)) (줄당 \(Int(lineH)) × 2) / 메뉴바 \(Int(h))",
            "잘림(높이)  : " + (need <= h + 0.5 ? "없음" : "**있음**"),
            "열 개수     : \(columns)  열폭 \(Int(Self.colWidth))pt (고정)",
            "업   Droid  : |\(droidUp.attributedStringValue.string)| \(fit(droidUp))",
            "다운 Droid  : |\(droidDown.attributedStringValue.string)| \(fit(droidDown))",
            "업   기기   : |\(devUp.attributedStringValue.string)| \(fit(devUp))",
            "다운 기기   : |\(devDown.attributedStringValue.string)| \(fit(devDown))",
        ]
    }
}
