#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
test "$(uname -sm)" = 'Darwin arm64' || { echo 'This local package targets Apple Silicon macOS.' >&2; exit 1; }
"$root/scripts/package/build-core.sh"
"$root/scripts/package/prepare-core.sh"
tauri="$root/apps/desktop/src-tauri"
stage="$tauri/target/package"
triple=$(rustc -vV | awk '/^host:/ {print $2}')
# SwiftPM does not track the Info.plist passed to the linker as an input; force that output to relink.
rm -f "$root/agents/macos-capture/.build/release/interline-capture-macos"
(cd "$root/agents/macos-capture" && swift build -c release)
cp "$root/agents/macos-capture/.build/release/interline-capture-macos" "$stage/interline-capture-$triple"
cd "$root/apps/desktop"
pnpm install --frozen-lockfile
pnpm build
rm -rf "$stage/ui"
cp -R dist "$stage/ui"
# Keep generated paths in a build overlay, so clean tauri dev and cargo check need no package staging.
config='{"bundle":{"resources":{"target/package/core/":"core/","target/package/ui/":"ui/"},"externalBin":["target/package/interline-capture"],"macOS":{"hardenedRuntime":false,"signingIdentity":"-"}}}'
# An earlier bundle may have left read-only jlink resources in Tauri's build-copy directory.
rm -rf "$tauri/target/release/core" "$tauri/target/release/ui"
pnpm tauri build --bundles app --config "$config" -- --locked
app="$tauri/target/release/bundle/macos/Interline.app"
# whisper.framework uses @loader_path and must sit beside the sidecar.
ditto "$root/agents/macos-capture/.build/release/whisper.framework" "$app/Contents/MacOS/whisper.framework"
# Local ad-hoc signatures only. Do not access signing identities or keychains.
while IFS= read -r -d '' binary; do
  if file "$binary" | grep -q 'Mach-O'; then codesign --force --options=0 --sign - "$binary"; fi
done < <(find "$app/Contents" -type f -print0)
codesign --force --options=0 --sign - "$app/Contents/MacOS/whisper.framework"
codesign --force --options=0 --sign - "$app"
"$root/scripts/package/assert-bundle.sh" "$app"
# A local DMG without invoking the release/signing pipeline.
dmg="$tauri/target/release/bundle/dmg/Interline-local-$triple.dmg"
mkdir -p "$(dirname "$dmg")"
dmg_root=$(mktemp -d "$stage/dmg.XXXXXX")
trap 'rm -rf "$dmg_root"' EXIT
ditto "$app" "$dmg_root/Interline.app"
ln -s /Applications "$dmg_root/Applications"
hdiutil create -ov -format UDZO -volname Interline -srcfolder "$dmg_root" "$dmg"
du -sk "$app" "$dmg"
