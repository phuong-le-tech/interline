#!/usr/bin/env bash
# Signs Interline.app inside-out with a project identity and an explicit designated requirement (ADR-004).
# Usage: sign-macos.sh <path/to/Interline.app>
# Env:   SIGN_IDENTITY   certificate common name (required)
#        SIGN_KEYCHAIN   keychain file that holds it (optional, default: search list)
#        SIGN_ENTITLEMENTS_DIR  folder with jvm.plist and agent.plist (default: ./entitlements)
#        SIGN_VERIFY_ONLY  1 = skip signing, only run the final assertion (used by the negative tests)
#        SIGN_HARDENED   1 = hardened runtime with entitlements (default 0, see ADR-014)
set -euo pipefail
app=${1:?path to Interline.app}
identity=${SIGN_IDENTITY:?set SIGN_IDENTITY to the certificate common name}
here=$(cd "$(dirname "$0")" && pwd)
ents=${SIGN_ENTITLEMENTS_DIR:-$here/entitlements}
kc_args=(); [ -z "${SIGN_KEYCHAIN:-}" ] || kc_args=(--keychain "$SIGN_KEYCHAIN")
kc_list=(); [ -z "${SIGN_KEYCHAIN:-}" ] || kc_list=("$SIGN_KEYCHAIN")
# bash 3.2 (macOS) fails on empty arrays under set -u, hence the ${a[@]+...} form.
# security prints "SHA-1 hash: <40 hex>"; the DR pins this leaf certificate.
sha1=$(security find-certificate -c "$identity" -Z ${kc_list[@]+"${kc_list[@]}"} | awk '/^SHA-1 hash:/ {print $3; exit}')
[[ $sha1 =~ ^[0-9A-F]{40}$ ]] || { echo "identity '$identity' not found" >&2; exit 1; }
opts=(--options=0); [ "${SIGN_HARDENED:-0}" != 1 ] || opts=(--options=runtime)

sign() { # <path> <identifier> [entitlements]
  local ent=(); [ -z "${3:-}" ] || ent=(--entitlements "$3")
  codesign --force --timestamp=none "${opts[@]}" ${kc_args[@]+"${kc_args[@]}"} ${ent[@]+"${ent[@]}"} --sign "$identity" -i "$2" \
    -r="designated => identifier \"$2\" and certificate leaf = H\"$sha1\"" "$1"
}
macho() { file -b "$1" | grep -q 'Mach-O'; }

if [ "${SIGN_VERIFY_ONLY:-0}" != 1 ]; then
# 1. every loose Mach-O: jlink runtime (java, jspawnhelper, dylibs), libsqlitejdbc.dylib, the Swift agent.
while IFS= read -r -d '' f; do
  macho "$f" || continue
  name=$(basename "$f")
  case "$f" in
    */Contents/MacOS/interline) continue ;;                      # the bundle signature covers the main executable
    */Contents/MacOS/interline-capture) sign "$f" app.interline.capture "$ents/agent.plist" ;;
    */core/runtime/bin/java) sign "$f" app.interline.runtime.java "$ents/jvm.plist" ;;
    */core/runtime/*) sign "$f" "app.interline.runtime.${name%.dylib}" ;;
    */core/native/libsqlitejdbc.dylib) sign "$f" app.interline.sqlite ;;
    *.framework/*) continue ;;                                   # signed as a unit in step 2
    *) sign "$f" "app.interline.${name%.dylib}" ;;
  esac
done < <(find "$app/Contents" -type f -print0)
# 2. frameworks, then 3. the bundle (the shell is its main executable).
while IFS= read -r -d '' fw; do sign "$fw" "app.interline.$(basename "${fw%.framework}")"; done < <(find "$app/Contents" -type d -name '*.framework' -print0)
sign "$app" app.interline.desktop
fi

# Assert: nothing left unsigned or ad-hoc, every Mach-O carries our DR, the whole bundle verifies.
bad=0
while IFS= read -r -d '' f; do
  macho "$f" || continue
  if ! codesign --verify --strict "$f" 2>/dev/null || ! codesign -d -r- "$f" 2>&1 | grep -qi "H\"$sha1\""; then echo "NOT SIGNED WITH $identity: $f" >&2; bad=1; fi
done < <(find "$app/Contents" -type f -print0)
[ "$bad" = 0 ] || exit 1
codesign --verify --deep --strict --verbose=2 "$app"
echo "Signed with '$identity' (SHA-1 $sha1), hardened=${SIGN_HARDENED:-0}"
codesign -d -r- "$app" 2>&1 | grep '^designated'
