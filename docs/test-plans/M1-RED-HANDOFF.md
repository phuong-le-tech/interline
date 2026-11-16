# M1 RED handoff

This is the failing-tests stage, not an implemented M1 feature. No M0 test/helper,
M0 golden, production behavior, CI job or protected instruction file was changed.
`M1-RED-RESULTS.md` contains the parsed Java execution counts and one line per
failed Java/Swift invocation.

## AC mapping

Paths follow `M1-TEST-PLAN.md`: A=app M1Test, J=M1GoldenTest,
C=agent-conformance M1Test, S=CaptureSelfTest, U=e2e/m1.spec.ts.

| AC | Tests |
|---|---|
| AC1 | A `AC1_regressionDurableIngressAndAuthenticatedHttpKeepM0TranscriptIdentity`; unchanged M0 suites; C included in reactor |
| AC2 | S `AC2_codec_*`, `AC2_resample_*`, `AC2_spool_*`, `AC2_window_*`, `AC2_ack_stream_isolation_and_spool_retention`, `AC2_backpressure_warns_only_above_60s_without_loss`, `AC2_silent_detector_*`, `AC2_chunk_policy_*` |
| AC3 | J `AC3_goldensM1CorpusCoversEveryAdditiveMessage`, `AC3_goldensEveryMessageValidatesAndDecodesWithoutLosingFields`, `AC3_goldensRejectMissingRequiredBodyAndIncorrectType`, `AC3_goldensM0IsByteIdenticalToMilestoneBase`; S `AC3_*` |
| AC4 | C `AC4_contract_*`, enumerated below |
| AC5 | A `AC5_restartAfterRealSigkillReplaysFramesAndPersistsEveryOutsideGapUtteranceOnce`, `AC5_swiftSigkillHeadlessInputUsesRealNativeProcess`; test-only M1NativeProbe/M1NativeContract |
| AC6 | A `AC6_silentSystemSurfacesPermissionBannerEventWithinTenSeconds`, `AC6_swiftSilentInjectionRelaysPermissionDenialOnRealSse`; U `AC6_silent` for Mock silent-system and explicit-denial cases |
| AC7 | A `AC7_downloadResumesRangeAndOnlyRenamesAfterSha256Verification`, `AC7_interruptedHttpBodyLeavesPartThenResumesExactOffset`, `AC7_checksumMismatchDeletesPartAndNeverPublishesFinalModel`, `AC7_explicitDownloadEndpointIsTheOnlyUserInitiatedFetchPath`, `AC7_bootRecordingAndShutdownNeverContactConfiguredModelOrigin` |
| AC8 | `scripts/m1/catalog-check.sh`: `AC8_catalog` and per-model checks |
| AC9 | A `AC9_echoRetainsSqliteRowsButHidesOnlySuppressedMicFinals` (70%/85%, +/-2000ms edges, delivery order, distinct text), `AC9_publicTranscriptHidesPersistedSuppressedRowsWithoutDeletingThem` |
| AC10 | A `AC10_startupImportsStaleHeaderSpoolAcrossHoleExactlyOnce`, `AC10_recoveryQueuesUntilNextAgentThenPersistsFinalsWithoutDuplicates` (recording/degraded) |
| AC11 | `scripts/m1/latency.sh`: `AC11_latency` and per-stream receipt/formula/median/p95/log checks over actual evidence |
| AC12 | U `AC12_live`: changing accessible meters; partial replaced on the same DOM row; real crash/restart; explicit download progress/checksum failure |
| AC16 | `scripts/m1/repo-check.sh`: `AC16_hygiene` |

AC4 method suffixes after `AC4_contract_`:

- `helloWithinOneSecondHasVersionIdentityAndCapabilities`
- `helloRejectExitsThreeWithinTwoSeconds`
- `heartbeatEveryTwoSecondsIdleOrCapturing`
- `audioHasContiguousPerStreamSequenceTimelineAnd100msPcm`
- `windowBoundsAndReopensWithoutLoss`
- `levelsAreTenHzPerStreamAndFiniteRms`
- `partialsHaveIncreasingRevisionsAndAreReplacedByMatchingFinal`
- `finalsHaveSessionStreamSegmentBoundsAndSixScriptedUtterances`
- `stopDrainsAndReportsExactFinalSequencesThenNoSessionEvents`
- `unknownRequestIsCorrelatedUnsupportedMessageAndSessionSurvives`
- `unknownEventsAreIgnoredAndUnknownFieldsDoNotBreakRequests`
- `badFrameReportsFatalThenCloses`
- `stdinEofExitsZeroWithinTwoSeconds`
- `shutdownCorrelatesAndExitsZero`
- `permissionStatusReturnsBothStatesWithoutPromptAndStaysAlive`
- `mockDeviceChangeIsAnObservableSessionEvent`
- `transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio`

