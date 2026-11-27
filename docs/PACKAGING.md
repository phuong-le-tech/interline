# Local macOS packaging

This builds an unsigned distribution with local ad-hoc signatures for Apple Silicon. It does not use a signing identity, a keychain, entitlements, notarization, an updater or a release workflow. The release signing policy remains ADR-004. No models or user databases are bundled.

## Build and check

From the repository root, with JDK 21, Xcode Command Line Tools, Rust, Node and pnpm available:

```sh
scripts/package/build.sh
scripts/package/assert-bundle.sh
scripts/package/smoke-core.sh
scripts/package/measure-agent.sh
scripts/package/smoke-quit.sh
```

The explicit build downloads Maven, SwiftPM, Cargo and pnpm dependencies as needed. Launching the app does not download them. `build-core.sh` runs `cd services/core && ./mvnw -B -DskipTests package`. `prepare-core.sh` can be rerun independently after building that jar. `build.sh` builds the Swift sidecar, UI and release shell, assembles the app through Tauri, applies local ad-hoc signatures and creates a compressed local DMG with an Applications link.

Outputs:

- `apps/desktop/src-tauri/target/release/bundle/macos/Interline.app`
- `apps/desktop/src-tauri/target/release/bundle/dmg/Interline-local-aarch64-apple-darwin.dmg`
- `apps/desktop/src-tauri/target/package/`: module analysis, training log, jar SHA-256, sizes and staging resources.

Each check accepts an absolute app path as its first argument. `assert-bundle.sh` verifies required paths, all three usage strings, the byte-identical core jar and the local signature. Tauri embeds `frontendDist` for the webview; the same UI is also copied to `Resources/ui` to satisfy architecture 14.3. The capture agent requires `Contents/MacOS/whisper.framework` beside it because its load path is relative to the executable. This is the only additional runtime component beyond the tree shown in 14.3.

Resources and `externalBin` are supplied in the build script's Tauri configuration overlay. The base `tauri.conf.json` has `bundle.active=true`; keeping generated resource paths in the overlay allows `cargo check` and `pnpm tauri dev` from a clean checkout without first staging a release bundle. Development still uses the repository jar, `JAVA_HOME` or `java`, and the existing MockAgent fallback. Both old development sidecar names and the packaged `interline-capture` name are accepted.

The base configuration carries no signing identity; `signingIdentity: "-"` exists only in the local packaging overlay. The Maven wrapper pins the SHA-256 of the Maven 3.9.11 distribution zip, downloaded from its configured Maven Central URL. Tauri's installed CLI forwards Cargo arguments after `--`, so the build uses `pnpm tauri build ... -- --locked` to enforce the committed Cargo lockfile.

## Runtime and CDS

Verified toolchain: Oracle JDK 21.0.10, Spring Boot 4.1.2. The installed JDK's `jlink --list-plugins` accepts `--compress=zip-6`; the actual build uses `--strip-debug --no-header-files --no-man-pages --compress=zip-6`.

`jdeps --multi-release 21 --print-module-deps --ignore-missing-deps` on the final executable jar reports:

```text
java.base,java.management,java.net.http
```

That result cannot see the Boot jar's nested dependencies. The script extracts a temporary analysis copy and repeats the analysis recursively with `BOOT-INF/lib/*` on the classpath. It reports:

```text
java.base,java.compiler,java.desktop,java.instrument,java.net.http,java.prefs,java.scripting,java.security.jgss,java.sql.rowset,jdk.jfr,jdk.management,jdk.unsupported
```

Passing each modular third-party jar as a root instead fails resolution on the optional `io.swagger.v3.oas.annotations` module in the Anthropic SDK, even with `--ignore-missing-deps`. Recursive classpath analysis avoids that module-resolution failure.

The final explicit jlink roots are:

```text
java.base,java.sql,java.naming,java.desktop,java.management,java.logging,java.xml,java.net.http,java.security.jgss,java.instrument,jdk.unsupported,jdk.crypto.ec,jdk.zipfs
```

