# Releasing Interline (macOS)

Design and trade-offs: ADR-004 (self-signed project identity), ADR-014 (no hardened runtime). The workflow is `.github/workflows/release.yml`; it only ever creates a **draft** release. It cannot be run end to end without the secrets below, so the first tag is the real test (see the checklist).

## One-time setup (maintainer, on an offline machine)

Use OpenSSL 3 (`brew install openssl@3`; the macOS `openssl` is LibreSSL and has no `-legacy`). Work in an empty folder that is not inside the repository.

1. **Create the signing identity** (10 years, code signing only):

   ```sh
   openssl req -x509 -newkey rsa:3072 -sha256 -days 3650 -nodes \
     -keyout interline-signing.key -out interline-signing.crt \
     -subj "/CN=Interline Release Signing" \
     -addext "basicConstraints=critical,CA:false" \
     -addext "keyUsage=critical,digitalSignature" \
     -addext "extendedKeyUsage=critical,codeSigning"
   ```

2. **Export a PKCS#12** (`-legacy` so that `security import` can read it) and choose a password:

   ```sh
   openssl pkcs12 -export -legacy -inkey interline-signing.key -in interline-signing.crt \
     -name "Interline Release Signing" -out interline-signing.p12
   ```

3. **Read the fingerprints.** The SHA-256 is published and pinned in `install.sh`; the SHA-1 is only used inside the designated requirement (the script computes it itself).

   ```sh
   openssl x509 -in interline-signing.crt -noout -fingerprint -sha256
   openssl x509 -in interline-signing.crt -noout -fingerprint -sha1
   ```

4. **Create the updater key pair** (minisign format, separate from the certificate):

   ```sh
   cd apps/desktop && pnpm install --frozen-lockfile && pnpm tauri signer generate -w ~/interline-updater.key
   ```

   It prints the public key and writes `interline-updater.key` (private) and `interline-updater.key.pub`. Put the content of the `.pub` file into `plugins.updater.pubkey` in `apps/desktop/src-tauri/tauri.conf.json` (replacing the `REPLACE_WITH...` placeholder) and commit that change. The release job refuses to run while the placeholder is there.

