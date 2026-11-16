#!/usr/bin/env python3
"""Runs one real-time session through the core and the native agent and records the AC11 evidence.

Usage: latency-run.py EVIDENCE_DIR MODELS_DIR [SESSION_WAV] [--network-check SECONDS]

Starts the packaged core jar with the Swift agent on --test-input at speed 1, records until the audio ends,
then writes run.json, finals.ndjson, stats.json and core.log for scripts/m1/latency.sh. Each final's receipt
time is taken from the SSE stream when it arrives. With --network-check the offline proof runs against the same
processes for that many seconds (output: network-check.txt). Stdlib only.
"""
import json
import os
import pathlib
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAR = (ROOT / "services/core/app/target/interline-core.jar").resolve()
AGENT = (ROOT / "agents/macos-capture/.build/release/interline-capture-macos").resolve()
DEFAULT_SESSION = (ROOT / "fixtures/speech/session-10min.wav").resolve()
SESSION_SECONDS = 610
SETTLE_SECONDS = 8
BOOT_TIMEOUT_SECONDS = 30
MODEL = "small"
MODEL_FILES = ("ggml-small.bin", "ggml-silero-v6.2.0.bin")


def _resolve_dir(path: str) -> pathlib.Path:
    p = pathlib.Path(path).resolve()
    p.mkdir(parents=True, exist_ok=True)
    return p


def _resolve_file(path: str) -> pathlib.Path:
    p = pathlib.Path(path).resolve()
    if not p.is_file():
        raise SystemExit(f"not a file: {path}")
    return p


class Core:
    def __init__(self, work: pathlib.Path, models: pathlib.Path, session: pathlib.Path):
        self.token = secrets.token_urlsafe(32)
        data = work / "data"
        (data / "models").mkdir(parents=True)
        for name in MODEL_FILES:
            source = _resolve_file(str(models / name))
            (data / "models" / name).symlink_to(source)
        self.log_dir = work / "logs"
        args = [
            "java", "-jar", str(JAR), f"--data-dir={data}", f"--log-dir={self.log_dir}", f"--agent={AGENT}",
            "--agent-arg=--test-speed=1", f"--agent-arg=--test-input={session}", "--agent-arg=--language=en",
            "--parent=stdio",
        ]
        self.process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=open(work / "core.err", "w"), text=True)
        assert self.process.stdin is not None
        self.process.stdin.write(json.dumps({"token": self.token}) + "\n")
        self.process.stdin.flush()
        self.port = self._read_port()

    def _read_port(self) -> int:
        deadline = time.time() + BOOT_TIMEOUT_SECONDS
        while time.time() < deadline:
            line = self.process.stdout.readline()
            if not line:
                break
            try:
                message = json.loads(line)
            except ValueError:
                continue
            if message.get("event") == "ready":
                return int(message["port"])
        raise SystemExit("core did not announce READY")

    def call(self, method: str, path: str):
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}/api/v1{path}", method=method,
                                         headers={"Authorization": f"Bearer {self.token}"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read() or b"null")

    def stop(self) -> None:
        try:
            self.process.stdin.write('{"cmd":"shutdown"}\n')
            self.process.stdin.close()
        except OSError:
            pass
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()


def collect_finals(core: Core, finals: list, stop: threading.Event) -> None:
    request = urllib.request.Request(f"http://127.0.0.1:{core.port}/api/v1/events",
                                     headers={"Authorization": f"Bearer {core.token}", "Accept": "text/event-stream"})
    with urllib.request.urlopen(request) as stream:
        name = None
        for raw in stream:
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:") and name == "stt.final":
                data = json.loads(line[5:])
                # The core stamps the instant it received the final from the agent; the SSE hop would add noise.
                finals.append({"stream": data["stream"], "receipt_wall_ms": data["receivedAtMs"], "end_ms": data["endMs"]})
            if stop.is_set():
                return


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    network_seconds = 0
    if "--network-check" in argv:
        network_seconds = int(argv[argv.index("--network-check") + 1])
        args.remove(str(network_seconds))
    if len(args) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    evidence = _resolve_dir(args[0])
    models = _resolve_dir(args[1])
    session = _resolve_file(args[2]) if len(args) > 2 else DEFAULT_SESSION
    for needed in (JAR, AGENT, session, *(models / n for n in MODEL_FILES)):
        if not needed.exists():
            raise SystemExit(f"missing {needed}")
    work = pathlib.Path(tempfile.mkdtemp(prefix="interline-latency-"))
    core = Core(work, models, session)
    finals: list = []
    stop = threading.Event()
    network = None
    try:
        threading.Thread(target=collect_finals, args=(core, finals, stop), daemon=True).start()
        time.sleep(1)
        started = time.time()
        meeting = core.call("POST", "/recording/start")["meetingId"]
        if network_seconds:
            time.sleep(3)
            network = subprocess.Popen([str(ROOT / "scripts/m1/no-network-check.sh"), str(network_seconds)],
                                       stdout=open(evidence / "network-check.txt", "w"), stderr=subprocess.STDOUT)
        time.sleep(SESSION_SECONDS + SETTLE_SECONDS)
        core.call("POST", "/recording/stop")
        time.sleep(SETTLE_SECONDS)
        wall = time.time() - started
        stats = core.call("GET", f"/meetings/{meeting}/stats")
        stop.set()
    finally:
        if network is not None:
            network.wait(timeout=network_seconds + 30)
        core.stop()
    t0 = stats["t0WallMs"]
    with open(evidence / "finals.ndjson", "w") as out:
        for final in finals:
            end_sample = final["end_ms"] * 16
            latency = final["receipt_wall_ms"] - (t0 + end_sample / 16)
            out.write(json.dumps({"stream": final["stream"], "receipt_wall_ms": final["receipt_wall_ms"],
                                  "t0_wall_ms": t0, "end_sample": end_sample, "stt_latency_ms": latency}) + "\n")
    shaped = {stream: {"stt_latency_ms": {"median": v["median_ms"], "p95": v["p95_ms"], "count": v["count"]}}
              for stream, v in stats["sttLatencyMs"].items()}
    (evidence / "stats.json").write_text(json.dumps(shaped, indent=2))
    (evidence / "stats-api.json").write_text(json.dumps(stats, indent=2))
    (evidence / "run.json").write_text(json.dumps({
        "model": MODEL, "speed": 1, "audio_s": SESSION_SECONDS, "wall_s": round(wall, 1),
        "agent": "interline-capture-macos", "session": session.name}, indent=2))
    lines = [line for log in sorted(core.log_dir.rglob("*.log")) for line in log.read_text().splitlines()
             if "stt_latency_ms=" in line]
    (evidence / "core.log").write_text("\n".join(lines) + "\n")
    print(json.dumps(shaped))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
