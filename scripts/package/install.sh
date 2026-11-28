#!/bin/sh
# Interline installer for macOS (ADR-004, install path A). Read it before you run it.
# It downloads the release tarball, checks the SHA-256 and the signing certificate fingerprint pinned in this
# file, copies the app to /Applications (or ~/Applications) and opens it.
# It never uses sudo, never installs a certificate and never changes a Gatekeeper setting.
# Test overrides: INTERLINE_BASE_URL (https:// or file://), INTERLINE_INSTALL_DIR, INTERLINE_OS_VERSION, INTERLINE_NO_OPEN=1.
set -eu

FINGERPRINT='@FINGERPRINT_SHA256@' # SHA-256 of the release signing certificate; filled in by release.yml
BASE_URL=${INTERLINE_BASE_URL:-https://github.com/phuong-le-tech/interline/releases/latest/download}

die() { echo "interline install: $*" >&2; exit 1; }

case $FINGERPRINT in @*) die "no pinned fingerprint in this copy; download install.sh from a release page" ;; esac
pinned=$(printf '%s' "$FINGERPRINT" | tr -d ':' | tr 'a-f' 'A-F')
case $pinned in *[!0-9A-F]*) die "the pinned fingerprint is not hexadecimal" ;; esac
[ "${#pinned}" -eq 64 ] || die "the pinned fingerprint must be 64 hex digits (SHA-256)"
[ "$(uname -s)" = Darwin ] || die "macOS only"
[ "$(uname -m)" = arm64 ] || die "this release is built for Apple Silicon (arm64)"
os=${INTERLINE_OS_VERSION:-$(sw_vers -productVersion)}
major=${os%%.*}; rest=${os#*.}; minor=${rest%%.*}
[ "$major" -gt 14 ] || { [ "$major" -eq 14 ] && [ "${minor:-0}" -ge 2 ]; } || die "macOS 14.2 or later is required (found $os)"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/interline-install.XXXXXX")
staged=; aside=
# On failure the previous app is put back; the staged copy and the temp folder are always removed.
cleanup() {
  rm -rf "$tmp"; [ -z "$staged" ] || rm -rf "$staged"
  if [ -n "$aside" ] && [ ! -e "$dest/Interline.app" ]; then mv "$aside" "$dest/Interline.app"; fi
}
trap cleanup EXIT
trap 'exit 1' INT TERM HUP

fetch() { curl -fsSL --proto '=https,file' --proto-redir '=https' -o "$tmp/$1" "$BASE_URL/$1" || die "download failed: $BASE_URL/$1"; }
fetch Interline.app.tar.gz
fetch SHA256SUMS

expected=$(grep '  Interline.app.tar.gz$' "$tmp/SHA256SUMS" | head -n 1 | cut -d ' ' -f 1)
[ -n "$expected" ] || die "SHA256SUMS has no entry for Interline.app.tar.gz"
actual=$(shasum -a 256 "$tmp/Interline.app.tar.gz" | cut -d ' ' -f 1)
[ "$expected" = "$actual" ] || die "checksum mismatch, nothing was installed"

mkdir "$tmp/x" "$tmp/cert"
tar -xzf "$tmp/Interline.app.tar.gz" -C "$tmp/x"
app="$tmp/x/Interline.app"
[ -d "$app" ] || die "the archive does not contain Interline.app"
codesign --verify --deep --strict "$app" || die "code signature is not valid, nothing was installed"
# codesign writes the certificate chain as codesign0 (leaf), codesign1, ... into the working directory.
(cd "$tmp/cert" && codesign -d --extract-certificates "$app" 2>/dev/null)
found=$(/usr/bin/openssl x509 -inform DER -noout -fingerprint -sha256 -in "$tmp/cert/codesign0" | sed 's/.*=//' | tr -d ':' | tr 'a-f' 'A-F')
[ "$found" = "$pinned" ] || die "certificate fingerprint mismatch (found $found), nothing was installed"

dest=${INTERLINE_INSTALL_DIR:-}
if [ -z "$dest" ]; then
  if [ -w /Applications ]; then dest=/Applications; else dest=$HOME/Applications; fi
fi
mkdir -p "$dest"
staged="$dest/.Interline.app.$$"
ditto "$app" "$staged"
codesign --verify --deep --strict "$staged" || die "the copied app does not verify, nothing was replaced"
if [ -e "$dest/Interline.app" ]; then aside="$dest/.Interline.old.$$"; mv "$dest/Interline.app" "$aside"; fi
mv "$staged" "$dest/Interline.app" || die "cannot move the new app into place"
staged=
[ -z "$aside" ] || { rm -rf "$aside"; aside=; }
xattr -dr com.apple.quarantine "$dest/Interline.app" 2>/dev/null || true
echo "Installed $dest/Interline.app (certificate $found)"
[ "${INTERLINE_NO_OPEN:-}" = 1 ] || open "$dest/Interline.app"
