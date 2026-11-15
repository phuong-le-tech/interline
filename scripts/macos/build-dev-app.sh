#!/bin/sh
# Builds Interline.app (debug) with the Swift capture agent as a sidecar, every Mach-O signed with one stable
# identity (scripts/macos/signing-identity.sh). Development only: the shell still launches the core from this
# repository's jar (bundled runtimes arrive in M5).
# Usage: scripts/macos/build-dev-app.sh   -> apps/desktop/src-tauri/target/debug/bundle/macos/Interline.app
#        INTERLINE_SIGN=adhoc scripts/macos/build-dev-app.sh   signs ad-hoc instead (ADR-004 spike d)
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
identity="Interline Dev Signing"
keychain="$HOME/Library/Keychains/interline-dev.keychain-db"
agent_dir="$root/agents/macos-capture"
tauri_dir="$root/apps/desktop/src-tauri"
triple=$(rustc -vV | sed -n 's/^host: //p')

if [ "${INTERLINE_SIGN:-identity}" = "adhoc" ]; then
  sign() { codesign --force --sign - "$@"; }
else
  hash=$(security find-identity -p codesigning "$keychain" 2>/dev/null | awk -v id="\"$identity\"" 'index($0, id) { print $2; exit }')
  [ -n "$hash" ] || { echo "no '$identity' identity: run scripts/macos/signing-identity.sh first" >&2; exit 1; }
  security unlock-keychain -p "$(cat "$HOME/.interline-dev/keychain-password")" "$keychain"
  sign() { codesign --force --keychain "$keychain" --sign "$hash" "$@"; }
fi

echo "== agent"
(cd "$agent_dir" && swift build -c release)
mkdir -p "$tauri_dir/binaries"
cp "$agent_dir/.build/release/interline-capture-macos" "$tauri_dir/binaries/interline-capture-macos-$triple"
# The identifier is part of the signature TCC remembers: keep it equal to the embedded Info.plist.
sign --identifier app.interline.capture "$tauri_dir/binaries/interline-capture-macos-$triple"

echo "== core"
(cd "$root/services/core" && ./mvnw -B -q package -DskipTests)

echo "== app"
config='{"bundle":{"active":true,"targets":["app"],"externalBin":["binaries/interline-capture-macos"],"macOS":{"minimumSystemVersion":"14.2"}}}'
(cd "$root/apps/desktop" && pnpm tauri build --debug --bundles app --config "$config")

app="$tauri_dir/target/debug/bundle/macos/Interline.app"
macos="$app/Contents/MacOS"
# The agent loads whisper.framework from @loader_path, so it travels beside the agent.
ditto "$agent_dir/.build/release/whisper.framework" "$macos/whisper.framework"

echo "== signing (inside out)"
find "$macos/whisper.framework" -type f -perm +111 | while read -r file; do
  if file "$file" | grep -q "Mach-O"; then sign "$file"; fi
done
sign "$macos/whisper.framework"
sign --identifier app.interline.capture "$macos/interline-capture-macos"
for file in "$macos"/*; do
  [ -f "$file" ] || continue
  case "$file" in */interline-capture-macos) continue ;; esac
  if file "$file" | grep -q "Mach-O"; then sign "$file"; fi
done
sign "$app"
codesign --verify --deep --strict "$app"
echo "built and signed: $app"
