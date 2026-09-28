import AppKit
import DroidRelayCore
import SwiftUI

/// 메뉴바 항목 수명 관리.
///
/// **왜 `MenuBarExtra` 가 아니라 수동 `NSStatusItem` 인가**
/// macOS 26+ 에서 사용자가 "시스템 설정 → 제어 센터 → 메뉴바" 에서 이 앱을 끄면
/// `MenuBarExtra` 씬은 **크래시 로그 없이 프로세스가 조용히 종료**된다 (프레임워크가
/// 표시 가능한 씬이 없다고 판단해 비예외 경로로 나간다). 수동 `NSStatusItem` 은
/// 그 경우 `isVisible = false` 가 될 뿐 프로세스가 살아 있다.
///
/// 또 `NSStatusItem` 은 **강한 참조**를 유지해야 한다. 해제되면 아이콘이 조용히 사라진다.
@MainActor
final class StatusItemController {
    private var statusItem: NSStatusItem?
    private var popover: NSPopover?
    private let model: AppModel

    /// 메뉴바에서 이 앱을 끌 수 있을지 (아이콘 위치 예약) — 아님면 다른 앱의 것도 건드린다.
    private static let positionKey = "NSStatusItem Preferred Position com.borasarang.DroidRelayMac"

    init(model: AppModel) { self.model = model }

    /// 현재 메뉴바에 그려진 줄 (검증용)
    private var drawnLines: [MenuBarTitle.MenuBarLine] = []
    /// 모델 관찰 루프 — 팝오버와 무관하게 메뉴바를 갱신한다.
    private var observe: Task<Void, Never>?
    /// 메뉴바에 붙는 뷰 — `statusItem.view` 로 들어간다.
    private var speedView: MenuBarSpeedView?

