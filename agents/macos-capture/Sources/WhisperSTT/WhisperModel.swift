import Foundation
import CaptureCore
import whisper

/// Failure to bring up the speech models; reported to the core as `model_load_failed`.
public struct ModelLoadError: Error, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// A loaded whisper.cpp context. whisper contexts are not thread-safe: every call goes through the STT queue.
final class WhisperModel {
    let name: String
    let path: String
    private let context: OpaquePointer

    init(path: String) throws {
        guard FileManager.default.isReadableFile(atPath: path) else { throw ModelLoadError("model file not readable: \(path)") }
        var params = whisper_context_default_params()
        params.use_gpu = true
        guard let context = whisper_init_from_file_with_params(path, params) else {
            throw ModelLoadError("whisper could not load \(URL(fileURLWithPath: path).lastPathComponent)")
        }
        self.context = context
        self.path = path
        self.name = URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
            .replacingOccurrences(of: "ggml-", with: "")
    }

    deinit { whisper_free(context) }

    /// One greedy decode of a finished region. Empty text means "nothing worth keeping".
    func decode(_ pcm: [Int16], policy: STTPolicy, detectedLanguage: inout String?) -> DecodedText? {
        let samples = pcm.map { Float($0) / 32768 }
        var params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY)
        params.n_threads = Int32(min(8, max(2, ProcessInfo.processInfo.activeProcessorCount - 2)))
        params.print_progress = false
        params.print_realtime = false
        params.print_timestamps = false
        params.print_special = false
        params.no_context = true
        params.suppress_nst = true
        params.single_segment = false
        params.no_timestamps = true
        let language = policy.language ?? detectedLanguage
        let languageC = language.map { strdup($0) } ?? nil
        let promptC = policy.initialPrompt.map { strdup($0) } ?? nil
        defer { free(languageC); free(promptC) }
        params.language = UnsafePointer(languageC)
        params.detect_language = language == nil
        params.initial_prompt = UnsafePointer(promptC)
        guard whisper_full(context, params, samples, Int32(samples.count)) == 0 else { return nil }
        var text = ""
        var logprobSum = 0.0
        var tokens = 0
        for segment in 0..<whisper_full_n_segments(context) {
            if let raw = whisper_full_get_segment_text(context, segment) { text += String(cString: raw) }
            for token in 0..<whisper_full_n_tokens(context, segment) {
                let probability = Double(whisper_full_get_token_p(context, segment, token))
                if probability > 0 { logprobSum += log(probability); tokens += 1 }
            }
        }
        let languageId = whisper_full_lang_id(context)
        let spoken = languageId >= 0 ? String(cString: whisper_lang_str(languageId)) : language
        if policy.language == nil && detectedLanguage == nil { detectedLanguage = spoken }
        let cleaned = Self.clean(text)
        return DecodedText(text: cleaned, language: spoken, avgLogprob: tokens > 0 ? logprobSum / Double(tokens) : nil)
    }

    /// Whisper emits bracketed markers on silence ("[BLANK_AUDIO]", "(music)") and bare symbols: not speech.
    static func clean(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasLetters = trimmed.unicodeScalars.contains { CharacterSet.letters.contains($0) || CharacterSet.decimalDigits.contains($0) }
        if !hasLetters { return "" }
        let wrapped = (trimmed.hasPrefix("[") && trimmed.hasSuffix("]")) || (trimmed.hasPrefix("(") && trimmed.hasSuffix(")"))
        return wrapped ? "" : trimmed
    }
}

/// Silero VAD through whisper.cpp's built-in VAD API. One context per stream: the LSTM state is per signal.
final class SileroDetector: SpeechDetector {
    static let windowSamples = 512
    private let context: OpaquePointer

    init(modelPath: String) throws {
        guard FileManager.default.isReadableFile(atPath: modelPath) else { throw ModelLoadError("VAD model not readable: \(modelPath)") }
        var params = whisper_vad_default_context_params()
        params.n_threads = 1
        params.use_gpu = false
        guard let context = whisper_vad_init_from_file_with_params(modelPath, params) else {
            throw ModelLoadError("whisper could not load the VAD model")
        }
        self.context = context
    }

    deinit { whisper_vad_free(context) }

    func speechProbabilities(_ samples: [Int16]) -> [Float] {
        let floats = samples.map { Float($0) / 32768 }
        guard whisper_vad_detect_speech_no_reset(context, floats, Int32(floats.count)) else {
            return [Float](repeating: 0, count: samples.count / Self.windowSamples)
        }
        let count = Int(whisper_vad_n_probs(context))
        guard count > 0, let probabilities = whisper_vad_probs(context) else { return [] }
        return Array(UnsafeBufferPointer(start: probabilities, count: count))
    }
}

/// Fallback when no VAD model is configured: a plain level gate. Good enough for clean synthetic speech,
/// not a substitute for Silero on a real call.
final class EnergyDetector: SpeechDetector {
    static let windowSamples = 512
    private let threshold: Double = 0.01

    func speechProbabilities(_ samples: [Int16]) -> [Float] {
        stride(from: 0, to: samples.count, by: Self.windowSamples).map { start in
            let window = samples[start..<start + Self.windowSamples]
            let rms = (window.reduce(0.0) { $0 + pow(Double($1) / 32768, 2) } / Double(Self.windowSamples)).squareRoot()
            return rms >= threshold ? 1 : 0
        }
    }
}
