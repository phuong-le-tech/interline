import Foundation

public struct CaptureWarning: Equatable {
    public let code: String
    public let severity: String
    public init(code: String, severity: String) { self.code = code; self.severity = severity }
}

/// Flow control towards the core: at most `window` unacknowledged frames in flight per stream.
/// Every frame is spooled to disk before it is offered for sending, so a full window never loses audio;
/// overflow is read back from the spool, in order, as acknowledgements open slots.
public final class DeliveryWindow {
    /// A backlog above this many frames (60 s) raises a `backpressure` warning, once per episode.
    static let backpressureFrames = 600

    private struct StreamState {
        var lastSent: Int64 = -1
        var lastAcked: Int64 = -1
        /// Sequence and capture host time of frames spooled but not sent; the PCM stays on disk.
        var queued: [(seq: UInt32, hostTimeNS: UInt64, flags: UInt8)] = []
        var warned = false
    }

    private var window: Int
    private let spool: SpoolStore
    private var states: [UInt8: StreamState] = [:]
    private var warnings: [CaptureWarning] = []
    private let lock = NSLock()

    public init(window: Int, spool: SpoolStore) {
        self.window = max(1, window)
        self.spool = spool
    }

    /// A restarted agent continues the core's numbering: everything below `nextSeq` is already durable there.
    public func resume(stream: UInt8, nextSeq: UInt32) {
        lock.withLock {
            var state = StreamState()
            state.lastSent = Int64(nextSeq) - 1
            state.lastAcked = Int64(nextSeq) - 1
            states[stream] = state
        }
    }

    /// Spools the frame and returns the frames that may be sent right now (the frame itself, or nothing).
    /// `resumingLive` marks the first live frame after a restart: it opens a new spool file and is flagged.
    public func offer(_ offered: AudioFrame, resumingLive: Bool = false) throws -> [AudioFrame] {
        try lock.withLock {
            let frame: AudioFrame
            if resumingLive {
                frame = try spool.resumeLive(offered)
            } else {
                try spool.append(offered)
                frame = offered
            }
            var state = states[frame.stream] ?? StreamState()
            defer { states[frame.stream] = state }
            if state.queued.isEmpty && state.lastSent - state.lastAcked < Int64(window) {
                state.lastSent = Int64(frame.seq)
                return [frame]
            }
            state.queued.append((frame.seq, frame.hostTimeNS, frame.flags))
            if state.queued.count > Self.backpressureFrames && !state.warned {
                state.warned = true
                warnings.append(CaptureWarning(code: "backpressure", severity: "warn"))
            }
            return []
        }
    }

    /// A cumulative acknowledgement: returns the queued frames that the freed slots release.
    public func acknowledge(stream: UInt8, seq: UInt32, window newWindow: Int) throws -> [AudioFrame] {
        try lock.withLock {
            guard newWindow >= 1 else { throw ProtocolValidationError(reason: "window must be at least 1") }
            var state = states[stream] ?? StreamState()
            defer { states[stream] = state }
            window = newWindow
            guard Int64(seq) > state.lastAcked, Int64(seq) <= state.lastSent else { return [] }
            state.lastAcked = Int64(seq)
            try spool.release(stream: stream, upToSeq: seq)
            var released: [AudioFrame] = []
            while !state.queued.isEmpty && state.lastSent - state.lastAcked < Int64(window) {
                let next = state.queued.removeFirst()
                guard let stored = try spool.read(stream: stream, seq: next.seq) else { continue }
                state.lastSent = Int64(next.seq)
                released.append(AudioFrame(stream: stored.stream, flags: next.flags, seq: stored.seq,
                                           firstSample: stored.firstSample, hostTimeNS: next.hostTimeNS, pcm: stored.pcm))
            }
            if state.queued.count <= Self.backpressureFrames { state.warned = false }
            return released
        }
    }

    public func takeWarnings() throws -> [CaptureWarning] {
        lock.withLock {
            defer { warnings.removeAll() }
            return warnings
        }
    }
}
