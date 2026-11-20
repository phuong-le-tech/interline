import Foundation

/// A frame or message that violates Agent Protocol v1 framing or schema rules.
public struct ProtocolValidationError: Error, CustomStringConvertible {
    public let reason: String
    public init(reason: String) { self.reason = reason }
    public var description: String { "protocol violation: \(reason)" }
}

/// Stream index on the wire: 0 = mic, 1 = system.
public enum StreamID {
    public static let mic: UInt8 = 0
    public static let system: UInt8 = 1
    public static let names = ["mic", "system"]

    public static func name(_ stream: UInt8) -> String { names[Int(stream)] }
    public static func index(named name: String) -> UInt8? { names.firstIndex(of: name).map { UInt8($0) } }
}

public enum FrameFlags {
    /// The frame was replayed from the spool after a restart.
    public static let recovered: UInt8 = 1
    /// The timeline jumps before this frame (restart gap, device change).
    public static let discontinuityBefore: UInt8 = 2
    public static let allBits: UInt8 = 7
}

public enum AudioConstants {
    public static let sampleRate = 16_000
    public static let frameSamples = 1_600
    public static let frameMS = 100
    public static let samplesPerMS = 16
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

    public func with(flags: UInt8) -> AudioFrame {
        AudioFrame(stream: stream, flags: flags, seq: seq, firstSample: firstSample, hostTimeNS: hostTimeNS, pcm: pcm)
    }
}

/// The JSON control envelope. `body` stays untyped so unknown fields survive a decode/encode round trip.
public struct ControlMessage {
    public static let envelopeVersion = 1
    public var version: Int
    public var type: String
    public var id: String?
    public var replyTo: String?
    public var timestamp: Int64
    public var body: [String: Any]

    public init(type: String, id: String? = nil, replyTo: String? = nil, body: [String: Any] = [:],
                version: Int = ControlMessage.envelopeVersion,
                timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        self.version = version; self.type = type; self.id = id; self.replyTo = replyTo
        self.timestamp = timestamp; self.body = body
    }

    public static func event(_ type: String, _ body: [String: Any]) -> ControlMessage {
        ControlMessage(type: type, body: body)
    }

    public static func response(to request: ControlMessage, type: String, _ body: [String: Any] = [:]) -> ControlMessage {
        ControlMessage(type: type, replyTo: request.id, body: body)
    }
}

/// `u32 length (big-endian, kind byte included) | u8 kind | payload`, see ARCHITECTURE 5.3.
public enum WireCodec {
    public static let kindControl: UInt8 = 1
    public static let kindAudio: UInt8 = 2
    public static let maxControlLength = 1 << 20
    public static let maxAudioLength = 1 << 16
    public static let audioHeaderBytes = 24
    static let lengthPrefixBytes = 4

    // MARK: audio

    public static func encodeAudio(_ frame: AudioFrame) throws -> Data {
        try require(frame.stream <= StreamID.system, "stream index out of range")
        try require(frame.flags & ~FrameFlags.allBits == 0, "unknown flag bits")
        let length = 1 + audioHeaderBytes + frame.pcm.count * 2
        try require(length <= maxAudioLength, "audio frame length \(length) over limit \(maxAudioLength)")
        var data = Data(capacity: lengthPrefixBytes + length)
        data.appendBigEndian(UInt32(length))
        data.append(kindAudio)
        data.append(frame.stream)
        data.append(frame.flags)
        data.appendLittleEndian(UInt16(0))
        data.appendLittleEndian(frame.seq)
        data.appendLittleEndian(frame.firstSample)
        data.appendLittleEndian(frame.hostTimeNS)
        for sample in frame.pcm { data.appendLittleEndian(sample) }
        return data
    }

    /// Takes a complete transport frame, including its length prefix and kind byte.
    public static func decodeAudio(_ wire: Data) throws -> AudioFrame {
        let bytes = [UInt8](wire)
        try require(bytes.count >= lengthPrefixBytes + 1, "truncated length prefix")
        let length = Int(bigEndian32(bytes, at: 0))
        try require(length >= 1 && length <= maxAudioLength, "frame length \(length) out of range")
        try require(bytes.count >= lengthPrefixBytes + length, "truncated frame payload")
        try require(bytes.count == lengthPrefixBytes + length, "trailing bytes after frame")
        try require(bytes[lengthPrefixBytes] == kindAudio, "not an audio frame")
        let payload = Array(bytes[(lengthPrefixBytes + 1)...])
        try require(payload.count >= audioHeaderBytes, "audio frame shorter than its header")
        try require((payload.count - audioHeaderBytes) % 2 == 0, "odd PCM byte count")
        try require(payload[0] <= StreamID.system, "stream index out of range")
        try require(payload[1] & ~FrameFlags.allBits == 0, "unknown flag bits")
        try require(payload[2] == 0 && payload[3] == 0, "reserved field must be zero")
        let seq = littleEndian(payload, at: 4, as: UInt32.self)
        let firstSample = littleEndian(payload, at: 8, as: UInt64.self)
        let hostTime = littleEndian(payload, at: 16, as: UInt64.self)
        var pcm = [Int16](); pcm.reserveCapacity((payload.count - audioHeaderBytes) / 2)
        var at = audioHeaderBytes
        while at < payload.count { pcm.append(Int16(bitPattern: littleEndian(payload, at: at, as: UInt16.self))); at += 2 }
        return AudioFrame(stream: payload[0], flags: payload[1], seq: seq, firstSample: firstSample,
                          hostTimeNS: hostTime, pcm: pcm)
    }