    func install() {
        seedPositionOnce()
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem = item

        // ## `statusItem.view` 로 넣는다 — 여기가 다섯 번째 실패의 답
        //
        // `statusItem.button` 의 title·image·서브뷰 로는 **전부 실패했다.**
        // `NSStatusItem` 은 **`.view` 로도** 내용을 가질 수 있고, 여기에 직접 배치한
        // `NSTextField` 는 시스템이 건드릴 일이 없다.
        //
        // (TetherLens 의 검증된 방식. 2줄이 성립하는 이유도 거기서 찾았다 —
        //  **9pt 폰트**는 줄당 11pt, 2줄이 22pt 로 메뉴바 두께에 딱 들어간다.
        //  내 이전 구현은 10.5pt(줄당 13pt)라 26pt 가 필요했고 잘렸다.)
        let v = MenuBarSpeedView(
            onClick: { [weak self] in self?.togglePopover() },
            onRightClick: { [weak self] in self?.showMenu() }
        )
        item.view = v
        speedView = v
        updateBadge()

        // ## 갱신을 누가 시키는가 — 여기가 마지막 함정이었다
        //
        // 이전에는 `PopoverContainer` 의 `.onChange(of: model.badgeCount)` 가
        // `.drBadgeChanged` 를 올리고 그걸 여기서 받았다. 그럼 **팝오버가 살아야** 갱신된다.
        //
        // 그런데 `NSHostingController` 는 **팝오버를 처음 열 때** 만들어진다.
        // → 앱을 켠 직후에는 아무것도 알리지 않는다 → 메뉴바가 `install()` 시점의
        // `—` 로 **영영 고착된다.** 사용자가 클릭해 팝오버를 열기 전까지.
        //
        // `--watch` 로그로 실측: 서버에는 62.8 KB/s 가 흐르는데 메뉴바는 `—` .
        // 서버 문제가 아니라 **갱신 트리거가 팝오버에 묶여 있던 것** 이었다.
        //
        // → **컨트롤러가 모델을 직접 관찰한다.** 팝오버는 그릴 대상일 뿐 아니다.
        observe = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                if Task.isCancelled { break }
                await MainActor.run { self?.updateBadge() }
            }
        }

        // **팝오버를 직접 닫지 않는다.** 바깥 클릭은 `behavior = .transient` 가 처리한다.
        // `NSWindow.didResignKeyNotification` 으로 닫으면 팝오버 안에서 설정 시트를 열었을 때
        // resign key 가 울려 **시트가 열리는 순간 팝오버까지 사라진다**.
    }

    /// 노치가 있는 화면에서 새 항목이 노치 밑에 숨어 보이지 않는 문제가 있다.
    /// macOS 는 생성 시점에 이 값을 한 번만 읽는다. **미설정 시에만** 시드한다 —
    /// 매번 덮어쓰면 사용자의 ⌘-드래그로 정렬한 순서를 파괴한다.
    private func seedPositionOnce() {
        let d = UserDefaults.standard
        if d.object(forKey: Self.positionKey) == nil {
            d.set(0, forKey: Self.positionKey)   // 0 = 우측 끝
        }
    }

    /// 배지와 속도를 **한 번에** 그린다.
    ///
    /// **버튼의 `title` 을 만지지 않는 이유** — `attributedTitle` 을 지우기 때문이다.
    /// 한 번 더 상처를 냈다: 처음엔 `attributedTitle` 에 넣고 배지만 `b.title` 로 갱신했는데,
    /// AppKit 이 `attributedTitle` 을 **삭제**해서 속도가 사라졌다(배지만 남음).
    /// 지금은 버튼에도 뷰에도 대입하지 않고 **뷰 하나만** 갱신한다.
    /// 배지와 속도를 **한 번에** 그린다.
    ///
    /// ## `b.image` 로 넣는다 — 서브뷰는 실패했다
    ///
    /// 커스텀 `NSView` 를 서브뷰로 붙이는 길을 먼저 시도했다. **결과는 아무것도 안
    /// 보였다** — 안테나 아이콘까지 사라졌다. 원인은 `NSStatusBarButton` 이 컨테이너가
    /// 아니라 `NSButton` 이고, `NSStatusBar` 이 레이아웃을 다시 잡을 때 **버튼 프레임을
    /// 자기가 결정한다** — 서브뷰 프레임은 그때마다 버려진다. 넣은 직후 조회하면
    /// "정상" 으로 보이는데 실제로는 그려지지 않는다.
    ///
    /// `b.image` 는 시스템이 **항상** 그리는 자리다(안테나 심볼이 거기서 그려졌다).
    /// 그래서 그림을 이미지로 구워서 이 자리에 넣는다.
    func updateBadge() {
        let src = model.speedSetting
        let drawn = MenuBarTitle.lines(
            badge: model.badgeCount,
            droid: model.droidSpeed,
            // **못 쓰는 출처는 열을 만들지 않는다** — 서버가 값을 안 주는데 0 을 넣으면
            // 사용자는 화면에서 "고장 났구나" 를 읽는다.
            device: model.deviceSpeedAvailable ? model.deviceSpeed : nil,
            includeDroid: src.showDroid,
            includeDevice: src.showDevice
        )
        drawnLines = drawn
        // **버튼이 아니라 `speedView` 를 갱신한다.** `NSStatusItem.view` 로 붙인 뒤
        // 여기서 내용을 채운다 — 시스템이 개입하지 않으므로 값이 유실되지 않는다.
        // 값이 없으면 빈 문자열이 들어가고 앱 아이콘 하나만 남는다.
        speedView?.apply(drawn)
        renderTooltip()
    }

    /// 실제로 그리는 줄 — `--title-check` 검증용.
    var debugLines: [String] { drawnLines.map(\.text) }

    /// 메뉴바에 붙은 실제 프레임 — **잘림 여부를 숫자로 본다.**
    var debugFrames: [String] {
        guard let v = speedView else { return ["speedView    : **nil — install() 이 안 돌았다**"] }
        return ["speedView ok : true"] + v.debugGeometry
    }

    private func renderTooltip() {
        guard let b = statusItem?.button else { return }
        let sources = model.visibleSpeedSources
        b.toolTip = sources.isEmpty ? "DroidRelay" :
            sources.map { "\($0.label) ↓ \(SpeedFormat.text(model.speed(for: $0).downBps))"
                        + " ↑ \(SpeedFormat.text(model.speed(for: $0).upBps))" }
                   .joined(separator: "\n")
    }

    @objc private func clicked(_ sender: NSStatusBarButton) {
        let isRight = NSApp.currentEvent?.type == .rightMouseUp
        if isRight { showMenu() } else { togglePopover() }
    }

    private func ensurePopover() -> NSPopover {
        if let p = popover { return p }
        let p = NSPopover()
        p.behavior = .transient          // 바깥 클릭 시 닫힘
        p.animates = true
        // **그래프(≈60pt) + 탭/목록/통계가 함께 들어가야 한다.**
        // 420 으로 고정하면 아래 내용이 잘린다 — 고정 높이를 유지하는 편이 덜 깜빡인다.
        p.contentSize = NSSize(width: 352, height: 520)
        p.contentViewController = NSHostingController(rootView: PopoverContainer(model: model))
        popover = p
        return p
    }

    /// 팝오버를 띄운다.
    ///
    /// ## `statusItem.button` 을 쓰면 안 된다 — 이게 회귀의 원인
    ///
    /// `statusItem.view` 로 전환하면 **`statusItem.button` 이 nil 이 된다.**(시스템이
    /// 버튼을 만들지 않으므로) 그런데 여기서 `button` 으로 위치를 잡으려고 해서
    /// `guard` 에서 조용히 `return` — **클릭해도 아무 일도 일어나지 않았다.**
    ///
    /// → **`speedView` 를 기준 뷰로** 쓴다. 직접 배치한 뷰이므로 항상 있다.
    private func togglePopover() {
        let p = ensurePopover()
        if p.isShown {
            p.performClose(nil)
        } else {
            guard let v = speedView else { return }
            p.show(relativeTo: v.bounds, of: v, preferredEdge: .minY)
            // 팝오버 안의 컨트롤이 키보드를 받을 수 있게 활성화
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    /// 팝오버가 실제로 붙어 있는지 — `--watch` 로 확인한다.
    ///
    /// **팝오버가 안 뜨는 버그를 diagnose 로는 못 잡는다.** `statusItem` 을 만들지
    /// 않으므로. "클릭했는데 아무것도 없음" 을 눈으로만 확인하게 되던 문제다.
    var debugPopover: [String] {
        guard let p = popover else { return ["팝오버      : **nil — install() 이 안 돌았다**"] }
        return [
            "팝오버      : 생성됨",
            "기준 뷰     : " + (speedView == nil ? "**nil — 팝오버가 뜨지 않는다**" : "speedView (ok)"),
            "표시 중     : \(p.isShown)",
            "컨트롤러    : \(p.contentViewController == nil ? "nil" : "ok")",
        ]
    }

    /// **팝오버를 열고 그대로 대기한다.** 외부 도구(`Tools/DumpAX.swift`)가
    /// 화면 내용을 읽을 수 있게 하는 것이 목적이다.
    ///
    /// 왜 이게 필요한가: **눈으로 볼 수 없는 경우 "화면에 뭐가 있는가" 를
    /// 확인할 방법이 이것뿐이다.** 앱이 스스로 덤프하면 자기 자신을 원격 AX 로
    /// 질의할 수 없어(실측: 창 0개) 아무것도 안 나온다. → 반드시 밖에서 물어봐야 한다.
    func holdPopoverOpen() {
        showPopoverForCheck()
    }

    /// **무조건 팝오버를 연다.** 진단 전용 — 토글이 아니라 "연 상태" 를 만든다.
    func forceShowPopover() {
        if popover?.isShown != true { showPopoverForCheck() }
    }

    /// **원격(프로세스 간) AX 트리로 화면에 보이는 텍스트를 뽑는다.**
    ///
    /// ## 왜 로컬 AX 로는 안 되는가
    ///
    /// 자기 프로세스의 `NSView.accessibilityTitle()` 은 SwiftUI 텍스트를 **거의 안 준다.**
    /// AX 덤프 결과가 `AXUnknown` 만 나오는 것이 그 증거다.
    /// SwiftUI 는 텍스트를 `NSAccessibilityElement`(한 프로세스 밖에서 질의되는
    /// 원격 요소)로 노출하므로, **자기 자신에게는 값이 없고 다른 프로세스에서 물어보면 나온다.**
    ///
    /// → `AXUIElementCreate` 로 **자기 자신의 프로세스**를 질의한다.
    /// 이것이 VoiceOver 가 실제로 읽는 경로라 "화면에 뭐가 있나" 의 정답이다.
    /// AX 속성 키 — C 전역 상수라 Swift 심볼로 안 들어온다. **문자열 리터럴이 정답이다.**
    private enum AXKey {
        static let windowChildren = "AXWindows"
        static let children = "AXChildren"
        static let role = "AXRole"
        static let subrole = "AXSubrole"
        static let title = "AXTitle"
        static let value = "AXValue"
        static let desc = "AXDescription"
    }

    func debugRemoteAX() -> [String] {
        var out = ["══ 원격 AX 덤프 (VoiceOver 가 읽는 경로) ══"]
        let pid = ProcessInfo.processInfo.processIdentifier
        let app = AXUIElementCreateApplication(pid)
        let wins = axCopy(app, AXKey.windowChildren) as? [AXUIElement] ?? []
        out.append("프로세스 \(pid) — 창 \(wins.count)개")
        for (i, w) in wins.enumerated() {
            let title = axCopy(w, AXKey.title) as? String ?? "(제목 없음)"
            let role = axCopy(w, AXKey.role) as? String ?? "?"
            out.append("")
            out.append("── 창 #\(i): \(role) “\(title)”")
            axDump(w, depth: 1, into: &out)
        }
        return out
    }

    private func axCopy(_ e: AXUIElement, _ attr: String) -> CFTypeRef? {
        var v: CFTypeRef?
        guard AXUIElementCopyAttributeValue(e, attr as CFString, &v) == .success else { return nil }
        return v
    }

    private func axDump(_ e: AXUIElement, depth: Int, into out: inout [String]) {
        guard depth < 24 else { return }
        let pad = String(repeating: "  ", count: depth)
        let role = axCopy(e, AXKey.role) as? String ?? "?"
        var label = axCopy(e, AXKey.title) as? String ?? ""
        if label.isEmpty, let v = axCopy(e, AXKey.value) { label = Self.axString(v) }
        if label.isEmpty { label = axCopy(e, AXKey.desc) as? String ?? "" }
        let sub = axCopy(e, AXKey.subrole) as? String ?? ""
        var line = pad + role + (sub.isEmpty ? "" : ".\(sub)")
        if !label.isEmpty { line += "  “\(label)”" }
        out.append(line)
        let kids = axCopy(e, AXKey.children) as? [AXUIElement] ?? []
        for k in kids { axDump(k, depth: depth + 1, into: &out) }
    }

    /// AX 값(`CFTypeRef`)을 사람이 읽는 문자열로.
    ///
    /// **AXValue 는 CFTypeRef 그 자체라 조건부 캐스팅이 경고가 된다.**
    /// 그래서 `AXValueGetType` 으로 문자열인지 먼저 확인한 뒤에만 값을 꺼낸다.
    /// (숫자/문자열 속성이 `AXValue` 안에 들어오고 `as? String` 은 조용히 nil 이 된다)
    private static func axString(_ v: CFTypeRef) -> String {
        if let s = v as? String { return s }
        if CFGetTypeID(v) == AXValueGetTypeID() {
            let av = v as! AXValue          // 위 ID 검사로 AXValue 임이 보장된다
            // **AXValueType 원시값 3 = kAXValueStringType, 2 = kAXValueDoubleType**
            // C 상수(kAXValueStringType 등)가 Swift 에 노출되지 않으므로 번호를 쓴다.
            // (값 정의는 macOS AXValue.h — 바꾸면 안 된다)
            let t = AXValueGetType(av).rawValue
            if t == 3 {
                var sv: CFString?
                if AXValueGetValue(av, AXValueType(rawValue: 3)!, &sv), let s = sv { return s as String }
            }
            var dv = Double(0)
            if AXValueGetValue(av, AXValueType(rawValue: 2)!, &dv) { return String(format: "%g", dv) }
        }
        if let n = v as? NSNumber { return n.stringValue }
        return String(describing: v)
    }

    /// **접근성(AX) 트리로 화면에 보이는 텍스트를 뽑는다.**
    ///
    /// ## 왜 AX 트리인가
    ///
    /// 뷰 계층을 내려가면 `CGDrawingView` 만 나온다(위 덤프이 그 증거다).
    /// SwiftUI 는 텍스트를 CoreGraphics 레이어로 그려서 **뷰 계층에 문자열이 없다.**
    /// 그러면 "화면에 뭐가 있는지"를 확인할 방법이 눈뿐이다.
    ///
    /// **접근성 트리는 그 문제를 우회한다.** VoiceOver 가 읽는 것이 곧 화면에 있는
    /// 텍스트이고, SwiftUI 는 접근성을 의도적으로 노출한다.
    /// → "사용자가 화면 낭독기로 hears 하는 것" = "화면에 있는 것".
    func debugAX() -> [String] {
        guard let p = popover, let host = p.contentViewController?.view else {
            return ["══ AX 덤프 ══\n팝오버 없음"]
        }
        var out = ["══ AX 덤프 (화면에 보이는 텍스트) ══",
                   "표시 중: \(p.isShown)", ""]
        axWalk(host, depth: 0, into: &out)
        return out
    }

    private func axWalk(_ e: Any, depth: Int, into out: inout [String]) {
        guard depth < 40 else { return }
        let pad = String(repeating: "  ", count: depth)
        var line = pad
        if let v = e as? NSView {
            let f = v.frame
            line += v.isHidden || f.width < 1 ? "[숨김] " : ""
            let n = String(describing: type(of: v))
            line += (n.split(separator: ".").last.map(String.init) ?? "?") + " "
        }
        // **AX 접근은 `NSAccessibilityProtocol` 인스턴스에만 된다.**
        // NSObject 로 캐스팅하면 컴파일은 되지만 런타임에 부재 메서드가 된다 —
        // 그래서 protocol 을 실제로 conform 하는 값만 받는다.
        if let a = e as? NSAccessibilityProtocol {
            let role = a.accessibilityRole()?.rawValue ?? "?"
            // `accessibilityValue()` 는 `Any?` 다 — NSNumber/String/NSAttributedString
            // 이 섞여 온다. 화면에 보이는 값은 **문자열로 바꾼 뒤** 비교한다.
            // (숫자 0 은 "비어 있음" 이 아니라 "값 0" 이므로 String 변환 후 판정)
            let valText = a.accessibilityValue().map { v -> String in
                if let s = v as? String { return s }
                if let n = v as? NSNumber { return n.stringValue }
                if let s = v as? NSAttributedString { return s.string }
                return String(describing: v)
            } ?? ""
            if let t = a.accessibilityTitle(), !t.isEmpty {
                out.append("\(line)\(role): “\(t)”")
            } else if !valText.isEmpty {
                out.append("\(line)\(role): \(valText)")
            } else if let lab = a.accessibilityLabel(), !lab.isEmpty {
                out.append("\(line)\(role): \(lab)")
            } else {
                out.append("\(line)\(role)")
            }
        } else {
            out.append(line + "—")
        }
        // 자식 순회 — NSView 계층은 subviews, AX 요소는 accessibilityChildren()
        var kids: [Any] = []
        if let v = e as? NSView { kids = v.subviews }
        else if let a = e as? NSAccessibilityProtocol {
            kids = (a.accessibilityChildren() ?? []).map { $0 as Any }
        }
        for k in kids { axWalk(k, depth: depth + 1, into: &out) }
    }

    /// **화면에 실제로 그려진 것을 텍스트로 덤프한다.**
    ///
    /// ## 왜 이게 필요한가
    ///
    /// 이전까지 "팝오버에 버튼이 있다" 는 주장이 **코드만 보고** 나왔다.
    /// 실제로 뜨는지, 그려지는지 확인하지 않았다. 그래서
    /// "시트가 안 닫히는", "대상 칸이 안 채워지는" 같은 걸
    /// 사용자가 화면을 보고 발견하게 되었다.
    ///
    /// **눈으로 볼 수 없는 경우 화면의 진실은 뷰 계층에 있다.**
    /// NSHostingView 아래의 모든 서브뷰를 재귀적으로 내려가며
    /// 라벨·버튼·텍스트필드의 **실제 문자열과 프레임** 을 뽑는다.
    /// 이것을 파일로 남기면 "무엇이 그려졌는가" 를 근거로 말할 수 있다.
    func debugUI() -> [String] {
        guard let p = popover, let host = p.contentViewController?.view else {
            return ["팝오버 없음 — install() 이 안 돌았다"]
        }
        var out: [String] = ["══ 팝오버 UI 덤프 ══",
                             "표시 중: \(p.isShown)",
                             "크기: \(Int(p.contentSize.width))×\(Int(p.contentSize.height))", ""]
        walk(host, depth: 0, into: &out)
        return out
    }

    /// 서브뷰 계층을 재귀적으로 덤프한다.
    ///
    /// ## 왜 접근성(AX) API 를 쓴다
    ///
    /// SwiftUI 의 `Text` 는 `NSTextField` 가 아니다. 대개 `NSHostingView` 안의
    /// 비공개 레이어로 그려지므로 `as? NSTextField` 로는 **거의 아무것도 못 잡는다.**
    /// 접근성 레이블은 SwiftUI 가 **의도적으로 노출**하는 값이라 사람이 읽는
    /// 화면과 일치한다. 프레임이 0×0 인 항목(숨김/스크롤 밖)은 "보이지 않음" 으로
    /// 표시한다 — 눈에 있는데 덤프에 없는 것과, 눈에 없는데 덤프에 있는 것을 구분한다.
    private func walk(_ v: NSView, depth: Int, into out: inout [String]) {
        let pad = String(repeating: "  ", count: depth)
        let f = v.frame
        var line = "\(pad)\(type(of: v))"
        // 숨김/크기 0 — 그려지지 않는다. 존재하지만 보이지 않는 것과 구분해야 한다
        let visible = f.width > 0.5 && f.height > 0.5 && !v.isHidden
        line += visible ? "  [\(Int(f.width))×\(Int(f.height))]" : "  [숨김 \(Int(f.width))×\(Int(f.height))]"
        let label = Self.axText(v)
        if !label.isEmpty { line += "  “\(label)”" }
        out.append(line)
        for s in v.subviews { walk(s, depth: depth + 1, into: &out) }
    }

    /// 뷰에서 사람이 읽는 문자열을 최대한 하나만 뽑는다.
    ///
    /// **순서가 중요하다** — 구체적인 것(NSTextField 값, AX 라벨, AX 제목)에서
    /// 시작해 없으면 타입 이름이라도 준다. 빈 문자열이면 아무것도 없는 뷰다.
    private static func axText(_ v: NSView) -> String {
        if let t = v as? NSTextField, !t.stringValue.isEmpty { return t.stringValue }
        if let b = v as? NSButton, !b.title.isEmpty { return b.title }
        if let p = v as? NSPopUpButton { return p.titleOfSelectedItem ?? "popup(\(p.numberOfItems)개)" }
        if let a = v as? NSView, let l = a.accessibilityLabel(), !l.isEmpty { return l }
        if let a = v as? NSView, let t2 = a.accessibilityTitle(), !t2.isEmpty { return t2 }
        return ""
    }

    /// **고정 소스에서 팝오버를 띄워 본다** — 클릭 없이 경로를 검증한다.
    /// 위치가 안 잡히는지, 콘텐츠가 붙는지, 화면 안에 뜨는지 눈으로 확인할 수 있다.
    func showPopoverForCheck() {
        togglePopover()
    }

    /// 우클릭 — 시스템 표준 메뉴
    private func showMenu() {
        let m = NSMenu()
        m.addItem(withTitle: "대시보드 열기", action: #selector(openDash), keyEquivalent: "").target = self
        m.addItem(withTitle: "설정…", action: #selector(openSettings), keyEquivalent: ",").target = self
        m.addItem(.separator())
        m.addItem(withTitle: "DroidRelay 종료", action: #selector(quit), keyEquivalent: "q").target = self
        // **버튼이 아니라 view 로 메뉴를 연다.** `statusItem.view` 로 전환한 뒤
        // `statusItem.button` 은 nil 이라 `performClick` 이 조용히 안 된다.
        guard let v = speedView else { return }
        m.popUp(positioning: nil, at: NSPoint(x: 0, y: v.bounds.maxY + 4), in: v)
    }

    @objc private func openDash() { model.openDashboard() }
    @objc private func openSettings() { NSApp.activate(ignoringOtherApps: true) }
    @objc private func quit() {
        observe?.cancel()
        observe = nil
        NSApp.terminate(nil)
    }
}

/// 설정 창은 M4 범위라 지금은 주소 입력만 최소로 제공한다.
struct PopoverContainer: View {
    @Bindable var model: AppModel
    @State private var showSettings = false
    @State private var address = ""
    /// 로그인 시 자동 실행 — **Launch Services 를 직접 조회**한다(UserDefaults 플래그가 아니라).
    @State private var loginOn = LoginItem.isEnabled
    @State private var showDroid = true
    @State private var showDevice = false
    @State private var loginMsg: String? = nil

    var body: some View {
        PopoverView(
            model: model,
            onSettings: {
                address = model.storedAddress ?? ""
                loginOn = LoginItem.isEnabled   // 시트를 열 때마다 실제 상태로 새로고침
                showDroid = model.speedSetting.showDroid
                showDevice = model.speedSetting.showDevice
                loginMsg = nil
                showSettings = true
            },
            onQuit: { NSApp.terminate(nil) }
        )
        .sheet(isPresented: $showSettings) {
            settingsSheet
        }
        .task { if model.phase == .idle { await model.connect() } }
        .onChange(of: model.badgeCount) { _, _ in
            NotificationCenter.default.post(name: .drBadgeChanged, object: nil)
        }
        .onChange(of: showDroid) { _, _ in
            model.speedSetting.showDroid = showDroid
        }
        .onChange(of: showDevice) { _, _ in
            model.speedSetting.showDevice = showDevice
        }
        .onChange(of: model.droidSpeed) { _, _ in NotificationCenter.default.post(name: .drBadgeChanged, object: nil) }
        .onChange(of: model.deviceSpeed) { _, _ in NotificationCenter.default.post(name: .drBadgeChanged, object: nil) }
    }

    private var settingsSheet: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("DroidRelay 연결").font(.system(size: 14, weight: .bold))
            Text("자동으로 찾지 못하면 주소를 직접 입력하세요.")
                .font(.system(size: 11.5)).foregroundStyle(.secondary)
            HStack {
                TextField("10.0.0.5:3000", text: $address)
                    .textFieldStyle(.roundedBorder)
                Button("연결") { Task { await model.useManualAddress(address) } }
                    .disabled(address.isEmpty)
            }
            Divider()
            HStack {
                Text("자동 검색으로 다시 시도").font(.system(size: 12))
                Spacer()
                Button("다시 찾기") { Task { await model.connect() } }
            }

            Divider()
            Text("메뉴바 속도").font(.system(size: 14, weight: .bold))
            speedToggle("Droid 속도", "이 앱이 쓰는 트래픽", $showDroid)
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("기기 속도").font(.system(size: 12))
                    Text(model.deviceSpeedAvailable
                         ? "폰 전체 트래픽" : "서버 지원 대기 중 — 켜도 값이 나오지 않습니다")
                        .font(.system(size: 10.5))
                        .foregroundStyle(model.deviceSpeedAvailable ? .secondary : Color.orange)
                }
                Spacer()
                Toggle("", isOn: $showDevice)
                    .labelsHidden()
                    .disabled(!model.deviceSpeedAvailable)
            }

            Divider()
            Text("시작").font(.system(size: 14, weight: .bold))
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("로그인 시 자동 실행").font(.system(size: 12))
                    if let loginMsg {
                        Text(loginMsg).font(.system(size: 10.5)).foregroundStyle(.secondary)
                    } else {
                        Text("Mac 에 로그인하면 DroidRelay 가 자동으로 뜹니다")
                            .font(.system(size: 10.5)).foregroundStyle(.tertiary)
                    }
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { loginOn },
                    set: { toggleLogin($0) }
                ))
                .labelsHidden()
            }

            HStack { Spacer(); Button("닫기") { showSettings = false }.keyboardShortcut(.defaultAction) }
        }
        .padding(20)
        .frame(width: 420)
    }

    private func speedToggle(_ t: String, _ h: String, _ binding: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(t).font(.system(size: 12))
                Text(h).font(.system(size: 10.5)).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: binding).labelsHidden()
        }
    }

    /// 실패해도 앱은 죽지 않는다 — 사유를 설정 화면에 남기고 스위치를 원래대로 되돌린다.
    private func toggleLogin(_ want: Bool) {
        let r = LoginItem.set(want)
        loginOn = LoginItem.isEnabled   // 요청한 값이 아니라 **실제** 상태를 따른다
        if case .applied = r { loginMsg = nil } else { loginMsg = r.message }
    }
}

extension Notification.Name { static let drBadgeChanged = Notification.Name("drBadgeChanged") }
