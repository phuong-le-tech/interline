"""Exercise the actual shell, including Apple-event quit and abrupt shell death, with isolated data."""
import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
APP = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "apps/desktop/src-tauri/target/release/bundle/macos/Interline.app"
LIBPROC = ctypes.CDLL("/usr/lib/libproc.dylib")


def identity(pid):
    # proc_bsdinfo from the macOS SDK, start time is also the precision used by JDK ProcessHandle.
    info = ctypes.create_string_buffer(136)
    path = ctypes.create_string_buffer(4096)
    if LIBPROC.proc_pidinfo(pid, 3, 0, info, len(info)) != len(info):
        return None
    if LIBPROC.proc_pidpath(pid, path, len(path)) <= 0:
        return None
    sec = ctypes.c_uint64.from_buffer(info, 120).value
    usec = ctypes.c_uint64.from_buffer(info, 128).value
    return {"pid": pid, "startTime": sec * 1000 + usec // 1000,
            "executable": str(Path(os.fsdecode(path.value)).resolve())}


def alive(saved):
    return saved is not None and identity(saved["pid"]) == saved


def wait_for(check, seconds, message):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(0.02)
    raise AssertionError(message)


def run(mode, scratch, window_probe):
    data = scratch / mode
    data.mkdir()
    log = data / "shell.log"
    env = dict(os.environ, INTERLINE_DATA_DIR=str(data), INTERLINE_LOG_DIR=str(data / "logs"))
    saved = []
    with log.open("w") as output:
        began = time.monotonic()
        wall = time.time()
        shell = subprocess.Popen([str(APP / "Contents/MacOS/interline")], env=env,
                                 stdout=output, stderr=output)
        probe = subprocess.Popen([str(window_probe), str(shell.pid), str(wall)], stdout=subprocess.PIPE, text=True)
        try:
            wait_for(lambda: '"event":"ready"' in log.read_text(), 30,
                     f"Core never reached READY: {log.read_text()}")
            ready_seconds = time.monotonic() - began
            window_seconds, _ = probe.communicate(timeout=10)
            assert probe.returncode == 0, "No visible shell window"
            registry = data / "run/pids.json"
            snapshot = wait_for(lambda: json.loads(registry.read_text()) if registry.exists() else None,
                                3, "No process registry")
            saved = [snapshot[key] for key in ("core", "agent") if snapshot.get(key)]
            assert len(saved) == 2, snapshot
            assert all(alive(p) for p in saved), snapshot
            # Capture a steady idle sample; model loading and user permissions are never requested.
            time.sleep(10)
            rss = {name: int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(pid)], text=True).strip())
                   for name, pid in [("shell", shell.pid), ("core", snapshot["core"]["pid"]),
                                     ("agent", snapshot["agent"]["pid"])]}
            started = time.monotonic()
            if mode == "quit":
                subprocess.run(["osascript", "-e", f'tell application "{APP}" to quit'], check=True, timeout=5)
            else:
                shell.kill()
            budget = 8 if mode == "quit" else 6
            shell.wait(timeout=budget)
            wait_for(lambda: not any(alive(p) for p in saved), budget - min(budget, time.monotonic() - started),
                     f"Leftover core or agent after {mode}: {saved}")
            print(json.dumps({"mode": mode, "ready_seconds": round(ready_seconds, 3),
                              "window_visible_seconds": float(window_seconds),
                              "quit_seconds": round(time.monotonic() - started, 3), "rss_kib": rss}), flush=True)
        finally:
            if probe.poll() is None:
                probe.kill()
            probe.wait()
            if shell.poll() is None:
                shell.kill()
            shell.wait()
            # Only terminate identities observed as children of this test's own shell.
            for process in saved:
                if alive(process):
                    os.kill(process["pid"], signal.SIGKILL)


with tempfile.TemporaryDirectory(prefix="interline-package-smoke-") as directory:
    scratch = Path(directory)
    window_probe = scratch / "window-visible"
    subprocess.run(["swiftc", str(ROOT / "scripts/package/window-visible.swift"), "-o", str(window_probe)], check=True)
    for case in ("quit", "sigkill"):
        run(case, scratch, window_probe)
print("Packaged shell quit and SIGKILL: PASS")
