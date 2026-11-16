import Foundation

/// Speech/non-speech decision per 512-sample (32 ms) window of 16 kHz audio.
public protocol SpeechDetector: AnyObject {
    static var windowSamples: Int { get }
    /// `samples.count` is a multiple of `windowSamples`; returns one speech probability per window.
    func speechProbabilities(_ samples: [Int16]) -> [Float]
}

public struct DecodedText {
    public let text: String
    public let language: String?
    public let avgLogprob: Double?
    public init(text: String, language: String?, avgLogprob: Double?) {
        self.text = text; self.language = language; self.avgLogprob = avgLogprob
    }
}

/// Cuts one stream into speech regions with the chunk policy (ARCHITECTURE 9.1) and hands each region to a
/// decoder: a `vad.segment` opens with the first speech, `stt.partial` revises it while it grows, and
/// `stt.final` plus a closing `vad.segment` end it. Pure logic: the detector and decoder are injected.
public final class RegionChunker {
    static let speechThreshold: Float = 0.5
    /// Audio kept before the first speech window so a word onset is not clipped.
    static let preRollSamples = 200 * AudioConstants.samplesPerMS
    /// Trailing silence kept in the decoded chunk.
    static let tailPadSamples = 300 * AudioConstants.samplesPerMS
    static let partialIntervalSamples = 2_000 * AudioConstants.samplesPerMS
    static let minPartialSamples = 1_000 * AudioConstants.samplesPerMS
    /// Raw audio retained for pre-roll and overlap; must cover the longest of those plus one frame.
    static let recentSamples = 2 * AudioConstants.sampleRate

    private struct Region {
        var start: UInt64
        var pcm: [Int16]
        var lastSpeechEnd: UInt64
        var trailingSilence = 0
        var revision = 0
        var lastPartialAt: UInt64
        var seg: String { "s-\(start)" }
        var end: UInt64 { start + UInt64(pcm.count) }
    }

    private let stream: UInt8
    private let chunkPolicy: ChunkPolicy
    private let partials: Bool
    private let detector: SpeechDetector
    private let decode: (_ pcm: [Int16], _ partial: Bool) -> DecodedText?
    private let emit: (TranscriptEvent) -> Void
    private let model: String
    private let canPartial: () -> Bool
    private var recent: [Int16] = []
    private var recentStart: UInt64 = 0
    private var vadCarry: [Int16] = []
    private var vadCarryStart: UInt64 = 0
    private var region: Region?
    private var notBefore: UInt64
    private var timelineEnd: UInt64

    public private(set) var finals = 0

    public init(stream: UInt8, policy: STTPolicy, detector: SpeechDetector, model: String, startAt: UInt64 = 0,
                canPartial: @escaping () -> Bool = { true },
                decode: @escaping (_ pcm: [Int16], _ partial: Bool) -> DecodedText?,
                emit: @escaping (TranscriptEvent) -> Void) throws {
        self.stream = stream
        self.chunkPolicy = try policy.chunkPolicy()
        self.partials = policy.partials
        self.detector = detector
        self.model = model
        self.decode = decode
        self.emit = emit
        self.canPartial = canPartial
        self.notBefore = startAt
        self.timelineEnd = startAt
        self.recentStart = startAt
        self.vadCarryStart = startAt
    }

    public func process(_ frame: AudioFrame) {
        let frameEnd = frame.firstSample + UInt64(frame.pcm.count)
        guard frameEnd > notBefore else { return }
        timelineEnd = frameEnd
        remember(frame)
        if region != nil { region!.pcm.append(contentsOf: frame.pcm) }
        runDetector(frame)
        guard var current = region else { return }
        let elapsedMS = Int(current.pcm.count) / AudioConstants.samplesPerMS
        let silenceMS = current.trailingSilence / AudioConstants.samplesPerMS
        if (try? chunkPolicy.shouldFinalize(elapsedMS: elapsedMS, silenceMS: silenceMS)) == true {
            let forced = silenceMS < chunkPolicy.minSilenceMS
            finalize(&current, continuingSpeech: forced)
            return
        }
        revise(&current)
        region = current
    }

