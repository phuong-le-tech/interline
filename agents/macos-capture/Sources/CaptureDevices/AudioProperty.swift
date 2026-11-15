import CoreAudio
import Foundation

/// Thin helpers over the CoreAudio property API.
enum AudioProperty {
    static func address(_ selector: AudioObjectPropertySelector,
                        scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    static func get<T>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, as type: T.Type, default value: T) -> T? {
        var address = address(selector)
        var size = UInt32(MemoryLayout<T>.size)
        var result = value
        let status = AudioObjectGetPropertyData(object, &address, 0, nil, &size, &result)
        return status == noErr ? result : nil
    }

    static func string(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
        var address = address(selector)
        var size = UInt32(MemoryLayout<CFString?>.size)
        var value: Unmanaged<CFString>?
        let status = withUnsafeMutablePointer(to: &value) {
            AudioObjectGetPropertyData(object, &address, 0, nil, &size, $0)
        }
        return status == noErr ? value?.takeRetainedValue() as String? : nil
    }

    static func defaultDevice(input: Bool) -> AudioObjectID? {
        let selector = input ? kAudioHardwarePropertyDefaultInputDevice : kAudioHardwarePropertyDefaultOutputDevice
        guard let id = get(AudioObjectID(kAudioObjectSystemObject), selector, as: AudioObjectID.self,
                           default: AudioObjectID(kAudioObjectUnknown)), id != kAudioObjectUnknown else { return nil }
        return id
    }

    static func deviceName(_ device: AudioObjectID) -> String {
        string(device, kAudioObjectPropertyName) ?? "Unknown device"
    }
}

enum CaptureDeviceError: Error, CustomStringConvertible {
    case coreAudio(String, OSStatus)
    case unavailable(String)

    var description: String {
        switch self {
        case let .coreAudio(call, status): return "\(call) failed with OSStatus \(status)"
        case let .unavailable(reason): return reason
        }
    }
}
