#!/usr/bin/env swift
// 외부 프로세스에서 실행 중인 앱의 접근성(AX) 트리를 텍스트로 덤프한다.
//
// ## 왜 이게 필요한가
//
// **눈으로 화면을 볼 수 없는 경우(에이전트 작업), "화면에 뭐가 있는지"를
// 확인할 유일한 방법이 이 스크립트다.**
//
// 이전까지는 코드를 읽고 "버튼이 있다"고Anaigs했다가 실제로는 시트가 안 닫히고
// 입력 칸이 비어 있는 식으로 여러 번 틀렸다. 원인은 검증 방법이 없었기 때문이다.
//
// ## SwiftUI 왜 안 되는가
//
// 뷰 계층(`NSView.subviews`)으로는 `CGDrawingView` 만 나온다 — SwiftUI 가
// 텍스트를 CoreGraphics 레이어로 그려서 **문자열이 뷰 계층에 없다.**
//
// 접근성 트리는 그걸 우회한다. SwiftUI 는 접근성을 의도적으로 노출하고,
// **VoiceOver 가 읽는 경로가 곧 화면에 있는 텍스트**다.
//
// ## 왜 외부 프로세스여야 하는가
//
// 자기 자신을 원격 AX 로 질의하면 **항상 0개**가 나온다(실측).
// AX 는 "다른 프로세스에서 나를 본다"는 전제라 자기 자신에게는 아무것도 안 준다.
// → 앱은 열려 있게 두고, **이 스크립트**가 pid 를 받아 질의한다.
//
// ## 사용법
//   swift Tools/DumpAX.swift <pid> [최대깊이]
//   swift Tools/DumpAX.swift <pid> | grep 일시정지
//
// ## 예
//   # 앱을 팝오버를 연 채로 대기시킨 뒤
//   open -a ~/Applications/DroidRelay.app --args --ui-hold
//   swift Tools/DumpAX.swift $(pgrep -x DroidRelay)

import Foundation
import ApplicationServices
import AppKit

/// AX 속성 키. C 전역 상수라 Swift 에 노출되지 않아 문자열로 쓴다.
enum AXKey {
    static let windows = "AXWindows"
    static let focused = "AXFocusedWindow"
    static let main = "AXMainWindow"
    static let children = "AXChildren"
    static let role = "AXRole"
    static let subrole = "AXSubrole"
    static let title = "AXTitle"
    static let value = "AXValue"
    static let desc = "AXDescription"
    static let enabled = "AXEnabled"
    static let roleDescription = "AXRoleDescription"
}

func copyAttr(_ e: AXUIElement, _ attr: String) -> CFTypeRef? {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(e, attr as CFString, &v) == .success else { return nil }
    return v
}

