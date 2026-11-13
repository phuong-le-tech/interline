# ADR-004 - macOS distribution without the Apple Developer Program

Status: **Deferred**, 2026-11-13. Two things are settled: no Apple Developer Program (firm constraint, decided by Phuong), and notarization is not part of M4. The choice between the free options (1, 2, 3, 4 below) is **postponed to the M4 gate** ("on verra plus tard", Phuong, 2026-11-13). The recommendation below (option 2, self-signed project identity, because it should keep TCC grants across updates) becomes Accepted only when the M1 spike fills in "Spike results" AND Phuong picks it at M4. Until then, M1 must not depend on a signing choice: build locally with Xcode Command Line Tools.
Related: ADR-001 (hybrid stack, embedded JVM), `docs/ARCHITECTURE.md` section 14.5, `docs/PLATFORM.md` (macOS packaging), `docs/ROADMAP.md` M1 and M4, `docs/REPORT.md` R7, R8, R14.
Note: this number was reserved for "core owns the data" in draft 2; that ADR moves to ADR-011.

## Constraint

Interline will not join the Apple Developer Program (99 USD/year). There is no Developer ID certificate, so no notarization, no stapled ticket, no Apple Team ID. This is firm; the paid route is described below only so the reader knows what is given up.

## Consequences we must be honest about

1. **Gatekeeper blocks a downloaded app.** Any app that carries the `com.apple.quarantine` attribute (set by browsers, Mail, AirDrop) and is not notarized is refused at first launch. Since macOS 15 the Control-click > Open override is gone (Apple developer news, 2024-08-06); the user must try to open the app, then go to System Settings > Privacy & Security > Security > "Open Anyway" (available for about an hour after the attempt), and enter their password. On Apple Silicon, native arm64 code must carry at least an ad-hoc signature to run at all; an unsigned or broken signature shows "is damaged and can't be opened" instead of offering "Open Anyway" [to verify exact dialog wording on macOS 15, 26 and 27].
2. **TCC grants are tied to the code signature.** macOS stores, with each microphone and system-audio grant, the app's designated requirement (DR). An ad-hoc signature has no certificate, so its DR is the code hash (cdhash), which changes on every build: after every rebuild or update, macOS treats the app as new and asks again for the microphone and for "System Audio Recording Only". For system audio the second prompt may not even appear clearly: a denied tap delivers silence (ARCHITECTURE 9.1), so a lost grant looks like a dead stream.
3. **Keychain items are tied to the signature too.** The agent stores LLM API keys as Keychain generic passwords; their access list names the app's signature. A new cdhash means a "wants to use your confidential information" password prompt after an update [to verify for items created by an ad-hoc build].
4. **No Apple-issued trust chain.** macOS cannot tell the user who published the app. Integrity has to come from us: SHA-256 checksums, a published signing-certificate fingerprint, and the minisign signature that the Tauri updater already checks.
5. **No Team ID.** Hardened runtime library validation only accepts libraries signed by Apple or by the same Team ID. A self-signed or ad-hoc JVM loading its own dylibs may fail library validation [to verify]; the likely answer is to ship without hardened runtime, or with `com.apple.security.cs.disable-library-validation` on the JVM [decision M4].
6. **The official Homebrew cask repository is closed to us.** Homebrew disabled every `homebrew/cask` cask that fails Gatekeeper checks on 2026-09-01, and deprecated the `--no-quarantine` flag in Homebrew 5.0.0 (2025-11-12). Only a tap we own remains.
7. **Good news.** Notarization work disappears: no `notarytool`, no stapling, no rejected native code inside jars (old R14), no Apple account delay in M4.

## Options

