import Foundation

/// The transcription policy of `session.start.stt` (ARCHITECTURE 5.5), with the documented defaults.
public struct STTPolicy {
    public var modelPath: String?
    public var modelSHA256: String?
    public var vadModelPath: String?
    public var language: String?
    public var initialPrompt: String?
    public var minSilenceMS = 500
    public var targetChunkSeconds = 5...15
    public var maxChunkSeconds = 30
    public var overlapMS = 500
    public var partials = false

    public init() {}

    public static func parse(_ body: [String: Any]?) throws -> STTPolicy {
        var policy = STTPolicy()
        guard let body else { return policy }
        policy.modelPath = body["model_path"] as? String
        policy.modelSHA256 = body["model_sha256"] as? String
        policy.vadModelPath = body["vad_model_path"] as? String
        policy.language = body["language"] as? String
        policy.initialPrompt = body["initial_prompt"] as? String
        if let value = WireCodec.integer(body["min_silence_ms"]) { policy.minSilenceMS = value }
        if let range = body["target_chunk_s"] as? [Any], range.count == 2,
           let low = WireCodec.integer(range[0]), let high = WireCodec.integer(range[1]), low <= high {
            policy.targetChunkSeconds = low...high
        }
        if let value = WireCodec.integer(body["max_chunk_s"]) { policy.maxChunkSeconds = value }
        if let value = WireCodec.integer(body["overlap_ms"]) { policy.overlapMS = value }
        policy.partials = body["partials"] as? Bool ?? false
        _ = try policy.chunkPolicy()
        return policy
    }

    public func chunkPolicy() throws -> ChunkPolicy {
        try ChunkPolicy(minSilenceMS: minSilenceMS, targetSeconds: targetChunkSeconds,
                        maxSeconds: maxChunkSeconds, overlapMS: overlapMS)
    }
}

/// A transcription result handed to the protocol layer.
public enum TranscriptEvent {
    case partial(stream: UInt8, seg: String, revision: Int, startSample: UInt64, text: String)
    case vad(stream: UInt8, seg: String, startSample: UInt64, endSample: UInt64?, closed: Bool)
    case final(stream: UInt8, seg: String, startSample: UInt64, endSample: UInt64, text: String,
               language: String?, avgLogprob: Double?, model: String)
}

/// Speech-to-text behind the capture path. `feed` must not block the capture callback.
public protocol Transcriber: AnyObject {
    /// Name reported in `hello.capabilities.stt.engine`.
    var engine: String { get }
    /// Audio waiting for transcription, in ms; reported in the heartbeat.
    var backlogMS: Int { get }
    /// Begin a session; audio before `resumeFrom[stream]` was already transcribed.
    func begin(policy: STTPolicy, resumeFrom: [UInt8: UInt64], emit: @escaping (TranscriptEvent) -> Void) throws
    func feed(_ frame: AudioFrame)
    /// Cut the open region now (end of input or stop) and wait until everything pending has been emitted.
    func finish()
    /// Releases the models. The process must call this before it exits: freeing GPU resources from a static
    /// destructor aborts inside the Metal backend.
    func shutdown()
    /// Offline import of an audio file: finals go through `emit`; returns the number of finals and the
    /// length of the file in 16 kHz samples.
    func transcribeFile(_ url: URL, stream: UInt8, policy: STTPolicy,
                        emit: @escaping (TranscriptEvent) -> Void) throws -> (segments: Int, durationSamples: Int)
}
