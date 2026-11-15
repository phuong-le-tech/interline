#!/bin/sh
# Creates the self-signed code signing identity "Interline Dev Signing" in a dedicated keychain.
# Why: macOS keys microphone and audio-capture grants to the code signature. An ad-hoc signature changes with every
# build and drops the grant; a stable identity keeps it (ADR-004 spike d). Development only, never a release identity.
# Nothing is committed: the key lives in ~/.interline-dev/ (mode 700) and the keychain file only.
# Usage: scripts/macos/signing-identity.sh        (idempotent)
set -eu

identity="Interline Dev Signing"
home="$HOME/.interline-dev"
keychain="$HOME/Library/Keychains/interline-dev.keychain-db"
secret_file="$home/keychain-password"

if security find-certificate -c "$identity" "$keychain" >/dev/null 2>&1; then
  echo "identity already present in $keychain"
else
  umask 077
  mkdir -p "$home"
  [ -f "$secret_file" ] || openssl rand -base64 24 > "$secret_file"
  password=$(cat "$secret_file")
  work=$(mktemp -d "$home/new.XXXXXX")
  trap 'rm -rf "$work"' EXIT

  cat > "$work/openssl.cnf" <<'EOF'
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = Interline Dev Signing
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
EOF
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$work/openssl.cnf" \
    -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null
  # `security import` only reads the legacy PKCS12 algorithms.
  openssl pkcs12 -export -inkey "$work/key.pem" -in "$work/cert.pem" -out "$work/identity.p12" \
    -passout "pass:$password" -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1

  security create-keychain -p "$password" "$keychain"
  security set-keychain-settings "$keychain"          # no auto-lock timeout
  security unlock-keychain -p "$password" "$keychain"
  security import "$work/identity.p12" -k "$keychain" -P "$password" -T /usr/bin/codesign
  security set-key-partition-list -S apple-tool:,apple: -s -k "$password" "$keychain" >/dev/null
  # Make the keychain visible to codesign without removing the ones already searched.
  existing=$(security list-keychains -d user | tr -d '"' | tr '\n' ' ')
  # shellcheck disable=SC2086
  security list-keychains -d user -s "$keychain" $existing
  echo "created identity '$identity' in $keychain"
fi

security unlock-keychain -p "$(cat "$secret_file")" "$keychain"
# A self-signed certificate is not "trusted", so find-identity -v hides it; codesign still accepts its hash.
security find-identity -p codesigning "$keychain" | grep "$identity"
