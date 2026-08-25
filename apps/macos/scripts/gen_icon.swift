// DroidRelay 앱 아이콘 생성 스크립트 (CoreGraphics + ImageIO)
// usage: swift gen_icon.swift <output.icns 경로>
// 디자인: 다크 네이비 라운드 사각형 + 파랑 신호 아크 + 흰색 다운로드 화살표

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024

func makeContext(_ w: Int, _ h: Int) -> CGContext {
    CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
              space: CGColorSpace(name: CGColorSpace.sRGB)!,
              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
}

func drawIcon(ctx: CGContext) {
    let s = CGFloat(size)
    ctx.clear(CGRect(x: 0, y: 0, width: s, height: s))

    // macOS 마진(약 10%) + 스쿼클 느낌의 코너 반경
    let inset = s * 0.09
    let rect = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let radius = rect.width * 0.225
    let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(path)
    ctx.clip()

    // 배경 그라디언트 (상단 밝은 남색 → 하단 딥 네이비)
    let colors = [
        CGColor(red: 0.16, green: 0.32, blue: 0.72, alpha: 1),
        CGColor(red: 0.05, green: 0.10, blue: 0.24, alpha: 1)
    ] as CFArray
    let grad = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                          colors: colors, locations: [0, 1])!
    ctx.drawLinearGradient(grad, start: CGPoint(x: rect.midX, y: rect.maxY),
                           end: CGPoint(x: rect.midX, y: rect.minY), options: [])

    // 신호 아크 3개 (좌상단 방향, 안테나 발산 느낌)
    let center = CGPoint(x: rect.midX, y: rect.midY - rect.height * 0.05)
    for (i, r) in [rect.width * 0.30, rect.width * 0.42, rect.width * 0.54].enumerated() {
        let alpha = 0.85 - Double(i) * 0.22
        ctx.setStrokeColor(CGColor(red: 0.55, green: 0.75, blue: 1.0, alpha: alpha))
        ctx.setLineWidth(rect.width * 0.045)
        ctx.setLineCap(.round)
        // 위쪽 부채꼴 (60°~120°)
        ctx.addArc(center: center, radius: r,
                   startAngle: .pi * 0.62, endAngle: .pi * 0.38,
                   clockwise: false)
        ctx.strokePath()
    }

    // 중앙 다운로드 화살표
    let white = CGColor(red: 1, green: 1, blue: 1, alpha: 1)
    ctx.setFillColor(white)

    let shaftW = rect.width * 0.13
    let shaftH = rect.height * 0.34
    let headW = rect.width * 0.36
    let headH = rect.height * 0.20
    let cx = rect.midX
    let arrowTop = center.y + rect.height * 0.16
    let arrowBottom = arrowTop + shaftH

    // 축(세로 막대)
    ctx.fill(CGRect(x: cx - shaftW / 2, y: arrowBottom - shaftH, width: shaftW, height: shaftH))
    // 머리(아래 삼각형)
    let head = CGMutablePath()
    head.move(to: CGPoint(x: cx - headW / 2, y: arrowBottom))
    head.addLine(to: CGPoint(x: cx + headW / 2, y: arrowBottom))
    head.addLine(to: CGPoint(x: cx, y: arrowBottom - headH))
    head.closeSubpath()
    ctx.addPath(head)
    ctx.fillPath()

    // 하단 받침선
    let baseH = rect.height * 0.065
    let baseW = rect.width * 0.44
    ctx.fill(CGRect(x: cx - baseW / 2, y: arrowBottom - headH - baseH * 2.2, width: baseW, height: baseH))
}

func writePNG(ctx: CGContext, sizePx: Int, to url: URL) throws {
    let img = ctx.makeImage()!
    let scaled = makeContext(sizePx, sizePx)
    scaled.interpolationQuality = .high
    scaled.draw(img, in: CGRect(x: 0, y: 0, width: sizePx, height: sizePx))
    guard let out = scaled.makeImage(),
          let dest = CGImageDestinationCreateWithURL(url as CFURL,
                                                    UTType.png.identifier as CFString, 1, nil)
    else { fatalError("dest fail") }
    CGImageDestinationAddImage(dest, out, nil)
    CGImageDestinationFinalize(dest)
}

// MARK: - 실행

let args = CommandLine.arguments
guard args.count >= 2 else {
    print("usage: swift gen_icon.swift <output.icns>")
    exit(1)
}
let icnsPath = args[1]

let fm = FileManager.default
let tmp = fm.temporaryDirectory.appendingPathComponent("DroidRelay.iconset")
try? fm.removeItem(at: tmp)
try fm.createDirectory(at: tmp, withIntermediateDirectories: true)

let masterCtx = makeContext(size, size)
drawIcon(ctx: masterCtx)

let variants: [(String, Int)] = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024)
]

for (name, px) in variants {
    try writePNG(ctx: masterCtx, sizePx: px, to: tmp.appendingPathComponent(name))
}

// 기존 icns 제거 후 재생성
if fm.fileExists(atPath: icnsPath) {
    try fm.removeItem(atPath: icnsPath)
}
let proc = Process()
proc.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
proc.arguments = ["-c", "icns", tmp.path, "-o", icnsPath]
try proc.run()
proc.waitUntilExit()

if proc.terminationStatus == 0 {
    print("icns 생성 완료: \(icnsPath)")
} else {
    print("iconutil 실패: \(proc.terminationStatus)")
    exit(proc.terminationStatus)
}