Optional compiler, scripting, rowset, JFR and management tooling roots are excluded. JDBC, naming, Spring bean introspection, HTTP/TLS and ZipFS support remain. Starting Tomcat proved that `java.security.jgss` and `java.instrument` are required in addition to the proposed architecture list: removing them caused `GSSException` and `IllegalClassFormatException` class-loading failures. Transitive modules are recorded by `core/runtime/release`. The linked runtime, including its base CDS archive, measured 64,140 KiB before assembly on this machine.

The script creates the base archive with `runtime/bin/java -Xshare:dump`, then trains with `-XX:ArchiveClassesAtExit=interline-core.jsa -Dspring.context.exit=onRefresh` and the production stack, GC, heap and headless options. Training sends a public `sk-test-` token through stdin, uses temporary data/log directories, and starts no agent. Boot's `onRefresh` exit succeeds with the original executable jar; no extracted application needs to be shipped. Dynamic proxy and JFR-class skip warnings are expected. The dynamic archive measured 49,296 KiB in the initial successful build.

`smoke-core.sh` enforces `-Xshare:on`, checks that Spring classes load from the dynamic archive, then starts and stops a MockAgent meeting against the bundled runtime and extracted SQLite library. The first assembled bundle loaded 2,489 Spring classes from the top archive. This also verifies relocation from the staging directory into the app. The jar is never rewritten: the script extracts only `org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib` from its nested SQLite jar, and the launcher supplies `-Dorg.sqlite.lib.path=<resources>/core/native`.

