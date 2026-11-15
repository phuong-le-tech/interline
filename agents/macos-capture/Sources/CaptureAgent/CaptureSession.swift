import Foundation
import CaptureCore
import CaptureDevices

/// What `session.start` asks for, parsed once.
struct SessionRequest {
    let id: String
    let spoolDir: URL
    let window: Int
    let policy: STTPolicy
    let resume: ResumeRequest?

    struct ResumeRequest {
        let previousSpoolDirs: [URL]
        let nextSeq: [UInt32]
        let sttDoneUntilSample: [UInt64]
    }

    init(body: [String: Any], defaults: ModelDefaults = ModelDefaults()) throws {
        guard let id = body["session_id"] as? String, let spool = body["spool_dir"] as? String else {
            throw ProtocolValidationError(reason: "session.start needs session_id and spool_dir")
        }
        let audio = body["audio"] as? [String: Any] ?? [:]
        self.id = id
        self.spoolDir = URL(fileURLWithPath: spool)
        self.window = max(1, WireCodec.integer(audio["window"]) ?? 50)
        self.policy = defaults.applied(to: try STTPolicy.parse(body["stt"] as? [String: Any]))
        if let resume = body["resume"] as? [String: Any] {
            func perStream<T>(_ key: String, _ transform: (Int) -> T) -> [T] {
                let values = resume[key] as? [String: Any] ?? [:]
                return StreamID.names.map { transform(WireCodec.integer(values[$0]) ?? 0) }
            }
            self.resume = ResumeRequest(
                previousSpoolDirs: (resume["previous_spool_dirs"] as? [String] ?? []).map { URL(fileURLWithPath: $0) },
                nextSeq: perStream("next_seq") { UInt32(max(0, $0)) },
                sttDoneUntilSample: perStream("stt_done_until_sample") { UInt64(max(0, $0)) })
        } else {
            self.resume = nil
        }
    }
}

/// One recording: paces the input through spool, window, level meters, silent-tap detection and STT.
/// The test input and the live devices share this path; only the frame source differs.
final class CaptureSession {
    private static let tickNanos = UInt64(AudioConstants.frameMS) * 1_000_000

    let request: SessionRequest
    private let transport: StdioTransport
    private let input: TestInput
    private let window: DeliveryWindow
    private let spool: SpoolStore
    private var transcriber: Transcriber
    private let speed: Double
    private let simulateSilentSystem: Bool
    private let liveSetup: LiveSetup?
    private var live: LiveCapture?
    private let queue = DispatchQueue(label: "capture.session", qos: .userInteractive)
    private let detector = SilentTapDetector()
    private var timer: DispatchSourceTimer?
    private var cursor = 0
    private var liveStart: [Int]
    private var firstLive = [true, true]
    private var lastSeq: [Int64] = [-1, -1]
    private var stopped = false
    private var nextLive: [Int] = [0, 0]

    /// What a live (device) session needs beyond a test input.
    struct LiveSetup {
        let voiceProcessing: Bool
    }

    init(request: SessionRequest, transport: StdioTransport, input: TestInput, transcriber: Transcriber,
         speed: Double, simulateSilentSystem: Bool, live: LiveSetup? = nil) {
        self.request = request
        self.transport = transport
        self.input = simulateSilentSystem ? input.zeroingSystem() : input
        self.transcriber = transcriber
        self.speed = speed
        self.simulateSilentSystem = simulateSilentSystem
        self.liveSetup = live
        self.spool = SpoolStore(directory: request.spoolDir)
        self.window = DeliveryWindow(window: request.window, spool: spool)
        self.liveStart = [0, 0]
    }

    var sttBacklogMS: Int { transcriber.backlogMS }

    /// Replays what the previous agent left unacknowledged, then starts live capture right after it.
    func start() throws {
        var resumeFrom: [UInt8: UInt64] = [StreamID.mic: 0, StreamID.system: 0]
        if let resume = request.resume {
            for stream in [StreamID.mic, StreamID.system] {
                let next = resume.nextSeq[Int(stream)]
                window.resume(stream: stream, nextSeq: next)
                liveStart[Int(stream)] = Int(next)
                resumeFrom[stream] = resume.sttDoneUntilSample[Int(stream)]
                try replay(stream: stream, from: next, in: resume.previousSpoolDirs)
            }
            cursor = liveStart.min() ?? 0
        }
        do {
            try transcriber.begin(policy: request.policy, resumeFrom: resumeFrom) { [weak self] event in self?.emit(event) }
        } catch {
            // Capture, the spool and the meters stay useful without text; the core decides what to tell the user.
            log("speech recognition unavailable: \(error)")
            transport.send(.event("error", ["code": "model_load_failed", "severity": "warn", "retryable": false,
                                            "session_id": request.id, "message": "\(error)"]))
            transcriber = NullTranscriber()
        }
        try catchUpTranscription(from: resumeFrom)
        if let liveSetup {
            nextLive = liveStart
            try startDevices(liveSetup)
        } else {
            startTimer()
        }
    }

