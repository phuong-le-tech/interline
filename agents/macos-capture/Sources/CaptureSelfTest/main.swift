import Foundation
import Darwin
import CaptureCore
import CaptureDevices

// Always-on assertions: unlike assert(), checks also run in release builds.
struct CheckFailure: Error, CustomStringConvertible { let description: String }
func require(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
    if try !condition() { throw CheckFailure(description: message) }
}
func rejects(_ action: () throws -> Void) throws {
    do { try action() } catch is ProtocolValidationError { return }
    throw CheckFailure(description: "expected ProtocolValidationError")
}
func temporaryDirectory(_ body: (URL) throws -> Void) throws {
    let base = ProcessInfo.processInfo.environment["TMPDIR"].map { URL(fileURLWithPath: $0) }
        ?? FileManager.default.temporaryDirectory
    let directory = base.appendingPathComponent("CaptureSelfTest-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    try body(directory)
}
let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
let goldens = repo.appendingPathComponent("protocol/agent/v1/golden")
func frame(_ seq: UInt32, stream: UInt8 = 0, firstSample: UInt64? = nil) -> AudioFrame {
    AudioFrame(stream: stream, flags: 0, seq: seq,
               firstSample: firstSample ?? UInt64(seq) * 1600,
               hostTimeNS: UInt64(seq) * 100_000_000, pcm: Array(repeating: 123, count: 1600))
}
func wav(_ pcm: [Int16], declaredBytes: UInt32) -> Data {
    var data = Data()
    func text(_ value: String) { data.append(contentsOf: value.utf8) }
    func le<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
    }
    text("RIFF"); le(36 + declaredBytes); text("WAVEfmt "); le(UInt32(16))
    le(UInt16(1)); le(UInt16(1)); le(UInt32(16000)); le(UInt32(32000))
    le(UInt16(2)); le(UInt16(16)); text("data"); le(declaredBytes)
    for sample in pcm { le(sample) }
    return data
}
func rows(_ file: URL) throws -> [Data] {
    try String(contentsOf: file, encoding: .utf8).split(separator: "\n").map { Data($0.utf8) }
}
func object(_ bytes: Data) throws -> [String: Any] {
    guard let result = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
        throw CheckFailure(description: "golden is not an object")
    }
    return result
}
func json(_ value: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: value) }
var cases: [(String, () throws -> Void)] = []
@MainActor
func test(_ name: String, _ body: @escaping () throws -> Void) { cases.append((name, body)) }

