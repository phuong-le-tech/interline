# ADR-009 - macOS capture: process tap, echo cancellation, whisper packaging, test input, compression

Status: **Draft**, 2026-11-16, until Phuong accepts it. Spikes (a) to (e) were run in three human sittings
(`docs/runbooks/M1-EXIT.md`, results in `results.json`); the remaining open gates are the 30 min real call and
the M1 8 GB / M2 / M3 benchmark rows.
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
**Measured (second sitting, ADR-004 "Spike results"):** with the app signed by the stable identity, the tap and
the microphone deliver audio when the agent is a grandchild (Interline.app > JVM > agent): both meters moved and
real transcript rows exist for both streams. The grants appear in System Settings under `interline.app`, so the
attribution holds; the dialog texts were not recorded, so that evidence is the Settings entry only. The Unix-socket
fallback is not built because spike (a) did not fail.
**Measured (third sitting, spike e):** with system audio denied and other audio playing, the red banner appeared
4.3 s after the Deny click (limit 10 s) and its "Open System Settings" link opened Screen & System Audio
Recording. The 17 s of the first sitting included answering the dialogs. Short system utterances are not dropped
by the pipeline (headless check); the empty system stream of the second sitting stays unexplained, but the third
sitting's four French sentences all reached the system stream.

### 2. Microphone and echo cancellation

`AVAudioEngine` input node. Apple voice processing (acoustic echo cancellation) is behind `--voice-processing`,
**off by default**. The agent advertises `capture.aec` only when it is on, and the core then raises the echo
suppression threshold from 70 % to 85 % of token overlap, because a real canceller already removed most echo.
**Measured (third sitting, spike c, French sentence spoken 4 times on the speakers, user silent, same session
setup for both runs):**

| Voice processing | System rows | System rows with the sentence | Visible Me rows in the database |
|---|---|---|---|
| off | 4 | 4 | 0 (4 mirrored Me rows stored with `suppressed=1`) |
| on | 0 | 0 | 0 |

**Decision: voice processing stays off by default, `capture.aec` stays false, the dedup threshold stays at 70 %
(not 85 %).** With processing off the speaker echo does reach the microphone, but the core's text dedup
(ARCHITECTURE 9.2) flags every mirrored Me final, so no Me row survives in the public transcript. Cancelling
the echo at the source is not needed on this evidence (one Mac, built-in speakers, one sentence).

**Finding, voice processing on:** the system stream produced nothing (0 rows against 4, same audio, twice). It is
more than an inconclusive run. Two candidate causes were identified:

1. Enabling `setVoiceProcessingEnabled(true)` makes the input node reconfigure the audio hardware
   (aggregate/IO unit), which rebuilds or invalidates the process tap that is bound to the default output device.
   The agent restarted the microphone on `AVAudioEngineConfigurationChange` but did not rebuild the tap on that
   path, so the system stream died.
2. The process tap is incompatible with the voice-processing IO configuration and cannot produce frames while it
   is active.

**What shipped.** A `ConfigurationRestartCoordinator` now handles `AVAudioEngineConfigurationChange`: it stops and
restarts the microphone, then stops and rebuilds the process tap and its private aggregate device. A 500 ms
coalescing window absorbs the burst of notifications that the mic restart itself produces, so at most one
restart/rebuild pair fires. When voice processing is on, the coordinator also disables other-audio ducking
(`voiceProcessingOtherAudioDuckingConfiguration` with advanced ducking off and level set to min) before starting
the microphone, so the active call is not ducked. One-line diagnostics on stderr log tap/aggregate creation and
destruction (id, device, format, OSStatus) and, every 5 s per stream, frame count and peak level, so the hardware
sitting can confirm both streams are alive without logging PCM.

**Verification.** The restart coordinator and the diagnostics formatter are exercised in `CaptureSelfTest` with
injected fakes and a manual clock; all self-tests pass. Whether cause 1, cause 2 or both are the real failure on
hardware is only provable by re-running the sitting. The human gate (`scripts/m1/human-gate.sh --only c`) must
show `c_on_rows_system > 0` before this ADR is accepted.

**What the UI does with a suppressed row.** Finding at the sitting: the public transcript (`GET` transcript)
excludes suppressed rows (`suppressed = 0` filter) and they stay in the database, but the live panel applied
`stt.final` from SSE as it arrived and no event announced the suppression, so a mirrored Me row stayed visible live
until the transcript was reloaded. That explained the answer `c_mic_echo_visible_off=yes` against 0 visible rows in
the database: a live-display gap, not a storage defect.

**Closed by `transcript.suppressed`.** After the `stt.final` of a segment, the core publishes
`transcript.suppressed` `{meetingId, stream: "mic", seg}` for every mic final the dedup flags, in both delivery
orders: a mic final that repeats a system final is announced right after itself, and a system final that mirrors an
already stored mic final announces that earlier segment. The live panel removes the row of that `stream:seg` key and
remembers it, so a later partial or final on the same key cannot bring the row back. The event is additive and the
public transcript is unchanged.

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

Native AC5 compares normalized outside-gap transcript regions using character similarity
`1 - Levenshtein distance / max length`. The hypothesis that different chunk boundaries after restart
make whisper decode slightly different input is [to verify]; PCM outside the gap was byte-identical.
On 2026-11-27, three consecutive runs on one Apple M4 with the release agent, default GPU,
`ggml-tiny.bin`, `ggml-silero-v6.2.0.bin`, `--language=en` and Samantha passed: in each run, mic regions
0/1/3/4 scored 1.000000/1.000000/0.772152/1.000000 and system regions 0/1/3/4 scored
1.000000/1.000000/1.000000/1.000000 (region 2 overlaps the gap and is excluded). The threshold is 0.75,
a margin of 0.022152 below the observed minimum; an initial calibration run produced the same scores.
This threshold and its approximately 0.022 margin are based on measurements on that one Apple M4 with
the tiny model and are unverified on other GPUs and the CI VM; the first CI log prints every region score.
Unit controls reject blank recovery and half missing text, accept the reported region 3 pair (0.775) and
identical text, and fail for that pair when the threshold is temporarily raised to 0.80. PCM equality,
gap size, discontinuity, replay, region counts and the 1,999,999,999 ns resume limit are unchanged.
The GitHub macOS VM was not run locally; its 2 s latency gate may still fail independently of this change.

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

The spike items are filled. Left: the open gates above, one follow-up (voice processing kills the system stream),
and Phuong's acceptance of this ADR.
