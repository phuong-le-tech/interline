import AVFoundation
import CoreAudio
import Foundation

/// System audio through a Core Audio process tap on a private aggregate device (macOS 14.2+).
/// UNVERIFIED until the human sitting: the code compiles and follows Apple's documented sequence, but the TCC
/// prompt, the attribution of the permission to the parent process and real audio have not been observed.
final class SystemTapSource {
    private let queue = DispatchQueue(label: "capture.system-tap", qos: .userInteractive)
    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private var aggregateID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?

    var deviceName: String { AudioProperty.defaultDevice(input: false).map(AudioProperty.deviceName) ?? "System output" }

    func start(_ handler: @escaping (AVAudioPCMBuffer) -> Void) throws {
        guard let output = AudioProperty.defaultDevice(input: false),
              let outputUID = AudioProperty.string(output, kAudioDevicePropertyDeviceUID) else {
            throw CaptureDeviceError.unavailable("no default output device to tap")
        }
        // An empty exclusion list taps every process; the stereo mixdown is what the STT needs.
        let description = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
        description.name = "Interline system audio"
        description.isPrivate = true
        description.muteBehavior = .unmuted
        try check(AudioHardwareCreateProcessTap(description, &tapID), "AudioHardwareCreateProcessTap")

        let aggregate: [String: Any] = [
            kAudioAggregateDeviceNameKey: "Interline tap",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: outputUID]],
            kAudioAggregateDeviceTapListKey: [[
                kAudioSubTapDriftCompensationKey: true,
                kAudioSubTapUIDKey: description.uuid.uuidString,
            ]],
        ]
        try check(AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &aggregateID), "AudioHardwareCreateAggregateDevice")

        var format = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        var formatAddress = AudioProperty.address(kAudioTapPropertyFormat)
        try check(AudioObjectGetPropertyData(tapID, &formatAddress, 0, nil, &size, &format), "read tap format")
        guard let tapFormat = AVAudioFormat(streamDescription: &format) else {
            throw CaptureDeviceError.unavailable("the tap reports an unsupported audio format")
        }
        try check(AudioDeviceCreateIOProcIDWithBlock(&procID, aggregateID, queue) { _, input, _, _, _ in
            // The input list is only valid inside this callback, so the samples are copied out before it returns.
            guard let view = AVAudioPCMBuffer(pcmFormat: tapFormat, bufferListNoCopy: input, deallocator: nil),
                  let copy = AVAudioPCMBuffer(pcmFormat: tapFormat, frameCapacity: view.frameLength) else { return }
            copy.frameLength = view.frameLength
            SystemTapSource.copySamples(from: view, to: copy)
            handler(copy)
        }, "AudioDeviceCreateIOProcIDWithBlock")
        try check(AudioDeviceStart(aggregateID, procID), "AudioDeviceStart")
    }

    func stop() {
        if aggregateID != kAudioObjectUnknown, let procID {
            AudioDeviceStop(aggregateID, procID)
            AudioDeviceDestroyIOProcID(aggregateID, procID)
        }
        if aggregateID != kAudioObjectUnknown { AudioHardwareDestroyAggregateDevice(aggregateID) }
        if tapID != kAudioObjectUnknown { AudioHardwareDestroyProcessTap(tapID) }
        procID = nil
        aggregateID = AudioObjectID(kAudioObjectUnknown)
        tapID = AudioObjectID(kAudioObjectUnknown)
    }

    private func check(_ status: OSStatus, _ call: String) throws {
        guard status == noErr else {
            stop()
            throw CaptureDeviceError.coreAudio(call, status)
        }
    }

    private static func copySamples(from source: AVAudioPCMBuffer, to destination: AVAudioPCMBuffer) {
        let from = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: source.audioBufferList))
        let to = UnsafeMutableAudioBufferListPointer(destination.mutableAudioBufferList)
        for index in 0..<min(from.count, to.count) {
            guard let src = from[index].mData, let dst = to[index].mData else { continue }
            memcpy(dst, src, Int(min(from[index].mDataByteSize, to[index].mDataByteSize)))
        }
    }

    /// True when another process is playing audio right now: silence from the tap is then suspicious (ADR-004).
    static func otherProcessProducesOutput() -> Bool {
        var address = AudioProperty.address(kAudioHardwarePropertyProcessObjectList)
        var size: UInt32 = 0
        let system = AudioObjectID(kAudioObjectSystemObject)
        guard AudioObjectGetPropertyDataSize(system, &address, 0, nil, &size) == noErr, size > 0 else { return false }
        var processes = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(system, &address, 0, nil, &size, &processes) == noErr else { return false }
        let own = ProcessInfo.processInfo.processIdentifier
        return processes.contains { process in
            let pid = AudioProperty.get(process, kAudioProcessPropertyPID, as: pid_t.self, default: 0)
            let running = AudioProperty.get(process, kAudioProcessPropertyIsRunningOutput, as: UInt32.self, default: 0)
            return pid != own && (running ?? 0) != 0
        }
    }
}
