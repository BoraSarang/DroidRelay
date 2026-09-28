// swift-tools-version: 6.0
import PackageDescription

// 순수 로직(Core)과 UI(App)를 분리한다.
// Core 는 테스트 가능하고 UI 는 AppKit 위에서만 의미가 있다 — 분리해야
// "탐색 계산이 옳은가" 를 브라우저/OS 없이 검증할 수 있다.
let package = Package(
    name: "DroidRelayMac",
    platforms: [.macOS(.v14)],
    targets: [
        .target(
            name: "DroidRelayCore",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .executableTarget(
            name: "DroidRelayMac",
            dependencies: ["DroidRelayCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "DroidRelayMacTests",
            dependencies: ["DroidRelayCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
