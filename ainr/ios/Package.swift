// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "SCUNetDenoiser",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "SCUNetDenoiser", targets: ["SCUNetDenoiser"]),
    ],
    targets: [
        .target(
            name: "SCUNetDenoiser",
            dependencies: [],
            resources: [
                .copy("Resources/Models"),
                .copy("Resources/Legal"),
                .copy("Resources/Samples"),
            ]
        ),
    ]
)
