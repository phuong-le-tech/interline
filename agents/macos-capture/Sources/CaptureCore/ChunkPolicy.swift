import Foundation

/// When a chunk of speech is cut for transcription, see ARCHITECTURE 9.1.
public struct ChunkPolicy {
    public let minSilenceMS: Int
    public let targetSeconds: ClosedRange<Int>
    public let maxSeconds: Int
    public let overlapMS: Int

    public init(minSilenceMS: Int, targetSeconds: ClosedRange<Int>, maxSeconds: Int, overlapMS: Int) throws {
        guard minSilenceMS >= 0 else { throw ProtocolValidationError(reason: "min_silence_ms must not be negative") }
        guard targetSeconds.lowerBound >= 1 else { throw ProtocolValidationError(reason: "target_chunk_s must be positive") }
        guard maxSeconds >= targetSeconds.upperBound else { throw ProtocolValidationError(reason: "max_chunk_s must reach the target upper bound") }
        guard overlapMS >= 0, overlapMS < targetSeconds.lowerBound * 1000 else {
            throw ProtocolValidationError(reason: "overlap_ms must be non-negative and shorter than the shortest chunk")
        }
        self.minSilenceMS = minSilenceMS; self.targetSeconds = targetSeconds
        self.maxSeconds = maxSeconds; self.overlapMS = overlapMS
    }

    /// Cut after a pause once the minimum length is reached, and always at the hard maximum.
    public func shouldFinalize(elapsedMS: Int, silenceMS: Int) throws -> Bool {
        if elapsedMS >= maxSeconds * 1000 { return true }
        return elapsedMS >= targetSeconds.lowerBound * 1000 && silenceMS >= minSilenceMS
    }

    /// The next chunk re-reads `overlapMS` of audio so a word on the cut is heard whole once.
    public func nextStartSample(afterEndSample end: UInt64) throws -> UInt64 {
        let overlap = UInt64(overlapMS * AudioConstants.samplesPerMS)
        return end > overlap ? end - overlap : 0
    }
}

/// Spots a system-audio tap that delivers nothing but exact zeros while another process is playing:
/// the signature of a missing "System Audio Recording" permission (ADR-004).
public final class SilentTapDetector {
    /// 5 s at 16 kHz.
    public static let silentSamplesToDeny = 5 * AudioConstants.sampleRate
    private var zeroRun = 0
    private var raised = false

    public init() {}

    /// Returns true once per silent episode, at the sample that completes the five seconds.
    public func observe(samples: [Int16], otherProcessProducingOutput: Bool) throws -> Bool {
        guard otherProcessProducingOutput else { zeroRun = 0; return false }
        var triggered = false
        for sample in samples {
            if sample != 0 { zeroRun = 0; raised = false; continue }
            zeroRun += 1
            if zeroRun >= Self.silentSamplesToDeny && !raised { raised = true; triggered = true }
        }
        return triggered
    }
}
