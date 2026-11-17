import AVFoundation
import CaptureCore
import CoreAudio
import Foundation

/// Both live sources behind one start/stop: normalises their audio to protocol frames, follows the default
/// devices and reports changes. Frames are delivered on `deliveryQueue`, never on a Core Audio thread.
/// UNVERIFIED on real hardware until the human sitting (docs/runbooks/M1-EXIT.md).
public final class LiveCapture {
    public typealias FrameHandler = (_ stream: UInt8, _ pcm: [Int16]) -> Void
    public typealias DeviceHandler = (_ stream: UInt8, _ deviceName: String) -> Void
    public typealias FailureHandler = (_ stream: UInt8, _ reason: String) -> Void

    private let deliveryQueue = DispatchQueue(label: "capture.live.delivery", qos: .userInteractive)
    private let onFrame: FrameHandler
    private let onDeviceChange: DeviceHandler
    private let onFailure: FailureHandler
    private let diagnostics: CaptureDiagnostics?
    private let microphone = MicrophoneSource()
    private let systemTap = SystemTapSource()
    private var normalizers: [UInt8: StreamNormalizer] = [:]
    private var voiceProcessing = false
    private var listeners: [(AudioObjectPropertyAddress, AudioObjectPropertyListenerBlock)] = []
    private var configurationObserver: NSObjectProtocol?
    private var coordinator: ConfigurationRestartCoordinator?

    public init(onFrame: @escaping FrameHandler, onDeviceChange: @escaping DeviceHandler, onFailure: @escaping FailureHandler,
                diagnostics: CaptureDiagnostics? = nil) {
        self.onFrame = onFrame
        self.onDeviceChange = onDeviceChange
        self.onFailure = onFailure
        self.diagnostics = diagnostics
    }

    public var devices: [String: String] { ["mic": microphone.deviceName, "system": systemTap.deviceName] }

    /// Names of the default devices, for `session.started`.
    public static func currentDevices() -> [String: Any] {
        ["mic": MicrophoneSource().deviceName, "system": SystemTapSource().deviceName]
    }

    public func start(voiceProcessing: Bool) throws {
        self.voiceProcessing = voiceProcessing
        wireDiagnostics()
        let source = RestartSourceAdapter(capture: self)
        let clock = DiagnosticsClock()
        coordinator = ConfigurationRestartCoordinator(
            source: source,
            clock: { clock.nowMS() },
            onFailure: { [weak self] stream, reason in self?.onFailure(stream, reason) },
            onDeviceChanged: { [weak self] stream in
                guard let self else { return }
                let name = stream == StreamID.mic ? self.microphone.deviceName : self.systemTap.deviceName
                self.onDeviceChange(stream, name)
            })
        try coordinator?.start(voiceProcessing: voiceProcessing)
        watchDefaultDevices()
        configurationObserver = microphone.onConfigurationChange { [weak self] in self?.coordinator?.configurationChanged() }
    }

    public func stop() {
        coordinator?.stop()
        coordinator = nil
        removeListeners()
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
        configurationObserver = nil
    }

    /// See SystemTapSource.otherProcessProducesOutput.
    public static func otherProcessProducesOutput() -> Bool { SystemTapSource.otherProcessProducesOutput() }

    // MARK: sources

    fileprivate func startMicrophone(voiceProcessing: Bool) throws {
        try microphone.start(voiceProcessing: voiceProcessing) { [weak self] buffer in self?.deliver(buffer, stream: StreamID.mic) }
    }

    fileprivate func stopMicrophone() {
        normalizers[StreamID.mic] = nil
        microphone.stop()
    }

    fileprivate func startSystemTap() throws {
        try systemTap.start { [weak self] buffer in self?.deliver(buffer, stream: StreamID.system) }
    }

    fileprivate func stopSystemTap() {
        normalizers[StreamID.system] = nil
        systemTap.stop()
    }

    fileprivate func setOtherAudioDucking(_ configuration: OtherAudioDucking) {
        microphone.setOtherAudioDucking(configuration)
    }

    /// The tap is bound to the output device it was created for, so a new default output needs a new tap.
    private func restartSystem() {
        stopSystemTap()
        do { try startSystemTap() } catch { onFailure(StreamID.system, "\(error)") }
        onDeviceChange(StreamID.system, systemTap.deviceName)
    }

