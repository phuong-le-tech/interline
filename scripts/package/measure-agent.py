"""Measure the real bundled agent's first hello; no handshake, model or permissions are requested."""
import json
from pathlib import Path
import selectors
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
APP = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "apps/desktop/src-tauri/target/release/bundle/macos/Interline.app"
began = time.monotonic()
agent = subprocess.Popen([str(APP / "Contents/MacOS/interline-capture"), "--protocol=1"],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
try:
    selector = selectors.DefaultSelector()
    selector.register(agent.stdout, selectors.EVENT_READ)
    assert selector.select(5), "No agent hello in 5 seconds"
    prefix = agent.stdout.read(4)
    assert len(prefix) == 4, agent.stderr.read().decode()
    size, = struct.unpack(">I", prefix)
    assert 1 < size <= 1048576, size
    frame = agent.stdout.read(size)
    assert frame[0] == 1 and json.loads(frame[1:])["t"] == "hello", frame
    print(json.dumps({"agent_hello_seconds": round(time.monotonic() - began, 4)}))
finally:
    agent.stdin.close()
    try:
        agent.wait(timeout=3)
    except subprocess.TimeoutExpired:
        agent.kill()
        agent.wait()
