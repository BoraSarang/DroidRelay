#!/usr/bin/env swift
// 앱의 접근성(AX) 트리에서 요소를 찾아 **실제로 누른다**.
//
// ## 왜 이게 필요한가
//
// "코드에 버튼이 있다" 는 **증거가 아니다.** PR #28 의 UI 는 전부 컴파일됐고
// 전부 실패했다(시트 안 닫힘, 입력 칸 안 채워짐, 실행 버튼 영영 비활성).
// 유일하게 믿을 수 있는 증거는 **화면에서 누르고 서버 상태를 다시 조회**하는 것이다.
//
// 이 스크립트는 그 "누르는" 부분을 자동화한다. 손으로 못 하는 일이 아니다 —
// **눈으로 확인하지 못할 때** 대신 클릭하고, 그 결과를 curl 로 대조한다.
//
// ## 사용법
//   swift Tools/Press.swift <pid> <버튼라벨>
//   swift Tools/Press.swift <pid> --popup <현재선택값> <고를값>
//
// ## 예
//   swift Tools/Press.swift 1234 재개
//   swift Tools/Press.swift 1234 --popup 무제한 1MB/s

import Foundation
import ApplicationServices

func copyAttr(_ e: AXUIElement, _ a: String) -> CFTypeRef? {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(e, a as CFString, &v) == .success else { return nil }
    return v
}