Parameterized invocations cover idle/capturing, windows 3/50, future/pause/resume,
seven corrupt frames, status/request and six file imports. File requests use
`job_id`; finals correlate via `session_id=job_id`, then done counts/duration and
request correlation are asserted. The mock-only device flag case is explicitly
skipped for a configured native executable, not silently simulated.

## Commands actually run and results

From `services/core`:

```sh
./mvnw -B verify
./mvnw -B --fail-at-end verify
```

Both exit 1 on the new missing-M1-corpus assertions. Maven skips downstream
modules after an upstream test failure, even with fail-at-end. To exercise all
modules without pretending that a forced-success exit is green:

```sh
./mvnw -B --fail-at-end -Dmaven.test.failure.ignore=true clean verify
```

Exit 0 because failure-ignore was explicitly requested. Parsed XML: **298
executions, 35 failures, 0 errors, 2 skipped**. All 229 existing M0 executions
pass. New M1: 69 invocations; 35 assertion failures; two native tests opt-in only.
Suite breakdown: J 9/7 failures, C 35/12, A 25/16 with two native skips.

Independent M0 regression run, without ignoring failures:

```sh
./mvnw -B '-Dtest=*,!M1Test,!M1GoldenTest' -Dsurefire.failIfNoSpecifiedTests=false verify
```

Exit 0. This command excludes only the newly added suites for an explicit
baseline check; no exclusions were installed in the normal build.

Latest targeted default C run after protocol-request fixture corrections:

```sh
./mvnw -B -pl agent-conformance -am -Dtest=M1Test -Dsurefire.failIfNoSpecifiedTests=false verify
```

Exit 1, 35 invocations / 12 assertion failures / 0 errors / 0 skips.

Native conformance was actually exercised against the compiled stub:

```sh
./mvnw -B -Pswift-agent -pl agent-conformance -am -Dtest=M1Test -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -B -Pswift-agent -pl app -am '-Dtest=M1Test#AC5_swiftSigkillHeadlessInputUsesRealNativeProcess+AC6_swiftSilentInjectionRelaysPermissionDenialOnRealSse' -Dsurefire.failIfNoSpecifiedTests=false test
```

These use already packaged prerequisites. Native C: 35 invocations / 34 assertion
failures / 0 errors / one mock-only skip. Native A: two assertion failures / zero
errors / zero skips. Native failures are missing readiness/hello/SSE because the
agent exits 10. A subsequent native permission-request subset rerun also failed
on missing hello after fixing its request body to `{permission:"microphone"}`.
No native STT, recovery timing or TCC success is claimed.

From `agents/macos-capture`:

```sh
swift build -c release
swift run CaptureSelfTest
```

Build exit 0; selftest exit 1: **198 cases, 197 failures**. The sole passing case
is M0 corpus presence. Other failures are unimplemented skeleton APIs or missing
M1 corpus. Local CommandLineTools linker-search-path warnings do not prevent the
build. No `swift test`, XCTest or swift-testing was used.

From `apps/desktop`:

```sh
pnpm lint && pnpm typecheck
pnpm test:e2e --workers=1
```

Lint/typecheck exit 0. Browser: six new assertion failures, one unchanged M0 smoke
passes. No fake routed SSE or substitute API was used.

One line per browser failure:

- `AC6_silent --deny=system_audio`: actionable system-audio banner absent.
- `AC6_silent --silent-system`: actionable system-audio banner absent.
- `AC12_live accessible Me and Them meters`: aria-labelled meter absent.
- `AC12_live same partial transcript row becomes final`: partial row absent.
- `AC12_live real MockAgent crash`: Capture restarting alert absent; actual crash/recovery observed.
- `AC12_live explicit model download`: Models button absent; later progress/checksum assertions not reached.

From the repository root:

```sh
scripts/m1/catalog-check.sh
scripts/m1/latency.sh
scripts/m1/repo-check.sh
actionlint .github/workflows/ci.yml
git diff --check
```

The first three each exit 1: missing catalog, missing actual latency-run evidence,
missing agent-macos job respectively. Hygiene assertions preceding the missing-job
check pass, including unchanged M0/core/ui and no forbidden tracked artifacts.
Standalone actionlint and diff whitespace checks exit 0. AC8 has not contacted HF
because no catalog exists; AC11 has not measured latency. These are explicitly
missing deliverables, not import/configuration errors or fabricated evidence.

