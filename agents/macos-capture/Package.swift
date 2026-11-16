// swift-tools-version: 6.0
import PackageDescription

// whisper.cpp v1.9.5 (release asset of build tag b5454), prebuilt with Metal and the shader library embedded.
// SwiftPM's own downloader can stall on some networks; INTERLINE_WHISPER_XCFRAMEWORK points at a copy of the same
// zip fetched with curl (verify its sha256 against the checksum below).
let whisperBinary: Target = {
    if let local = Context.environment["INTERLINE_WHISPER_XCFRAMEWORK"], !local.isEmpty {
        return .binaryTarget(name: "whisper", path: local)
    }
    return .binaryTarget(
        name: "whisper",
        url: "https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-b5454-xcframework.zip",
        checksum: "e57f8c48933000acabc13bb913fbe82805d483a2deff692cc6738836bd92393b")
}()

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
        whisperBinary,
        .target(name: "WhisperSTT", dependencies: ["CaptureCore", "whisper"]),
        .executableTarget(name: "CaptureAgent", dependencies: ["CaptureCore", "CaptureDevices", "WhisperSTT"],
                          // TCC reads the usage strings from the binary itself; a bare executable has no bundle.
                          linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                                                         "-Xlinker", "__info_plist", "-Xlinker", "Resources/Info.plist"])]),
        .executableTarget(name: "CaptureSelfTest", dependencies: ["CaptureCore", "CaptureDevices"],
                          swiftSettings: [.swiftLanguageMode(.v6)])
    ],
    // Language mode 5: the agent shares plain mutable state across its capture and STT threads under locks.
    swiftLanguageModes: [.v5]
)