References: [JDK 21 jlink](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html), [Boot 4.1 CDS training](https://docs.spring.io/spring-boot/4.1/reference/packaging/aot-cache.html). The local checks above are the evidence for this exact executable-jar layout.

## Launch and shutdown

The release shell starts `Resources/core/runtime/bin/java` in `Resources/core`, in its own process group, with:

```text
-XX:SharedArchiveFile=interline-core.jsa -Xss512k -XX:+UseSerialGC -Xmx256m
-Djava.awt.headless=true -Dapple.awt.UIElement=true
-Dorg.sqlite.lib.path=<resources>/core/native -jar interline-core.jar
```

It passes the absolute bundled agent path, data/log directories, allowed origin and `--parent=stdio`. The launch token remains on stdin. A token-free READY line is mirrored to shell stderr for smoke checks.

The core's `run/pids.json` stores `owner` (shell), `core` and `agent` identities: pid, start time in epoch milliseconds and canonical executable path. The registry is replaced atomically on each agent launch/restart. A held `run/pids.lock` protects the data directory from concurrent cores. Both shell and core recovery compare all three identity fields before signalling a process. A live matching shell/core pair is refused rather than reaped. The core can also reap a matched orphan that still holds the lock after its owner disappeared, then retry the lock. The registry is deleted after clean shutdown; the lock file itself can remain.

The agent pid is recorded immediately after spawn; a core crash before that write relies on the agent's stdin-EOF lifeline and the shell's process-group fallback. Recovery never signals its own pid or reserved pids 0/1. Both readers consume at most 64 KiB; oversized, malformed or incomplete registries cause no signal and are replaced when the new core acquires ownership (the shell discards its invalid copy first). On POSIX filesystems, `run/` is created/tightened to 0700 and each new atomic `pids.json` is created as 0600.

The existing stdin-EOF lifelines in `Lifeline.java` and `CaptureAgent/Agent.swift` are reused. No parallel lifeline was added. Quit sends the shutdown command, waits up to 8 seconds, then signals the core's process group. Killing the shell closes the pipe and triggers core shutdown, which shuts down the agent and database. Java regression tests cover changed start time, changed executable, concurrent ownership and a locked orphan. The Rust test independently checks kernel identity matching through macOS libproc at the same millisecond precision as JDK `ProcessHandle`.

`smoke-quit.sh` creates an isolated HOME for each launch, so Tauri resolves data to `<home>/Library/Application Support/app.interline.desktop` and logs under that temporary home. It waits for READY and a visible layer-zero window, samples RSS ten seconds later, and tests Apple-event Quit (8 seconds) and shell SIGKILL (6 seconds). It only checks or cleans up process identities launched by that test. `INTERLINE_DATA_DIR`, `INTERLINE_LOG_DIR` and `INTERLINE_AGENT_ARGS` are debug-only: release builds ignore them. The release smoke supplies invalid values for those overrides and all three JVM injection variables, preloads oversized/malformed registry files, and checks recovery plus 0700/0600 permissions. Both core and agent spawn environments strip `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and every `DYLD_*` key. No test-only release feature was added. The application token is never logged or written by the scripts.

Local ad-hoc builds disable hardened runtime only in the packaging overlay and explicitly clear inherited runtime signing flags while applying their local signatures. The first real-agent probe otherwise failed dyld library validation because the ad-hoc process and whisper framework had no matching Team ID. This is a local test-build requirement, not the release hardened-runtime decision; that decision and any release entitlements remain open under ADR-004. No keychain was accessed.

## Measured budgets

Baseline build: commit `1ad579d`, before the security follow-up below. Machine: Apple M4, 32 GiB RAM, macOS 27.2.1 (26B131), arm64. Date: 2026-11-27. MB means 1,000,000 bytes; MiB means 1,048,576 bytes. M1 8 GB results, a second Mac, GitHub runners and real Gatekeeper dialogs remain [to verify].

These are measured process-cold launches with fresh databases and a warm filesystem cache, not post-reboot cold-cache measurements. The final core-only smoke reached READY in 1.302 s with CDS enforced; the two full-shell launches reached READY in 1.596 s and 2.024 s. Earlier full-shell launches under simultaneous build/test load took 2.679-3.057 s. The M1 cold-start target remains [to verify]. No test requested a model or actual microphone/system-audio recording.

| Metric from architecture 14.6 | Budget | Local result |
|---|---|---|
| Installed app | <= 200 MB, target 150 MB | **308.26 MB / 293.98 MiB, fails**; 308,256,768 allocated bytes, 54.1% over 200 MB |
| DMG | <= 100 MB | **215.22 MB / 205.25 MiB, fails**; 215,224,830 bytes |
| Window visible | <= 0.5 s | **0.254 s / 0.370 s, passes** |
| Core READY with CDS | M1 8 GB <= 2.0 s | M4 core-only **1.302 s**; full shell **1.596 s / 2.024 s**; M1 [to verify] |
| Agent hello | <= 0.3 s | **0.0124 s, passes**, real bundled Swift agent, first framed hello after spawn |
| Core idle RSS | <= 200 MB | **269.27 MB, fails**, 262,960 KiB in the LaunchServices sample |
| Shell idle RSS | shell + webview <= 120 MB | Shell **112.23 MB**; WebKit **96.60 MB**; combined **208.83 MB, fails** |
| Agent idle RSS | <= 30 MB | **18.79 MB, passes**, 18,352 KiB, no model loaded |
| All idle processes | <= 350 MB | **551.11 MB, fails**, 538,192 KiB including app-attributed WebKit and system XPC services |
| Whisper models | separate download | No models in installer; model sizes unchanged, not remeasured here |
| Recording RAM | idle + model | [to verify], no real model/call measured in this task |
| Live transcript latency | median < 5 s on M1 | [to verify], MockAgent smoke is not a transcription benchmark |
| Clean quit | <= 8 s, no leftovers | **0.716 s, passes**; both recorded processes gone |
| Shell SIGKILL cleanup | a few seconds, no leftovers | **0.604 s, passes**; both recorded processes gone |

Final assembled components: runtime 63,440 KiB (64.96 MB), unchanged jar 163,978,378 bytes, dynamic CDS 50,479,104 bytes, whisper.framework 15,248 KiB. The staging runtime is slightly larger because local ad-hoc signatures replace the original signatures during assembly. This task reports the overrun; it does not remove dependencies or alter the cross-platform jar to hide it.

Methods: installed size with `du -sk Interline.app`, DMG bytes with `stat -f %z`; window visibility with `CGWindowListCopyWindowInfo` sampled every 10 ms for the launched shell pid; READY from the shell's mirrored event; agent hello from the first framed control message; quit durations from the action until the shell and both recorded child identities disappear. The final smoke's two RSS samples at ten seconds after READY were core 251,472 / 263,824 KiB, shell 111,344 / 111,152 KiB and agent 18,416 / 18,416 KiB.

The all-process RAM sample above is from a separate `open -n -g -a <app>` LaunchServices launch after more than ten seconds idle, with fresh data. It uses `ps -axo pid=,ppid=,rss=,comm=` and macOS's measurement-only `responsibility_get_pid_responsible_for_pid` symbol from `/usr/lib/libSystem.B.dylib` to attribute XPC processes to the shell. No private API is used by the shipped app. Per-process sample:

| Process | RSS KiB |
|---|---:|
| Shell | 109,600 |
| Core | 262,960 |
| Swift capture agent | 18,352 |
| WebKit GPU | 26,128 |
| WebKit WebContent | 53,472 |
| WebKit Networking | 14,736 |
| SetStoreUpdateService, two processes | 8,384 + 8,672 |
| Audio SandboxHelper | 11,168 |
| ThemeWidgetControlViewService | 24,720 |
| Total | 538,192 |

This is a sum of RSS, not unique physical memory: shared pages can be counted in several processes. Excluding the other system XPC services still gives 496.89 MB for shell, WebKit, core and agent, above 350 MB. Smoke checks use temporary data and do not access personal notes, models, credentials or the login keychain.

## Initial M4-A verification and integration notes

All commands ran locally on 2026-11-27. No dependencies or workflow changes were added. No push or PR was made.

| Command / check | Result |
|---|---|
| `scripts/package/build.sh` | PASS, complete app and DMG from the documented root command |
| `scripts/package/assert-bundle.sh` | PASS, required layout, framework, exact core/launcher entries, jar identity, three usage strings, deep signature verification |
| `scripts/package/smoke-core.sh` | PASS, relocated runtime, enforced CDS, 2,489 shared Spring classes, SQLite and MockAgent meeting start/stop |
| `scripts/package/measure-agent.sh` | PASS, real agent hello and stdin EOF exit |
| `scripts/package/smoke-quit.sh` | PASS, Apple-event Quit and SIGKILL, no leftover core or agent |
| `cd services/core && ./mvnw -B verify` | PASS, 1,396 test executions from Maven aggregate summaries, 0 failures/errors, 7 skipped |
| `cd apps/desktop && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test` | PASS, 98 UI tests across 6 files |
| `cd apps/desktop && pnpm test:e2e` | PASS, 26 passed, 1 skipped; shared `/tmp/interline-e2e.lock` held and released |
| `cd apps/desktop && pnpm tauri dev --no-watch` | PASS, default Vite port 5173 and core READY, isolated data; CLI and Vite stopped afterwards under the same shared lock |
| `cd apps/desktop/src-tauri && cargo check && cargo test && cargo clippy -- -D warnings` | PASS, 3 Rust tests, no clippy warnings |
| LaunchServices plus Orca accessibility/screenshot inspection | PASS, real Interline window with Record, Models, Search, Today and Meetings, idle core and live real agent |
| Read-only DMG mount | PASS, `Interline.app` and Applications link present |
| `git fetch origin && git merge origin/main` | Already up to date; no import, API allowlist or Flyway migration changes |

The Java count includes unit and packaged-contract executions; skipped native tests are not real-device evidence. The extra locked-orphan regression and portable Java test children were also checked with `./mvnw -B -pl adapter-agent-protocol -am -Dtest=ProcessRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test` (3 passed). The existing core and Swift lifelines were inspected and the packaged tests exercised them without adding a second implementation.

The initial shell used **392 physical lines across all Rust source files, including tests**: `main.rs` 162, `supervisor.rs` 147, `process_registry.rs` 83. With coordinator approval, the legacy repository guard changed from 299 to the architecture's 400-line ceiling, still counting every Rust file. The security follow-up below exceeds that ceiling with explicit coordinator authorization; this branch leaves AC16 unchanged for the coordinator's separate cap decision.

Changed areas: `scripts/package/`; the Tauri Info.plist, configuration and launcher plus process identity matcher; the Java agent adapter's registry and launch bookkeeping; core bean lifecycle wiring and the repository budget regression; this document and ADR-013. The pre-existing untracked root `package.json` and `pnpm-lock.yaml` were left untouched.

Not verified: M1 8 GB, a second Mac, actual Gatekeeper/quarantine dialogs, release signing/TCC persistence, release entitlements or hardened-runtime policy, GitHub runners, real-model recording RAM and live transcript latency. The installed-size, DMG-size and RSS budgets currently fail and need separate work before the M4 milestone can claim those budgets.

## Security follow-up (2026-11-27)

Recovery now excludes the shell's own pid and Java's own/reserved pids, bounds both readers to 64 KiB, discards malformed/incomplete input without signalling, and creates private 0700/0600 registry storage. Regression tests exercise self identity, oversized valid JSON, malformed/trailing input, an incomplete core paired with a live agent, and actual POSIX permissions. The existing pid/start-time/executable and live-owner checks remain in place.

Release builds compile out the three development overrides and remove them from the core environment as well: otherwise Spring's relaxed binding can still interpret inherited `INTERLINE_AGENT_ARGS`. JVM and `DYLD_*` injection keys are removed independently at both process-spawn boundaries. Debug mode retains its directory/agent overrides. Packaged checks use HOME isolation instead of a test-only release feature and deliberately provide invalid development overrides/JVM options to catch either direct or indirect inheritance.

Usage strings are truthful in both the shell plist and the native agent's embedded plist: transcription occurs locally; calendar/transcript content can be sent when the user asks a configured AI provider to use it. The bundle assertion compares all three source strings with the installed plist and the actual native executable. Packaging removes only the generated agent executable before SwiftPM builds it, forcing relinking because SwiftPM does not track the plist supplied through linker flags. No Swift capture behavior changed.

The Maven distribution checksum was checked with a fresh `MAVEN_USER_HOME` and `./mvnw -v` (Maven 3.9.11). A copied wrapper with an intentionally incorrect checksum rejected the download with a SHA-256 validation error. Cargo receives `--locked` through Tauri's runner arguments. Ad-hoc signing remains exclusively in the local build overlay; no dependency, workflow, release-signing or entitlement change was added.

The current Rust total is **500 physical lines, including all tests**: `main.rs` 164, `supervisor.rs` 183, `process_registry.rs` 153. **M4 exception:** the product owner raised the AC16 shell cap to 550 lines (all Rust files, including tests); the measured 500 here fits. The original 400 budget is exceeded by process recovery and security guards [to review in M5].

| Security follow-up check | Result |
|---|---|
| `./mvnw -B -pl adapter-agent-protocol -am -Dtest=ProcessRegistryTest,ProcessEnvironmentTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS, 7 tests |
| `./mvnw -B '-Dtest=*,!RepositoryTest#AC16_manualShellSourceStaysWithinFourHundredRustLines' -Dsurefire.failIfNoSpecifiedTests=false verify` | PASS, 1,407 executions, 0 failures/errors, 7 skipped; excludes only the authorized AC16 red |
| `./mvnw -B -pl app -am -Dtest=RepositoryTest#AC16_manualShellSourceStaysWithinFourHundredRustLines -Dsurefire.failIfNoSpecifiedTests=false test` | PASS after the cap was raised to 550; 500 measured |
| `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test` | PASS, 98 UI tests |
| `pnpm test:e2e` under the shared lock | PASS, 26 passed, 1 existing native skip; lock released |
| `cargo check && cargo test && cargo clippy -- -D warnings` | PASS, 7 debug tests, no warnings |
| `cargo test --release --locked` | PASS, 7 release tests, including removal of Spring environment aliases |
| `pnpm tauri dev --no-watch` under the shared lock | PASS, development overrides honored, invalid JVM injection removed, READY reached; CLI and Vite stopped |

The first default `./mvnw -B verify` attempt reported AC16 plus a boot-time 404 timeout in `M2EnhanceTest`. The serialized all-other verification above reran that class (37 cases) and the entire gate successfully. Counts here come from Maven's per-phase aggregate log summaries, because subsequent targeted tests overwrite some JUnit XML files; the initial M4-A count above is corrected on the same basis. No test assertion or timeout was relaxed.