| # | Option | Cost | User friction | What it does NOT solve |
|---|---|---|---|---|
| 1 | **Ad-hoc signed release** (`codesign -s -`, Tauri `signingIdentity: "-"`) | 0 USD, near-zero CI work | "Open Anyway" or one `xattr` command at first install; **microphone + system audio + Keychain re-prompts after every update** | Gatekeeper, TCC persistence, trust. Variant: ad-hoc with an explicit DR `designated => identifier "app.interline"` may keep grants [to verify], but then any binary claiming that identifier inherits microphone access. Rejected for that reason |
| 2 | **Self-signed project identity**: one code signing certificate created once, private key in CI secrets, every Mach-O signed with it, DR pinned to the certificate | 0 USD, ~1 pd of setup, key custody | Same first-install step as option 1. Users never install or trust our certificate. Expected: grants survive rebuilds and updates because the DR no longer depends on the cdhash [to verify] | Gatekeeper (still unnotarized), no Apple trust chain, no Team ID (point 5). If the key leaks, a malicious binary with our identity would inherit users' microphone grants |
| 3 | **Source only** (`cargo tauri build` or a `make install`), no binary release | 0 USD, no release pipeline | High: Xcode Command Line Tools, Rust, Node + pnpm, JDK 21, roughly 10 to 30 min first build [estimate]; no updater, updating = pull and rebuild. Locally built apps carry no quarantine, so no Gatekeeper dialog [to verify] | Excludes persona P1 (non-developer). TCC still re-prompts per rebuild unless the builder also creates a local self-signed identity (option 2 applied locally) |
| 4 | **Homebrew tap we own** (`brew install --cask <org>/interline/interline`) | 0 USD, a tap repo + one bump per release (automatable) | One command, but Homebrew still sets the quarantine attribute on cask downloads [to verify]; `--no-quarantine` is deprecated and may already be removed from current Homebrew [to verify]; a `postflight` that strips quarantine in our own tap may work [to verify] | It is a channel, not a signing method: TCC behaviour is whatever option 1 or 2 gives. `brew upgrade` and the Tauri updater must not fight (`auto_updates true` in the cask) |
| 5 | **Developer ID + notarization** (the only paid route) | 99 USD/year, enrolment delay of days | None: the DMG opens normally, official cask allowed | Still prompts once for each permission at first use; does not solve TCC attribution through the JVM (R13); brings back notarization of the embedded JVM (old R14). **Not chosen** |

Never documented as an option: disabling Gatekeeper globally (`spctl --master-disable` or equivalent). It weakens the whole machine for one app.

## Recommendation

**Option 2 as the signing basis, distributed through an install script, with the DMG and our own Homebrew tap as secondary channels, and option 3 documented for contributors.**

- Every release is signed in CI with the project identity "Interline Release Signing" (self-signed, 10-year validity), with an explicit DR pinned to that certificate. Its SHA-256 fingerprint is published in the README, the release notes and the install script.
- **Primary install path**: one Terminal command that downloads and runs `install.sh` from the GitHub release. `curl` does not set the quarantine attribute, so no Gatekeeper dialog appears [to verify on macOS 26.4+ and 27, which added Terminal and script protections]. The script checks integrity itself (SHA-256 and certificate fingerprint).
- **Updates**: the Tauri updater. It verifies its own minisign signature, not Apple's, so notarization is irrelevant to it [to verify end to end on an unnotarized build], and it downloads with its own HTTP client, so the new bundle should carry no quarantine [to verify]. Because the identity is stable, grants and Keychain access should survive the update [to verify]. macOS 15+ "App Management" protection may prompt when an app without Team ID replaces itself in `/Applications` [to verify].
- **Secondary**: the DMG on the release page (with the "Open Anyway" steps and screenshots), and the tap only if the spike shows that quarantine can be handled without `--no-quarantine`.
- **Plan B**, if the spike shows that a self-signed identity does NOT keep TCC grants: ship option 1, release at most once a month, show a one-line notice after each update ("macOS will ask for the microphone and system audio again"), and bring the measured result back to Phuong. The constraint stands; only he can reopen it.

## Install steps (users)

**A. Install script (recommended)**, macOS 14.2+:
1. `curl -fsSL https://github.com/<org>/interline/releases/latest/download/install.sh -o install-interline.sh`
2. Read it: `less install-interline.sh`
3. `sh install-interline.sh`

What the script does, in order: checks macOS >= 14.2 and the architecture; downloads `Interline.app.tar.gz` and `SHA256SUMS` with `curl`; checks `shasum -a 256`; extracts to a temporary folder; runs `codesign --verify --deep --strict` and compares the leaf certificate fingerprint (`codesign -d --extract-certificates`, then `openssl x509 -inform DER -noout -fingerprint -sha256 -in codesign0`) with the pinned value, and aborts on mismatch; copies with `ditto` to `/Applications/Interline.app` (or `~/Applications` without admin rights); removes any quarantine defensively with `xattr -dr com.apple.quarantine`; opens the app. It never asks for `sudo`, never installs a certificate, never touches Gatekeeper settings.

**B. DMG (manual)**:
1. Download `Interline_<version>_aarch64.dmg` from the release page, drag Interline to Applications.
2. Double-click it. macOS refuses; click "Done" (not "Move to Trash").
3. System Settings > Privacy & Security > scroll to Security > "Open Anyway" (within about an hour), enter your password, confirm "Open".
4. Terminal alternative to steps 2 and 3: `xattr -dr com.apple.quarantine /Applications/Interline.app`

**C. Homebrew tap (only if retained after the spike)**:
1. `brew tap <org>/interline`
2. `brew install --cask interline`
3. If macOS still refuses to open it: `xattr -dr com.apple.quarantine /Applications/Interline.app`
Do not use `--no-quarantine`: deprecated by Homebrew, possibly removed [to verify].

