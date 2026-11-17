import AVFoundation
import Foundation

/// Microphone through AVAudioEngine, optionally with Apple's voice processing (echo cancellation).
/// UNVERIFIED until the human sitting: whether voice processing removes the speaker echo is spike (c).
final class MicrophoneSource {
    private static let tapBufferFrames: AVAudioFrameCount = 4096
    private var engine: AVAudioEngine?
    private var pendingDucking: OtherAudioDucking?

    var deviceName: String { AudioProperty.defaultDevice(input: true).map(AudioProperty.deviceName) ?? "Microphone" }

    func start(voiceProcessing: Bool, _ handler: @escaping (AVAudioPCMBuffer) -> Void) throws {
        let engine = AVAudioEngine()
        let input = engine.inputNode
        // Must be set before the format is read: it changes the node's format and ducks other audio.
        try input.setVoiceProcessingEnabled(voiceProcessing)
        applyPendingDucking(to: input)
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw CaptureDeviceError.unavailable("the microphone reports no usable format (permission or device missing)")
        }
        input.installTap(onBus: 0, bufferSize: Self.tapBufferFrames, format: format) { buffer, _ in handler(buffer) }
        engine.prepare()
        try engine.start()
        self.engine = engine
    }

    func stop() {
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        engine = nil
    }

    /// Fires when the engine's configuration changes (a headset plugged in, a sample rate switch).
    func onConfigurationChange(_ action: @escaping () -> Void) -> NSObjectProtocol {
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: nil) { _ in action() }
    }

    /// Configures other-audio ducking for the voice-processing path. Applied immediately if the input node
    /// is live, otherwise remembered for the next start.
    func setOtherAudioDucking(_ configuration: OtherAudioDucking) {
        pendingDucking = configuration
        if let engine {
            applyDucking(configuration, to: engine.inputNode)
        }
    }

    private func applyPendingDucking(to input: AVAudioInputNode) {
        guard let configuration = pendingDucking else { return }
        applyDucking(configuration, to: input)
    }

    private func applyDucking(_ configuration: OtherAudioDucking, to input: AVAudioInputNode) {
        var native = AVAudioVoiceProcessingOtherAudioDuckingConfiguration()
        native.enableAdvancedDucking = ObjCBool(configuration.advancedDuckingEnabled)
        switch configuration.level {
        case .min: native.duckingLevel = .min
        case .mid: native.duckingLevel = .mid
        case .max: native.duckingLevel = .max
        }
        input.voiceProcessingOtherAudioDuckingConfiguration = native
    }
}
