#!/usr/bin/env swift
// 앱의 **입력 칸에 글자를 넣는다.**
//
// ## 왜 이게 필요한가
//
// "입력 칸이 있다" 와 "입력 칸에 글자가 들어가서 실행 버튼이 살아난다" 는
// 전혀 다른 주장이다. PR #28 의 보관함 이름바꾸기는
// **텍스트필드를 만들었는데 실행 버튼이 영영 비활성이었다** (바인딩이 어긋남).
//
// 화면에 글자가 **실제로 들어간 뒤** 실행이 되는지 보려면 값을 넣어줘야 한다.
// 손으로 치는 것과 같은 효과를, 대신 해준다.
//
// ## 사용법
//   swift Tools/SetValue.swift <pid> <필드플레이스홀더> <넣을 글자> [--n 0]
//
// ## 예
//   swift Tools/SetValue.swift 1234 "새 이름" "연기.mp4"
//   swift Tools/SetValue.swift 1234 "폴더 이름" "새폴더"
//
// ## 주의 — 글자를 **보이게** 만드는 것과 **모델에 넣는 것** 은 다르다
//
// `AXValue` 를 직접 쓰면 글자는 화면에 보이지만 SwiftUI 바인딩이 갱신되지 않는다.
// 그래서 **실제 키 이벤트**를 쏜다. 결과는 "칸에 글자가 보이는가" 로 확인한다.

import Foundation
import ApplicationServices
import AppKit

func copyAttr(_ e: AXUIElement, _ a: String) -> CFTypeRef? {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(e, a as CFString, &v) == .success else { return nil }
    return v
}

func textOf(_ e: AXUIElement) -> [String] {
    var out: [String] = []
    for k in ["AXTitle", "AXDescription", "AXValue", "AXPlaceholderValue"] {
        guard let v = copyAttr(e, k) else { continue }
        var s = ""
        if let str = v as? String { s = str }
        else if CFGetTypeID(v) == AXValueGetTypeID() {
            let av = v as! AXValue
            if AXValueGetType(av).rawValue == 3 {
                var sv: CFString?
                if AXValueGetValue(av, AXValueType(rawValue: 3)!, &sv), let x = sv { s = x as String }
            }
        } else if let n = v as? NSNumber { s = n.stringValue }
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.isEmpty && !out.contains(s) { out.append(s) }
    }
    return out
}

func role(_ e: AXUIElement) -> String { copyAttr(e, "AXRole") as? String ?? "" }
func children(_ e: AXUIElement) -> [AXUIElement] {
    copyAttr(e, "AXChildren") as? [AXUIElement] ?? []
}
func roots(_ app: AXUIElement) -> [AXUIElement] {
    // 메뉴바 앱(LSUIElement)의 팝오버는 AXWindows 에 등록되지 않는다(실측).
    if let w = copyAttr(app, "AXWindows") as? [AXUIElement], !w.isEmpty { return w }
    return [app]
}
func all(_ root: AXUIElement, _ want: String, _ test: (AXUIElement) -> Bool) -> [AXUIElement] {
    var out: [AXUIElement] = []
    if role(root) == want, test(root) { out.append(root) }
    for k in children(root) { out += all(k, want, test) }
    return out
}

// ── 실행 ──
guard CommandLine.arguments.count >= 4,
      let pid = pid_t(CommandLine.arguments[1]) else {
    print("사용법: swift Tools/SetValue.swift <pid> <플레이스홀더> <글자> [--n 0]")
    exit(2)
}
var args = Array(CommandLine.arguments.dropFirst(2))
var nth = 0
if let i = args.firstIndex(of: "--n"), i + 1 < args.count, let n = Int(args[i + 1]) {
    nth = n
    args.removeSubrange(i...i + 1)
}
let want = args[0], text = args[1]
/// **기존 값을 먼저 지운다** — 이름변경처럼 미리 채워진 칸에 뒤에 붙지 않게.
let clearFirst = args.contains("--keep") == false
let app = AXUIElementCreateApplication(pid)

guard AXIsProcessTrusted() else { print("접근성 권한 없음"); exit(3) }

let fields = roots(app).flatMap { all($0, "AXTextField", { _ in true }) }
print("텍스트필드 \(fields.count)개")
for (i, f) in fields.enumerated() {
    print("  #\(i) \(textOf(f))")
}
guard nth < fields.count else { print("텍스트필드가 \(fields.count)개뿐이다"); exit(1) }
let f = fields[nth]