    /// A new default input device needs a new microphone tap.
    private func restartMicrophone() {
        stopMicrophone()
        do { try startMicrophone(voiceProcessing: voiceProcessing) } catch { onFailure(StreamID.mic, "\(error)") }
        onDeviceChange(StreamID.mic, microphone.deviceName)
    }

    private func deliver(_ buffer: AVAudioPCMBuffer, stream: UInt8) {
        diagnostics?.observe(stream: stream, frames: Int(buffer.frameLength), peak: peakLevel(of: buffer))
        deliveryQueue.async { [self] in
            if normalizers[stream] == nil { normalizers[stream] = StreamNormalizer(input: buffer.format) }
            guard let normalizer = normalizers[stream] else {
                onFailure(stream, "unsupported device format \(buffer.format)")
                return
            }
            for frame in normalizer.push(buffer) { onFrame(stream, frame) }
        }
    }

    private func peakLevel(of buffer: AVAudioPCMBuffer) -> Double {
        guard buffer.frameLength > 0 else { return 0 }
        if let data = buffer.floatChannelData {
            var peak: Float = 0
            for channel in 0..<Int(buffer.format.channelCount) {
                let samples = data[channel]
                for frame in 0..<Int(buffer.frameLength) {
                    peak = max(peak, abs(samples[frame]))
                }
            }
            return Double(peak)
        }
        if let data = buffer.int16ChannelData {
            var peak: Int16 = 0
            for channel in 0..<Int(buffer.format.channelCount) {
                let samples = data[channel]
                for frame in 0..<Int(buffer.frameLength) {
                    peak = max(peak, abs(samples[frame]))
                }
            }
            return Double(peak) / 32768.0
        }
        return 0
    }

    private func wireDiagnostics() {
        systemTap.onTapCreated = { [weak self] id, device, format, status in
            self?.diagnostics?.tapCreated(id: id, device: device, format: format, status: status)
        }
        systemTap.onTapDestroyed = { [weak self] id, device, format, status in
            self?.diagnostics?.tapDestroyed(id: id, device: device, format: format, status: status)
        }
        systemTap.onAggregateCreated = { [weak self] id, device, format, status in
            self?.diagnostics?.aggregateCreated(id: id, device: device, format: format, status: status)
        }
        systemTap.onAggregateDestroyed = { [weak self] id, device, format, status in
            self?.diagnostics?.aggregateDestroyed(id: id, device: device, format: format, status: status)
        }
    }

    // MARK: device listeners

    private func watchDefaultDevices() {
        listen(kAudioHardwarePropertyDefaultInputDevice) { [weak self] in self?.restartMicrophone() }
        listen(kAudioHardwarePropertyDefaultOutputDevice) { [weak self] in self?.restartSystem() }
    }

    private func listen(_ selector: AudioObjectPropertySelector, _ action: @escaping () -> Void) {
        var address = AudioProperty.address(selector)
        let block: AudioObjectPropertyListenerBlock = { _, _ in action() }
        if AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, deliveryQueue, block) == noErr {
            listeners.append((address, block))
        }
    }

    private func removeListeners() {
        for (address, block) in listeners {
            var mutable = address
            AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &mutable, deliveryQueue, block)
        }
        listeners.removeAll()
    }
}

// MARK: - Adapter

/// Bridges `LiveCapture` to the restart coordinator's source protocol so the coordinator stays hardware-free.
private final class RestartSourceAdapter: ConfigurationRestartSource {
    private weak var capture: LiveCapture?

    init(capture: LiveCapture) { self.capture = capture }

    func startMicrophone(voiceProcessing: Bool) throws {
        try capture?.startMicrophone(voiceProcessing: voiceProcessing)
    }

    func stopMicrophone() {
        capture?.stopMicrophone()
    }

    func startSystemTap() throws {
        try capture?.startSystemTap()
    }

    func stopSystemTap() {
        capture?.stopSystemTap()
    }

    func setOtherAudioDucking(_ configuration: OtherAudioDucking) {
        capture?.setOtherAudioDucking(configuration)
    }
}

// MARK: - Clock

/// Monotonic millisecond clock for diagnostics; the self-test injects a fake one.
private final class DiagnosticsClock {
    private let start = DispatchTime.now().uptimeNanoseconds

    func nowMS() -> Int {
        Int((DispatchTime.now().uptimeNanoseconds - start) / 1_000_000)
    }
}
