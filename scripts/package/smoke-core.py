"""Prove bundled runtime/CDS/JDBC and a complete MockAgent meeting without accessing user data."""
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
APP = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "apps/desktop/src-tauri/target/release/bundle/macos/Interline.app"
CORE = APP / "Contents/Resources/core"

with tempfile.TemporaryDirectory(prefix="interline-packaged-core-") as directory:
    data = Path(directory)
    token = secrets.token_urlsafe(32)
    command = [str(CORE / "runtime/bin/java"), "-Xshare:on", "-XX:SharedArchiveFile=interline-core.jsa",
               "-Xss512k", "-XX:+UseSerialGC", "-Xmx256m", "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true",
               f"-Dorg.sqlite.lib.path={CORE / 'native'}", "-Xlog:cds=info:file=" + str(data / "cds.log"),
               "-Xlog:class+load=info:file=" + str(data / "classes.log"),
               "-jar", "interline-core.jar", f"--data-dir={data}", f"--log-dir={data / 'logs'}",
               f"--agent={ROOT / 'services/core/mock-agent/bin/mock-agent'}", "--agent-arg=--speed=20", "--parent=stdio"]
    with (data / "stderr.log").open("w") as errors:
        began = time.monotonic()
        process = subprocess.Popen(command, cwd=CORE, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=errors, text=True)
        try:
            process.stdin.write(json.dumps({"token": token}) + "\n")
            process.stdin.flush()
            import selectors
            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ)
            assert selector.select(30), (data / "stderr.log").read_text()
            line = process.stdout.readline()
            assert line, (data / "stderr.log").read_text()
            ready = json.loads(line)
            assert ready["event"] == "ready", ready
            print(f"Bundled core READY with enforced CDS: {time.monotonic() - began:.3f} s", flush=True)
            classes = (data / "classes.log").read_text()
            shared = [line for line in classes.splitlines() if "org.springframework." in line and "shared objects file (top)" in line]
            assert shared, "Spring classes were not loaded from the trained CDS archive"
            print(f"Spring classes loaded from trained CDS archive: {len(shared)}", flush=True)

            def request(method, path):
                req = urllib.request.Request(f"http://127.0.0.1:{ready['port']}/api/v1{path}",
                                             method=method, data=b"{}" if method == "POST" else None,
                                             headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=20) as response:
                    body = response.read()
                    return json.loads(body) if body else None

            meeting = request("POST", "/recording/start")
            assert meeting["meetingId"], meeting
            time.sleep(1)
            request("POST", "/recording/stop")
            print("Bundled JDBC and MockAgent meeting start/stop: PASS", flush=True)
            print((data / "cds.log").read_text(), flush=True)
            process.stdin.write('{"cmd":"shutdown"}\n')
            process.stdin.flush()
            assert process.wait(timeout=6) == 0, (data / "stderr.log").read_text()
        finally:
            process.stdin.close()
            if process.poll() is None:
                try:
                    process.wait(timeout=6)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
