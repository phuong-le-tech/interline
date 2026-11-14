# M0 red-test handoff

## Verified state

The red step adds tests and compilation/build skeletons only. Production behavior is intentionally absent. No PR is opened by this card.

- JDK 21.0.10, macOS: Maven wrapper 3.9.11 compiles all tests and packages both executable skeleton jars.
- Full collection: 229 Java executions, 220 assertion failures, 0 errors, 0 skipped. Nine scaffold/contract guard executions pass already; they are not artificially made red.
- Normal `./mvnw -B verify` exits 1 at the expected missing-schema assertions. Use the collection command below to execute later reactor modules while earlier modules remain red.
- Desktop frozen install, lint, typecheck and production build pass. One Chromium `AC14_live` test fails its explicit READY assertion because the real packaged core main is a throwing skeleton. Vite and the placeholder UI load successfully.
- A separately executed real fault-child smoke test passes: framed hello, observed hello.ack, silence while alive, clean EOF exit 0.
- The base repository had no application test suite. Existing documentation/design/scripts were not deleted or rewritten.

## AC mapping and failure output

[`RED_TEST_RESULTS.txt`](RED_TEST_RESULTS.txt) contains every AC1-AC17 mapping to the exact test methods, followed by one failure-output line per failing test method, with parameterized executions grouped. Raw Surefire/Failsafe XML and Playwright artifacts are build outputs, not source fixtures.

AC1 and AC12 cannot meaningfully be red after supplying a valid build and boundary-compliant skeleton. BuildTest, all three ArchitectureTest methods, bundled FTS5, clean EOF decoding, exact golden-type coverage, positive schema validation and the tracked-tree guard already pass. Positive validation alone is not sufficient: the empty-schema rejection test is red.

AC15 WorkflowTest checks static shape only. Actual green PR jobs require downstream PR-manager evidence. AC16 has a named manual checklist and a red Rust-budget guard, not fabricated native-window evidence. Neither external acceptance criterion is claimed complete.

## Commands actually run

```bash
# From services/core, using JDK 21
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -DskipTests package
# PASS: compilation and packaging, not behavior verification.

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -Dmaven.test.failure.ignore=true clean verify
# COLLECTOR ONLY: inspect XML; 229 executions / 220 failures / 0 errors / 0 skipped.
# The ignore flag makes Maven exit 0 despite red tests. Never use it as CI's success criterion.

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
# EXPECTED FAIL: missing V1 schema assertions in core-persistence.

# From apps/desktop
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm build
JAVA_HOME=$(/usr/libexec/java_home -v 21) pnpm test:e2e
# First four PASS; E2E EXPECTED FAIL: core READY absent.
```

## Minimal skeletons

- Maven parent and six module poms; generated Maven Wrapper 3.3.4 targeting Maven 3.9.11.
- `core-domain`: empty `PlatformCapabilities` interface.
- `core-ai`: empty `LlmPort` interface; no AI provider.
- `adapter-agent-protocol`: `Frame`, `ControlMessage`, `AudioChunk` records; empty `FrameCodec`, `AgentConnection`, `AgentProcess`, `RestartPolicy` signatures. No codec or supervisor logic.
- `core-persistence`: empty `V1__init.sql` so Flyway can run before assertions inspect the missing schema.
- `mock-agent`: throwing `MockAgent.main` and `FixtureWav.generateIfMissing`, plus POSIX jar launcher.
- `app`: SpringBootApplication annotation plus throwing main; throwing `DurableCaptureIngest` signatures. No endpoints, security, persistence or lifecycle wiring.
- `protocol/agent/v1/schema.json`: empty object skeleton. Synthetic goldens cover all 15 M0 types, including shutdown.ok, with binary and readable hex audio examples.
- `apps/desktop`: package/tooling/lockfile and empty React placeholder. No Record/Stop behavior or Tauri shell. Playwright uses real core/MockAgent, never intercepted HTTP.

## Reasonable decisions and plan deviations

- Tests run from a clean reactor: packaged core lifecycle/BuildTest run under app Failsafe after packaging. HandshakeTest stays in its planned adapter source path but runs through app Failsafe after both jars exist. This avoids missing-jar setup failures and a cyclic mock-agent dependency.
- jqwik 1.10.0 is not published as an engine; Maven Central metadata and actual builds verify 1.10.2, within the required 1.10.x line. ArchUnit 1.5.1, sqlite-jdbc 3.54.1.0, Boot 4.1.2 and AI BOM 2.0.2 resolve. Boot manages Flyway 12.4.0; SQLite support is in flyway-core, not a nonexistent flyway-database-sqlite module.
- In-process config/API/SSE assumptions are documented in `app/src/test/java/app/interline/app/APP_TEST_CONTRACT.md`. Use the same `interline.*` test bindings for security and pipeline contexts; production token input remains stdin only.
- AC7 introduces one proposed real durable-ingress adapter signature. The actual pipeline must use that path, not implement a separate facade that only satisfies the tests.
- Fixture JSON uses eight fake French regions per stream. Pipeline test input is generated in isolated temporary directories, not committed. Its deterministic sawtooth tone convention is test input, not a required production DSP algorithm. The production generator test checks format, determinism, regions and silence without pinning tone shape.
- The test-only transparent stdio tee observes actual resume/ACK frames. Readable bytes before ACK are necessary but do not prove fsync; fsync-before-ACK requires implementation/source review. Constant-time token comparison, absence of arbitrary secrets, and DEV-only endpoint fallback also require security review, not timing or entropy guesses.
- ArchitectureTest intentionally disallows all direct System property/environment discovery in production, stricter than just forbidden OS keys; use injected config and shell-supplied paths.
- Ordinary speaker inserts are tested with foreign keys enabled. V1 must not retain a live FK to the deferred people table. No product decision is needed to avoid that unusable schema.
- AC15/16 PR/manual evidence cannot be manufactured in this red step. Their actual validation belongs to implementation and PR-ready work.

## Downstream cautions

- Replace skeletons with behavior; do not weaken tests. Deeper pipeline/resume/dedup assertions are currently unreachable after the first missing-behavior assertion and must be exercised as implementation progresses.
- A test-only 50 ms heartbeat interval is proposed for AC8; default production interval remains 2 s. Backoff remains 0.5/1/2/4 seconds and six crashes in 60 seconds exhaust the budget.
- Keep test-only ProcessHandle/ProcessBuilder and Java Sound usage outside production imports.
- `SecurityTest` inspects the actual listener with lsof and does not force server.address to make binding pass. Ensure lsof exists on the Linux runner.
- The generated Windows wrapper uses standard CRLF; whitespace validation was run with `git -c core.whitespace=cr-at-eol diff --check`.
- Update ARCHITECTURE sections 13 and 17, ADR-010/011, workflow files, platform placeholder READMEs and the root status text during implementation. Carry the instruction-file block from the card into the PR body; never add CLAUDE.md or AGENTS.md.