## Failure groups and limitations

- AC3: new M1 corpus missing. Schema/decoder assertions behind its presence cannot
  yet run against M1 examples.
- AC4: non-default window ignored (10 observed vs 3 requested); no levels/partials;
  permissions and file import return unsupported errors; device-change flag absent.
- AC5 Mock: recovery PCM/sequence/transcript checks pass, restart exceeds 2s.
  Native uses a generated 60s stereo French speech baseline and deterministic
  SIGKILL at sample 416000. Assertions compare resumed frame hashes, recovered
  flags, contiguous sequence, sample gaps, SQLite uniqueness and normalized
  baseline utterance regions outside the observed gap. They remain unreached
  until native readiness works.
- AC6: silent-system SSE absent. Native uses the binding `--simulate-silent-system`.
- AC7: downloader throws UnsupportedOperationException, endpoint returns 404.
  The interrupted-body server advertises full length and severs after 700 bytes;
  the retry must send Range `bytes=700-` and publish only SHA-verified bytes.
- AC9: missing persisted suppression and hidden-row filtering. Negative/distinct
  boundary controls already pass and are retained as regression guards.
- AC10: orphan spool not imported; abandoned status not changed to recovered.
  Later real-agent queued-job/final assertions remain unreached.
- AC1 and already supported AC4 lifecycle/framing checks pass immediately. They
  cannot meaningfully be forced red without breaking existing correct behavior.

## Deviations and seams to carry forward

1. **Unresolved AC3 spec conflict:** unchanged `GoldenTest.goldens()` reads every
   NDJSON while its first test requires exactly 15 M0 types. Adding m1.ndjson
   necessarily breaks that old assertion. No old test was edited, hidden, skipped
   or weakened; M1 corpus/schema production additions remain for implementation.
   Resolve explicitly with tech-lead/Phuong before adding the corpus. A narrowly
   scoped M0 corpus helper change would require an explicit exception to the
   unchanged-M0 rule; this handoff does not grant one.
2. Java `-D` properties are not repeatable: repeated keys overwrite each other.
   `interline.conformance.agentArgs` therefore accepts a JSON argument array with
   `{wav}`, `{script}`, `{work}` substitutions. This preserves literal argv and
   spaces without a shell. Native fixture generation is macOS-only.
3. The root `swift-agent` profile activates native app tests when selected;
   the module profile selects the native conformance executable. Neither builds
   Swift or downloads models. Preinstall real models first; conformance model
   properties and native core optional argv seams are documented in source/README.
4. New test helper files and `evidence-check.py`/`red-report.py` were added beyond
   the plan's shorthand paths to keep process probes, evidence parsing and result
   extraction out of production. Only a Java downloader API and Swift signatures
   were needed as production skeletons. No implementation or dispatch wiring.
5. Proposed configuration seams: `interline.models.catalog=<file>` for generated
   local HTTP catalogs, entries `sizeBytes`; `interline.echo.aec` for deterministic
   threshold fixtures. Real AEC must still originate from agent capabilities.
   Permission and statistics payload shapes follow architecture where specified;
   otherwise evidence serialization is documented as a proposed seam, not a new
   product decision.
6. AC11 remains the planned implementation-stage ten-minute measurement. Its
   script verifies actual receipt/stat/log evidence; it does not start the run or
   replace the measurement with a unit-test number. AC13/14/15/17 remain planned
   implementation/human gates and are not marked passed.
7. Spool tests use the binding `<stream>-<firstSeq>-<firstSample>.wav`, derive
   sequence/sample positions from filenames, and retain ARCHITECTURE 5.8's fully
   acked-file deletion rule. PCMConverter and SilentTapDetector skeletons live
   in CaptureCore per decision 1, not CaptureDevices.

## Skeleton files

- `agents/macos-capture/Package.swift`
- `agents/macos-capture/Sources/CaptureCore/CaptureCore.swift`
- `agents/macos-capture/Sources/CaptureDevices/CaptureDevices.swift` (reserved target only)
- `agents/macos-capture/Sources/CaptureAgent/main.swift` (exits 10)
- `services/core/app/src/main/java/app/interline/app/ModelDownloader.java`

The selftest harness, protocol/process peers, generated speech, local HTTP origin,
Maven test module/profiles and evidence scripts are testing infrastructure only.
No binaryTarget/whisper download, model catalog, migration, live device code,
UI production code, signing material or CI implementation was added.
