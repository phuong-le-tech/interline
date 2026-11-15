// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MacOSCapture",
    platforms: [.macOS("14.2")],
    products: [
        .library(name: "CaptureCore", targets: ["CaptureCore"]),
        .library(name: "CaptureDevices", targets: ["CaptureDevices"]),
        .executable(name: "interline-capture-macos", targets: ["CaptureAgent"]),
        .executable(name: "CaptureSelfTest", targets: ["CaptureSelfTest"])
    ],
    targets: [
        .target(name: "CaptureCore"),
        .target(name: "CaptureDevices", dependencies: ["CaptureCore"]),
        .executableTarget(name: "CaptureAgent", dependencies: ["CaptureCore", "CaptureDevices"]),
        .executableTarget(name: "CaptureSelfTest", dependencies: ["CaptureCore", "CaptureDevices"])
    ]
)