/// 사람이 읽는 문자열 후보 — SwiftUI 는 title/description/value 중 어디에 두는지가
/// 일관되지 않는다. 셋 다 모아야 "보이는 글자"를 놓치지 않는다.
func textOf(_ e: AXUIElement) -> [String] {
    var out: [String] = []
    for k in ["AXTitle", "AXDescription", "AXValue"] {
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
    // 메뉴바 앱(LSUIElement)의 팝오버는 AXWindows 에 **없다**(실측: 창 0개).
    // → AXWindows 가 비면 앱 루트에서 내려간다.
    if let w = copyAttr(app, "AXWindows") as? [AXUIElement], !w.isEmpty { return w }
    return [app]
}

func find(_ e: AXUIElement, role want: String, matching test: (AXUIElement) -> Bool) -> AXUIElement? {
    if role(e) == want, test(e) { return e }
    for k in children(e) {
        if let f = find(k, role: want, matching: test) { return f }
    }
    return nil
}

func press(_ e: AXUIElement) -> AXError {
    AXUIElementPerformAction(e, kAXPressAction as CFString)
}

// ── 실행 ──
// 사용법:
//   swift Tools/Press.swift <pid> <버튼라벨> [--n 3]           3번째 일치 (0부터)
//   swift Tools/Press.swift <pid> --open <행텍스트> <항목>     행 메뉴 열고 항목 선택
//   swift Tools/Press.swift <pid> --in <행텍스트> <버튼라벨>    그 행 안의 버튼
//   swift Tools/Press.swift <pid> --popup <현재값> <고를값> [--n 0]
guard CommandLine.arguments.count >= 3,
      let pid = pid_t(CommandLine.arguments[1]) else {
    print("사용법: swift Tools/Press.swift <pid> <버튼라벨> [--n 3]")
    print("      swift Tools/Press.swift <pid> --in <행텍스트> <버튼라벨>")
    print("      swift Tools/Press.swift <pid> --popup <현재값> <고를값> [--n 0]")
    exit(2)
}
var args = Array(CommandLine.arguments.dropFirst(2))
let app = AXUIElementCreateApplication(pid)

/// **같은 라벨이 여러 개일 때 몇 번째인지** — 목록의 N번째 행을 고르기 위해.
///
/// "앞으로" 버튼이 폴더마다 하나씩 붙어 있으면 라벨만으로는 대상을 못 정한다.
/// 이게 없으면 "항상 첫 번째" 를 눌러 버려 엉뚱한 곳을 검증하게 된다.
var nth = 0
if let i = args.firstIndex(of: "--n"), i + 1 < args.count, let n = Int(args[i + 1]) {
    nth = n
    args.removeSubrange(i...i + 1)
}

// 권한이 없으면 트리가 비어 "버튼을 못 찾았다" 로 나온다.
// 그건 "화면에 없다"가 아니라 "물어볼 수 없다" 다 → 구분해 준다.
guard AXIsProcessTrusted() else {
    print("접근성 권한 없음 — 트리가 비어서 아무것도 못 찾는다")
    exit(3)
}

/// 트리를 훑어 조건에 맞는 요소를 **순서대로 모두** 모은다.
/// `find` 처럼 첫 번째에서 멈추면 "몇 번째였는지" 알 수 없어 검증이 불가능해진다.
func all(_ root: AXUIElement, _ want: String, _ test: (AXUIElement) -> Bool) -> [AXUIElement] {
    var out: [AXUIElement] = []
    if role(root) == want, test(root) { out.append(root) }
    for k in children(root) { out += all(k, want, test) }
    return out
}

/// **요소의 화면 세로 위치(좌표)** — 행 구분에 쓴다.
///
/// `AXValueGetValue` 는 macOS 에서 `Bool` 을 돌려준다(`AXError` 가 아니다).
/// `.success` 와 비교하면 `DispatchTimeoutResult` 로 해석되어 컴파일이 깨진다 →
/// `true` 와 비교한다.
func topY(_ e: AXUIElement) -> CGFloat? {
    guard let p = copyAttr(e, "AXPosition") else { return nil }
    guard CFGetTypeID(p) == AXValueGetTypeID() else { return nil }
    var pt = CGPoint.zero
    guard AXValueGetValue(p as! AXValue, .cgPoint, &pt) else { return nil }
    return CGFloat(pt.y)
}

if args[0] == "--item" && args.count >= 2 {
    // **열린 메뉴의 항목을 누른다.**
    //
    // 앱이 열어 둔 메뉴는 앱 트리 안에 `AXMenuItem` 으로 나타난다(실측).
    // 그래서 `AXPopUpButton` 을 먼저 누를 필요 없이 항목만 바로 찍으면 된다.
    let want = args[1]
    let items = roots(app).flatMap { all($0, "AXMenuItem", { textOf($0).contains(want) }) }
    guard nth < items.count else {
        print("메뉴 항목을 못 찾았다: \(want) (일치 \(items.count)개)"); exit(1)
    }
    let r = press(items[nth])
    print("항목 \(want) → \(r == .success ? "성공" : "실패 \(r.rawValue)")")
    exit(r == .success ? 0 : 1)
}

/// **특정 행과 같은 세로 띠에 있는 버튼**을 찾는다.
///
/// ## 왜 좌표를 쓰는가
///
/// 처음엔 "그룹 안에 이름과 버튼이 같이 있으면" 로 골랐다. 그러나
/// **팝오버 전체가 하나의 AXGroup** 이라 `__agent_test__` 행을 찾았는데도
/// **맨 위(`AI`) 행의 '앞으로' 를 눌렀다.** → 엉뚱한 폴더를 열었다.
///
/// 같은 라벨이 모든 행에 반복되므로 **화면상의 세로 위치로 행을 가른다** 것이
/// 유일하게 확실하다. 목록은 세로로 정렬되어 있으므로 좌표는 모호하지 않다.
func buttonInRow(rowText: String, label: String) -> AXUIElement? {
    let texts = roots(app).flatMap { all($0, "AXStaticText", { _ in true }) }
    guard let anchor = texts.first(where: { t in
        textOf(t).contains(where: { $0.contains(rowText) }) && topY(t) != nil
    }), let y = topY(anchor) else { return nil }
    // `AXMenuButton`(SwiftUI `Menu`) 도 함께 본다 — `AXButton` 만 보면
    // 행 조작 메뉴를 "없는 버튼" 으로 잘못 판단한다.
    let btns = roots(app).flatMap { r in
        all(r, "AXButton", { e in textOf(e).contains(where: { $0.contains(label) }) })
        + all(r, "AXMenuButton", { e in textOf(e).contains(where: { $0.contains(label) }) })
    }
    var best: AXUIElement?
    var bestDy: CGFloat = .greatestFiniteMagnitude
    for b in btns {
        guard let by = topY(b) else { continue }
        let dy = abs(by - y)
        // **같은 행으로 볼 수 있는 거리** — 12pt 이내. 행 높이는 30px 안팎이라
        // 충분히 좁고, 인접 행(30~50pt 이상)과는 확실히 갈린다.
        if dy <= 12, dy < bestDy { best = b; bestDy = dy }
    }
    return best
}

if args[0] == "--open" && args.count >= 3 {
    // **행 메뉴를 열고 항목을 누른다 — 한 프로세스에서.**
    //
    // ## 왜 한 번에 해야 하는가
    //
    // 두 번 따로 부르면 **셸이 그 사이 새 swift 를 컴파일**한다(1~2초).
    // 그 사이 **메뉴가 스스로 닫힌다.** → "항목을 못 찾았다" 는
    // **탐색 실패가 아니라 타이밍 문제** 인데, 실패로 보고하면 원인이 다르다.
    //
    // 한 프로세스 안에서 누르면 지연이 없어서 메뉴가 살아 있다.
    //
    // ## 왜 한 번에 해야 하는가
    //
    // 두 번 따로 부르면 **셸이 그 사이 새 swift 를 컴파일**한다(1~2초).
    // 그 동안 **메뉴가 스스로 닫힌다.** → "항목을 못 찾았다" 는
    // **탐색 실패가 아니라 타이밍 문제** 인데, 실패로 보고하면 원인�� 다르다.
    //
    // 한 프로세스 안에서 누르면 지연이 없어서 메뉴가 살아 있다.
    let rowText = args[1], itemLabel = args[2]
    guard let menuBtn = buttonInRow(rowText: rowText, label: "더 보기")
            ?? buttonInRow(rowText: rowText, label: "조작") else {
        print("행 '\(rowText)' 의 메뉴 버튼을 못 찾았다"); exit(1)
    }
    let r1 = press(menuBtn)
    guard r1 == .success else { print("메뉴 열기 실패 \(r1.rawValue)"); exit(1) }
    usleep(400_000)
    let items = roots(app).flatMap { all($0, "AXMenuItem", { textOf($0).contains(itemLabel) }) }
    guard let item = items.first else {
        print("메뉴 항목을 못 찾았다: \(itemLabel) (열린 메뉴: \(roots(app).flatMap { all($0, "AXMenuItem", { _ in true }) }.map { textOf($0) })")
        exit(1)
    }
    let r2 = press(item)
    print("메뉴 \(rowText) → \(itemLabel): \(r2 == .success ? "성공" : "실패 \(r2.rawValue)")")
    exit(r2 == .success ? 0 : 1)
}

if args[0] == "--in" && args.count >= 3 {
    let rowText = args[1], btnLabel = args[2]
    guard let b = buttonInRow(rowText: rowText, label: btnLabel) else {
        print("행 '\(rowText)' 의 '\(btnLabel)' 버튼을 못 찾았다"); exit(1)
    }
    let r = press(b)
    print("누름: \(rowText) 행의 \(btnLabel) → \(r == .success ? "성공" : "실패 \(r.rawValue)")")
    exit(r == .success ? 0 : 1)
}

if args[0] == "--popup" && args.count >= 3 {
    let want = args[2]
    // 1) 메뉴 버튼을 누른다 (메뉴가 열린다)
    //
    // **`AXPopUpButton` 과 `AXMenuButton` 둘 다 찾아야 한다.**
    // SwiftUI `Picker(.menu)` 는 전자, `Menu` 는 후자로 노출된다(실측).
    // 한쪽만 보면 "메뉴 버튼이 없다" 는 잘못된 결론이 나온다.
    let btns = roots(app).flatMap { r in
        all(r, "AXPopUpButton", { textOf($0).contains(where: { $0 == args[1] }) })
        + all(r, "AXMenuButton", { textOf($0).contains(where: { $0 == args[1] }) })
    }
    guard nth < btns.count else {
        print("팝업 버튼을 못 찾았다: \(args[1]) (일치 \(btns.count)개, --n \(nth))"); exit(1)
    }
    let r1 = press(btns[nth])
    print("팝업 열기 \(args[1])#\(nth) → \(r1 == .success ? "성공" : "실패 \(r1.rawValue)")")
    guard r1 == .success else { exit(1) }

    // 2) 열린 메뉴에서 고를 항목을 누른다.
    //    **메뉴는 별도 프로세스(system)** 에서 뜨므로 앱 트리에 없다.
    //    → 전역에서 찾아야 한다. 이걸 안 하면 "메뉴가 안 떴다" 는
    //    잘못된 결론이 나온다(첫 시도에서 실제로 그랬다).
    usleep(300_000)
    var best: AXUIElement?
    for pid2 in candidates(containing: "SystemUIServer")
        + candidates(containing: "ControlCenter")
        + candidates(containing: "WindowServer") + [pid] {
        let a = AXUIElementCreateApplication(pid2)
        if let f = all(a, "AXMenuItem", { textOf($0).contains(want) }).first { best = f; break }
    }
    guard let item = best else { print("메뉴 항목을 못 찾았다: \(want)"); exit(1) }
    let r2 = press(item)
    print("항목 선택 \(want) → \(r2 == .success ? "성공" : "실패 \(r2.rawValue)")")
    exit(r2 == .success ? 0 : 1)
}

// 일반 버튼
let btns = roots(app).flatMap {
    all($0, "AXButton", { textOf($0).contains(where: { $0 == args[0] || $0.contains(args[0]) }) })
}
guard nth < btns.count else {
    print("버튼을 못 찾았다: \(args[0]) (일치 \(btns.count)개, --n \(nth))")
    exit(1)
}
let r = press(btns[nth])
print("누름: \(args[0])#\(nth) → \(r == .success ? "성공" : "실패 \(r.rawValue)")")
exit(r == .success ? 0 : 1)

/// 이름에 문자열이 들어간 프로세스들의 pid
func candidates(containing s: String) -> [pid_t] {
    var out: [pid_t] = []
    var count = proc_listpids(UInt32(PROC_ALL_PIDS), 0, nil, 0)
    var pids = [pid_t](repeating: 0, count: Int(count) / MemoryLayout<pid_t>.size + 16)
    count = proc_listpids(UInt32(PROC_ALL_PIDS), 0, &pids, Int32(count))
    for p in pids where p > 0 {
        var buf = [CChar](repeating: 0, count: 1024)
        if proc_name(p, &buf, 1024) > 0 {
            let n = String(cString: buf)
            if n.contains(s) { out.append(p) }
        }
    }
    return out
}
