// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "RemoteCapture",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "RemoteCapture", targets: ["RemoteCapture"]),
    ],
    dependencies: [
        .package(url: "https://github.com/SDWebImage/libwebp-Xcode.git", exact: "1.5.0"),
    ],
    targets: [
        .binaryTarget(name: "ONNXRuntimeBinary", path: "Vendor/onnxruntime.xcframework"),
        .target(
            name: "RawRefineryRuntime",
            dependencies: ["ONNXRuntimeBinary"],
            path: "Sources/RawRefineryRuntime",
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedLibrary("c++"),
                .linkedLibrary("z"),
                .linkedFramework("CoreML"),
                .linkedFramework("Accelerate"),
            ]
        ),
        .target(
            name: "RemoteCapture",
            dependencies: ["RawRefineryRuntime", .product(name: "libwebp", package: "libwebp-Xcode")],
            resources: [.process("Resources")]
        ),
        .testTarget(
            name: "RemoteCaptureTests",
            dependencies: ["RemoteCapture"]
        ),
    ]
)
