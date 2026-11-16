import CaptureCore
import Foundation

/// How the microphone path ducks other audio while voice processing is on.
/// Mirrors `AVAudioVoiceProcessingOtherAudioDuckingConfiguration` without an audio device, so the restart
/// coordinator can be driven and asserted in `CaptureSelfTest`; the microphone adapter maps it onto the
/// input node's `voiceProcessingOtherAudioDuckingConfiguration`.
public struct OtherAudioDucking: Equatable, Sendable {
    /// Mirrors `AVAudioVoiceProcessingOtherAudioDuckingLevel`; `min` ducks the least.
    public enum Level: String, Equatable, Sendable {
        case min, mid, max
    }

    public let advancedDuckingEnabled: Bool
    public let level: Level

    public init(advancedDuckingEnabled: Bool, level: Level) {
        self.advancedDuckingEnabled = advancedDuckingEnabled
        self.level = level
    }

    /// Advanced ducking off and the minimum level: voice processing must not take the other audio away.
    public static let disabled = OtherAudioDucking(advancedDuckingEnabled: false, level: .min)
}

/// One capture pair (microphone and process tap) that the coordinator can start, restart and rebuild.
/// `LiveCapture` drives the real devices through an adapter; `CaptureSelfTest` drives a fake, so the
/// restart paths are exercised with no hardware and no TCC prompt.
public protocol ConfigurationRestartSource: AnyObject {
    /// Starts (or restarts) the microphone; `voiceProcessing` is the engine's voice processing switch.
    func startMicrophone(voiceProcessing: Bool) throws
    func stopMicrophone()
    /// Creates the process tap and its private aggregate device; throws when either creation fails.
    func startSystemTap() throws
    func stopSystemTap()
    /// Requests this other-audio ducking configuration from the microphone path.
    func setOtherAudioDucking(_ configuration: OtherAudioDucking)
}

/// Serialises the microphone restart and the process tap rebuild that follow an
/// `AVAudioEngineConfigurationChange`, so a configuration change (or a burst of them) cannot leave the
/// system stream dead and cannot start a restart loop.
/// Hardware-free by construction: the pair, the clock and the handlers are injected, so `CaptureSelfTest`
/// runs the real coordinator in milliseconds and never sleeps.
public final class ConfigurationRestartCoordinator {
    /// At most one restart-and-rebuild pair per window. The mic restart itself fires the notification again.
    public static let coalescingWindowMS = 500

    private let source: ConfigurationRestartSource
    private let clock: () -> Int
    private let onFailure: (UInt8, String) -> Void
    private let onDeviceChanged: (UInt8) -> Void
    private var voiceProcessing = false
    private var running = false
    private var lastRebuildMS: Int?

    /// `clock` is a monotonic millisecond clock; `onFailure` mirrors `LiveCapture.onFailure`;
    /// `onDeviceChanged` is called after each successful restart so the agent can send `device.changed`.
    public init(source: ConfigurationRestartSource,
                clock: @escaping () -> Int,
                onFailure: @escaping (UInt8, String) -> Void,
                onDeviceChanged: @escaping (UInt8) -> Void = { _ in }) {
        self.source = source
        self.clock = clock
        self.onFailure = onFailure
        self.onDeviceChanged = onDeviceChanged
    }

    public var isRunning: Bool { running }

    /// First start: ducking configuration first when voice processing is on, then the mic, then the tap.
    public func start(voiceProcessing: Bool) throws {
        // TODO(voice-processing tap fix): code-writer implements the start sequence.
    }

    /// `AVAudioEngineConfigurationChange`: restart the mic, then rebuild the tap, at most once per window.
    public func configurationChanged() {
        // TODO(voice-processing tap fix): code-writer implements the coalesced restart.
    }

    /// Stops both sources; later configuration changes are ignored.
    public func stop() {
        // TODO(voice-processing tap fix): code-writer implements the stop.
    }
}
