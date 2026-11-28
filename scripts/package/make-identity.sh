#!/usr/bin/env bash
# Creates a self-signed code signing identity in a DEDICATED keychain file (never the login keychain).
# Usage: make-identity.sh <keychain-path> <common-name> <password>   (writes nothing else to disk)
# Prints "SHA1=<hex>" and "SHA256=<hex>" of the leaf certificate on stdout.
set -euo pipefail
kc=${1:?keychain path}; cn=${2:?common name}; pw=${3:?keychain password}
case "$kc" in *login.keychain*|*/System.keychain*) echo "refusing to touch $kc" >&2; exit 1;; esac
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
openssl req -x509 -newkey rsa:3072 -sha256 -days 3650 -nodes -keyout "$work/k.key" -out "$work/c.crt" -subj "/CN=$cn" \
  -addext 'basicConstraints=critical,CA:false' -addext 'keyUsage=critical,digitalSignature' -addext 'extendedKeyUsage=critical,codeSigning' 2>/dev/null
# -legacy is needed by OpenSSL 3+ so that macOS "security import" reads the PKCS#12; LibreSSL has no such flag.
openssl pkcs12 -export -legacy -inkey "$work/k.key" -in "$work/c.crt" -name "$cn" -out "$work/i.p12" -passout "pass:$pw" 2>/dev/null \
  || openssl pkcs12 -export -inkey "$work/k.key" -in "$work/c.crt" -name "$cn" -out "$work/i.p12" -passout "pass:$pw"
rm -f "$kc"
security create-keychain -p "$pw" "$kc"
security set-keychain-settings -lut 21600 "$kc"
security unlock-keychain -p "$pw" "$kc"
security import "$work/i.p12" -k "$kc" -P "$pw" -T /usr/bin/codesign >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$pw" "$kc" >/dev/null
echo "SHA1=$(openssl x509 -in "$work/c.crt" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d : )"
echo "SHA256=$(openssl x509 -in "$work/c.crt" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d :)"