    /// Stops capture and drains STT. Returns the last sequence number captured on each stream (-1 if none).
    func stop() -> [Int64] {
        timer?.cancel()
        timer = nil
        live?.stop()
        live = nil
        queue.sync { stopped = true }
        transcriber.finish()
        try? spool.close()
        return lastSeq
    }

    func acknowledge(stream: UInt8, seq: UInt32, window newWindow: Int) {
        do { for frame in try window.acknowledge(stream: stream, seq: seq, window: newWindow) { transport.send(frame) } } catch {
            log("ack rejected: \(error)")
        }
    }

    // MARK: replay and catch-up

    private func replay(stream: UInt8, from next: UInt32, in directories: [URL]) throws {
        var frames: [UInt32: AudioFrame] = [:]
        for directory in directories {
            for frame in try SpoolStore.recover(directory: directory, stream: stream, nextSequence: next) { frames[frame.seq] = frame }
        }
        var expected = next
        for seq in frames.keys.sorted() {
            guard seq == expected, let frame = frames[seq] else { break }
            for out in try window.offer(frame) { transport.send(out) }
            lastSeq[Int(stream)] = Int64(seq)
            expected += 1
        }
        liveStart[Int(stream)] = Int(expected)
    }

    /// STT re-reads the input from where it stopped. The recording is the source, so a restart loses no text.
    private func catchUpTranscription(from resumeFrom: [UInt8: UInt64]) throws {
        guard request.resume != nil else { return }
        for stream in [StreamID.mic, StreamID.system] {
            let first = Int(resumeFrom[stream] ?? 0) / AudioConstants.frameSamples
            for index in first..<liveStart[Int(stream)] where index < input.frameCount {
                transcriber.feed(AudioFrame(stream: stream, flags: FrameFlags.recovered, seq: UInt32(index),
                                            firstSample: UInt64(index * AudioConstants.frameSamples), hostTimeNS: 0,
                                            pcm: input.samples(stream: stream, frame: index)))
            }
        }
    }

    // MARK: live devices

    private func startDevices(_ setup: LiveSetup) throws {
        let devices = LiveCapture(
            onFrame: { [weak self] stream, pcm in self?.queue.async { self?.liveFrame(stream: stream, pcm: pcm) } },
            onDeviceChange: { [weak self] stream, name in
                guard let self else { return }
                let atSample = self.queue.sync { UInt64(self.nextLive[Int(stream)] * AudioConstants.frameSamples) }
                self.transport.send(.event("device.changed", [
                    "session_id": self.request.id, "stream": StreamID.name(stream), "device": name, "at_sample": atSample]))
            },
            onFailure: { [weak self] stream, reason in
                guard let self else { return }
                self.transport.send(.event("error", ["code": "device_failure", "severity": "warn", "retryable": true,
                                                     "session_id": self.request.id,
                                                     "message": "\(StreamID.name(stream)): \(reason)"]))
            })
        try devices.start(voiceProcessing: setup.voiceProcessing)
        live = devices
    }

    private func liveFrame(stream: UInt8, pcm: [Int16]) {
        guard !stopped else { return }
        let index = nextLive[Int(stream)]
        nextLive[Int(stream)] += 1
        capture(stream: stream, index: index, pcm: pcm)
    }

    // MARK: pacing

    private func startTimer() {
        let source = DispatchSource.makeTimerSource(flags: .strict, queue: queue)
        let period = UInt64(Double(Self.tickNanos) / speed)
        source.schedule(deadline: .now(), repeating: .nanoseconds(Int(period)), leeway: .nanoseconds(0))
        source.setEventHandler { [weak self] in self?.tick() }
        timer = source
        source.resume()
    }