    /// Ends the open region now, e.g. at the end of the input or when the session stops.
    public func flush() {
        guard var current = region else { return }
        finalize(&current, continuingSpeech: false)
    }

    // MARK: detector

    private func runDetector(_ frame: AudioFrame) {
        if vadCarry.isEmpty { vadCarryStart = frame.firstSample }
        vadCarry.append(contentsOf: frame.pcm)
        let window = type(of: detector).windowSamples
        let usable = vadCarry.count / window * window
        guard usable > 0 else { return }
        let probabilities = detector.speechProbabilities(Array(vadCarry[0..<usable]))
        for (index, probability) in probabilities.enumerated() {
            let windowStart = vadCarryStart + UInt64(index * window)
            if probability >= Self.speechThreshold {
                if region == nil { open(at: windowStart) }
                region?.trailingSilence = 0
                region?.lastSpeechEnd = windowStart + UInt64(window)
            } else if region != nil {
                region!.trailingSilence += window
            }
        }
        vadCarry.removeFirst(usable)
        vadCarryStart += UInt64(usable)
    }

    private func open(at windowStart: UInt64) {
        let start = max(notBefore, windowStart > UInt64(Self.preRollSamples) ? windowStart - UInt64(Self.preRollSamples) : 0,
                        recentStart)
        let offset = Int(start - recentStart)
        let pcm = Array(recent[min(offset, recent.count)...])
        region = Region(start: start, pcm: pcm, lastSpeechEnd: windowStart, lastPartialAt: start)
        emit(.vad(stream: stream, seg: region!.seg, startSample: start, endSample: nil, closed: false))
    }

    // MARK: output

    private func revise(_ region: inout Region) {
        guard partials, region.end - region.lastPartialAt >= UInt64(Self.partialIntervalSamples),
              region.pcm.count >= Self.minPartialSamples, canPartial() else { return }
        region.lastPartialAt = region.end
        guard let decoded = decode(region.pcm, true), !decoded.text.isEmpty else { return }
        region.revision += 1
        emit(.partial(stream: stream, seg: region.seg, revision: region.revision, startSample: region.start, text: decoded.text))
    }

    private func finalize(_ region: inout Region, continuingSpeech: Bool) {
        let keep = min(region.pcm.count, Int(region.lastSpeechEnd - region.start) + Self.tailPadSamples)
        let pcm = Array(region.pcm[0..<max(keep, 0)])
        let end = region.start + UInt64(pcm.count)
        let seg = region.seg, start = region.start
        self.region = nil
        if let decoded = decode(pcm, false), !decoded.text.isEmpty {
            finals += 1
            emit(.final(stream: stream, seg: seg, startSample: start, endSample: end, text: decoded.text,
                        language: decoded.language, avgLogprob: decoded.avgLogprob, model: model))
        }
        emit(.vad(stream: stream, seg: seg, startSample: start, endSample: end, closed: true))
        notBefore = end
        if continuingSpeech, let restart = try? chunkPolicy.nextStartSample(afterEndSample: end) {
            notBefore = restart
            reopen(at: restart)
        }
    }

    /// A chunk cut in the middle of speech: the next one re-reads the overlap and continues at once.
    private func reopen(at start: UInt64) {
        let begin = max(start, recentStart)
        let offset = Int(begin - recentStart)
        guard offset <= recent.count else { return }
        region = Region(start: begin, pcm: Array(recent[offset...]), lastSpeechEnd: timelineEnd, lastPartialAt: begin)
        emit(.vad(stream: stream, seg: region!.seg, startSample: begin, endSample: nil, closed: false))
    }

    private func remember(_ frame: AudioFrame) {
        if recent.isEmpty { recentStart = frame.firstSample }
        recent.append(contentsOf: frame.pcm)
        if recent.count > Self.recentSamples {
            let drop = recent.count - Self.recentSamples
            recent.removeFirst(drop)
            recentStart += UInt64(drop)
        }
    }
}
