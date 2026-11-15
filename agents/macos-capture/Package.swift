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
        .executableTarget(name: "CaptureSelfTest", dependencies: ["CaptureCore", "CaptureDevices"],
                          swiftSettings: [.swiftLanguageMode(.v6)])
    ],
    // Language mode 5: the agent shares plain mutable state across its capture and STT threads under locks.
    swiftLanguageModes: [.v5]
)
