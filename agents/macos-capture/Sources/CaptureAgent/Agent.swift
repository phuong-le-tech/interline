import Foundation
import CaptureCore
import CaptureDevices
import WhisperSTT

/// Stands in when the speech models cannot be loaded: capture, spool, meters and permissions keep working.
final class NullTranscriber: Transcriber {
    let engine = "none"
    let backlogMS = 0
    func begin(policy: STTPolicy, resumeFrom: [UInt8: UInt64], emit: @escaping (TranscriptEvent) -> Void) throws {}
    func feed(_ frame: AudioFrame) {}
    func finish() {}
    func shutdown() {}
    func transcribeFile(_ url: URL, stream: UInt8, policy: STTPolicy,
                        emit: @escaping (TranscriptEvent) -> Void) throws -> (segments: Int, durationSamples: Int) {
        throw ModelLoadError("no speech recognition available")
    }
}

/// Exit codes of ARCHITECTURE 5.7.
enum ExitCode {
    static let ok: Int32 = 0
    static let badArguments: Int32 = 2
    static let protocolMismatch: Int32 = 3
    static let internalError: Int32 = 10
}

/// The protocol endpoint: handshake, heartbeats and the request loop.
final class Agent {
    private static let name = "interline-capture-macos"
    private static let version = "0.1.0"
    private static let heartbeatSeconds = 2

    private let options: AgentOptions
    private let transport = StdioTransport()
    private let transcriber: Transcriber
    private let defaults: ModelDefaults
    private let secrets = KeychainSecretStore()
    private var session: CaptureSession?
    private var heartbeat: DispatchSourceTimer?
    private var input: TestInput?

    init(options: AgentOptions, transcriber: Transcriber) {
        self.options = options
        self.transcriber = transcriber
        self.defaults = ModelDefaults(model: options.model, vadModel: options.vadModel)
    }

    func run() -> Int32 {
        transport.send(ControlMessage(type: "hello", id: "a-1", body: helloBody()))
        guard handshake() else { return ExitCode.protocolMismatch }
        startHeartbeat()
        while true {
            switch transport.next() {
            case .endOfStream:
                shutDown()
                return ExitCode.ok
            case .badFrame(let detail):
                transport.send(.event("error", ["code": "bad_frame", "severity": "fatal", "retryable": true,
                                                "message": "Invalid frame: \(detail)"]))
                shutDown()
                return ExitCode.ok
            case .ignoredAudio:
                continue
            case .control(let message):
                if message.type == "shutdown" {
                    shutDown()
                    transport.send(.response(to: message, type: "shutdown.ok"))
                    return ExitCode.ok
                }
                handle(message)
            }
        }
    }

    private func handshake() -> Bool {
        guard case .control(let reply) = transport.next() else { return false }
        return reply.type == "hello.ack"
    }

    // MARK: requests

    private func handle(_ message: ControlMessage) {
        switch message.type {
        case "session.start": startSession(message)
        case "session.stop": stopSession(message)
        case "audio.ack": acknowledge(message)
        case "transcribe.file": importFile(message)
        case "permission.status", "permission.request": answerPermissions(message)
        case "secret.get", "secret.put", "secret.delete": answerSecret(message)
        default:
            // Unknown requests are answered, unknown events are ignored (ARCHITECTURE 5.1).
            guard message.id != nil else { return }
            transport.send(.response(to: message, type: "error", errorBody("unsupported_message", "warn",
                                                                             "Unsupported request \(message.type)")))
        }
    }

