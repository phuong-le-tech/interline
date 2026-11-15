import Foundation
import CaptureCore
import WhisperSTT

/// `--bench --model=<ggml> --input=<wav>`: transcribes the file offline and prints one JSON line,
/// `{model, audio_s, wall_s, rtf, peak_rss_mb}`, for docs/BENCHMARKS.md. Model loading is timed apart.
enum Bench {
    static func run(_ options: AgentOptions, transcriber: WhisperTranscriber) -> Int32 {
        defer { transcriber.shutdown() }
        guard let model = options.model, let input = options.benchInput else {
            log("--bench needs --model=<path> and --input=<wav>")
            return ExitCode.badArguments
        }
        do {
            var policy = STTPolicy()
            policy.modelPath = model.path
            policy.vadModelPath = options.vadModel?.path
            policy.language = options.benchLanguage
            let loadStart = Date()
            try transcriber.preload(modelPath: model.path)
            let loadSeconds = Date().timeIntervalSince(loadStart)
            let start = Date()
            let result = try transcriber.transcribeFile(input, stream: StreamID.mic, policy: policy) { event in
                if case let .final(_, _, _, _, text, _, _, _) = event { print(text, to: &standardError) }
            }
            let wall = Date().timeIntervalSince(start)
            let audio = Double(result.durationSamples) / Double(AudioConstants.sampleRate)
            let report: [String: Any] = [
                "model": model.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "ggml-", with: ""),
                "audio_s": round(audio * 100) / 100, "wall_s": round(wall * 100) / 100,
                "rtf": audio > 0 ? round(wall / audio * 1000) / 1000 : 0,
                "load_s": round(loadSeconds * 100) / 100, "peak_rss_mb": peakResidentMegabytes(),
                "segments": result.segments]
            let data = try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
            FileHandle.standardOutput.write(data + Data("\n".utf8))
            return ExitCode.ok
        } catch {
            log("bench failed: \(error)")
            return ExitCode.internalError
        }
    }

    private static func peakResidentMegabytes() -> Int {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        return Int(usage.ru_maxrss / 1_048_576)
    }
}

/// Recognised text goes to stderr in bench mode so stdout stays one parseable JSON line.
struct StandardError: TextOutputStream {
    mutating func write(_ string: String) { FileHandle.standardError.write(Data(string.utf8)) }
}
nonisolated(unsafe) var standardError = StandardError()
