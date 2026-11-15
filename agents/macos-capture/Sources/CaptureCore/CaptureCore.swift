import Foundation

// M1 RED API surface only. No capture, codec, spool or policy behavior yet.
public struct NotImplemented: Error, CustomStringConvertible {
    public let operation: String
    public init(_ operation: String) { self.operation = operation }
    public var description: String { "not implemented: \(operation)" }
}
// Negative tests accept only this error, never the NotImplemented sentinel.
public struct ProtocolValidationError: Error {
    public let reason: String
    public init(reason: String) { self.reason = reason }
}
public struct AudioFrame: Equatable {
    public let stream: UInt8
    public let flags: UInt8
    public let seq: UInt32
    public let firstSample: UInt64
    public let hostTimeNS: UInt64
    public let pcm: [Int16]
    public init(stream: UInt8, flags: UInt8, seq: UInt32, firstSample: UInt64,
                hostTimeNS: UInt64, pcm: [Int16]) {
        self.stream = stream; self.flags = flags; self.seq = seq
        self.firstSample = firstSample; self.hostTimeNS = hostTimeNS; self.pcm = pcm
    }
}
public struct ControlMessage {
    public init() {}
}
public enum WireCodec {
    public static func decodeAudio(_ wire: Data) throws -> AudioFrame { throw NotImplemented("WireCodec.decodeAudio") }
    public static func encodeAudio(_ frame: AudioFrame) throws -> Data { throw NotImplemented("WireCodec.encodeAudio") }
    public static func decodeControl(_ payload: Data) throws -> ControlMessage { throw NotImplemented("WireCodec.decodeControl") }
    public static func encodeControl(_ message: ControlMessage) throws -> Data { throw NotImplemented("WireCodec.encodeControl") }
}
public final class SpoolStore {
    public init(directory: URL) {}
    public func append(_ frame: AudioFrame) throws { throw NotImplemented("SpoolStore.append") }
    public func close() throws { throw NotImplemented("SpoolStore.close") }
    public func resumeLive(_ frame: AudioFrame) throws -> AudioFrame { throw NotImplemented("SpoolStore.resumeLive") }
    public static func recover(directory: URL, stream: UInt8, nextSequence: UInt32) throws -> [AudioFrame] {
        throw NotImplemented("SpoolStore.recover")
    }
}
public struct CaptureWarning: Equatable {
    public let code: String
    public let severity: String
    public init(code: String, severity: String) { self.code = code; self.severity = severity }
}
public final class DeliveryWindow {
    public init(window: Int, spool: SpoolStore) {}
    public func offer(_ frame: AudioFrame) throws -> [AudioFrame] { throw NotImplemented("DeliveryWindow.offer") }
    public func acknowledge(stream: UInt8, seq: UInt32, window: Int) throws -> [AudioFrame] {
        throw NotImplemented("DeliveryWindow.acknowledge")
    }
    public func takeWarnings() throws -> [CaptureWarning] { throw NotImplemented("DeliveryWindow.takeWarnings") }
}
public struct ChunkPolicy {
    public init(minSilenceMS: Int, targetSeconds: ClosedRange<Int>, maxSeconds: Int, overlapMS: Int) throws { throw NotImplemented("ChunkPolicy.init") }
    public func shouldFinalize(elapsedMS: Int, silenceMS: Int) throws -> Bool { throw NotImplemented("ChunkPolicy.shouldFinalize") }
    public func nextStartSample(afterEndSample: UInt64) throws -> UInt64 { throw NotImplemented("ChunkPolicy.nextStartSample") }
}
public final class PCMConverter {
    public init() {}
    public func convert(interleaved: [Float], sampleRate: Int, channels: Int) throws -> [Int16] {
        throw NotImplemented("PCMConverter.convert")
    }
}
public final class SilentTapDetector {
    public init() {}
    public func observe(samples: [Int16], otherProcessProducingOutput: Bool) throws -> Bool {
        throw NotImplemented("SilentTapDetector.observe")
    }
}