    private func startSession(_ message: ControlMessage) {
        let request: SessionRequest
        do { request = try SessionRequest(body: message.body, defaults: defaults) } catch {
            transport.send(.response(to: message, type: "error", errorBody("invalid_request", "warn", "\(error)")))
            return
        }
        guard session == nil else {
            transport.send(.response(to: message, type: "error", errorBody("invalid_request", "warn",
                                                                             "A session is already running", request.id)))
            return
        }
        do {
            let source = isLive ? TestInput.empty : try loadInput()
            let started = CaptureSession(request: request, transport: transport, input: source,
                                         transcriber: transcriber, speed: options.testSpeed,
                                         simulateSilentSystem: options.simulateSilentSystem,
                                         live: isLive ? .init(voiceProcessing: options.voiceProcessing) : nil)
            transport.send(.response(to: message, type: "session.started", [
                "session_id": request.id, "t0_wall_ms": Int64(Date().timeIntervalSince1970 * 1000),
                "devices": isLive ? LiveCapture.currentDevices() : [String: Any](), "permissions": permissionStates()]))
            session = started
            try started.start()
        } catch {
            session = nil
            transport.send(.response(to: message, type: "error", errorBody("internal", "fatal", "\(error)", request.id)))
        }
    }

    private func stopSession(_ message: ControlMessage) {
        let id = message.body["session_id"] as? String ?? ""
        guard let running = session, running.request.id == id else {
            transport.send(.response(to: message, type: "error", errorBody("invalid_request", "warn", "No such session", id)))
            return
        }
        let last = running.stop()
        session = nil
        transport.send(.response(to: message, type: "session.stopped", [
            "session_id": id, "final_seq": ["mic": last[0], "system": last[1]]]))
    }

    private func acknowledge(_ message: ControlMessage) {
        guard let stream = (message.body["stream"] as? String).flatMap(StreamID.index(named:)),
              let seq = WireCodec.integer(message.body["seq"]), let window = WireCodec.integer(message.body["window"]) else { return }
        session?.acknowledge(stream: stream, seq: UInt32(seq), window: window)
    }

    /// Offline import: runs beside any live session, answers with the finals then `transcribe.file.done`.
    private func importFile(_ message: ControlMessage) {
        guard let jobId = message.body["job_id"] as? String, let path = message.body["path"] as? String,
              let stream = (message.body["stream"] as? String).flatMap(StreamID.index(named:)) else { return }
        let policy: STTPolicy
        do { policy = defaults.applied(to: try STTPolicy.parse(message.body["stt"] as? [String: Any])) } catch {
            transport.send(.response(to: message, type: "error", errorBody("invalid_request", "warn", "\(error)", jobId)))
            return
        }
        DispatchQueue.global(qos: .utility).async { [self] in
            do {
                let result = try transcriber.transcribeFile(URL(fileURLWithPath: path), stream: stream, policy: policy) { event in
                    if case .final = event { self.transport.send(TranscriptMessage.message(for: event, sessionId: jobId)) }
                }
                transport.send(.response(to: message, type: "transcribe.file.done", [
                    "job_id": jobId, "segments": result.segments, "duration_samples": result.durationSamples]))
            } catch let error as ModelLoadError {
                transport.send(.response(to: message, type: "error", errorBody("model_load_failed", "warn", "\(error)", jobId)))
            } catch {
                transport.send(.response(to: message, type: "error", errorBody("invalid_request", "warn", "\(error)", jobId)))
            }
        }
    }

    /// Keychain access for provider API keys. Values travel only in the reply to `secret.get` and are never logged.
    private func answerSecret(_ message: ControlMessage) {
        guard message.id != nil else { return }
        guard let account = message.body["account"] as? String, !account.isEmpty else {
            transport.send(.response(to: message, type: "error", errorBody("invalid_request", "warn", "account is required")))
            return
        }
        do {
            switch message.type {
            case "secret.put":
                guard let value = message.body["value"] as? String else {
                    transport.send(.response(to: message, type: "error", errorBody("invalid_request", "warn", "value is required")))
                    return
                }
                try secrets.put(account: account, value: value)
                transport.send(.response(to: message, type: message.type))
            case "secret.delete":
                try secrets.delete(account: account)
                transport.send(.response(to: message, type: message.type))
            default:
                transport.send(.response(to: message, type: message.type, ["value": try secrets.get(account: account)]))
            }
        } catch SecretStoreError.notFound {
            transport.send(.response(to: message, type: "error", errorBody("secret_not_found", "warn", "No secret for this account")))
        } catch {
            transport.send(.response(to: message, type: "error", errorBody("internal", "warn", "Keychain access failed")))
        }
    }