test("AC2_codec_binary_golden_roundtrip") {
    let wire = try Data(contentsOf: goldens.appendingPathComponent("audio.chunk.bin"))
    let decoded = try WireCodec.decodeAudio(wire)
    try require(decoded.stream == 1 && decoded.flags == 3, "stream/flags must match golden")
    try require(decoded.seq == UInt32.max, "sequence must remain unsigned")
    try require(decoded.firstSample == 1600 && decoded.hostTimeNS == 987654321, "little-endian timeline")
    try require(decoded.pcm == [-32768, -1, 0, 32767], "signed s16le extremes")
    try require(try WireCodec.encodeAudio(decoded) == wire, "wire roundtrip must be byte-identical")
}
// Audio codec takes full transport frames, including length and kind.
func audioWire(payload: Data, kind: UInt8 = 2, declaredLength: UInt32? = nil) -> Data {
    var length = (declaredLength ?? UInt32(payload.count + 1)).bigEndian
    var bytes = withUnsafeBytes(of: &length) { Data($0) }
    bytes.append(kind); bytes.append(payload)
    return bytes
}
for count in [0, 1, 2, 3, 4, 5, 28, 29, 36] {
    test("AC2_codec_reject_truncated_wire_\(count)") {
        let golden = try Data(contentsOf: goldens.appendingPathComponent("audio.chunk.bin"))
        try rejects { _ = try WireCodec.decodeAudio(Data(golden.prefix(count))) }
    }
}
test("AC2_codec_largest_even_audio_payload_roundtrip") {
    let audio = AudioFrame(stream: 0, flags: 7, seq: UInt32.max, firstSample: UInt64.max,
                           hostTimeNS: UInt64.max, pcm: Array(repeating: -32768, count: 32755))
    let wire = try WireCodec.encodeAudio(audio)
    try require(wire.count == 65539, "largest aligned length is 65535 after prefix, including kind")
    try require(try WireCodec.decodeAudio(wire) == audio, "unsigned limits and maximum payload roundtrip")
}
test("AC2_codec_reject_audio_over_limit_encode") {
    let audio = AudioFrame(stream: 0, flags: 0, seq: 0, firstSample: 0,
                           hostTimeNS: 0, pcm: Array(repeating: 0, count: 32756))
    try rejects { _ = try WireCodec.encodeAudio(audio) }
}
for (name, kind, payload, length) in [
    ("zero_length", UInt8(2), Data(), UInt32(0)),
    ("unknown_kind", 3, Data(repeating: 0, count: 24), 25),
    ("wrong_control_kind", 1, Data(repeating: 0, count: 24), 25),
    ("short_header", 2, Data(repeating: 0, count: 23), 24),
    ("odd_pcm", 2, Data(repeating: 0, count: 25), 26),
    ("over_limit", 2, Data(repeating: 0, count: 65536), 65537),
    ("hostile_length", 2, Data(), UInt32.max)
] {
    test("AC2_codec_reject_\(name)") {
        try rejects { _ = try WireCodec.decodeAudio(audioWire(payload: payload, kind: kind, declaredLength: length)) }
    }
}
for (name, index, value) in [("stream", 0, UInt8(2)), ("flags", 1, 8), ("reserved", 2, 1)] {
    test("AC2_codec_reject_invalid_\(name)") {
        var payload = Data(repeating: 0, count: 26); payload[index] = value
        try rejects { _ = try WireCodec.decodeAudio(audioWire(payload: payload)) }
    }
}
test("AC2_resample_44100_stereo_to_16k_mono_100ms") {
    let input: [Float] = (0..<4410).flatMap { n -> [Float] in
        let signal = Float(sin(2 * Double.pi * 1000 * Double(n) / 44100))
        return [0.75 * signal, 0.25 * signal]
    }
    let pcm = try PCMConverter().convert(interleaved: input, sampleRate: 44100, channels: 2)
    try require(pcm.count == 1600, "44.1kHz 100ms yields exactly 1600 output samples")
    let crossings = zip(pcm, pcm.dropFirst()).filter { $0 <= 0 && $1 > 0 }.count
    try require((99...101).contains(crossings), "44.1kHz preserves 1kHz frequency")
    let rms = sqrt(pcm.map { pow(Double($0) / 32768, 2) }.reduce(0, +) / Double(pcm.count))
    try require(abs(rms - 0.5 / sqrt(2)) < 0.03, "44.1kHz stereo must downmix")
}
test("AC2_spool_next_sequence_filters_without_renumbering") {
    try temporaryDirectory { directory in
        let firstPCM = Array(repeating: Int16(17), count: 1600)
        let secondPCM = Array(repeating: Int16(18), count: 1600)
        let pcm = firstPCM + secondPCM
        try wav(pcm, declaredBytes: 6400).write(to: directory.appendingPathComponent("mic-17-27200.wav"))
        let later = try SpoolStore.recover(directory: directory, stream: 0, nextSequence: 18)
        try require(later.map(\.seq) == [18] && later.map(\.firstSample) == [28800], "filter history; never relabel frames")
        try require(later.flatMap(\.pcm) == secondPCM, "filter exact frame PCM")
        try require(try SpoolStore.recover(directory: directory, stream: 0, nextSequence: 19).isEmpty, "exclude all older frames")
    }
}
test("AC2_resample_48k_stereo_to_16k_mono_100ms") {
    let input: [Float] = (0..<4800).flatMap { n -> [Float] in
        let signal = Float(sin(2 * Double.pi * 1000 * Double(n) / 48000))
        return [0.75 * signal, 0.25 * signal]
    }
    let pcm = try PCMConverter().convert(interleaved: input, sampleRate: 48000, channels: 2)
    try require(pcm.count == 1600, "100ms must yield exactly 1600 samples")
    let crossings = zip(pcm, pcm.dropFirst()).filter { $0 <= 0 && $1 > 0 }.count
    try require((99...101).contains(crossings), "1kHz frequency must survive resampling")
    let rms = sqrt(pcm.map { pow(Double($0) / 32768, 2) }.reduce(0, +) / Double(pcm.count))
    try require(abs(rms - 0.5 / sqrt(2)) < 0.03, "stereo must downmix, not select a channel")
}
test("AC2_spool_stale_header_uses_physical_pcm_length") {
    try temporaryDirectory { directory in
        let pcm = (0..<3200).map { Int16($0 % 1000) }
        try wav(pcm, declaredBytes: 3200).write(to: directory.appendingPathComponent("mic-17-27200.wav"))
        let recovered = try SpoolStore.recover(directory: directory, stream: 0, nextSequence: 17)
        try require(recovered.count == 2, "stale header must not hide second frame")
        try require(recovered.map(\.seq) == [17, 18], "sequence comes from filename, not next_seq renumbering")
        try require(recovered.map(\.firstSample) == [27200, 28800], "sample timeline comes from filename")
        try require(recovered.flatMap(\.pcm) == pcm, "recover all physical PCM exactly")
        try require(recovered.allSatisfy { $0.flags & 1 == 1 }, "recovery flag required")
    }
}
test("AC2_spool_names_rotate_exactly_at_60s") {
    try temporaryDirectory { directory in
        let spool = SpoolStore(directory: directory)
        for seq in UInt32(0)..<600 { try spool.append(frame(seq)) }
        let first = directory.appendingPathComponent("mic-0-0.wav")
        try require(FileManager.default.fileExists(atPath: first.path), "mic-0-0.wav required")
        try require(!FileManager.default.fileExists(atPath: directory.appendingPathComponent("mic-600-960000.wav").path), "do not rotate before next sample after 60s")
        try spool.append(frame(600)); try spool.append(frame(0, stream: 1)); try spool.close()
        try require(FileManager.default.fileExists(atPath: directory.appendingPathComponent("mic-600-960000.wav").path), "rotation name encodes first sequence and sample")
        try require(FileManager.default.fileExists(atPath: directory.appendingPathComponent("system-0-0.wav").path), "stream-specific names")
        try require(try Data(contentsOf: first).count == 44 + 600 * 3200, "60s WAV has canonical header and 960000 samples")
    }
}
test("AC2_spool_recovery_then_live_discontinuity_preserves_timeline") {
    try temporaryDirectory { directory in
        let oldFile = directory.appendingPathComponent("mic-9-14400.wav")
        let oldAudio = wav(Array(repeating: 1, count: 1600), declaredBytes: 0)
        try oldAudio.write(to: oldFile)
        let spool = SpoolStore(directory: directory)
        let recovered = try SpoolStore.recover(directory: directory, stream: 0, nextSequence: 9)
        try require(recovered.first?.seq == 9, "recovered sequence must continue")
        let live = try spool.resumeLive(frame(10, firstSample: 32000))
        try require(live.seq == 10 && live.firstSample == 32000, "restart gap must not shift timeline or reuse seq")
        try require(live.flags & 2 == 2 && live.flags & 1 == 0, "first live frame is discontinuous, not recovered")
        let next = try spool.resumeLive(frame(11, firstSample: 33600))
        try require(next.flags & 2 == 0, "discontinuity only on first live frame")
        try spool.close()
        try require(try Data(contentsOf: oldFile).dropFirst(44) == oldAudio.dropFirst(44), "restart must not append across gap")
        let liveFile = directory.appendingPathComponent("mic-10-32000.wav")
        try require(try Data(contentsOf: liveFile) == wav(live.pcm + next.pcm, declaredBytes: 6400), "new file records live discontinuous timeline exactly")
    }
}
test("AC2_window_overflow_spools_and_replays_in_order") {
    try temporaryDirectory { directory in
        let delivery = DeliveryWindow(window: 2, spool: SpoolStore(directory: directory))
        try require(try delivery.offer(frame(0)).map(\.seq) == [0], "first frame sent")
        try require(try delivery.offer(frame(1)).map(\.seq) == [1], "second frame sent")
        try require(try delivery.offer(frame(2)).isEmpty, "full window queues instead of sending")
        try require(try delivery.offer(frame(3)).isEmpty, "overflow must remain on disk")
        let persisted = try SpoolStore.recover(directory: directory, stream: 0, nextSequence: 0)
        try require(persisted.count == 4, "all frames spooled before sending, including overflow")
        try require(try delivery.acknowledge(stream: 0, seq: 0, window: 2).map(\.seq) == [2], "one cumulative ack opens one slot")
        try require(try delivery.acknowledge(stream: 0, seq: 2, window: 2).map(\.seq) == [3], "replay queued sequence without gaps")
        try require(try delivery.acknowledge(stream: 0, seq: 2, window: 2).isEmpty, "duplicate ack cannot replay twice")
    }
}
for window in [1, 50] {
    test("AC2_window_\(window)_bounded_replay_duplicate_stale_acks") {
        try temporaryDirectory { directory in
            let delivery = DeliveryWindow(window: window, spool: SpoolStore(directory: directory))
            // Exceed the 10s ring; returned batches must stay bounded, overflow durable.
            for seq in UInt32(0)..<125 {
                let sent = try delivery.offer(frame(seq))
                try require(sent.map(\.seq) == (seq < UInt32(window) ? [seq] : []), "never exceed advertised in-flight window")
            }
            let persisted = try SpoolStore.recover(directory: directory, stream: 0, nextSequence: 0)
            try require(persisted.map(\.seq) == Array(UInt32(0)..<125), "overflow beyond ring must all be durable")
            var released = window
            var ack = UInt32(window - 1)
            while released < 125 {
                let replay = try delivery.acknowledge(stream: 0, seq: ack, window: window)
                let end = min(released + window, 125)
                try require(replay.map(\.seq) == Array(UInt32(released)..<UInt32(end)), "release only available slots in sequence")
                try require(try delivery.acknowledge(stream: 0, seq: ack, window: window).isEmpty, "duplicate ack cannot grant slots")
                if ack > 0 {
                    try require(try delivery.acknowledge(stream: 0, seq: ack - 1, window: window).isEmpty, "stale ack cannot rewind or grant slots")
                }
                released = end; ack = UInt32(end - 1)
            }
        }
    }
}
test("AC2_window_zero_ack_rejected_without_mutation") {
    try temporaryDirectory { directory in
        let delivery = DeliveryWindow(window: 1, spool: SpoolStore(directory: directory))
        _ = try delivery.offer(frame(0)); _ = try delivery.offer(frame(1))
        try rejects { _ = try delivery.acknowledge(stream: 0, seq: 0, window: 0) }
        try require(try delivery.acknowledge(stream: 0, seq: 0, window: 1).map(\.seq) == [1], "invalid window must not mutate state")
    }
}
test("AC2_ack_stream_isolation_and_spool_retention") {
    try temporaryDirectory { directory in
        let delivery = DeliveryWindow(window: 1, spool: SpoolStore(directory: directory))
        _ = try delivery.offer(frame(0)); _ = try delivery.offer(frame(1))
        try require(try delivery.offer(frame(0, stream: 1)).count == 1, "mic window cannot block system")
        try require(try delivery.acknowledge(stream: 1, seq: 0, window: 1).isEmpty, "system ack cannot release mic")
        let file = directory.appendingPathComponent("mic-0-0.wav")
        try require(FileManager.default.fileExists(atPath: file.path), "unacked mic spool must remain")
        try require(try delivery.acknowledge(stream: 0, seq: 0, window: 1).map(\.seq) == [1], "mic ack replays mic")
        try require(FileManager.default.fileExists(atPath: file.path), "partial file ack cannot delete remaining frame")
        _ = try delivery.acknowledge(stream: 0, seq: 1, window: 1)
        try require(!FileManager.default.fileExists(atPath: file.path), "fully acked spool file is deleted per ARCHITECTURE 5.8")
    }
}
test("AC2_backpressure_warns_only_above_60s_without_loss") {
    try temporaryDirectory { directory in
        let delivery = DeliveryWindow(window: 1, spool: SpoolStore(directory: directory))
        for seq in UInt32(0)...600 { _ = try delivery.offer(frame(seq)) }
        try require(try delivery.takeWarnings().isEmpty, "exactly 60s backlog is not above threshold")
        _ = try delivery.offer(frame(601))
        try require(try delivery.takeWarnings() == [CaptureWarning(code: "backpressure", severity: "warn")], "60.1s backlog emits backpressure warn")
        let recovered = try SpoolStore.recover(directory: directory, stream: 0, nextSequence: 0)
        try require(recovered.count == 602, "backpressure must never discard captured frames")
    }
}
test("AC2_silent_detector_exact_5s_once") {
    let detector = SilentTapDetector()
    try require(try !detector.observe(samples: Array(repeating: 0, count: 79999), otherProcessProducingOutput: true), "no early denial at 79999 samples")
    try require(try detector.observe(samples: [0], otherProcessProducingOutput: true), "denial exactly at 80000 samples")
    try require(try !detector.observe(samples: Array(repeating: 0, count: 80000), otherProcessProducingOutput: true), "emit once, not every five seconds")
}
test("AC2_silent_detector_requires_exact_consecutive_zeros_and_output") {
    let detector = SilentTapDetector()
    try require(try !detector.observe(samples: Array(repeating: 0, count: 80000), otherProcessProducingOutput: false), "idle output is not denial")
    try require(try !detector.observe(samples: Array(repeating: 0, count: 79999), otherProcessProducingOutput: true), "idle period must not count")
    try require(try !detector.observe(samples: [1], otherProcessProducingOutput: true), "one LSB is not exact zero")
    try require(try !detector.observe(samples: Array(repeating: 0, count: 79999), otherProcessProducingOutput: true), "nonzero resets consecutive count")
    try require(try detector.observe(samples: [0], otherProcessProducingOutput: true), "new full silent interval triggers")
}
// Millisecond inputs make default policy inclusive boundaries explicit.
for (elapsed, silence, expected) in [
    (4999, 500, false), (5000, 499, false), (5000, 500, true),
    (14999, 0, false), (15000, 500, true), (15001, 0, false),
    (29999, 0, false), (30000, 0, true), (30001, 0, true)
] {
    test("AC2_chunk_policy_elapsed_\(elapsed)_silence_\(silence)") {
        let policy = try ChunkPolicy(minSilenceMS: 500, targetSeconds: 5...15, maxSeconds: 30, overlapMS: 500)
        try require(try policy.shouldFinalize(elapsedMS: elapsed, silenceMS: silence) == expected, "min 5s, silence >=500ms, hard max 30s")
    }
}
test("AC2_chunk_policy_overlap_500ms") {
    let policy = try ChunkPolicy(minSilenceMS: 500, targetSeconds: 5...15, maxSeconds: 30, overlapMS: 500)
    try require(try policy.nextStartSample(afterEndSample: 480000) == 472000, "500ms overlap equals 8000 samples")
    try require(try policy.nextStartSample(afterEndSample: 4000) == 0, "overlap must clamp at session zero")
}
for (name, silence, target, maximum, overlap) in [
    ("negative_silence", -1, 5...15, 30, 500),
    ("zero_target", 500, 0...15, 30, 500),
    ("negative_target", 500, -1...15, 30, 500),
    ("max_below_target", 500, 5...15, 14, 500),
    ("zero_max", 500, 5...15, 0, 500),
    ("negative_overlap", 500, 5...15, 30, -1),
    ("overlap_equals_min_chunk", 500, 5...15, 30, 5000),
    ("overlap_exceeds_min_chunk", 500, 5...15, 30, 5001)
] {
    test("AC2_chunk_policy_reject_\(name)") {
        try rejects { _ = try ChunkPolicy(minSilenceMS: silence, targetSeconds: target, maxSeconds: maximum, overlapMS: overlap) }
    }
}
// M1 lives in golden/m1/ so the unchanged M0 Java GoldenTest (non-recursive, exactly 15 M0 types) never sees it.
for filename in ["m0.ndjson", "m1/m1.ndjson", "m3/m3.ndjson"] {
    test("AC3_\(filename)_corpus_present_nonempty") {
        let file = goldens.appendingPathComponent(filename)
        try require(FileManager.default.fileExists(atPath: file.path), "missing shared golden \(filename)")
        try require(try !rows(file).isEmpty, "shared corpus cannot be empty")
    }
}
// Discover future M1 corpus additions without changing shared goldens.
do {
    let files = ["m0.ndjson", "m1/m1.ndjson", "m3/m3.ndjson"].map { goldens.appendingPathComponent($0) }
    for file in files {
        for (index, bytes) in try rows(file).enumerated() {
            let original = try object(bytes)
            let type = original["t"] as? String ?? "missing-type"
            test("AC3_decode_\(file.lastPathComponent)_\(index + 1)_\(type)") {
                let decoded = try WireCodec.decodeControl(bytes)
                let encoded = try WireCodec.encodeControl(decoded)
                try require(try NSDictionary(dictionary: object(encoded)).isEqual(to: original), "roundtrip preserves envelope and body")
            }
            for key in ["v", "t", "ts", "body"] {
                test("AC3_missing_\(key)_\(file.lastPathComponent)_\(index + 1)") {
                    var invalid = original; invalid.removeValue(forKey: key)
                    try rejects { _ = try WireCodec.decodeControl(json(invalid)) }
                }
                test("AC3_invalid_type_\(key)_\(file.lastPathComponent)_\(index + 1)") {
                    var invalid = original; invalid[key] = key == "t" ? 123 : "wrong-type"
                    try rejects { _ = try WireCodec.decodeControl(json(invalid)) }
                }
            }
        }
    }
} catch {
    let diagnostic = String(describing: error)
    test("AC3_discover_shared_goldens") { throw CheckFailure(description: diagnostic) }
}
test("AC2_codec_reject_trailing_bytes") {
    var wire = try Data(contentsOf: goldens.appendingPathComponent("audio.chunk.bin"))
    wire.append(0)
    try rejects { _ = try WireCodec.decodeAudio(wire) }
}
for (name, payload) in [
    ("truncated_json", Data(#"{"v":1,"t":"heartbeat""#.utf8)),
    ("invalid_utf8", Data([0xff, 0xfe])),
    ("over_1MiB", Data(#"{"v":1,"t":"heartbeat","ts":0,"body":{}}"#.utf8) + Data(repeating: 0x20, count: 1048577))
] {
    test("AC3_control_reject_\(name)") {
        try rejects { _ = try WireCodec.decodeControl(payload) }
    }
}
test("AC3_reject_missing_required_audio_ack_seq") {
    try rejects { _ = try WireCodec.decodeControl(Data(#"{"v":1,"t":"audio.ack","ts":0,"body":{"stream":"mic","window":50}}"#.utf8)) }
}
test("AC3_reject_invalid_body_audio_ack_seq_type") {
    try rejects { _ = try WireCodec.decodeControl(Data(#"{"v":1,"t":"audio.ack","ts":0,"body":{"stream":"mic","seq":"zero","window":50}}"#.utf8)) }
}
test("AC3_unknown_request_reaches_dispatch_for_unsupported_response") {
    let bytes = Data(#"{"v":1,"t":"not.a.message","id":"c-unknown","ts":0,"body":{}}"#.utf8)
    let decoded = try WireCodec.decodeControl(bytes)
    let encoded = try object(WireCodec.encodeControl(decoded))
    try require(encoded["t"] as? String == "not.a.message" && encoded["id"] as? String == "c-unknown", "decoder must preserve unknown request type and correlation for unsupported_message dispatch")
}
test("AC2_frame_assembler_reframes_odd_chunks_to_100ms_without_loss") {
    var assembler = FrameAssembler()
    var emitted: [[Int16]] = []
    var next: Int16 = 0
    for size in [1, 479, 1120, 4000, 1, 2999] {
        let chunk = (0..<size).map { _ -> Int16 in defer { next &+= 1 }; return next }
        emitted += assembler.push(chunk)
    }
    let total = 1 + 479 + 1120 + 4000 + 1 + 2999
    try require(emitted.count == total / 1600, "expected \(total / 1600) frames, got \(emitted.count)")
    try require(emitted.allSatisfy { $0.count == 1600 }, "every frame must hold exactly 1600 samples")
    try require(assembler.pendingSamples == total % 1600, "the remainder must wait for the next chunk")
    let flat = emitted.flatMap { $0 }
    try require(flat.enumerated().allSatisfy { Int16(truncatingIfNeeded: $0.offset) == $0.element }, "samples must stay in order")
}
// Voice processing on must not kill the system (process tap) stream (ADR-009 section 2).
// The coordinator, the diagnostics formatter and the statistics window are real; the source pair, the
// clock and the diagnostics sink are fakes, so nothing here touches hardware and nothing sleeps.

/// Records what the coordinator asked for and can fail the tap build on demand.
final class FakeConfigurationSource: ConfigurationRestartSource {
    enum TapFailure: Error, CustomStringConvertible {
        case unavailable
        var description: String { "the process tap could not be rebuilt" }
    }

    var events: [String] = []
    var duckingRequests: [OtherAudioDucking] = []
    var voiceProcessingFlags: [Bool] = []
    var failTapStart = false
    private(set) var micStarts = 0
    private(set) var micStops = 0
    private(set) var tapStarts = 0
    private(set) var tapStops = 0
    private(set) var micRunning = false

    func startMicrophone(voiceProcessing: Bool) throws {
        micStarts += 1
        micRunning = true
        voiceProcessingFlags.append(voiceProcessing)
        events.append("mic.start")
    }

    func stopMicrophone() {
        micStops += 1
        micRunning = false
        events.append("mic.stop")
    }

    func startSystemTap() throws {
        tapStarts += 1
        if failTapStart { throw TapFailure.unavailable }
        events.append("tap.start")
    }

    func stopSystemTap() {
        tapStops += 1
        events.append("tap.stop")
    }

    func setOtherAudioDucking(_ configuration: OtherAudioDucking) {
        duckingRequests.append(configuration)
        events.append("ducking")
    }
}

/// Monotonic millisecond clock the cases move by hand; the coordinator can never wait on real time.
final class ManualClock {
    private(set) var nowMS = 0
    func advance(_ milliseconds: Int) { nowMS += milliseconds }
}

func coordinator(_ source: FakeConfigurationSource, _ clock: ManualClock,
                 onFailure: @escaping (UInt8, String) -> Void = { _, _ in },
                 onDeviceChanged: @escaping (UInt8) -> Void = { _ in }) -> ConfigurationRestartCoordinator {
    ConfigurationRestartCoordinator(source: source, clock: { clock.nowMS },
                                    onFailure: onFailure, onDeviceChanged: onDeviceChanged)
}

test("AC1_event_restarts_mic_then_tap") {
    let source = FakeConfigurationSource()
    let clock = ManualClock()
    var failures: [(UInt8, String)] = []
    var devices: [UInt8] = []
    let restarts = coordinator(source, clock, onFailure: { failures.append(($0, $1)) },
                               onDeviceChanged: { devices.append($0) })
    try restarts.start(voiceProcessing: false)
    try require(source.micStarts == 1 && source.tapStarts == 1,
                "start must run the mic once and the tap once, got mic=\(source.micStarts) tap=\(source.tapStarts)")
    source.events.removeAll()
    restarts.configurationChanged()
    try require(source.events == ["mic.stop", "mic.start", "tap.stop", "tap.start"],
                "one configuration change must restart the mic, then rebuild the tap; got \(source.events)")
    try require(source.micStarts == 2 && source.tapStarts == 2,
                "exactly one mic restart and one tap rebuild, got mic=\(source.micStarts) tap=\(source.tapStarts)")
    try require(devices == [StreamID.mic, StreamID.system],
                "each successful restart must announce device.changed for mic then system, got \(devices)")
    try require(failures.isEmpty, "a nominal restart must not report a failure, got \(failures)")
}

test("AC2_five_events_one_pair") {
    let source = FakeConfigurationSource()
    let clock = ManualClock()
    let restarts = coordinator(source, clock)
    try restarts.start(voiceProcessing: false)
    for _ in 0..<5 {
        restarts.configurationChanged()
        clock.advance(100)
    }
    try require(source.micStarts == 2, "five events at 0,100,200,300,400 ms must restart the mic once, got \(source.micStarts - 1) restarts")
    try require(source.tapStarts == 2, "five events at 0,100,200,300,400 ms must rebuild the tap once, got \(source.tapStarts - 1) rebuilds")
}

test("AC2_window_boundary_499_500") {
    let source = FakeConfigurationSource()
    let clock = ManualClock()
    let restarts = coordinator(source, clock)
    try restarts.start(voiceProcessing: false)
    restarts.configurationChanged()
    clock.advance(499)
    restarts.configurationChanged()
    try require(source.micStarts == 2 && source.tapStarts == 2,
                "an event 499 ms after the pair still coalesces, got mic=\(source.micStarts) tap=\(source.tapStarts)")
    clock.advance(1)
    restarts.configurationChanged()
    try require(source.micStarts == 3 && source.tapStarts == 3,
                "an event exactly 500 ms after the pair starts a new pair, got mic=\(source.micStarts) tap=\(source.tapStarts)")
}

test("AC2_idle_no_restart_loop") {
    let source = FakeConfigurationSource()
    let clock = ManualClock()
    let restarts = coordinator(source, clock)
    try restarts.start(voiceProcessing: false)
    restarts.configurationChanged()
    clock.advance(5000)
    try require(source.micStarts == 2 && source.tapStarts == 2,
                "five idle seconds with no event must not restart anything, got mic=\(source.micStarts) tap=\(source.tapStarts)")
}

test("AC3_event_after_stop_ignored") {
    let stopped = FakeConfigurationSource()
    let stoppedClock = ManualClock()
    let stoppedCoordinator = coordinator(stopped, stoppedClock)
    try stoppedCoordinator.start(voiceProcessing: false)
    stoppedCoordinator.stop()
    stopped.events.removeAll()
    stoppedCoordinator.configurationChanged()
    try require(stopped.micStarts == 1 && stopped.tapStarts == 1,
                "an event after stop must not restart or rebuild, got mic=\(stopped.micStarts) tap=\(stopped.tapStarts)")
    try require(stopped.events.isEmpty, "an event after stop must not touch the sources, got \(stopped.events)")

    let restarted = FakeConfigurationSource()
    let restartedClock = ManualClock()
    let restartedCoordinator = coordinator(restarted, restartedClock)
    try restartedCoordinator.start(voiceProcessing: false)
    restartedCoordinator.configurationChanged()
    restartedCoordinator.stop()
    restartedCoordinator.configurationChanged()
    try require(restarted.micStarts == 2 && restarted.tapStarts == 2,
                "a stop after one pair must keep that pair and ignore the late event, got mic=\(restarted.micStarts) tap=\(restarted.tapStarts)")
}

test("AC4_tap_failure_reports_once_mic_alive") {
    let source = FakeConfigurationSource()
    let clock = ManualClock()
    var failures: [(UInt8, String)] = []
    let restarts = coordinator(source, clock, onFailure: { failures.append(($0, $1)) })
    try restarts.start(voiceProcessing: false)
    source.failTapStart = true
    restarts.configurationChanged()
    try require(failures.count == 1, "a tap rebuild failure must report exactly one error, got \(failures)")
    try require(failures.first?.0 == StreamID.system,
                "the tap failure belongs to the system stream, got stream \(String(describing: failures.first?.0))")
    try require(!(failures.first?.1 ?? "").isEmpty, "the tap failure must carry a reason")
    try require(source.micStarts == 2, "the mic restarts once before the tap rebuild, got \(source.micStarts) starts")
    try require(source.micStops == 1, "a failed rebuild must not stop the mic again, got \(source.micStops) stops")
    try require(source.micRunning, "the mic must keep running after a failed tap rebuild")
}

test("AC5_ducking_disabled_only_when_on") {
    let on = FakeConfigurationSource()
    let onClock = ManualClock()
    let onCoordinator = coordinator(on, onClock)
    try onCoordinator.start(voiceProcessing: true)
    try require(on.duckingRequests.count == 1,
                "voice processing on must request other-audio ducking exactly once, got \(on.duckingRequests.count)")
    try require(on.duckingRequests.first?.advancedDuckingEnabled == false,
                "voice processing on must turn advanced ducking off, got \(String(describing: on.duckingRequests.first))")
    try require(on.duckingRequests.first?.level == .min,
                "voice processing on must duck at the minimum level, got \(String(describing: on.duckingRequests.first))")
    try require(on.events.first == "ducking",
                "the ducking configuration must be requested before the mic starts, got \(on.events)")

    let off = FakeConfigurationSource()
    let offClock = ManualClock()
    let offCoordinator = coordinator(off, offClock)
    try offCoordinator.start(voiceProcessing: false)
    try require(off.duckingRequests.isEmpty,
                "voice processing off must leave other-audio ducking untouched, got \(off.duckingRequests)")
}

test("AC6_diagnostics_line_shapes") {
    var lines: [String] = []
    let clock = ManualClock()
    let diagnostics = CaptureDiagnostics(clock: { clock.nowMS }, sink: { lines.append($0) })
    diagnostics.tapCreated(id: 42, device: "MacBook Pro Speakers", format: "48000 Hz, 2 ch", status: 0)
    diagnostics.tapDestroyed(id: 42, device: "MacBook Pro Speakers", format: "48000 Hz, 2 ch", status: 0)
    diagnostics.aggregateCreated(id: 7, device: "Interline tap", format: "48000 Hz, 2 ch", status: 0)
    diagnostics.aggregateDestroyed(id: 7, device: "Interline tap", format: "48000 Hz, 2 ch", status: -50)
    try require(lines == [
        #"capture.tap created id=42 device="MacBook Pro Speakers" format="48000 Hz, 2 ch" status=0"#,
        #"capture.tap destroyed id=42 device="MacBook Pro Speakers" format="48000 Hz, 2 ch" status=0"#,
        #"capture.aggregate created id=7 device="Interline tap" format="48000 Hz, 2 ch" status=0"#,
        #"capture.aggregate destroyed id=7 device="Interline tap" format="48000 Hz, 2 ch" status=-50"#
    ], "tap and aggregate lines must carry id, device, format and OSStatus; got \(lines)")

    diagnostics.observe(stream: StreamID.mic, frames: 1600, peak: 0.5)
    diagnostics.observe(stream: StreamID.system, frames: 800, peak: 0.25)
    clock.advance(4999)
    diagnostics.observe(stream: StreamID.mic, frames: 1600, peak: 0.9)
    try require(lines.count == 4, "no stats line before the 5 s interval elapses, got \(lines.count) lines")
    clock.advance(1)
    diagnostics.observe(stream: StreamID.mic, frames: 1600, peak: 0.75)
    try require(Array(lines.dropFirst(4)) == [
        "capture.stats stream=mic window_ms=5000 frames=4800 peak=0.900",
        "capture.stats stream=system window_ms=5000 frames=800 peak=0.250"
    ], "one stats line per active stream on the 5 s boundary, frame count and peak only; got \(Array(lines.dropFirst(4)))")
    try require(lines.allSatisfy { !$0.contains("[") && !$0.lowercased().contains("pcm") },
                "diagnostics must never carry samples, got \(lines)")
}

test("AC7_regression_voice_processing_off_unchanged") {
    let source = FakeConfigurationSource()
    let clock = ManualClock()
    let restarts = coordinator(source, clock)
    try restarts.start(voiceProcessing: false)
    try require(source.duckingRequests.isEmpty,
                "voice processing off must not touch other-audio ducking, got \(source.duckingRequests)")
    try require(source.voiceProcessingFlags == [false],
                "the mic starts with voice processing off, got \(source.voiceProcessingFlags)")
    source.events.removeAll()
    restarts.configurationChanged()
    try require(source.events == ["mic.stop", "mic.start", "tap.stop", "tap.start"],
                "voice processing off still restarts the mic then rebuilds the tap; got \(source.events)")
}

test("AC19_keychain_roundTrip_putGetOverwriteDelete") {
    let store = KeychainSecretStore()
    let account = "selftest-\(UUID().uuidString)"
    defer { try? store.delete(account: account) }
    try store.put(account: account, value: "sk-test-keychain-0001")
    try require(try store.get(account: account) == "sk-test-keychain-0001", "get must return the stored value")
    try store.put(account: account, value: "sk-test-keychain-0002")
    try require(try store.get(account: account) == "sk-test-keychain-0002", "a second put must overwrite the first")
    try store.delete(account: account)
    do {
        _ = try store.get(account: account)
        throw CheckFailure(description: "get after delete must throw notFound")
    } catch SecretStoreError.notFound {
        // expected
    }
}

test("AC19_keychain_getOfUnknownAccountIsNotFound") {
    let store = KeychainSecretStore()
    do {
        _ = try store.get(account: "selftest-never-stored-\(UUID().uuidString)")
        throw CheckFailure(description: "get of an unknown account must throw notFound")
    } catch SecretStoreError.notFound {
        // expected
    }
}

test("AC19_keychain_deleteOfUnknownAccountIsNotAnError") {
    let store = KeychainSecretStore()
    try store.delete(account: "selftest-never-stored-\(UUID().uuidString)")
}

test("AC19_keychain_itemsLiveUnderServiceAppInterline") {
    try require(KeychainSecretStore.defaultService == "app.interline", "service must be app.interline")
    let account = "selftest-\(UUID().uuidString)"
    let other = KeychainSecretStore(service: "app.interline.selftest-other")
    defer { try? KeychainSecretStore().delete(account: account); try? other.delete(account: account) }
    try KeychainSecretStore().put(account: account, value: "sk-test-keychain-0003")
    do {
        _ = try other.get(account: account)
        throw CheckFailure(description: "an item stored under app.interline must not be visible under another service")
    } catch SecretStoreError.notFound {
        // expected
    }
}

var failures = 0
for (name, body) in cases {
    do { try body(); print("PASS \(name)") }
    catch { failures += 1; print("FAIL \(name): \(error)") }
}
print("CaptureSelfTest: \(cases.count) cases, \(failures) failures")
exit(failures == 0 ? 0 : 1)
