import Foundation
import CaptureCore

/// Speech-to-text on its own serial queue: one whisper context, one FIFO for both streams, so decoding a long
/// chunk delays the other stream instead of racing it. The capture path only ever enqueues.
public final class WhisperTranscriber: Transcriber {
    public let engine = "whisper.cpp"
    private let queue = DispatchQueue(label: "stt", qos: .userInitiated)
    private let counter = NSLock()
    private var pendingFrames = 0
    private var model: WhisperModel?
    private var detectedLanguage: String?
    private var chunkers: [UInt8: RegionChunker] = [:]

    public init() {}

    public var backlogMS: Int { counter.withLock { pendingFrames * AudioConstants.frameMS } }

    public func begin(policy: STTPolicy, resumeFrom: [UInt8: UInt64], emit: @escaping (TranscriptEvent) -> Void) throws {
        guard let modelPath = policy.modelPath else { throw ModelLoadError("session.start carries no stt.model_path") }
        try queue.sync {
            let loaded = try loadModel(at: modelPath)
            detectedLanguage = nil
            for stream in [StreamID.mic, StreamID.system] {
                chunkers[stream] = try makeChunker(stream: stream, policy: policy, model: loaded, startAt: resumeFrom[stream] ?? 0,
                                                   live: true, emit: emit)
            }
        }
    }

    public func feed(_ frame: AudioFrame) {
        counter.withLock { pendingFrames += 1 }
        queue.async { [self] in
            chunkers[frame.stream]?.process(frame)
            counter.withLock { pendingFrames -= 1 }
        }
    }

    public func finish() {
        queue.sync { for chunker in chunkers.values { chunker.flush() } }
    }

    /// Loads (and keeps) the model ahead of the first use, so a caller can time loading apart from decoding.
    public func preload(modelPath: String) throws {
        try queue.sync { _ = try loadModel(at: modelPath) }
    }

    public func shutdown() {
        queue.sync {
            chunkers.removeAll()
            model = nil
        }
    }

    public func transcribeFile(_ url: URL, stream: UInt8, policy: STTPolicy,
                               emit: @escaping (TranscriptEvent) -> Void) throws -> (segments: Int, durationSamples: Int) {
        guard let modelPath = policy.modelPath else { throw ModelLoadError("transcribe.file carries no stt.model_path") }
        let wav = try WavFile.read(url)
        let pcm = try PCMConverter().convert(interleaved: wav.mixedToMono(), sampleRate: wav.sampleRate, channels: 1)
        return try queue.sync {
            var imported = policy
            imported.partials = false
            let chunker = try makeChunker(stream: stream, policy: imported, model: try loadModel(at: modelPath),
                                          startAt: 0, live: false, emit: emit)
            var index = 0
            while index * AudioConstants.frameSamples < pcm.count {
                let start = index * AudioConstants.frameSamples
                let slice = Array(pcm[start..<min(start + AudioConstants.frameSamples, pcm.count)])
                let padded = slice + [Int16](repeating: 0, count: AudioConstants.frameSamples - slice.count)
                chunker.process(AudioFrame(stream: stream, flags: 0, seq: UInt32(index), firstSample: UInt64(start),
                                           hostTimeNS: 0, pcm: padded))
                index += 1
            }
            chunker.flush()
            return (chunker.finals, pcm.count)
        }
    }

    // MARK: internals

    private func loadModel(at path: String) throws -> WhisperModel {
        if let model, model.path == path { return model }
        let loaded = try WhisperModel(path: path)
        model = loaded
        return loaded
    }

    private func makeChunker(stream: UInt8, policy: STTPolicy, model: WhisperModel, startAt: UInt64, live: Bool,
                             emit: @escaping (TranscriptEvent) -> Void) throws -> RegionChunker {
        let detector: SpeechDetector = try policy.vadModelPath.map { try SileroDetector(modelPath: $0) } ?? EnergyDetector()
        return try RegionChunker(stream: stream, policy: policy, detector: detector, model: model.name, startAt: startAt,
                                 canPartial: { [weak self] in !live || (self?.backlogMS ?? 0) < Self.partialBacklogLimitMS },
                                 decode: { [weak self] pcm, _ in
                                     guard let self else { return nil }
                                     return model.decode(pcm, policy: policy, detectedLanguage: &self.detectedLanguage)
                                 },
                                 emit: emit)
    }

    /// Partials are best effort: skip them while the recogniser is more than a second behind.
    private static let partialBacklogLimitMS = 1_000
}
