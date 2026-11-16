import CoreAudio
import Foundation

/// One-line, greppable diagnostics for the live device path, written to the agent's stderr `log`.
/// Counts and levels only: never samples, never raw PCM.
///
/// The line shapes are part of the contract (the self-test asserts them verbatim):
///
///     capture.tap created id=42 device="MacBook Pro Speakers" format="48000 Hz, 2 ch" status=0
///     capture.tap destroyed id=42 device="MacBook Pro Speakers" format="48000 Hz, 2 ch" status=0
///     capture.aggregate created id=7 device="Interline tap" format="48000 Hz, 2 ch" status=0
///     capture.aggregate destroyed id=7 device="Interline tap" format="48000 Hz, 2 ch" status=-50
///     capture.stats stream=mic window_ms=5000 frames=4800 peak=0.900
///     capture.stats stream=system window_ms=5000 frames=800 peak=0.250
///
/// `status` is the `OSStatus` of the Core Audio call. Stats go out once per stream per window, in stream
/// order (mic then system), for streams that produced frames during that window.
public final class CaptureDiagnostics {
    /// Peak and frame count are reported on this period, per stream.
    public static let statsIntervalMS = 5_000

    private let clock: () -> Int
    private let sink: (String) -> Void
    private let intervalMS: Int

    /// `clock` is a monotonic millisecond clock, injected so the self-test advances time instead of sleeping.
    public init(clock: @escaping () -> Int, sink: @escaping (String) -> Void,
                intervalMS: Int = CaptureDiagnostics.statsIntervalMS) {
        self.clock = clock
        self.sink = sink
        self.intervalMS = intervalMS
    }

    public func tapCreated(id: UInt32, device: String, format: String, status: OSStatus) {
        // TODO(voice-processing tap fix): code-writer formats and emits the line.
    }

    public func tapDestroyed(id: UInt32, device: String, format: String, status: OSStatus) {
        // TODO(voice-processing tap fix): code-writer formats and emits the line.
    }

    public func aggregateCreated(id: UInt32, device: String, format: String, status: OSStatus) {
        // TODO(voice-processing tap fix): code-writer formats and emits the line.
    }

    public func aggregateDestroyed(id: UInt32, device: String, format: String, status: OSStatus) {
        // TODO(voice-processing tap fix): code-writer formats and emits the line.
    }

    /// Counts frames and tracks the peak for one delivered buffer. Emits the per-stream stats line when the
    /// stats interval has elapsed. Callers pass levels, never samples.
    public func observe(stream: UInt8, frames: Int, peak: Double) {
        // TODO(voice-processing tap fix): code-writer accumulates and flushes every statsIntervalMS.
    }
}