    private func tick() {
        guard !stopped else { return }
        guard cursor < input.frameCount else {
            timer?.cancel()
            timer = nil
            transcriber.finish()
            return
        }
        for stream in [StreamID.mic, StreamID.system] where cursor >= liveStart[Int(stream)] {
            capture(stream: stream, index: cursor)
        }
        cursor += 1
        for warning in (try? window.takeWarnings()) ?? [] {
            transport.send(.event("error", ["code": warning.code, "severity": warning.severity,
                                            "retryable": false, "session_id": request.id,
                                            "message": "Processing is behind; nothing is lost"]))
        }
    }

    private func capture(stream: UInt8, index: Int) {
        capture(stream: stream, index: index, pcm: input.samples(stream: stream, frame: index))
    }

    private func capture(stream: UInt8, index: Int, pcm: [Int16]) {
        let frame = AudioFrame(stream: stream, flags: 0, seq: UInt32(index),
                               firstSample: UInt64(index * AudioConstants.frameSamples),
                               hostTimeNS: clock_gettime_nsec_np(CLOCK_UPTIME_RAW), pcm: pcm)
        let resuming = request.resume != nil && firstLive[Int(stream)]
        firstLive[Int(stream)] = false
        do {
            for out in try window.offer(frame, resumingLive: resuming) { transport.send(out) }
        } catch {
            log("spool failure: \(error)")
            transport.send(.event("error", ["code": "spool_io", "severity": "warn", "retryable": true,
                                            "session_id": request.id, "message": "Cannot write the rescue spool"]))
        }
        lastSeq[Int(stream)] = Int64(index)
        sendLevel(stream: stream, pcm: pcm)
        transcriber.feed(frame)
        if stream == StreamID.system, (try? detector.observe(samples: pcm, otherProcessProducingOutput: otherProcessOutputs())) == true {
            transport.send(.event("permission.denied", [
                "session_id": request.id, "permission": "system_audio", "detection": "silent_stream",
                "evidence": "5 s of exact zeros while another process produces output", "can_prompt_again": false,
                "settings_uri": PermissionLinks.systemAudio]))
        }
    }

    /// The test input fakes "someone is playing audio" for the silent-tap path; live capture asks the system.
    private func otherProcessOutputs() -> Bool {
        liveSetup == nil ? simulateSilentSystem : LiveCapture.otherProcessProducesOutput()
    }

    private func sendLevel(stream: UInt8, pcm: [Int16]) {
        var sum = 0.0
        var peak = 0.0
        for sample in pcm {
            let value = Double(sample) / 32768
            sum += value * value
            peak = max(peak, abs(value))
        }
        let rms = (sum / Double(max(pcm.count, 1))).squareRoot()
        transport.send(.event("level", ["session_id": request.id, "stream": StreamID.name(stream),
                                        "rms": min(rms, 1), "peak": min(peak, 1)]))
    }

    private func emit(_ event: TranscriptEvent) {
        transport.send(TranscriptMessage.message(for: event, sessionId: request.id))
    }
}

/// Model paths given on the command line, used when `session.start` or `transcribe.file` carry none.
struct ModelDefaults {
    var model: URL?
    var vadModel: URL?

    func applied(to policy: STTPolicy) -> STTPolicy {
        var result = policy
        if result.modelPath == nil { result.modelPath = model?.path }
        if result.vadModelPath == nil { result.vadModelPath = vadModel?.path }
        return result
    }
}

enum TranscriptMessage {
    static func message(for event: TranscriptEvent, sessionId: String) -> ControlMessage {
        switch event {
        case let .partial(stream, seg, revision, start, text):
            return .event("stt.partial", ["session_id": sessionId, "stream": StreamID.name(stream), "seg": seg,
                                          "rev": revision, "start_sample": start, "end_sample": NSNull(), "text": text])
        case let .vad(stream, seg, start, end, closed):
            return .event("vad.segment", ["session_id": sessionId, "stream": StreamID.name(stream), "seg": seg,
                                          "start_sample": start, "end_sample": end.map { $0 as Any } ?? NSNull(),
                                          "state": closed ? "closed" : "open"])
        case let .final(stream, seg, start, end, text, language, logprob, model):
            return .event("stt.final", ["session_id": sessionId, "stream": StreamID.name(stream), "seg": seg,
                                        "start_sample": start, "end_sample": end, "text": text,
                                        "lang": language.map { $0 as Any } ?? NSNull(),
                                        "avg_logprob": logprob.map { $0 as Any } ?? NSNull(),
                                        "model": model, "words": NSNull()])
        }
    }
}

enum PermissionLinks {
    static let systemAudio = "x-apple.systempreferences:com.apple.preference.security?Privacy_AudioCapture"
    static let microphone = "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
}
