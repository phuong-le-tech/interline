import Foundation
import CaptureCore

/// What went wrong on the inbound pipe; always answered with a fatal `bad_frame` and a close.
enum InboundEvent {
    case control(ControlMessage)
    case ignoredAudio
    case endOfStream
    case badFrame(String)
}

/// Frames on stdin/stdout. stdout carries protocol frames only, everything else goes to stderr.
final class StdioTransport {
    private let input: Int32
    private let output: Int32
    private let writeLock = NSLock()
    private var closed = false

    init(input: Int32 = STDIN_FILENO, output: Int32 = STDOUT_FILENO) {
        self.input = input
        self.output = output
    }

    // MARK: reading

    func next() -> InboundEvent {
        var prefix = [UInt8](repeating: 0, count: 4)
        switch readFully(&prefix, count: 4) {
        case 0: return .endOfStream
        case 4: break
        default: return .badFrame("truncated length prefix")
        }
        let length = prefix.reduce(0) { ($0 << 8) | Int($1) }
        guard length >= 1 else { return .badFrame("zero frame length") }
        guard length <= WireCodec.maxControlLength else { return .badFrame("frame length \(length) over limit") }
        var kind = [UInt8](repeating: 0, count: 1)
        guard readFully(&kind, count: 1) == 1 else { return .badFrame("missing frame kind") }
        let limit: Int
        switch kind[0] {
        case WireCodec.kindControl: limit = WireCodec.maxControlLength
        case WireCodec.kindAudio: limit = WireCodec.maxAudioLength
        default: return .badFrame("unknown frame kind \(kind[0])")
        }
        guard length <= limit else { return .badFrame("frame length \(length) over limit \(limit) for kind \(kind[0])") }
        var payload = [UInt8](repeating: 0, count: length - 1)
        guard readFully(&payload, count: length - 1) == length - 1 else { return .badFrame("truncated frame payload") }
        if kind[0] == WireCodec.kindAudio { return .ignoredAudio }
        do { return .control(try WireCodec.decodeControl(Data(payload))) } catch {
            return .badFrame(String(describing: error))
        }
    }

    /// Reads exactly `count` bytes unless the stream ends first; returns the number actually read.
    private func readFully(_ buffer: inout [UInt8], count: Int) -> Int {
        var done = 0
        while done < count {
            let got = buffer.withUnsafeMutableBytes { read(input, $0.baseAddress! + done, count - done) }
            if got > 0 { done += got } else if got == 0 || errno != EINTR { break }
        }
        return done
    }

    // MARK: writing

    @discardableResult
    func send(_ message: ControlMessage) -> Bool {
        guard let frame = try? WireCodec.controlFrame(message) else { log("cannot encode \(message.type)"); return false }
        return write(frame)
    }

    @discardableResult
    func send(_ frame: AudioFrame) -> Bool {
        guard let wire = try? WireCodec.encodeAudio(frame) else { log("cannot encode audio frame \(frame.seq)"); return false }
        return write(wire)
    }

    /// A failed write means the core is gone: nothing useful is left to do but exit (stdin EOF follows anyway).
    private func write(_ data: Data) -> Bool {
        writeLock.lock()
        defer { writeLock.unlock() }
        guard !closed else { return false }
        var offset = 0
        let bytes = [UInt8](data)
        while offset < bytes.count {
            let wrote = bytes.withUnsafeBytes { Darwin.write(output, $0.baseAddress! + offset, bytes.count - offset) }
            if wrote > 0 { offset += wrote } else if errno != EINTR { closed = true; return false }
        }
        return true
    }
}