    // MARK: control

    /// Decodes a control payload (JSON only, no transport prefix). Unknown message types are accepted so the
    /// dispatcher can answer them with a correlated `unsupported_message`.
    public static func decodeControl(_ payload: Data) throws -> ControlMessage {
        try require(payload.count <= maxControlLength, "control payload over 1 MiB")
        let parsed: Any
        do { parsed = try JSONSerialization.jsonObject(with: payload) } catch {
            throw ProtocolValidationError(reason: "control payload is not JSON")
        }
        guard let root = parsed as? [String: Any] else { throw ProtocolValidationError(reason: "control payload is not an object") }
        guard let version = integer(root["v"]) else { throw ProtocolValidationError(reason: "v must be an integer") }
        try require(version == ControlMessage.envelopeVersion, "unsupported envelope version \(version)")
        guard let type = root["t"] as? String else { throw ProtocolValidationError(reason: "t must be a string") }
        guard let timestamp = integer(root["ts"]), timestamp >= 0 else { throw ProtocolValidationError(reason: "ts must be a non-negative integer") }
        guard let body = root["body"] as? [String: Any] else { throw ProtocolValidationError(reason: "body must be an object") }
        let id = try optionalString(root, "id")
        let replyTo = try optionalString(root, "re")
        let message = ControlMessage(type: type, id: id, replyTo: replyTo, body: body, version: version, timestamp: Int64(timestamp))
        try validateBody(message)
        return message
    }

    public static func encodeControl(_ message: ControlMessage) throws -> Data {
        var envelope: [String: Any] = ["v": message.version, "t": message.type, "ts": message.timestamp, "body": message.body]
        if let id = message.id { envelope["id"] = id }
        if let replyTo = message.replyTo { envelope["re"] = replyTo }
        guard JSONSerialization.isValidJSONObject(envelope) else { throw ProtocolValidationError(reason: "body is not JSON encodable") }
        let data = try JSONSerialization.data(withJSONObject: envelope, options: [.withoutEscapingSlashes])
        try require(data.count <= maxControlLength, "control payload over 1 MiB")
        return data
    }

    /// Wraps an encoded control payload in a transport frame.
    public static func controlFrame(_ message: ControlMessage) throws -> Data {
        let payload = try encodeControl(message)
        var data = Data(capacity: lengthPrefixBytes + 1 + payload.count)
        data.appendBigEndian(UInt32(payload.count + 1))
        data.append(kindControl)
        data.append(payload)
        return data
    }

    // MARK: body rules for the messages the agent consumes

    private static func validateBody(_ message: ControlMessage) throws {
        let body = message.body
        switch message.type {
        case "calendar.query":
            try require(message.id != nil, "calendar.query needs id")
            guard let from = integer(body["from_ms"]), let to = integer(body["to_ms"]), to > from,
                  Double(to) - Double(from) <= 31 * 86400000 else {
                throw ProtocolValidationError(reason: "calendar.query needs an integer window of at most 31 days")
            }
        case "audio.ack":
            try require(body["stream"] is String && StreamID.index(named: body["stream"] as! String) != nil, "audio.ack needs a stream")
            guard let seq = integer(body["seq"]), seq >= 0, seq <= Int(UInt32.max) else { throw ProtocolValidationError(reason: "audio.ack needs an unsigned seq") }
            guard let window = integer(body["window"]), window >= 1 else { throw ProtocolValidationError(reason: "audio.ack needs a window of at least 1") }
        case "session.start":
            try require(body["session_id"] is String, "session.start needs session_id")
            try require(body["streams"] is [Any], "session.start needs streams")
            try require(body["audio"] is [String: Any], "session.start needs audio")
            try require(body["spool_dir"] is String, "session.start needs spool_dir")
        case "session.stop":
            try require(body["session_id"] is String, "session.stop needs session_id")
        case "transcribe.file":
            try require(body["job_id"] is String && body["path"] is String, "transcribe.file needs job_id and path")
            try require((body["stream"] as? String).flatMap(StreamID.index(named:)) != nil, "transcribe.file needs a stream")
        default:
            break
        }
    }

    // MARK: helpers

    private static func require(_ condition: Bool, _ reason: String) throws {
        if !condition { throw ProtocolValidationError(reason: reason) }
    }

    private static func optionalString(_ root: [String: Any], _ key: String) throws -> String? {
        guard let value = root[key], !(value is NSNull) else { return nil }
        guard let text = value as? String else { throw ProtocolValidationError(reason: "\(key) must be a string") }
        return text
    }

    /// JSON integers only: a Bool or a fractional number is not an integer.
    public static func integer(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double == double.rounded(), abs(double) < 9.0e15 else { return nil }
        return number.intValue
    }

    private static func bigEndian32(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        (0..<4).reduce(UInt32(0)) { ($0 << 8) | UInt32(bytes[offset + $1]) }
    }

    private static func littleEndian<T: FixedWidthInteger>(_ bytes: [UInt8], at offset: Int, as type: T.Type) -> T {
        (0..<MemoryLayout<T>.size).reversed().reduce(T(0)) { ($0 << 8) | T(bytes[offset + $1]) }
    }
}

extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }

    mutating func appendBigEndian<T: FixedWidthInteger>(_ value: T) {
        var big = value.bigEndian
        Swift.withUnsafeBytes(of: &big) { append(contentsOf: $0) }
    }
}
