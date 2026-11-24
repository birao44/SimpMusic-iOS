// swift-tools-version: 6.0

import PackageDescription

// xtool project for the SimpMusic iOS host app.
//
// The whole UI lives in the Kotlin Multiplatform `ComposeApp` dynamic framework built
// by CI (.github/workflows/ios-framework.yml) and fetched into Frameworks/ by
// scripts/ios/fetch-framework.sh. xtool embeds and signs the dynamic binaryTarget.
//
// iOS 18 minimum: the Swift toolchain xtool uses links SwiftUICore, which only
// exists on iOS 18+.
let package = Package(
    name: "SimpMusic",
    platforms: [
        .iOS(.v18),
    ],
    products: [
        // xtool: exactly one library product, representing the main app.
        .library(
            name: "SimpMusic",
            targets: ["SimpMusic"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "ComposeApp",
            path: "Frameworks/ComposeApp.xcframework"
        ),
        .target(
            name: "SimpMusic",
            dependencies: ["ComposeApp"]
        ),
    ]
)
