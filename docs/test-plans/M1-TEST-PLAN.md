# M1 test plan

Paths: A=`services/core/app/src/test/java/app/interline/app/M1Test.java`; J=`services/core/adapter-agent-protocol/src/test/java/app/interline/agent/M1GoldenTest.java`; C=`services/core/agent-conformance/src/test/java/app/interline/conformance/M1Test.java`; S=`agents/macos-capture/Sources/CaptureSelfTest/main.swift`; U=`apps/desktop/e2e/m1.spec.ts`; R=`scripts/m1/`.

Generated/temp-only fixtures: F=48kHz-stereo-sine/silence; V=six-FR/EN-`say`/`afconvert`-utterances; D=SQLite/spool; H=HTTP-bytes. Real SQLite/Flyway/processes/codecs/SSE; external-HTTP/clock mocks only. Headless=MockAgent/Swift-`--test-input`,no-TCC. Predownload models.

AC1 | nominal | integration | A | AC1_regression | unchanged M0+conformance pass; none
AC2 | edge | unit | S | AC2_pipeline | F: golden roundtrip,1600-sample/100ms frequency preserved,stale-header recovery,60s rotation,window overflow replay,backpressure>60s,zeros=5s once,chunk-policy boundaries; clock
AC3 | error | unit | J+S | AC3_goldens | both schemas/decoders accept all goldens,reject invalid-type/missing-required; M0 byte-identical; none
AC4 | nominal/error | integration | C | AC4_contract | F+V: every AC4 observable asserted,including deadlines,counts,correlation,window,exit; none
AC5 | error | integration | A | AC5_restart | V+D: SIGKILL,new PID,recovered frames,gap<2s,contiguous seq,utterances outside gap persisted once; none
AC6 | error | integration/e2e | A+U | AC6_silent | F: both injection flags yield SSE/banner<10s; none
AC7 | error | integration | A | AC7_download | H: Range-resume,verified rename,checksum failure deletes part,zero unsolicited HTTP; external HTTP
AC8 | nominal | integration | R/catalog-check.sh | AC8_catalog | six commit-pinned entries match HF headers; none
AC9 | edge | integration | A | AC9_echo | D: +/-2s,70%/85% boundaries; duplicates suppressed/hidden,distinct retained; none
AC10 | error | integration | A | AC10_orphans | D: recording/degraded,holes/stale-header,repeated startup imports once,queues job,persists finals; none
AC11 | nominal | integration | R/latency.sh | AC11_latency | V:10min/small/speed1,per-stream median/p95+logs+PR evidence; none
AC12 | error | e2e | U | AC12_live | meters change,partial replaced,restart visible,download progress/checksum error; external HTTP
AC13 | nominal | integration | scripts/bench/ | AC13_benchmarks | V:five-model RTF/RSS,six-file WER,commands/hardware recorded; none
AC14 | nominal | integration | R/no-network-check.sh | AC14_offline | V:300s,three-process PID samples,zero non-loopback sockets; none
AC15 | nominal | integration | docs/runbooks/M1-EXIT.md | AC15_docs | review required sections/procedures/open reasons; none
AC16 | error | integration | R/repo-check.sh | AC16_hygiene | git-ls-files clean,actionlint passes,core/ui unchanged; none
AC17 | nominal | e2e | R/human-gate.sh | AC17_sitting | Phuong:decision12(a-e),results.json->ADRs; none

Run: `(cd services/core && ./mvnw -B verify [-Pswift-agent])`; `(cd agents/macos-capture && swift build -c release && swift run CaptureSelfTest)`; `(cd apps/desktop && pnpm lint && pnpm typecheck && pnpm test:e2e)`; scripts above.

Rewrites: Swift-utterances=uninterrupted-baseline,not-exact-TTS.

Not-tested: out-of-scope-features. AC11/13/14=code-writer-measurements; AC17=TCC/hardware/real-call/CI-human-gates.