**D. From source (contributors)**:
1. Prerequisites: macOS 14.2+, `xcode-select --install` (enough for the Swift agent [to verify], else full Xcode), Rust via rustup, Node + pnpm, JDK 21 (Temurin), git.
2. `git clone https://github.com/<org>/interline.git && cd interline`
3. Once: `make signing-identity` creates a local self-signed identity in a dedicated keychain, so permissions survive your rebuilds [to verify].
4. `make install` builds the jar, the jlink runtime, the agent and the Tauri bundle, signs inside-out with the local identity and copies the app to `~/Applications`. Both targets are planned for M4; until then, the commands in `CLAUDE.md` and `cargo tauri build`.

## Release steps (project)

One time (maintainer, offline machine):
1. Create the identity with OpenSSL 3 (the macOS-bundled LibreSSL may lack `-legacy` [to verify]):
   `openssl req -x509 -newkey rsa:3072 -sha256 -days 3650 -nodes -keyout interline-signing.key -out interline-signing.crt -subj "/CN=Interline Release Signing" -addext "basicConstraints=critical,CA:false" -addext "keyUsage=critical,digitalSignature" -addext "extendedKeyUsage=critical,codeSigning"`
2. `openssl pkcs12 -export -legacy -inkey interline-signing.key -in interline-signing.crt -name "Interline Release Signing" -out interline-signing.p12` (`-legacy` so that `security import` accepts it [to verify]).
3. `openssl x509 -in interline-signing.crt -noout -fingerprint -sha256` and `-fingerprint -sha1`: publish the SHA-256, use the SHA-1 in the DR.
4. Store the `.p12` and its password as CI secrets and in the offline vault next to the Tauri updater key. Delete the loose `.key` file.

Per release (`release.yml`, macOS job, on a tag):
1. Temporary keychain: `security create-keychain -p "$KC_PW" build.keychain-db`, `security set-keychain-settings -lut 21600 build.keychain-db`, `security unlock-keychain -p "$KC_PW" build.keychain-db`, `security import interline-signing.p12 -k build.keychain-db -P "$P12_PW" -T /usr/bin/codesign`, `security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KC_PW" build.keychain-db`, `security list-keychains -d user -s build.keychain-db login.keychain-db`. A self-signed identity is not trusted, so `security find-identity -v -p codesigning` may not list it; `codesign` should still accept it by name or SHA-1 [to verify].
2. Build the jar (Linux job), jlink runtime, CDS archive, agent, UI.
3. Sign inside-out with the same identity and the pinned DR, `codesign --force --timestamp=none --sign "Interline Release Signing" -r='designated => identifier "app.interline" and certificate leaf = H"<sha1>"'` (identifier per binary): runtime dylibs and executables including `jspawnhelper`, `libsqlitejdbc.dylib`, the agent, then the shell and the bundle (entitlements per ARCHITECTURE 14.5). Tauri's `signingIdentity` may cover the shell and `externalBin`; the jlink runtime under `Resources/` is signed by `tools/sign-macos.sh` before bundling [decision M4].
4. Produce the DMG and `Interline.app.tar.gz` + `.sig` (`TAURI_SIGNING_PRIVATE_KEY`, `createUpdaterArtifacts`) **after** the last `codesign`.
5. Check: `codesign --verify --deep --strict --verbose=2` on the bundle extracted from the tarball; `codesign -d -r-` shows the pinned DR; `spctl -a -vv` reports "rejected" (expected without notarization, not a failure).
6. Publish: DMG, tarball, `.sig`, `latest.json`, `SHA256SUMS`, `install.sh` (with the fingerprint), release notes repeating the fingerprint. Bump the tap if retained.
7. Delete the temporary keychain.

Certificate rotation (expiry or leak): new identity = one new permission prompt for every user; announce it in the release notes. A leaked key cannot be revoked (there is no CA): publish the new fingerprint and warn users.

## Spike results (M1)

To be filled by the M1 spike "confirm on a real Mac which install path keeps the microphone and system-audio permissions across a rebuild". Matrix: signing (ad-hoc, ad-hoc with identifier DR, self-signed identity) x replacement path (install script, Tauri updater, tap cask, local `make install`). For each cell: after a rebuild with a code change, is there a new prompt for microphone, system audio, Keychain; is either stream silent (a silent system stream means the grant was lost, check with `level`); is there a Gatekeeper or App Management dialog. Reset between runs: `tccutil reset Microphone app.interline` and `tccutil reset AudioCapture app.interline` [to verify the service name].
