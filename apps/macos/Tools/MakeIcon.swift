#!/usr/bin/env swift

// DroidRelay 앱 아이콘 생성기.
//
// ## 왜 스크립트로 그리는가
//
// 앱 아이콘이 없으면 DMG 에서 **빈 이름 아이콘**으로 보이고, Dock·Spotlight 에서도
// 구분이 되지 않는다. 에셋에 PNG 를 커밋하는 대신 **SF Symbol 을 즉석에서 그린다** —
//
// 1. 여러 해상도 PNG 를 손으로 맞추는 일이 없다(512/1024 등 전부 같은 벡터)
// 2. **메뉴바 아이콘과 같은 심볼**을 쓰므로 정체성이 일치한다
// 3. 리포에 바이너리를 넣지 않는다
//
// 사용: swift MakeIcon.swift <출력 .iconset 경로>
// 그다음: iconutil -c icns <iconset> -o AppIcon.icns

import AppKit
import Foundation

let outDir = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : FileManager.default.currentDirectoryPath + "/DroidRelay.iconset"

let fm = FileManager.default
try? fm.createDirectory(atPath: outDir, withIntermediateDirectories: true)

/// macOS 아이콘 규격 — 10pt 그리드 기준의 표준 크기들.
let sizes: [(name: String, px: Int)] = [
    ("icon_16x16", 16), ("icon_16x16@2x", 32),
    ("icon_32x32", 32), ("icon_32x32@2x", 64),
    ("icon_128x128", 128), ("icon_128x128@2x", 256),
    ("icon_256x256", 256), ("icon_256x256@2x", 512),
    ("icon_512x512", 512), ("icon_512x512@2x", 1024),
]

/// 둥근 사각형 배경 + 안테나 심볼.
///
/// **배경에 그라데이션을 쓰는 이유** — 단색 배경은 Dock 의 어두운 배경에서 경계가
/// 사라진다. macOS 는 아이콘 뒤에 자동으로 그림자를 넣지만, 그래도 가장자리가
/// 어두운 배경과 붙는다.
func drawIcon(size px: Int) -> Data? {
    let s = CGFloat(px)
    guard let ctx = CGContext(
        data: nil, width: Int(s), height: Int(s), bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }

    // 둥근 사각형 — macOS 아이콘 표준 비율(22.37% 모서리 반경)
    let inset = s * 0.055
    let box = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let radius = box.width * 0.2237
    let path = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(path)
    ctx.clip()

    let cs = CGColorSpaceCreateDeviceRGB()
    let grad = CGGradient(colorsSpace: cs, colors: [
        CGColor(red: 0.16, green: 0.42, blue: 0.85, alpha: 1),
        CGColor(red: 0.09, green: 0.24, blue: 0.58, alpha: 1),
    ] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: s), end: CGPoint(x: s, y: 0), options: [])

    // 안테나 심볼 — 메뉴바 아이콘과 같은 것
    let cfg = NSImage.SymbolConfiguration(pointSize: s * 0.46, weight: .semibold)
    guard let symbol = NSImage(systemSymbolName: "antenna.radiowaves.left.and.right",
                               accessibilityDescription: nil)?
        .withSymbolConfiguration(cfg) else { return nil }
    symbol.isTemplate = true
    // 템플릿 이미지(검정)를 흰색으로 칠한다.
    //
    // **`.sourceAtop` 으로는 안 된다**(실측: 심볼이 검은색으로 나왔다). sourceAtop 은
    // "**이미 있는** 위에만" 그리는데, 새 이미지에 그려진 게 없으므로 아무것도 안 칠해진다.
    // **`.sourceIn` 이 맞다** — "이미 그려진 부분만" 채운다.
    let tinted = NSImage(size: symbol.size, flipped: false) { rect in
        symbol.draw(in: rect)             // 1) 검정 심볼을 먼저 그린다
        NSColor.white.set()
        rect.fill(using: .sourceIn)        // 2) 그려진 부분만 흰색으로
        return true
    }
    // NSImage.draw 는 현재 컨텍스트에 그린다
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    let side = s * 0.46
    tinted.draw(in: CGRect(x: (s - side) / 2, y: (s - side) / 2, width: side, height: side),
                from: .zero, operation: .sourceOver, fraction: 1)
    NSGraphicsContext.restoreGraphicsState()

    guard let img = ctx.makeImage() else { return nil }
    let rep = NSBitmapImageRep(cgImage: img)
    return rep.representation(using: .png, properties: [:])
}

for (name, px) in sizes {
    guard let data = drawIcon(size: px) else {
        FileHandle.standardError.write(Data("아이콘 생성 실패: \(name)\n".utf8))
        exit(1)
    }
    try data.write(to: URL(fileURLWithPath: "\(outDir)/\(name).png"))
}
print("아이콘 생성: \(sizes.count)개 → \(outDir)")
