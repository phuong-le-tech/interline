#!/usr/bin/env python3
"""Checks that short utterances on the system channel reach the transcript through core and the native agent.

Usage: short-utterances-check.py MODELS_DIR [SESSION_WAV]

Reuses the core launcher of latency-run.py, records the whole WAV in real time, then prints every final with
its stream and text and fails unless the system stream produced at least one final per utterance announced in
the fixture (scripts/fixtures/gen-short-utterances.py). Spike (c) of the human sitting depends on this.
"""
import importlib.util
import json
import os
import pathlib
import sys
import tempfile
import threading
import time
import urllib.request
import wave

HERE = pathlib.Path(__file__).resolve().parent
EXPECTED_FINALS = 3
SETTLE_SECONDS = 10
MIN_WORDS = 3


def load_launcher():
    spec = importlib.util.spec_from_file_location("latency_run", HERE / "latency-run.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def collect(core, finals, stop):
    request = urllib.request.Request(f"http://127.0.0.1:{core.port}/api/v1/events",
                                     headers={"Authorization": f"Bearer {core.token}", "Accept": "text/event-stream"})
    with urllib.request.urlopen(request) as stream:
        name = None
        for raw in stream:
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:") and name == "stt.final":
                finals.append(json.loads(line[5:]))
            if stop.is_set():
                return


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    launcher = load_launcher()
    models = pathlib.Path(argv[1]).resolve()
    session = pathlib.Path(argv[2]).resolve() if len(argv) > 2 else launcher.DEFAULT_SESSION
    if not session.is_file():
        raise SystemExit(f"not a file: {session}")
    with wave.open(str(session), "rb") as source:
        seconds = source.getnframes() / source.getframerate()
    work = pathlib.Path(tempfile.mkdtemp(prefix="interline-short-"))
    core = launcher.Core(work, models, session)
    finals, stop = [], threading.Event()
    try:
        threading.Thread(target=collect, args=(core, finals, stop), daemon=True).start()
        time.sleep(1)
        core.call("POST", "/recording/start")
        time.sleep(seconds + SETTLE_SECONDS)
        core.call("POST", "/recording/stop")
        time.sleep(3)
        stop.set()
    finally:
        core.stop()
    for final in finals:
        print(f"{final['stream']:6} {final['startMs']:>7}-{final['endMs']:<7} {final['text']!r}")
    system = [f for f in finals if f["stream"] == "system" and len(f["text"].split()) >= MIN_WORDS]
    print(f"system finals with at least {MIN_WORDS} words: {len(system)} (expected {EXPECTED_FINALS})")
    return 0 if len(system) >= EXPECTED_FINALS else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
