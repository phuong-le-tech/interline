#!/usr/bin/env bash
# Offline tests for install.sh and sign-macos.sh with a THROWAWAY identity in a temporary keychain (deleted on exit).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
kc="$work/throwaway.keychain-db"
trap 'security delete-keychain "$kc" 2>/dev/null || true; rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

eval "$("$here/make-identity.sh" "$kc" "Interline Throwaway Test" pw-throwaway | sed 's/^/ID_/')"
mkdir -p "$work/src/Interline.app/Contents/MacOS" "$work/rel" "$work/tmp"
cp /usr/bin/true "$work/src/Interline.app/Contents/MacOS/interline"
cat > "$work/src/Interline.app/Contents/Info.plist" <<'P'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict><key>CFBundleExecutable</key><string>interline</string><key>CFBundleIdentifier</key><string>app.interline.desktop</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>
P
SIGN_IDENTITY="Interline Throwaway Test" SIGN_KEYCHAIN="$kc" "$here/sign-macos.sh" "$work/src/Interline.app" >/dev/null
tar -C "$work/src" -czf "$work/rel/Interline.app.tar.gz" Interline.app
(cd "$work/rel" && shasum -a 256 Interline.app.tar.gz > SHA256SUMS)

run() { # <script> <dest> [env...]: install.sh against the local release directory, private TMPDIR
  local script=$1 dest=$2; shift 2
  env TMPDIR="$work/tmp" INTERLINE_BASE_URL="file://$work/rel" INTERLINE_INSTALL_DIR="$dest" INTERLINE_NO_OPEN=1 "$@" sh "$script"
}
leftovers() { [ -z "$(ls -A "$work/tmp")" ] || fail "temporary files left: $(ls -A "$work/tmp")"; }
good="$work/install.good.sh"; "$here/render-install.sh" "$ID_SHA256" > "$good"
bad="$work/install.bad.sh"; "$here/render-install.sh" "$(printf '0%.0s' $(seq 64))" > "$bad"

mkdir -p "$work/ok/Interline.app"; echo old > "$work/ok/Interline.app/marker"   # an existing install gets replaced
run "$good" "$work/ok" >/dev/null
[ ! -e "$work/ok/Interline.app/marker" ] || fail "old app not replaced"
codesign --verify --deep --strict "$work/ok/Interline.app"; leftovers
[ "$(find "$work/ok" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ] || fail "staging folder left behind"
echo "PASS good install"

cp "$work/rel/SHA256SUMS" "$work/SHA256SUMS.orig"
sed 's/^./0/' "$work/SHA256SUMS.orig" > "$work/rel/SHA256SUMS"
run "$good" "$work/c" 2>"$work/err" && fail "checksum mismatch installed"; grep -q 'checksum mismatch' "$work/err"
[ ! -e "$work/c/Interline.app" ]; leftovers; cp "$work/SHA256SUMS.orig" "$work/rel/SHA256SUMS"
echo "PASS checksum mismatch aborts"

run "$bad" "$work/f" 2>"$work/err" && fail "fingerprint mismatch installed"; grep -q 'fingerprint mismatch' "$work/err"
[ ! -e "$work/f/Interline.app" ]; leftovers
echo "PASS fingerprint mismatch aborts"

for v in 14.1 13.6.4; do
  run "$good" "$work/o" INTERLINE_OS_VERSION=$v 2>"$work/err" && fail "macOS $v installed"; grep -q '14.2' "$work/err"
  [ ! -e "$work/o/Interline.app" ]; leftovers
done
run "$good" "$work/o14" INTERLINE_OS_VERSION=14.2 >/dev/null; run "$good" "$work/o15" INTERLINE_OS_VERSION=15.0 >/dev/null
echo "PASS old macOS aborts (14.1, 13.6.4), 14.2 and 15.0 accepted"

mkdir -p "$work/keep/Interline.app"; echo old > "$work/keep/Interline.app/marker"
run "$bad" "$work/keep" 2>/dev/null && fail "fingerprint mismatch installed over an existing app"
[ -f "$work/keep/Interline.app/marker" ] && [ "$(find "$work/keep" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ] || fail "existing app was not left untouched"
leftovers
echo "PASS a failed install leaves the existing app untouched"

for fp in abc "$(printf 'G%.0s' $(seq 64))"; do
  sed "s/@FINGERPRINT_SHA256@/$fp/" "$here/install.sh" > "$work/install.fp.sh"
  run "$work/install.fp.sh" "$work/fp" 2>"$work/err" && fail "malformed fingerprint accepted"; grep -q 'fingerprint' "$work/err"
done
leftovers
echo "PASS malformed pinned fingerprint aborts"

# Negative tests for the post-sign assertion (SIGN_VERIFY_ONLY=1 runs only the assertion).
verify() { SIGN_VERIFY_ONLY=1 SIGN_IDENTITY="Interline Throwaway Test" SIGN_KEYCHAIN="$kc" "$here/sign-macos.sh" "$work/neg/Interline.app" >/dev/null 2>&1; }
ditto "$work/src" "$work/neg"
verify || fail "assertion rejects a correctly signed app"
mkdir -p "$work/neg/Interline.app/Contents/Resources"; cp /usr/bin/false "$work/neg/Interline.app/Contents/Resources/extra"
codesign --remove-signature "$work/neg/Interline.app/Contents/Resources/extra"
verify && fail "assertion accepted an unsigned Mach-O"
codesign --force --sign - "$work/neg/Interline.app/Contents/Resources/extra"
verify && fail "assertion accepted an ad-hoc Mach-O"
codesign --force --sign "Interline Throwaway Test" --keychain "$kc" -i x.other "$work/neg/Interline.app/Contents/Resources/extra"
verify && fail "assertion accepted a Mach-O without the pinned DR"
echo "PASS sign-macos.sh assertion fails on unsigned, ad-hoc and unpinned Mach-O"

run "$here/install.sh" "$work/u" 2>"$work/err" && fail "unrendered script installed"; grep -q 'no pinned fingerprint' "$work/err"; leftovers
echo "PASS unrendered script aborts"
