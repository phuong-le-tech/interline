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
    private let microphone = MicrophoneSource()
    private let systemTap = SystemTapSource()
    private var normalizers: [UInt8: StreamNormalizer] = [:]
    private var voiceProcessing = false
    private var listeners: [(AudioObjectPropertyAddress, AudioObjectPropertyListenerBlock)] = []
    private var configurationObserver: NSObjectProtocol?
    private var running = false

    public init(onFrame: @escaping FrameHandler, onDeviceChange: @escaping DeviceHandler, onFailure: @escaping FailureHandler) {
        self.onFrame = onFrame
        self.onDeviceChange = onDeviceChange
        self.onFailure = onFailure
    }

    public var devices: [String: String] { ["mic": microphone.deviceName, "system": systemTap.deviceName] }

    /// Names of the default devices, for `session.started`.
    public static func currentDevices() -> [String: Any] {
        ["mic": MicrophoneSource().deviceName, "system": SystemTapSource().deviceName]
    }

    public func start(voiceProcessing: Bool) throws {
        self.voiceProcessing = voiceProcessing
        running = true
        try startMicrophone()
        do { try startSystem() } catch {
            // The microphone alone is still a recording; the core decides what to tell the user.
            onFailure(StreamID.system, "\(error)")
        }
        watchDefaultDevices()
        configurationObserver = microphone.onConfigurationChange { [weak self] in self?.restartMicrophone() }
    }

    public func stop() {
        running = false
        removeListeners()
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
        configurationObserver = nil
        microphone.stop()
        systemTap.stop()
    }

    /// See SystemTapSource.otherProcessProducesOutput.
    public static func otherProcessProducesOutput() -> Bool { SystemTapSource.otherProcessProducesOutput() }

    // MARK: sources

    private func startMicrophone() throws {
        try microphone.start(voiceProcessing: voiceProcessing) { [weak self] buffer in self?.deliver(buffer, stream: StreamID.mic) }
    }

    private func startSystem() throws {
        try systemTap.start { [weak self] buffer in self?.deliver(buffer, stream: StreamID.system) }
    }

    private func restartMicrophone() {
        guard running else { return }
        microphone.stop()
        normalizers[StreamID.mic] = nil
        do { try startMicrophone() } catch { onFailure(StreamID.mic, "\(error)") }
        onDeviceChange(StreamID.mic, microphone.deviceName)
    }

    /// The tap is bound to the output device it was created for, so a new default output needs a new tap.
    private func restartSystem() {
        guard running else { return }
        systemTap.stop()
        normalizers[StreamID.system] = nil
        do { try startSystem() } catch { onFailure(StreamID.system, "\(error)") }
        onDeviceChange(StreamID.system, systemTap.deviceName)
    }

    private func deliver(_ buffer: AVAudioPCMBuffer, stream: UInt8) {
        deliveryQueue.async { [self] in
            if normalizers[stream] == nil { normalizers[stream] = StreamNormalizer(input: buffer.format) }
            guard let normalizer = normalizers[stream] else {
                onFailure(stream, "unsupported device format \(buffer.format)")
                return
            }
            for frame in normalizer.push(buffer) { onFrame(stream, frame) }
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
