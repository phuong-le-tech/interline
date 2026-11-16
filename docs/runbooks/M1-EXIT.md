# M1 exit runbook: what only a person at the Mac can do

M1 is code-complete when `docs/ROADMAP.md` M1 content is merged and the checks in the PR pass. Three things remain
that an agent cannot do: the permission sitting, the 30 minute real call, and the benchmarks on other hardware.
This runbook is the exact procedure for each. Nothing here is automated away on purpose: macOS permission
dialogs and speakers cannot be driven from a script, and a real call is the only honest latency test.

## 0. One-time setup (5 min)

```sh
# Swift toolchain: Command Line Tools are enough (xcode-select --install). Full Xcode is not needed.
# SwiftPM can stall downloading the whisper xcframework; fetch it once and point the build at it:
cd agents/macos-capture
mkdir -p .whisper
curl -L -o .whisper/whisper-b5454-xcframework.zip \
  https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-b5454-xcframework.zip
swift package compute-checksum .whisper/whisper-b5454-xcframework.zip   # must print e57f8c48...392b
unzip -q .whisper/whisper-b5454-xcframework.zip -d .whisper
export INTERLINE_WHISPER_XCFRAMEWORK=.whisper/build-apple/whisper.xcframework
cd ../..

python3 scripts/bench/fetch-models.py ~/.interline-models small silero-v6.2.0   # sha256 verified
scripts/macos/signing-identity.sh        # creates "Interline Dev Signing" in its own keychain
(cd services/core && ./mvnw -B -DskipTests package)
```

## 1. The permission sitting (about 45 min) - ONE command

```sh
scripts/m1/human-gate.sh
```

It builds and signs `Interline.app`, then walks you through spikes (a) to (e), asks one question at a time and
writes `~/Library/Application Support/app.interline.dev/m1-gate/results.json`. Keep the machine on its speakers
(no headphones) for step (c) and have another app ready to play audio (a YouTube video is enough).

| Step | Permission | System Settings pane | Parent process of the agent | What you do | Success within 5 s | What the agent does with the answer |
|---|---|---|---|---|---|---|
| (a)+(b) attribution and tap | Microphone, System Audio Recording | Privacy & Security > Microphone; Privacy & Security > Screen & System Audio Recording > System Audio Recording Only | `Interline.app` > JVM (core) > `interline-capture-macos` | Click Record, allow both dialogs, speak, play audio elsewhere | Dialogs name Interline (not Java, not Terminal); the Me and Them meters move; Them text appears | Granted: frames flow. If the dialog names the wrong process, spike (a) FAILED and the Unix-socket fallback (ARCHITECTURE 5.1, shell-launched agent) is implemented in this PR |
| (c) echo cancellation | Microphone | same | same | Record twice (voice processing off, then on), run `say "the quick brown fox jumps over the lazy dog"` on the speakers while silent | With processing on, the sentence is NOT a visible Me row; with it off it is (or is suppressed by the core's text dedup) | The result decides whether `--voice-processing=on` becomes the default and `capture.aec:true` lowers the dedup threshold to 85 % (ADR-009) |
| (d) rebuild | Microphone, System Audio Recording | same | same | The script rebuilds with a code change signed with the same identity, then ad-hoc; Record and play audio each time | Same identity: no new dialog and Them meter moves. Ad-hoc: expect a dialog and possibly a silent stream | Fills the ADR-004 matrix rows (re-prompt yes/no, stream silent yes/no) |
| (e) denial | System Audio Recording | Privacy & Security > Screen & System Audio Recording | same | The script runs `tccutil reset` for both services and both bundle ids (exit codes are recorded: they verify the service names), you Deny system audio, play audio | The red banner "I can't hear the other participants" appears in under 10 s | The agent reports `permission.denied` (silent stream detector); the UI shows the banner with an "Open System Settings" link |

### Short re-run (after the first sitting)

The first sitting (2026-11-15) ran with no speech model, so only spike (d) is valid (ADR-004). The re-run covers
(a), (b), (c) and (e) in about 20 minutes and keeps the (d) answers:

```sh
scripts/m1/human-gate.sh --rerun
```

It downloads `small` and the Silero VAD into the app data directory first (sha256 verified), refuses to ask (b) or
(c) when the database holds no transcript rows for the stream concerned (it offers a retry instead), and starts
the (e) clock at the Deny click, not at the Record click.

When you are done, tell the agent runner (or comment on card t_1da2fafc) "gate done". It then reads
`results.json`, writes the ADR-004 "Spike results" and finishes ADR-009; if (a) failed it implements the
Unix-socket fallback in the same PR.

## 2. The 30 minute real call (open gate, not a block)

Goal: median STT latency under 5 s with `small` on an M1 with 8 GB, fully offline.

1. Build and sign: `scripts/macos/build-dev-app.sh`, then `open apps/desktop/src-tauri/target/debug/bundle/macos/Interline.app`.
2. In the app, Models: install `small` and the Silero VAD (the only moment the network is used; the download is
   explicit). Quit nothing.
3. Join a real call in a **browser** (Google Meet or Teams web) with at least one other person who talks.
4. In a Terminal, start the offline proof for the whole call:
   `scripts/m1/no-network-check.sh 1800 | tee ~/m1-network.txt`
   It must end with `PASS: ... zero non-loopback sockets`.
5. Click Record in Interline for the 30 minutes. Watch both meters; Me and Them text must scroll.
6. **Kill step**, at about minute 10: in another Terminal run `pkill -9 -x interline-capture-macos`.
   Expect the "Capture restarting" alert for under 2 s, then recording resumes, no duplicate text.
7. **Deny step**, at the end: Stop, then `tccutil reset AudioCapture app.interline.desktop`
   (and `app.interline.capture`), start a new recording, deny the dialog, check the banner appears in under 10 s.
8. Latency: `scripts/m1/latency-from-log.sh ~/Library/Logs/app.interline.desktop/*.log` prints the median and p95
   per stream (the same numbers as `GET /api/v1/meetings/{id}/stats`). Pass: median under 5000 ms.
9. Put the numbers, the macOS version, the model, `~/m1-network.txt` and any surprise in the PR.

## 3. Benchmarks on other hardware (open gate)

On each machine (M1 8 GB first, then M2/M3):

```sh
export INTERLINE_WHISPER_XCFRAMEWORK=...        # section 0
(cd agents/macos-capture && swift build -c release)
scripts/fixtures/gen-speech.sh
python3 scripts/bench/fetch-models.py ~/.interline-models        # about 2.2 GB
scripts/bench/run-bench.sh ~/.interline-models /tmp/bench
python3 scripts/bench/summarize.py /tmp/bench
```

Paste the tables into `docs/BENCHMARKS.md` under a new heading for the machine (replace the `[to verify]` rows).
On 8 GB machines also note whether `large-v3-turbo-q8_0` stays under memory pressure while a browser call runs.

## 4. Known limits when you start

- Everything under `agents/macos-capture/Sources/CaptureDevices` compiled and was self-tested for framing, but
  no real audio, TCC prompt or device change has been observed by an agent. Treat the first sitting as the test.
- System audio permission has no public status API: `permission.status` reports `unknown` for it until audio flows.
- The tap follows the default output device it was created for; a device switch rebuilds it (a short gap).
