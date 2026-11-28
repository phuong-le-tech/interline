#!/bin/sh
# Usage: make-latest-json.sh <version> <owner/repo> <tag> <path-to-.sig>   (Tauri updater static manifest on stdout)
set -eu
version=${1:?version}; repo=${2:?owner/repo}; tag=${3:?tag}; sig=${4:?signature file}
python3 -I - "$version" "$repo" "$tag" "$sig" <<'P'
import json, sys, datetime
version, repo, tag, sig = sys.argv[1:5]
print(json.dumps({
    "version": version,
    "notes": f"Interline {version}",
    "pub_date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "platforms": {"darwin-aarch64": {
        "signature": open(sig).read().strip(),
        "url": f"https://github.com/{repo}/releases/download/{tag}/Interline.app.tar.gz"}},
}, indent=2))
P
