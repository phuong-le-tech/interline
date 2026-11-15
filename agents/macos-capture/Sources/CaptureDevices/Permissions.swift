import AVFoundation
import Foundation

public enum PermissionState: String {
    case granted, denied, restricted, unknown
    case notDetermined = "not_determined"
}

/// Permission status and requests. Microphone goes through AVFoundation. System audio recording has no public
/// preflight API: its state is `unknown` until a tap delivers audio, and a silent tap is detected by the session.
public enum Permissions {
    private static let requestTimeoutSeconds = 60

    public static func microphone() -> PermissionState {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return .granted
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notDetermined
        @unknown default: return .unknown
        }
    }

    /// Shows the system prompt when the state is not determined; blocks until the user answers or the timeout hits.
    public static func requestMicrophone() -> PermissionState {
        guard microphone() == .notDetermined else { return microphone() }
        let answered = DispatchSemaphore(value: 0)
        AVCaptureDevice.requestAccess(for: .audio) { _ in answered.signal() }
        _ = answered.wait(timeout: .now() + .seconds(requestTimeoutSeconds))
        return microphone()
    }

    public static func systemAudio() -> PermissionState { .unknown }

    /// Creating a tap is what makes macOS ask; the answer is observed through the audio itself.
    public static func requestSystemAudio() -> PermissionState {
        let probe = SystemTapSource()
        do { try probe.start { _ in } } catch { return .unknown }
        probe.stop()
        return .unknown
    }
}
