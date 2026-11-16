# Agent process conformance

Tests only. The default subject is the packaged MockAgent jar, launched with `java -jar`
in a separate process. No fake peer, production logic, existing M0 tests, checked-in audio,
network, microphone, or TCC prompts are involved. Framing/envelope checks use a small
independent wire reader, not the production decoder under test.

From `services/core`:

```sh
# Compile and package prerequisite jars without running the intentionally red tests.
./mvnw -B --fail-at-end -pl agent-conformance -am -DskipTests package
# Run AC4; verify/package is important because the default subject is an executable jar.
./mvnw -B --fail-at-end -pl agent-conformance -am \
  -Dtest=M1Test -Dsurefire.failIfNoSpecifiedTests=false verify
# Exercise downstream modules even when an upstream M1 protocol test is red.
# This flag continues after assertion failures; it is NOT evidence of a green build.
./mvnw -B --fail-at-end -Dmaven.test.failure.ignore=true verify
python3 ../../scripts/m1/red-report.py
```

Do not use a fresh `test` lifecycle alone: it does not package the executable dependency.
An ordinary reactor `verify` packages MockAgent before executing conformance. The test
scoped dependency enforces ordering; it does not run MockAgent in the test JVM.

## Other executable

`interline.conformance.agent` is an executable path, never a shell command.
`interline.conformance.agentArgs` is a JSON array of literal argument strings, preserving
spaces without shell tokenization. Both standard `--protocol=1` and `--log-level=info`
are always passed. Extra arguments are appended afterwards. Supported substitutions:
`{wav}`, `{script}`, `{work}`. Generated fixtures live in JUnit temporary directories.

```sh
./mvnw -B --fail-at-end -pl agent-conformance -am verify \
  -Dinterline.conformance.agent=/absolute/path/to/agent \
  '-Dinterline.conformance.agentArgs=["--test-input={wav}"]'
```

The opt-in `swift-agent` profile is a macOS configuration skeleton, not a Swift builder.
Build the native executable independently before running it:

```sh
(cd ../../agents/macos-capture && swift build -c release)
./mvnw -B --fail-at-end -pl agent-conformance -am -Pswift-agent verify
```

Its default executable is `agents/macos-capture/.build/release/interline-capture-macos`.
Override either property if the native command line differs. Real STT model files must
already be downloaded; pass `-Dinterline.conformance.model_path=...`,
`-Dinterline.conformance.model_sha256=...`, and
`-Dinterline.conformance.vad_model_path=...` when required. The suite never creates fake
models, downloads a model, skips missing capabilities, or silently substitutes MockAgent
for a configured executable.

## Fixtures and observable coverage

* Default capture: generated 16 kHz stereo 440/660 Hz sine followed by exact silence,
  plus six scripted FR/EN utterances. Every PCM sample is checked for the default subject.
* Native capture: six generated `say`/`afconvert` utterances alternate channels in a
  24-second stereo timeline. Text need not equal TTS input. Native runs require these
  macOS commands and working preinstalled models.
* File import: six parameterized generated speech clips on macOS. On headless Linux the
  default MockAgent uses the generated tone plus script; real speech recognition is not
  claimed for MockAgent. No recordings are stored in the repository.
* AC4 checks hello within one second, version rejection/exit 3, two-second idle/active
  heartbeat cadence and metrics, stdout framing/envelopes, little-endian audio headers,
  per-stream sequence/sample continuity, 1600 samples per 100 ms, negotiated windows
  (3 and 50), reopening without loss, 10 Hz RMS levels, partial revisions/final identity,
  six final segments/VAD closure, stop counts and quiescence, unknown request correlation,
  unknown event/field compatibility, seven corrupt/truncated frame cases, stdin EOF and
  shutdown in idle/active states, permission.status, and six transcribe.file imports.

A red failure must identify missing observable behavior, not fail because the subject
was not packaged. Default MockAgent currently lacks M1 levels, partials, permission.status,
transcribe.file, and negotiated non-default windows. These are intentionally not fixed
in this tests-only module. Surefire reports identify each invocation with `AC4_contract_`.
