"""M1 acceptance assertions over real artifacts, not application implementation.

Run via catalog-check.sh, latency.sh or repo-check.sh. Catalog accepts an optional
catalog path. Latency requires an evidence directory (see README.md).
"""
import json
import math
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BASE = "7ecff48"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def _catalog_path(path: str) -> Path:
    p = Path(path).resolve()
    if not p.is_file():
        raise AssertionError(f"AC8_catalog: missing packaged catalog source: {p}")
    return p


def catalog(path):
    catalog_file = _catalog_path(path)
    document = json.loads(catalog_file.read_text())
    entries = document.get("models") if isinstance(document, dict) else document
    if not isinstance(entries, list):
        raise AssertionError("AC8_catalog: expected model entries array")
    require({row["id"] for row in entries} == {
        "tiny", "base", "small", "large-v3-turbo-q5_0", "large-v3-turbo-q8_0", "silero-v6.2.0"
    }, "AC8_catalog: exactly the six binding model IDs are required")
    require(len(entries) == 6, "AC8_catalog: duplicate entries")
    for row in entries:
        identifier = row["id"]
        url = row["url"]
        require(re.fullmatch(r"https://huggingface\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^?#]+", url),
                f"AC8_catalog_{identifier}: URL must pin a full HF commit")
        request = urllib.request.Request(url, method="HEAD")
        # The original response carries x-linked-*; do not follow a redirect to a signed blob.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        try:
            response = urllib.request.build_opener(NoRedirect).open(request, timeout=30)
        except urllib.error.HTTPError as error:
            require(error.code in (301, 302, 303, 307, 308), f"AC8_catalog_{identifier}: HF status {error.code}")
            response = error
        with response:
            digest = response.headers.get("x-linked-etag", "").strip('"')
            size = response.headers.get("x-linked-size", "")
            require(re.fullmatch(r"[0-9a-f]{64}", row["sha256"]), f"AC8_catalog_{identifier}: full SHA-256 required")
            require(isinstance(row["sizeBytes"], int) and row["sizeBytes"] > 0, f"AC8_catalog_{identifier}: positive byte size required")
            require(digest == row["sha256"], f"AC8_catalog_{identifier}: missing or mismatched x-linked-etag: {digest}")
            require(size == str(row["sizeBytes"]), f"AC8_catalog_{identifier}: missing or mismatched x-linked-size: {size}")
            print(f"PASS AC8_catalog_{identifier} commit_url={url} sha256={digest} size={size}")


def latency(directory):
    require(directory.is_dir(), f"AC11_latency: missing actual run evidence directory: {directory}")
    for name in ("run.json", "stats.json", "finals.ndjson", "core.log"):
        require((directory / name).is_file(), f"AC11_latency: missing measured {name}")
    run = json.loads((directory / "run.json").read_text())
    require(run["model"] == "small" and run["speed"] == 1 and run["audio_s"] >= 600,
            "AC11_latency: require >=600 audio seconds, small, speed 1")
    require(run["agent"] == "interline-capture-macos" and run["wall_s"] >= 600,
            "AC11_latency: require real-time native-agent run")
    stats = json.loads((directory / "stats.json").read_text())
    finals = [json.loads(line) for line in (directory / "finals.ndjson").read_text().splitlines() if line.strip()]
    logs = (directory / "core.log").read_text()
    for stream in ("mic", "system"):
        rows = [row for row in finals if row["stream"] == stream]
        require(len(rows) >= 20, f"AC11_latency_{stream}: nontrivial final corpus required")
        values = []
        for row in rows:
            value = row["receipt_wall_ms"] - (row["t0_wall_ms"] + row["end_sample"] / 16)
            require(math.isfinite(value) and value >= 0, f"AC11_latency_{stream}: invalid receipt/sample units")
            require(abs(value - row["stt_latency_ms"]) <= 1, f"AC11_latency_{stream}: incorrect latency formula")
            values.append(value)
        measured = stats[stream]["stt_latency_ms"]
        require(abs(measured["median"] - statistics.median(values)) <= 1,
                f"AC11_latency_{stream}: API median differs from receipts")
        # Do not impose an unspecified percentile interpolation convention.
        ordered = sorted(values)
        lower = ordered[max(0, math.floor(0.95 * (len(values) - 1)))]
        upper = ordered[math.ceil(0.95 * (len(values) - 1))]
        require(lower - 1 <= measured["p95"] <= upper + 1, f"AC11_latency_{stream}: p95 outside empirical bracket")
        require(re.search(rf"stt_latency_ms=\d+(?:\.\d+)? stream={stream}\b", logs),
                f"AC11_latency_{stream}: missing receipt latency log")
        print(f"PASS AC11_latency_{stream} finals={len(rows)} median={measured['median']} p95={measured['p95']}")


def job(text, name):
    match = re.search(rf"^  {re.escape(name)}:\n(?:(?!^  [\w-]+:).*(?:\n|$))*", text, re.MULTILINE)
    if match is None:
        raise AssertionError(f"AC16_hygiene: missing CI job {name}")
    return match.group().rstrip()


def hygiene():
    paths = git("ls-files", "-z").decode().split("\0")
    forbidden = [p for p in paths if re.search(r"\.(?:wav|aiff|caf|gguf|key|pem|p12|pfx|keychain-db)$", p, re.I)
                 or Path(p).name in ("AGENTS.md", "CLAUDE.md", "id_rsa", "id_ed25519")
                 or re.search(r"(?:ggml-[^/]+|model[^/]*)\.bin$", p, re.I)]
    require(not forbidden, f"AC16_hygiene: forbidden tracked artifacts: {forbidden}")
    original_paths = git("ls-tree", "-r", "--name-only", BASE).decode().splitlines()
    m0_tests = [p for p in original_paths if "/src/test/" in p or p.startswith("apps/desktop/e2e/")]
    changed = git("diff", "--name-only", BASE, "--", *m0_tests,
                  "protocol/agent/v1/golden/m0.ndjson").decode().splitlines()
    require(not changed, f"AC16_hygiene: existing M0 tests/golden changed: {changed}")
    workflow = ROOT / ".github/workflows/ci.yml"
    current = workflow.read_text()
    base = git("show", f"{BASE}:.github/workflows/ci.yml").decode()
    require(job(current, "core") == job(base, "core"), "AC16_hygiene: core CI job changed")
    require(job(current, "ui") == job(base, "ui"), "AC16_hygiene: ui CI job changed")
    native = job(current, "agent-macos")
    require("macos-15" in native, "AC16_hygiene: native job must use macos-15")
    for command in ("swift build", "CaptureSelfTest", "gen-speech.sh", "-Pswift-agent"):
        require(command in native, f"AC16_hygiene: native job missing {command}")
    require(shutil.which("actionlint") is not None, "AC16_hygiene: actionlint unavailable, not a passing check")
    subprocess.run(["actionlint", str(workflow)], cwd=ROOT, check=True)
    print("PASS AC16_hygiene: tracked artifacts, unchanged M0/core/ui and actionlint")


if __name__ == "__main__":
    try:
        mode = sys.argv[1]
        if mode == "catalog":
            catalog(Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "services/core/app/src/main/resources/models/catalog.json")
        elif mode == "latency":
            latency(Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "artifacts/m1/latency")
        elif mode == "hygiene":
            hygiene()
        else:
            raise AssertionError(f"Unknown evidence check: {mode}")
    except (AssertionError, OSError, KeyError, ValueError, subprocess.CalledProcessError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
