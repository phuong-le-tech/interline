#!/usr/bin/env python3
"""Fetch catalog models into a directory for benchmarking (stdlib only, outside the product code path).

Usage: fetch-models.py DEST_DIR [MODEL_ID ...]
Reads the packaged catalog, resumes partial downloads with HTTP Range and verifies SHA-256 before the final rename.
"""
import hashlib
import json
import pathlib
import re
import sys
import tempfile
import urllib.request

CATALOG = pathlib.Path(__file__).resolve().parents[2] / "services/core/app/src/main/resources/models/catalog.json"
CHUNK = 1 << 20
_FILE_RE = re.compile(r"^[A-Za-z0-9._-]+$")
_URL_RE = re.compile(r"^https://huggingface\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^?#]+$")
_SHA_RE = re.compile(r"^[0-9a-f]{64}$")


def _allowed_roots() -> list[pathlib.Path]:
    roots = [pathlib.Path.cwd(), pathlib.Path.home()]
    tmp = pathlib.Path("/tmp").resolve()
    if tmp not in roots:
        roots.append(tmp)
    try:
        tmpdir = pathlib.Path(tempfile.gettempdir()).resolve()
        if tmpdir not in roots:
            roots.append(tmpdir)
    except (OSError, RuntimeError):
        pass
    return roots


def _inside_any(path: pathlib.Path) -> pathlib.Path:
    resolved = path.resolve()
    if any(resolved == root or resolved.is_relative_to(root) for root in _allowed_roots()):
        return resolved
    raise SystemExit(f"path is outside allowed directories: {path}")


def _safe_dir(path: str) -> pathlib.Path:
    if "\x00" in path:
        raise SystemExit(f"invalid path: {path}")
    return _inside_any(pathlib.Path(path))


def _safe_file_name(name: str) -> str:
    if not name or pathlib.Path(name).name != name or not _FILE_RE.fullmatch(name):
        raise SystemExit(f"invalid model file name: {name}")
    return name


def _safe_url(url: str) -> str:
    if not _URL_RE.fullmatch(url):
        raise SystemExit(f"invalid model URL: {url}")
    return url


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(entry: dict, dest: pathlib.Path) -> None:
    file_name = _safe_file_name(entry["file"])
    url = _safe_url(entry["url"])
    expected_sha = entry.get("sha256")
    if not isinstance(expected_sha, str) or not _SHA_RE.fullmatch(expected_sha):
        raise SystemExit(f"{entry.get('id', '?')}: invalid sha256")
    target = dest / file_name
    if not target.resolve().is_relative_to(dest):
        raise SystemExit(f"path escapes destination: {target}")
    if target.exists() and sha256_of(target) == expected_sha:
        print(f"{entry['id']}: already present")
        return
    part = target.with_suffix(target.suffix + ".part")
    have = part.stat().st_size if part.exists() else 0
    request = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
    with urllib.request.urlopen(request) as response:
        if have and response.status != 206:
            have = 0
        mode = "ab" if have else "wb"
        with part.open(mode) as out:
            while block := response.read(CHUNK):
                out.write(block)
    if sha256_of(part) != expected_sha:
        part.unlink()
        raise SystemExit(f"{entry['id']}: checksum mismatch, partial file deleted")
    part.replace(target)
    print(f"{entry['id']}: downloaded and verified")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    dest = _safe_dir(argv[1])
    wanted = set(argv[2:])
    with CATALOG.open(encoding="utf-8") as handle:
        entries = json.load(handle)
    for entry in entries:
        if not wanted or entry["id"] in wanted:
            fetch(entry, dest)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
