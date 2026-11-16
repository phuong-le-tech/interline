#!/usr/bin/env python3
"""Fetch catalog models into a directory for benchmarking (stdlib only, outside the product code path).

Usage: fetch-models.py DEST_DIR [MODEL_ID ...]
Reads the packaged catalog, resumes partial downloads with HTTP Range and verifies SHA-256 before the final rename.
"""
import hashlib
import json
import os
import re
import sys
import urllib.request

CATALOG = os.path.join(os.path.dirname(__file__), "../../services/core/app/src/main/resources/models/catalog.json")
CHUNK = 1 << 20
_FILE_RE = re.compile(r"^[A-Za-z0-9._-]+$")
_URL_RE = re.compile(r"^https://huggingface\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^?#]+$")
_SHA_RE = re.compile(r"^[0-9a-f]{64}$")


def _safe_dir(path: str) -> str:
    if "\x00" in path:
        raise SystemExit(f"invalid path: {path}")
    resolved = os.path.realpath(path)
    os.makedirs(resolved, exist_ok=True)
    return resolved


def _safe_file_name(name: str) -> str:
    if not name or os.path.basename(name) != name or not _FILE_RE.fullmatch(name):
        raise SystemExit(f"invalid model file name: {name}")
    return name


def _safe_url(url: str) -> str:
    if not _URL_RE.fullmatch(url):
        raise SystemExit(f"invalid model URL: {url}")
    return url


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(entry: dict, dest: str) -> None:
    file_name = _safe_file_name(entry["file"])
    url = _safe_url(entry["url"])
    expected_sha = entry.get("sha256")
    if not isinstance(expected_sha, str) or not _SHA_RE.fullmatch(expected_sha):
        raise SystemExit(f"{entry.get('id', '?')}: invalid sha256")
    target = os.path.join(dest, file_name)
    if os.path.exists(target) and sha256_of(target) == expected_sha:
        print(f"{entry['id']}: already present")
        return
    part = target + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    request = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
    with urllib.request.urlopen(request) as response:
        if have and response.status != 206:
            have = 0
        mode = "ab" if have else "wb"
        with open(part, mode) as out:
            while block := response.read(CHUNK):
                out.write(block)
    if sha256_of(part) != expected_sha:
        os.remove(part)
        raise SystemExit(f"{entry['id']}: checksum mismatch, partial file deleted")
    os.replace(part, target)
    print(f"{entry['id']}: downloaded and verified")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    dest = _safe_dir(argv[1])
    wanted = set(argv[2:])
    with open(CATALOG, encoding="utf-8") as handle:
        entries = json.load(handle)
    for entry in entries:
        if not wanted or entry["id"] in wanted:
            fetch(entry, dest)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
