# macOS capture agent (`interline-capture-macos`)

Swift tools 6.0, macOS 14.2 minimum. The agent speaks Agent Protocol v1 on stdio (ADR-002): it captures the microphone and system audio, writes a rescue spool, streams 100 ms frames with an ack window, reports levels, and transcribes locally with whisper.cpp (Metal) and Silero VAD.

Targets: `CaptureCore` (pure logic, no device API), `CaptureDevices` (process tap, microphone, device listeners, permissions), `WhisperSTT`, the `interline-capture-macos` executable and `CaptureSelfTest`.

## Build and test

Command Line Tools are enough (no Xcode). `swift test` does not work with them, so unit tests live in `CaptureSelfTest`.

```sh
swift build -c release
swift run CaptureSelfTest                      # exits non-zero on any failure
```

If SwiftPM stalls downloading the whisper xcframework (it did on the development network), fetch it once and point the build at it:

```sh
mkdir -p .whisper
curl -L -o .whisper/whisper-b5454-xcframework.zip https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-b5454-xcframework.zip
swift package compute-checksum .whisper/whisper-b5454-xcframework.zip     # must equal the checksum in Package.swift
unzip -q .whisper/whisper-b5454-xcframework.zip -d .whisper
export INTERLINE_WHISPER_XCFRAMEWORK=.whisper/build-apple/whisper.xcframework
```

## Running without devices

`--test-input=<stereo.wav>` (left = mic, right = system, any sample rate, `--test-speed=<n>`) runs the same resample, spool, window, level, VAD and STT path as live capture with no device and no permission, so it works headless and in CI. `--simulate-silent-system` exercises the silent-tap path. `--bench --model=<ggml> --input=<wav>` prints `{model, audio_s, wall_s, rtf, peak_rss_mb, text}`. `--model` / `--vad-model` / `--language` give defaults when the core does not send them. `--voice-processing[=off]` turns Apple's echo cancellation on the microphone on (off by default, ADR-009).

## Permissions and `tauri dev`

macOS attributes microphone and system audio permissions to the responsible process. In `pnpm tauri dev` that is your terminal, not Interline: grant the terminal when prompted. In the built app (`scripts/macos/build-dev-app.sh`) the agent is a grandchild of `Interline.app` and carries its own `Info.plist` (`app.interline.capture`, embedded with a linker flag) with both usage strings. Whether the prompt names Interline is spike (a) of `docs/runbooks/M1-EXIT.md`. The live device code is unverified on real hardware until that sitting.

## RED-stage notes

The sections below come from the test-writer and stay accurate as the contract the self-tests check.

From this directory:

```sh
swift build -c release
swift run CaptureSelfTest
```

`CaptureSelfTest` is an always-on, dependency-free assertion executable, not XCTest or swift-testing. Each case maps to AC2 or AC3 and prints one line. Any failed case makes the executable exit 1. Negative cases accept only `ProtocolValidationError`, never `NotImplemented`, arbitrary I/O errors, or assertion failures. Test counts are discovered, not a compatibility contract.

## Binding spool contract corrections

- Filenames are `<stream>-<firstSeq>-<firstSample>.wav`, not the older `<stream>-<n>.wav` wording in ARCHITECTURE 5.8. Sequence and sample position derive from the filename and physical PCM offsets; `recover(nextSequence:)` filters earlier frames, never renumbers history.
- Stale-header fixture `mic-17-27200.wav` contains sequences 17/18 at samples 27200/28800. Recovery reads all physical PCM despite a short declared data length. A separate nextSequence=18/19 case checks filtering without relabeling.
- Rotation uses `mic-0-0.wav`, then `mic-600-960000.wav` when the first frame beyond 60 seconds arrives. The system stream starts in `system-0-0.wav`.
- Recovery from `mic-9-14400.wav` followed by live sequence 10 at sample 32000 creates `mic-10-32000.wav`. The old PCM must remain unchanged; the gap must not be silently appended into the old file or shift the session timeline. Only the first resumed live frame carries discontinuity_before.
- Spool files remain while any frame is unacked and are deleted once fully acked, as ARCHITECTURE 5.8 requires. The core's durable PCM is the source of truth after ack; later STT recovery must use it as needed.

## Boundary coverage

AC2 covers binary golden roundtrip; unsigned extremes; maximum aligned audio frame below the 64 KiB length cap; over-limit encode/decode; truncated prefixes/headers/PCM; zero/hostile lengths; odd PCM; unknown/wrong kinds; invalid stream/flags/reserved fields; and trailing bytes. Audio codec methods consume full wire frames, including the big-endian length and kind. Length counts kind plus payload, excluding the four-byte prefix. Negative codec tests require `ProtocolValidationError` specifically.

Generated 44.1 kHz and 48 kHz stereo sine fixtures must downmix to 1600 mono samples per 100 ms while preserving frequency and RMS amplitude. Other AC2 coverage includes stale WAV headers, rotation, recovery timelines, disk overflow replay, per-stream isolation, the strictly-greater-than-60-second backlog warning, and exact-zero five-second detection once.

Window boundary tests exercise 1 and 50 with 125 frames (beyond the 10-second ring), bounded sends/replay batches, complete on-disk history, and duplicate/stale cumulative acks that grant no extra slots. Window 0 is rejected, following the checked-in audioAck schema minimum of 1; invalid acknowledgements must leave state unchanged. These are observable output/durability bounds, not a heap-allocation measurement: an implementation still needs bounded ring storage and nonblocking callback verification.

Chunk policy permits finalization at >=5 seconds with >=500 ms silence; continuous speech is forced at 30 seconds, not the 15-second target. Overlap is 500 ms, clamped at sample zero. Invalid configuration cases cover negative silence/overlap, nonpositive targets/maxima, max below the target upper bound, and overlap >= the minimum chunk duration. Reversed ClosedRange inputs cannot be represented safely in Swift and are not constructed. `ChunkPolicy.init` is now `throws`, with a `NotImplemented` body only, and all callers use `try`. No policy validation implementation is added.

## Control protocol

AC3 discovers shared NDJSON corpora relative to the source path, checks semantic control roundtrips, and rejects missing/wrong-typed envelope fields, malformed acknowledgements, truncated JSON, invalid UTF-8, and control input above 1 MiB. The explicit `golden/m1/m1.ndjson` presence case fails if that shared corpus is absent. Shared goldens and schemas are not changed by this package.

Unknown request types must decode with their type and request id available for dispatch to produce correlated `unsupported_message` (ARCHITECTURE 5.1). Rejecting them in the decoder would prevent that behavior, so the previous unknown-type rejection was replaced by a decode/correlation-preservation test. This does not assert that the stub implements dispatch. Shared schema type restrictions remain a separate Java validation concern.
