#!/usr/bin/env python3
"""Fetch catalog models into a directory for benchmarking (stdlib only, outside the product code path).

Usage: fetch-models.py DEST_DIR [MODEL_ID ...]
Reads the packaged catalog, resumes partial downloads with HTTP Range and verifies SHA-256 before the final rename.
"""
import hashlib
import json
import os
import sys
import urllib.request

CATALOG = os.path.join(os.path.dirname(__file__), "../../services/core/app/src/main/resources/models/catalog.json")
CHUNK = 1 << 20


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(entry: dict, dest: str) -> None:
    target = os.path.join(dest, entry["file"])
    if os.path.exists(target) and sha256_of(target) == entry["sha256"]:
        print(f"{entry['id']}: already present")
        return
    part = target + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    request = urllib.request.Request(entry["url"], headers={"Range": f"bytes={have}-"} if have else {})
    with urllib.request.urlopen(request) as response, open(part, "ab" if have and response.status == 206 else "wb") as out:
        while block := response.read(CHUNK):
            out.write(block)
    if sha256_of(part) != entry["sha256"]:
        os.remove(part)
        raise SystemExit(f"{entry['id']}: checksum mismatch, partial file deleted")
    os.replace(part, target)
    print(f"{entry['id']}: downloaded and verified")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    dest, wanted = argv[1], set(argv[2:])
    os.makedirs(dest, exist_ok=True)
    with open(CATALOG, encoding="utf-8") as handle:
        entries = json.load(handle)
    for entry in entries:
        if not wanted or entry["id"] in wanted:
            fetch(entry, dest)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
