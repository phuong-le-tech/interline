#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
app=${1:-"$root/apps/desktop/src-tauri/target/release/bundle/macos/Interline.app"}
for item in MacOS/interline MacOS/interline-capture Resources/core/runtime/bin/java Resources/core/runtime/lib/jspawnhelper Resources/core/interline-core.jar Resources/core/interline-core.jsa Resources/core/native/libsqlitejdbc.dylib Resources/ui/index.html; do
  test -f "$app/Contents/$item" || { echo "Missing bundle file: $item" >&2; exit 1; }
done
for key in NSMicrophoneUsageDescription NSAudioCaptureUsageDescription NSCalendarsFullAccessUsageDescription; do
  /usr/libexec/PlistBuddy -c "Print :$key" "$app/Contents/Info.plist" >/dev/null
done
cmp "$root/services/core/app/target/interline-core.jar" "$app/Contents/Resources/core/interline-core.jar"
test -x "$app/Contents/MacOS/interline-capture"
test -x "$app/Contents/Resources/core/runtime/bin/java"
codesign --verify --deep --strict "$app"
echo "Bundle layout, jar identity and local signature: PASS"