5. **Set the GitHub secrets and variables** (Settings > Secrets and variables > Actions). Never commit any of these values.

   | Name | Kind | Value |
   |---|---|---|
   | `MACOS_SIGNING_P12_BASE64` | secret | `base64 -i interline-signing.p12 > p12.b64 && gh secret set MACOS_SIGNING_P12_BASE64 < p12.b64` (then delete `p12.b64`) |
   | `MACOS_SIGNING_P12_PASSWORD` | secret | the PKCS#12 password from step 2: `gh secret set MACOS_SIGNING_P12_PASSWORD` (prompts) |
   | `TAURI_SIGNING_PRIVATE_KEY` | secret | `gh secret set TAURI_SIGNING_PRIVATE_KEY < interline-updater.key` |
   | `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | secret | its password (never empty: an empty password leaves the update key unprotected if the secret leaks) |
   | `INTERLINE_SIGNING_IDENTITY` | variable | `Interline Release Signing` (the certificate common name) |
   | `INTERLINE_CERT_SHA256` | variable | the SHA-256 from step 3 (with or without colons) |

6. **Store the offline copies**: `.p12` plus password and the updater key plus password in your offline vault. Then delete the loose `interline-signing.key`, `.crt`, `.p12` and `p12.b64` files with a normal `rm` (secure overwrite does not work on APFS; the vault copy is the one that matters).

7. **Restrict who can use the secrets (you do this yourself in the GitHub settings).** Move the four secrets into a GitHub **Environment** (for example `release`) limited to tags `v*` with a required reviewer (you), and add `environment: release` to the `macos` job. Add a **tag protection ruleset** for `v*` so only you can create release tags. Without these, anyone who can push a tag can trigger a signed draft.

The minisign updater key is the update trust root: the app installs whatever `latest.json` points to if its signature verifies against the public key in `tauri.conf.json`. The "second click" is enforced by the UI only; there is no rollback (downgrade) protection. Guard that key accordingly.

## Cutting a release

```sh
git tag v0.1.0 && git push origin v0.1.0
```

Two jobs, so that no build code ever shares a runner with the keys:

1. **`build`** (no secrets, no environment): validates the tag, runs `pnpm install --frozen-lockfile` and `scripts/package/build.sh`, and uploads the UNSIGNED app as a tarball. It records the tarball's SHA-256 as a job output.
2. **`release`** (`environment: release`, `contents: write`): runs no repository build code. It checks the artifact against the recorded SHA-256 and treats it as data (extract, sign, never execute). One step imports the identity into a temporary keychain, signs every Mach-O inside-out with `scripts/package/sign-macos.sh` (the script from the tagged commit; DR pinned to the certificate SHA-1), checks the leaf certificate against `INTERLINE_CERT_SHA256`, and deletes the keychain and the `.p12` on exit, before any later step. Then it makes the tarball, DMG, `install.sh`, signs the tarball with a pinned `@tauri-apps/cli@2.12.4` installed with `--ignore-scripts` (the updater key is env of that single step), writes `latest.json` and `SHA256SUMS`, and creates a **draft** release. `scripts/package/check-release-workflow.sh` (run by `test-install.sh`) fails if the `release` job contains `pnpm`, `cargo`, `build.sh`, `GITHUB_ENV` or `GITHUB_PATH`, or if `build` mentions a secret.

**Residual risk:** whatever the `build` job produces gets signed. Dependency pinning (lockfiles, `--frozen-lockfile`, `cargo --locked`) and reviewing dependency updates are the defence. The signer is pinned by version, not by lockfile hash, because the release job installs nothing from the repository. **The GitHub Environment `release` is yours to configure** (step 7): if it does not exist when the workflow first runs, GitHub creates it WITHOUT protection rules, and secrets stored there are then available to any run on that tag.

The tag must match `^v[0-9]+\.[0-9]+\.[0-9]+$` and equal `v` plus the `version` in `tauri.conf.json` (the job fails early otherwise), and be higher than the installed version: the updater compares `latest.json` `version` with the app version in `tauri.conf.json`, so bump that first.

## First-release checklist

- [ ] Steps 1 to 6 above done; placeholder public key replaced and committed.
- [ ] Tag pushed on a machine you trust; the job is green; the draft has 6 assets: `Interline.app.tar.gz`, `.sig`, `Interline_<version>_aarch64.dmg`, `latest.json`, `install.sh`, `SHA256SUMS`.
- [ ] On a second Mac: `curl -fsSL <draft asset url>/install.sh -o install.sh && less install.sh && sh install.sh` ends with "Installed" and the app opens (a draft is not public: download the assets with `gh release download`).
- [ ] `codesign -d -r- /Applications/Interline.app` shows `identifier "app.interline.desktop" and certificate leaf = H"<sha1>"`.
- [ ] Grant microphone and system audio, quit, install the next build over it: no new prompt (ADR-004 spike cell, still [to verify] for the updater and install script).
- [ ] Publish the draft. Put the SHA-256 fingerprint in the release notes and README.
- [ ] Release N+1: Settings > Privacy and data > "Check for updates" finds it, installs after the second click, and the app restarts [to verify end to end on an unnotarized build].

## Certificate rotation (expiry or leak)

1. Repeat steps 1 to 3 and 5 with a new identity (new common name or the same). Update `MACOS_SIGNING_P12_*` and `INTERLINE_CERT_SHA256`.
2. Ship the next release; every user gets one new microphone and system-audio prompt, because the designated requirement changes. Say so in the release notes and publish the new fingerprint.
3. A leaked key cannot be revoked (there is no CA). Publish the new fingerprint, warn users and delete the old release assets. The updater key is independent: rotate it the same way (step 4) and note that apps built with the old public key can no longer verify updates, so they need a manual install.

## Contributors

`make signing-identity` (once) creates a local identity "Interline Dev Signing" in its own keychain `~/Library/Keychains/interline-dev.keychain-db`; it never touches the login keychain. The keychain password (`interline-dev`) is public by design: the keychain only holds a dev key for your own local builds. `make install` builds, signs with it and copies the app to `~/Applications`. Delete it with `security delete-keychain ~/Library/Keychains/interline-dev.keychain-db`.

## Local checks

`scripts/package/test-install.sh` runs the `install.sh` and `sign-macos.sh` tests offline with a throwaway identity in a temporary keychain that is deleted on exit.