/// AXValue 안에 들어온 값을 문자열로.
///
/// **숫자/문자열 속성은 `AXValue` 로 감싸여 온다.** `as? String` 만 쓰면 조용히
/// nil 이 되고 "화면에 텍스트가 없다" 는 잘못된 결론이 나온다.
/// AXValueType 원시값: 2 = double, 3 = string (macOS AXValue.h)
func axString(_ v: CFTypeRef) -> String {
    if let s = v as? String { return s }
    if CFGetTypeID(v) == AXValueGetTypeID() {
        let av = v as! AXValue
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

/// 사람이 읽는 문자열 후보를 우선순위대로 모은다.
///
/// **하나만 고르면 놓친다** — SwiftUI 는 같은 텍스트를 title/description/value
/// 중 어디에 두는지가 일관되지 않는다. 다 모아서 "무엇이 보인다" 의 근거를 넓힌다.
func labels(_ e: AXUIElement) -> [String] {
    var out: [String] = []
    for key in [AXKey.title, AXKey.value, AXKey.desc] {
        if let v = copyAttr(e, key) {
            let s = axString(v).trimmingCharacters(in: .whitespacesAndNewlines)
            if !s.isEmpty && !out.contains(s) { out.append(s) }
        }
    }
    return out
}

func dump(_ e: AXUIElement, depth: Int, maxDepth: Int, into out: inout [String]) {
    guard depth <= maxDepth else { return }
    let role = copyAttr(e, AXKey.role) as? String ?? "?"
    let sub = copyAttr(e, AXKey.subrole) as? String ?? ""
    let enabled = (copyAttr(e, AXKey.enabled) as? NSNumber)?.boolValue ?? true

    var line = String(repeating: "  ", count: depth)
    line += sub.isEmpty ? role : "\(role).\(sub)"
    let ls = labels(e)
    if !ls.isEmpty { line += "  “" + ls.joined(separator: " | ") + "”" }
    // **비활성 버튼을 드러낸다.** 화면에 있는데 눌리지 않는 것이 이번에 찾은
    // 실제 결함(실행 버튼이 항상 비활성)이라, 덤프에도 상태를 남긴다.
    if !enabled { line += "  [비활성]" }
    out.append(line)

    let kids = copyAttr(e, AXKey.children) as? [AXUIElement] ?? []
    for k in kids { dump(k, depth: depth + 1, maxDepth: maxDepth, into: &out) }
}

// ── 실행 ──
let args = CommandLine.arguments
guard args.count >= 2, let pid = pid_t(args[1]) else {
    print("사용법: swift Tools/DumpAX.swift <pid> [최대깊이]")
    print("  앱을 --ui-hold 로 띄운 다음: swift Tools/DumpAX.swift <pid>")
    exit(2)
}
let maxDepth = args.count >= 3 ? (Int(args[2]) ?? 24) : 24

// **접근성 권한이 없으면 트리가 비어 나온다.** 그때는 "앱에 텍스트가 없다"가 아니라
// "권한이 없다" 다. 구분하지 않으면 조용히 잘못된 결론을 낸다.
let trusted = AXIsProcessTrusted()
print("══ DroidRelay AX 덤프 ══")
print("접근성 권한: \(trusted ? "있음" : "**없음** — 트리가 비면 권한 문제다**")")
print("pid \(pid)")

if !trusted {
    let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
    _ = AXIsProcessTrustedWithOptions(opts)
    print("권한 요청창을 띄웠다. 허용 후 다시 실행할 것.")
}

let app = AXUIElementCreateApplication(pid)

// **AXWindows 가 비어 있을 수 있다** — 메뉴바 앱(LSUIElement)의 팝오버는
// AX 에 창으로 등록되지 않는 경우가 있다(실측). 그러면 "앱에 내용이 없다"가 아니라
// **.windows 경로로 못 찾는 것** 이다 → 다른 경로로 내려간다.
//
// 시도 순서:
//   1. AXWindows              (일반 창)
//   2. AXFocusedWindow        (현재 키)
//   3. AXMainWindow           (메인)
//   4. AXChildren (앱 루트)  (위 셋 다 빈 경우 — 팝오버가 자식에 붙는 경우)
var roots: [AXUIElement] = []
var rootName = ""
let wins = copyAttr(app, AXKey.windows) as? [AXUIElement] ?? []
if !wins.isEmpty {
    roots = wins; rootName = "AXWindows"
} else if let f = copyAttr(app, AXKey.focused) {
    // CFTypeRef 는 조건부 캐스팅이 컴파일 오류가 된다 — ID 로 확인 후 강제 변환
    roots = [f as! AXUIElement]; rootName = "AXFocusedWindow"
} else if let m = copyAttr(app, AXKey.main) {
    roots = [m as! AXUIElement]; rootName = "AXMainWindow"
} else {
    roots = [app]; rootName = "앱 루트(AXChildren)"
}
print("경로: \(rootName) — 루트 \(roots.count)개")
if wins.isEmpty { print("  ※ AXWindows 가 비었다 — 메뉴바 앱 팝오버는 창으로 등록되지 않을 수 있다") }

var out: [String] = []
for (i, w) in roots.enumerated() {
    let title = copyAttr(w, AXKey.title) as? String ?? "(제목 없음)"
    let role = copyAttr(w, AXKey.role) as? String ?? "?"
    out.append("")
    out.append("── 루트 #\(i): \(role) “\(title)”")
    dump(w, depth: 1, maxDepth: maxDepth, into: &out)
}
print(out.joined(separator: "\n"))
