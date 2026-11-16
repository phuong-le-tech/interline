# ADR-009 - macOS capture: process tap, echo cancellation, whisper packaging, test input, compression

Status: **Draft**, 2026-11-15. Written at the end of the M1 implementation. Sections marked **PENDING** wait for
the human sitting (`docs/runbooks/M1-EXIT.md`, results in `results.json`); the code for them is written but no
agent has observed a real permission prompt or real device audio.
Related: ADR-002 (agent contract), ADR-003 (ports), ADR-004 (distribution and TCC), `docs/ARCHITECTURE.md` 5.8, 9, 14.

## Decisions

### 1. System audio: Core Audio process tap, ScreenCaptureKit only as a fallback

A global stereo tap (`CATapDescription(stereoGlobalTapButExcludeProcesses: [])`) on a private aggregate device,
macOS 14.2 minimum. It asks for "System Audio Recording Only", not for screen recording, which is the right
alarm level for a notepad. ScreenCaptureKit stays out of M1 (out of scope in the card); it remains the planned
fallback if a tap cannot be created.

A tap bound to the default output device stays on the old device after a switch, so the agent rebuilds it on
`kAudioHardwarePropertyDefaultOutputDevice` and sends `device.changed`. A denied tap returns zeros instead of an
error, hence the silent-stream detector (5 s of exact zeros while `kAudioProcessPropertyIsRunningOutput` is true for
another process) emitting `permission.denied{detection:"silent_stream"}`.
**PENDING (spike a, b, e), one re-run needed:** the first sitting could not answer them (ADR-004 "Spike results").
What is known: with the app signed by the stable identity both level meters moved, so the tap and the microphone
deliver audio when the agent is a grandchild (shell > JVM > agent) and the grants are held by
`app.interline.desktop`. Not yet confirmed: the app name shown in the dialogs, and the time from a denial to the
banner (the 17 s measured included answering the dialogs). The Unix-socket fallback is implemented only if the
re-run shows the dialog naming the wrong process.

### 2. Microphone and echo cancellation

`AVAudioEngine` input node. Apple voice processing (acoustic echo cancellation) is behind `--voice-processing`,
**off by default**. The agent advertises `capture.aec` only when it is on, and the core then raises the echo
suppression threshold from 70 % to 85 % of token overlap, because a real canceller already removed most echo.
**PENDING (spike c), one re-run needed:** the first sitting had no speech model, so no Me row could exist and
the answers are void. **Decision for now: voice processing stays off by default and `capture.aec` stays false.**
That is "unmeasured", not "refuted": nothing here claims that echo cancellation works. If the re-run shows that
voice processing removes the echo without damaging the voice, the default flips to on; if it ducks other audio
or hurts the Them stream, it stays off and the core's text dedup (ARCHITECTURE 9.2) is the only mitigation.

### 3. whisper.cpp packaging

The official prebuilt `whisper-b5454-xcframework.zip` (whisper.cpp v1.9.5, Metal shaders embedded) as a SwiftPM
`binaryTarget` pinned by checksum. It links and runs with Command Line Tools only: no Xcode, no `metal` compiler,
no cmake were needed. The fallback of a vendored C/C++ target was therefore not taken.
Two facts worth knowing:
- SwiftPM's own URL download of the zip stalled on the development network. `Package.swift` therefore honours
  `INTERLINE_WHISPER_XCFRAMEWORK` (path to the unzipped `.xcframework`); CI and a healthy network use the URL.
- The framework is dynamic and loaded from `@loader_path`: it must sit beside the agent in the app bundle
  (`scripts/macos/build-dev-app.sh` copies and signs it).

One whisper context per agent, one FIFO for both streams, STT on its own thread, backlog reported in
`heartbeat.stt_backlog_ms`. Silero VAD (`ggml-silero-v6.2.0.bin`) through whisper.cpp's built-in VAD.
Measured numbers (RTF, memory, WER) are in `docs/BENCHMARKS.md`.

Known limitation: with the `small` model, one 33 s synthetic French clip lost its middle (WER 78 %) when it was
forced into a single 30 s chunk because the synthetic voice has no pause. The turbo models did not. Real speech
has pauses (chunks close at 5 s + 500 ms silence), so this is recorded, not fixed, and re-checked on the real call.

### 4. `--test-input`: the same pipeline without devices

`--test-input=<wav>` feeds a stereo recording (left = mic, right = system) through the same resample, spool,
window, level, VAD and STT path as live capture, paced in real time (`--test-speed`). It touches no device and no
TCC API, so the whole contract (conformance kit, crash test, latency run, offline proof) runs headless and in CI.
The live devices differ only in how frames are produced (`LiveCapture` pushes 100 ms frames into the session).
Swift unit tests live in `CaptureSelfTest` because `swift test` needs Xcode.

### 5. Crash restart gap

The restart after `kill -9` takes about 1 s end to end (core detects the end of the agent's stdout, waits the
500 ms first restart delay, launches, handshakes). Two causes of a longer gap were found and fixed: the core
waited for the process exit instead of the end of the stream, and `resume.acked` used the last ack that reached the
dead agent instead of the durable watermark. A test tee JVM lingered about 2 s after its child died because a
thread blocked on stdin delays JVM exit on macOS; the test helpers now SIGKILL themselves.

### 6. Audio compression: decision only, no implementation in M1

The core stores 16 kHz PCM (about 115 MB per hour per stream). Decision: **do not ship compression in v1.0
unless retention proves too heavy**; when needed, encode FLAC **in the agent** (the platform audio frameworks
provide a FLAC encoder on macOS and Windows) rather than in Java, because no permissively licensed pure-Java FLAC
encoder was found to be maintained [to verify before M4]. The `audio.encode` capability of ARCHITECTURE 9.2 is the
place for it. No code was written.

## Consequences

- The agent binary embeds an `Info.plist` (`CFBundleIdentifier app.interline.capture`, both usage strings) with a
  linker flag, because a bare executable has no bundle for TCC to read.
- A stable code signature is required for grants to survive rebuilds (ADR-004 spike d).
- Everything in `agents/macos-capture/Sources/CaptureDevices` is **unverified on real hardware** until the sitting.

## Pending after the sitting

Fill the PENDING items above from `results.json`, flip the AEC default if justified, record the Unix-socket
fallback decision, and move this ADR to Accepted.
