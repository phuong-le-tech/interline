# ADR-014 - Hardened runtime is off for the self-signed release

Status: **Accepted** (M4-B, 2026-11-28). Settles the "[decision M4]" left open in ADR-004 point 5 and ARCHITECTURE 14.5.
Related: ADR-004, ADR-013, `scripts/package/sign-macos.sh`.

## Question

With a self-signed identity (no Apple Team ID), does the hardened runtime (`codesign --options runtime`) let the app run? Library validation only maps libraries signed by Apple or by the same Team ID, and our binaries have none.

## Experiment

Machine: Apple M4, macOS 27.2.1, arm64, 2026-11-28. App built by `scripts/package/build.sh`, copied four times and signed by `scripts/package/sign-macos.sh` with a THROWAWAY identity ("Interline Throwaway", RSA 3072, own keychain file deleted afterwards, login keychain untouched). Each copy was run with `scripts/package/smoke-quit.sh <app>` (isolated HOME, waits for READY and a visible window, Apple-event Quit, shell SIGKILL, real Swift agent started).

| Variant | Signing | Result |
|---|---|---|
| A | no hardened runtime (`flags=0x0`), entitlements embedded (jvm: allow-jit, allow-unsigned-executable-memory; agent: audio-input) | **PASS**: READY 3.9 s (first launch, cold), then 1.5 s; window 0.29 s; quit 0.67 s; SIGKILL cleanup 0.51 s; `assert-bundle.sh` PASS |
| B | `SIGN_HARDENED=1`, same entitlements | **FAIL**: core never reaches READY. `java -version` prints `dyld: Library not loaded: @rpath/libjli.dylib ... not valid for use in process: mapping process and mapped file (non-platform) have different Team IDs`. The agent fails the same way on `whisper.framework` |
| C | hardened + `com.apple.security.cs.disable-library-validation` on java and the agent | PASS: READY 2.5 s then 1.5 s, quit 0.68 s, SIGKILL cleanup 2.46 s |
| D | hardened + disable-library-validation, WITHOUT allow-jit and allow-unsigned-executable-memory | **FAIL**: core never reaches READY, so the JVM needs both JIT entitlements under the hardened runtime |

Commands: `SIGN_IDENTITY=... SIGN_KEYCHAIN=<throwaway> [SIGN_HARDENED=1] [SIGN_ENTITLEMENTS_DIR=<dir>] scripts/package/sign-macos.sh X.app`, then `scripts/package/smoke-quit.sh X.app`. Also checked on A: `codesign -dv` shows `Identifier=app.interline.desktop`, `TeamIdentifier=not set`; `spctl -a -vv` says `rejected` (expected, not notarized).

## Decision

**Ship without the hardened runtime** (variant A). Library validation does NOT accept the jlink runtime, `libsqlitejdbc.dylib` (not measured separately, it loads after `libjli`) or `whisper.framework` without a Team ID. The only way to keep the hardened runtime is variant C, which needs `disable-library-validation`, `allow-jit` and `allow-unsigned-executable-memory`: three of the main protections switched off. Without notarization that buys nothing (no Gatekeeper benefit, no Apple requirement). The sealed signature, the pinned designated requirement and the fingerprint check in `install.sh` are the integrity story.

The entitlement files stay embedded (`scripts/package/entitlements/`): they are inert without the hardened runtime and make a later switch (`SIGN_HARDENED=1` plus `disable-library-validation`, variant C) a one-line change if notarization ever returns.

## Consequence and residual risks

With the hardened runtime off, `DYLD_*` variables are honoured by `Interline.app`'s own executable. **Any process of the same user can launch Interline.app with an injected dylib (`DYLD_INSERT_LIBRARIES`) and inherit its microphone and system-audio TCC grants without a prompt.** The M4-A environment stripping removes `DYLD_*` and JVM injection variables from the children (core, agent); it does not protect the shell itself, which has already loaded its libraries by then. This is accepted: a same-user attacker can already read the user's notes database, and without a Team ID the hardened runtime is only available with library validation disabled (variant C), which does not stop `DYLD_INSERT_LIBRARIES` of a library signed by the attacker's own identity. Listed as a residual risk; switching to variant C without `allow-dyld-environment-variables` is the mitigation to evaluate if it matters.

## Not verified

TCC prompts for the microphone and system audio on a signed build (needs a human at the machine; ADR-004 spike results still apply); a Sequoia or Tahoe-only behaviour on other macOS versions; whether `audio-input` is ever required without the hardened runtime (not required in A: the agent ran).