// 1) **앱을 전면에 세운다.**
//
// 메뉴바 앱(LSUIElement)은 기본적으로 활성 상태가 아니다. 활성 상태가 아니면
// `AXFocused` 를 설정해도 **앱의 `AXFocusedUIElement` 가 안 바뀐다**(실측).
// → 키 이벤트가 보낼 곳이 없다 → 아무 글자도 안 들어간다.
//
// `activate` 이후 확인: `앱의 AXFocusedUIElement = AXTextField` 로 바뀐다.
let _ = NSRunningApplication(processIdentifier: pid)?.activate(options: [.activateIgnoringOtherApps])
usleep(400_000)

// 2) 칸에 포커스를 준다
let focused = AXUIElementSetAttributeValue(f, "AXFocused" as CFString, true as CFTypeRef)
if focused != .success {
    // press 로도 해본다 (일부 컨트롤은 focus 속성을 못 받는다)
    _ = AXUIElementPerformAction(f, kAXPressAction as CFString)
}
usleep(250_000)

// 3) **기존 글자를 전부 선택한다.**
//
// 이름변경·이동 입력 칸은 **기존 값이 미리 채워져 있다.** 그 상태로 타이핑하면
// 값이 뒤에 붙는다(예: "예편.mkv2"). 사람이 하는 것과 똑같이 ⌘A 로 덮어쓴다.
if clearFirst {
    let a = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true)!
    a.keyboardSetUnicodeString(stringLength: 1, unicodeString: [0x00])  // 'a'
    let down = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true)!
    down.keyboardSetUnicodeString(stringLength: 0, unicodeString: [])
    // ⌘A 를 실제로 쏘려면 가상 키 코드 0x00(='a') + command 플래그가 필요하다.
    let cmdA = CGEvent(keyboardEventSource: nil, virtualKey: 0x00, keyDown: true)!
    cmdA.flags = .maskCommand
    cmdA.post(tap: .cghidEventTap)
    let cmdAup = CGEvent(keyboardEventSource: nil, virtualKey: 0x00, keyDown: false)!
    cmdAup.flags = .maskCommand
    cmdAup.post(tap: .cghidEventTap)
    usleep(120_000)
    // 선택 영역을 지운다
    for kc: CGKeyCode in [51] {   // 51 = forward delete
        let d = CGEvent(keyboardEventSource: nil, virtualKey: kc, keyDown: true)!
        d.post(tap: .cghidEventTap)
        let u = CGEvent(keyboardEventSource: nil, virtualKey: kc, keyDown: false)!
        u.post(tap: .cghidEventTap)
        usleep(60_000)
    }
    usleep(150_000)
}

// 4) **실제 키 이벤트를 쏜다.**
//
// ## 왜 AXValue 로 직접 값을 안 넣고 키를 누르는가
//
// `AXUIElementSetAttributeValue(field, "AXValue", text)` 는 **텍스트를 보이게만 한다.**
// SwiftUI 의 `TextField` 는 AppKit `NSTextField` 위에 얹혀 있고,
// 바인딩 setter 는 **delegate 콜백**을 통해서만 불린다. AX 값 설정은 그 콜백을
// 건드리지 않는다.
//
// 실측 증거: 값을 넣은 직후 화면에는 글자가 보였는데
// `실행` 버튼이 **계속 비활성**이었다. 즉 **모델 값은 그대로 빈 문자열**이었다.
// (PR #28 의 "실행 버튼이 영영 눌리지 않는다" 와 정확히 같은 증상)
//
// 그래서 **키 이벤트를 실제로 쏜다.** 사람이 치는 것과 같은 경로라
// 바인딩 갱신이 원래대로 일어난다.
for ch in text {
    // `keyboardEventSource:virtualKey:keyDown:` — macOS 의 실제 시그니처다.
    // `keyCode:` 라고 쓰면 컴파일러가 `keyDown` 을 bool 로 요구하며 깨진다.
    let uni = Array(String(ch).utf16)
    let down = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true)
    let up = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false)
    down?.keyboardSetUnicodeString(stringLength: uni.count, unicodeString: uni)
    up?.keyboardSetUnicodeString(stringLength: uni.count, unicodeString: uni)
    down?.flags = []
    down?.post(tap: .cghidEventTap)
    up?.post(tap: .cghidEventTap)
    usleep(30_000)
}
usleep(300_000)

// 5) **확인한다** — 넣고 나서 "됐네" 하고 끝내지 않는다.
//    칸에 글자가 보이는 것과 모델이 갱신된 것은 다르다.
let now = (copyAttr(f, "AXValue") as? String) ?? ""
print("입력 \(text.debugDescription) → 칸 내용 \(now.debugDescription) "
      + (now == text ? "✅" : "❌ 다름"))
exit(now == text ? 0 : 1)