    private func answerPermissions(_ message: ControlMessage) {
        guard message.id != nil else { return }
        if isLive, message.type == "permission.request" {
            // Prompting blocks until the user answers, so it must not stall the request loop's heartbeat.
            let permission = message.body["permission"] as? String
            DispatchQueue.global(qos: .userInitiated).async { [self] in
                if permission == "microphone" { _ = Permissions.requestMicrophone() }
                else if permission == "system_audio" { _ = Permissions.requestSystemAudio() }
                transport.send(.response(to: message, type: message.type, permissionStates()))
            }
            return
        }
        transport.send(.response(to: message, type: message.type, permissionStates()))
    }

    private var isLive: Bool { options.testInput == nil }

    /// The test input touches no device and no TCC API, so there is nothing to ask the OS.
    private func permissionStates() -> [String: Any] {
        if isLive { return ["microphone": Permissions.microphone().rawValue, "system_audio": Permissions.systemAudio().rawValue] }
        return ["microphone": "granted", "system_audio": options.simulateSilentSystem ? "unknown" : "granted"]
    }

    private func loadInput() throws -> TestInput {
        if let input { return input }
        guard let url = options.testInput else {
            throw ProtocolValidationError(reason: "live capture is not available in this build; use --test-input")
        }
        let loaded = try TestInput.load(url)
        input = loaded
        return loaded
    }

    // MARK: lifecycle

    private func shutDown() {
        heartbeat?.cancel()
        _ = session?.stop()
        session = nil
        transcriber.shutdown()
    }

    private func startHeartbeat() {
        let source = DispatchSource.makeTimerSource(flags: .strict, queue: DispatchQueue(label: "agent.heartbeat"))
        source.schedule(deadline: .now() + .seconds(Self.heartbeatSeconds), repeating: .seconds(Self.heartbeatSeconds),
                        leeway: .nanoseconds(0))
        source.setEventHandler { [weak self] in
            guard let self else { return }
            self.transport.send(.event("heartbeat", ["stt_backlog_ms": self.session?.sttBacklogMS ?? 0,
                                                     "rss_mb": ResidentMemory.megabytes()]))
        }
        heartbeat = source
        source.resume()
    }

    private func helloBody() -> [String: Any] {
        let os = ProcessInfo.processInfo.operatingSystemVersion
        return [
            "protocol": ["min": 1, "max": 1],
            "agent": ["name": Self.name, "version": Self.version, "os": "macos",
                      "os_version": "\(os.majorVersion).\(os.minorVersion)", "arch": Self.architecture],
            "capabilities": [
                "capture": ["streams": ["mic", "system"], "aec": isLive && options.voiceProcessing, "device_events": isLive],
                "stt": ["engine": transcriber.engine, "engine_version": "1.9.5", "partials": true,
                        "accel": "metal", "transcribe_file": true],
                "permissions": ["microphone", "system_audio"],
                "test_input": true,
            ] as [String: Any],
        ]
    }

    private static var architecture: String {
        #if arch(arm64)
        return "arm64"
        #else
        return "x86_64"
        #endif
    }

    private func errorBody(_ code: String, _ severity: String, _ message: String, _ sessionId: String? = nil) -> [String: Any] {
        var body: [String: Any] = ["code": code, "severity": severity, "retryable": false, "message": message]
        if let sessionId { body["session_id"] = sessionId }
        return body
    }
}

enum ResidentMemory {
    static func megabytes() -> Int {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int(info.resident_size / 1_048_576) : 0
    }
}
