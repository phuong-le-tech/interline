# M4-C - Data lifecycle and privacy verification

Machine: Apple M4, macOS, 2026-11-27 (Europe/Paris). This is not an M1 8 GB measurement.
Branch: `phuong-le-tech/m4-data-lifecycle`. Base: `origin/main` at `3d19c11`.

## Delivered

- Retention preference `audio.retention`: after transcription, 7 days (default), 30 days, forever. Deadlines are pinned when capture ends. One local daemon checks every 60 seconds and on startup. Audio purge removes canonical PCM, spool and recovery directories and clears the reference while preserving notes and transcripts. An unconfirmed transcription stays pending; timeouts do not erase its input.
- A 30-day trash, restore, immediate permanent delete, cascade deletion and retryable tombstones. Recording/processing cannot be trashed. Stalled recovery can be deleted; recovery may finish in trash, but late replies cannot recreate permanently deleted data. File deletion rejects traversal and symlinked roots or meeting directories and never trusts a stored path. One bad directory does not prevent other due meetings from purging.
- Authenticated retention, trash, restore and privacy routes. Privacy declares LLM generation, connection testing, ICS requests and model downloads from real configuration. Only host names are returned. ICS fetches require Sync; only local EventKit is polled. Auto update remains unavailable. If the secret store cannot be read, privacy returns 503 rather than falsely reporting an ICS source as off.
- Reachable Privacy and data view, labelled controls, confirmation dialogs, native keyboard/focus handling, live result messages and a non-blocking ochre FileVault notice. Existing Consent import and brief/ai client allowlist entry are preserved.
- Additive V7 migration and ADR-008, including migration/rollback and partial filesystem failure behavior. No dependencies added. No push or PR created.

## Verification

| Check | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Full core verify | 1399 | 1 (AC16 only) | 0 | 7 |
| Persistence subset of that run | 299 | 0 | 0 | 0 |
| UI Vitest | 105 | 0 | 0 | 0 |
| Final Playwright | 28 | 0 | 0 | 1 |
| FileVault parser | 1 | 0 | 0 | 0 |

Full core verify had 1391 passing tests and took 4 min 6 s. It is not green: the only failure is the acknowledged AC16 shell line cap. All six lifecycle persistence tests, the pinned V7 migration test, the two lifecycle API tests, the scheduler clock test, both privacy tests and all architecture tests passed. The final full browser run had 27 passes in 3.3 min; the new delete/restore/permanent-delete/restart case passed in 9.5 s. The prior core-startup failure did not recur against the stable jar. UI lint and typecheck, frozen installation, Rust check and clippy all exited 0. The packaging command exited 0 and took 3.061 s.


Commands were run from the repository root unless a working directory is shown:

```sh
cd services/core
./mvnw -B -pl core-persistence -am test -Dtest=LifecycleStoreTest,MigrationV7Test -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl app -am verify -Dtest=LifecycleStoreTest,MigrationV7Test,M4LifecycleApiTest,LifecyclePurgerTest,PrivacyCatalogTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B verify
./mvnw -B -pl app -am package -DskipTests
```

The package command produces the final browser-test artifact after the full suite reaches the known AC16 failure; it does not replace the test gate.

```sh
cd apps/desktop
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm test
pnpm test:e2e privacy-data.spec.ts
pnpm test:e2e
```

Each Playwright run acquired `/tmp/interline-e2e.lock` before starting and removed it via an EXIT trap. Lock acquisition retried every 15 seconds, capped at 30 minutes. The new spec covers delete -> trash -> restore -> hard delete and retention persistence across a real core restart. An earlier broad run overlapped a jar rebuild and had one core-startup failure; the final run uses an unchanged artifact.

```sh
cd apps/desktop/src-tauri
cargo check
cargo clippy -- -D warnings
cargo test filevault
```

These Rust commands passed. One parser test covers on, off, empty, unknown, privilege-error, garbage and contradictory output. A read-only `/usr/bin/fdesetup status` on this Mac returned `FileVault is On.` with exit code 0.

```sh
git fetch origin && git merge origin/main
git diff --check
wc -l apps/desktop/src-tauri/src/{main,supervisor,filevault}.rs
```

The merge was already up to date. Main has V1 through V6, so V7 remains the next free migration. No import, route or allowlist line was lost. Rust line counts: main.rs 160, supervisor.rs 136, filevault.rs 36, total 332. RepositoryTest AC16 still enforces 299; the coordinator explicitly owns the combined M4 shell-budget decision and requested that this assertion remain untouched.

## Limits and remaining integration work

- AC16 is an expected known red until the packaging worker/coordinator settles the combined Rust budget. This worker did not change RepositoryTest.
- FileVault checks the macOS startup data volume using the agreed fixed `fdesetup status` command. A custom data directory on another volume needs a volume-specific check. No application-level encryption is added.
- Real second-Mac behavior, GitHub runners, native live-audio E2E and Gatekeeper dialogs were not verified here. No signing or keychain changes were made.
- The initial untracked root `package.json` and `pnpm-lock.yaml` were left untouched.

## Changed files

- `apps/desktop/e2e/privacy-data.spec.ts`
- `apps/desktop/src-tauri/src/filevault.rs`
- `apps/desktop/src-tauri/src/main.rs`
- `apps/desktop/src/App.tsx`
- `apps/desktop/src/coreClient.ts`
- `apps/desktop/src/settings/DeleteConfirmation.tsx`
- `apps/desktop/src/settings/FileVaultNotice.tsx`
- `apps/desktop/src/settings/Privacy.tsx`
- `apps/desktop/src/settings/PrivacyData.tsx`
- `apps/desktop/src/settings/Retention.tsx`
- `apps/desktop/src/settings/Trash.tsx`
- `apps/desktop/src/settings/settings.test.tsx`
- `docs/ARCHITECTURE.md`
- `docs/adr/ADR-008-data-lifecycle-and-privacy.md`
- `docs/test-plans/m4-c-data-lifecycle.md`
- `services/core/app/src/main/java/app/interline/app/CoreConfiguration.java`
- `services/core/app/src/main/java/app/interline/app/LifecycleController.java`
- `services/core/app/src/main/java/app/interline/app/LifecyclePurger.java`
- `services/core/app/src/main/java/app/interline/app/PrivacyCatalog.java`
- `services/core/app/src/main/java/app/interline/app/PrivacyController.java`
- `services/core/app/src/main/java/app/interline/app/RecordingService.java`
- `services/core/app/src/test/java/app/interline/app/LifecyclePurgerTest.java`
- `services/core/app/src/test/java/app/interline/app/M4LifecycleApiTest.java`
- `services/core/app/src/test/java/app/interline/app/PrivacyCatalogTest.java`
- `services/core/core-persistence/src/main/java/app/interline/persistence/AudioStore.java`
- `services/core/core-persistence/src/main/java/app/interline/persistence/CaptureStore.java`
- `services/core/core-persistence/src/main/java/app/interline/persistence/LifecycleStore.java`
- `services/core/core-persistence/src/main/java/app/interline/persistence/SettingsStore.java`
- `services/core/core-persistence/src/main/java/app/interline/persistence/SqliteDatabase.java`
- `services/core/core-persistence/src/main/resources/db/migration-m4/V7__m4_lifecycle.sql`
- `services/core/core-persistence/src/test/java/app/interline/persistence/LifecycleStoreTest.java`
- `services/core/core-persistence/src/test/java/app/interline/persistence/MigrationV7Test.java`
