#!/bin/sh
# Usage: render-install.sh <sha256-fingerprint> > install.sh   (pins the certificate into the installer)
set -eu
fp=$(printf '%s' "${1:?SHA-256 fingerprint}" | tr -d ':' | tr 'a-f' 'A-F')
case $fp in *[!0-9A-F]*) echo "not a hex fingerprint" >&2; exit 1 ;; esac
[ "${#fp}" -eq 64 ] || { echo "a SHA-256 fingerprint has 64 hex digits" >&2; exit 1; }
sed "s/@FINGERPRINT_SHA256@/$fp/" "$(dirname "$0")/install.sh"
