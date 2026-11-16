# Echo suppression + STT language test plan

Paths: E=`services/core/app/src/test/java/app/interline/app/EchoLiveTest.java`; L=`.../app/LanguageTest.java`; U=`apps/desktop/e2e/live-echo.spec.ts`; F=`fixtures/audio/echo-live.transcript.json` (new); S=`services/core/app/src/test/java/app/interline/app/AppTestSupport.java` (add `post(port,path,body)` returning status+body, and an ordered-event helper).

F: 16 kHz, 30 s, order fixed by `end_sample`: `m-echo-1`/`s-echo-1` (system final first), `m-echo-2`/`s-echo-2` (mic final first), `m-solo` (no system final within 2 s), `m-dup` (one seg id sent twice) plus `s-dup`. Echo texts identical, starts equal, so `aec=false` 70 % flags them. MockAgent generates F's WAV. No mocks below: real MockAgent process, real SQLite, real SSE, real Vite.

AC1 | nominal | integration | E | AC1_micFinalThenSystemFinalPublishesSuppressedAfterBoth | `--speed=20 --script=F`: `stt.final` mic, `stt.final` system, then `transcript.suppressed{stream:mic,seg}`
AC2 | nominal | integration | E | AC2_systemFinalFirstThenMirroredMicFinalSuppressed | `stt.final` mic then `transcript.suppressed` for that seg
AC3 | edge | integration | E | AC3_nonEchoMicFinalPublishesNoSuppression | standup fixture: zero `transcript.suppressed`; F: none for `m-solo`
AC4 | edge | integration | E | AC4_duplicateFinalPublishesNothingTwice | one `stt.final` per (stream,seg), one `transcript.suppressed` for `m-dup`
AC5 | nominal/edge | e2e | U | AC5_suppressedRowRemovedAndNeverReadded | `--speed=1 --script=F`: mic row gone after suppression, `s-echo-1` and `m-solo` unchanged, still absent 3 s later; re-add rule via `page.evaluate(() => import('/src/liveState.ts'))` with a later partial and final on the same key
AC6 | nominal | integration | L | AC6_noBodyUsesDefaultFrench | no body: `session.start.stt.language=="fr"`, `meetings.language='fr'`
AC7 | nominal | integration | L | AC7_languageEnReachesSessionStartResumeAndTranscribeFile | `{"language":"en"}`: fresh and resumed `session.start` (`--crash-at`) and `transcribe.file` for a seeded orphan meeting with `language='en'` all carry `"en"`
AC8 | edge | integration | L | AC8_autoIsExplicitJsonNull | `{"language":"auto"}`: `language` present and `null`, not omitted; `meetings.language` NULL
AC9 | error | integration | L | AC9_invalidLanguageIs400AndCreatesNoMeeting | `EN`, `english`, `e`, `fr-FR`, `5`: 400, no new `meetings`/`capture_runs` row
AC10 | nominal | integration | L | AC10_configLanguageUsedWithoutBody | `--interline.stt.language=en`, no body: `"en"` in `session.start`
AC11 | nominal | integration | E+L+U | AC11_gatesAndGoldens | `./mvnw -B verify`, `pnpm lint`, `pnpm typecheck`, `pnpm test:e2e`, `git diff --stat origin/feat/t_69c59991-m1-macos-capture -- protocol/` empty

Run: `(cd services/core && ./mvnw -B verify -Dtest=EchoLiveTest,LanguageTest)`; `(cd apps/desktop && pnpm lint && pnpm typecheck && pnpm test:e2e)`.

Rewrites: AC5 "does not re-add" cannot come from MockAgent, hence the real `liveState` module plus continued absence. AC8 is present-and-null, not omitted. AC7/8 read `meetings.language`, NULL for `auto`.

Not tested: `GET transcript`, thresholds, `suppressed` storage (out of scope; M1Test AC9). Body `"language":null` (spec silent). Golden content (hash only). Swift agent, packaged TCC, 30 min call.
