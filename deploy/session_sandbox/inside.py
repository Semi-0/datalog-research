"""Inside Bubblewrap: HTTP over anonymous pipes to a private-network JVM.

No host-network socket or host socket directory enters this namespace.
"""
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading

ROOT = Path("/opt/runtime")
LOG_LIMIT = 65536
MAX_BODY = 2 * 1024 * 1024
MAX_RESPONSE = 16 * 1024 * 1024


def java_command():
    manifest = json.loads((ROOT / "manifest.json").read_text())
    entries = manifest["classpath"]
    if not entries or any(item != "source" and re.fullmatch(r"deps/[0-9a-f]{64}\.jar", item) is None
                          for item in entries):
        raise RuntimeError("Invalid trusted classpath manifest")
    return ["/opt/java/bin/java", "-Xmx768m", "-XX:MaxDirectMemorySize=128m",
            "-XX:MaxMetaspaceSize=256m", "-XX:ActiveProcessorCount=2",
            "-Duser.home=/workspace/home", "-Djava.io.tmpdir=/tmp", "-cp",
            ":".join(str(ROOT / item) for item in entries) + ":/workspace",
            "clojure.main", "-m", "deploy.session-sandbox.runtime", "4780"]


def copy_logs(pipe, output):
    written = 0
    while True:
        chunk = pipe.read(4096)
        if not chunk:
            return
        try:
            if written + len(chunk) > LOG_LIMIT:
                output.seek(0)
                output.truncate()
                written = 0
            output.write(chunk)
            output.flush()
            written += len(chunk)
        except OSError:
            pass  # Workspace exhaustion never redirects output into host logs.


class PipeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        self.rfile = sys.stdin.buffer
        self.wfile = sys.stdout.buffer
        self.connection = None

    def finish(self):
        self.wfile.flush()  # Keep both IPC pipes open for the next HTTP request.

    def log_message(self, *_):
        pass

    def do_POST(self):
        connection = None
        try:
            length = int(self.headers.get("Content-Length", "-1"))
            if self.path != "/api" or not 0 <= length <= MAX_BODY or self.headers.get("Transfer-Encoding"):
                raise ValueError("Invalid bounded HTTP request")
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError("Truncated HTTP request")
            connection = HTTPConnection("127.0.0.1", 4780, timeout=40)
            connection.request("POST", "/api", body,
                               {"Authorization": self.headers.get("Authorization", ""),
                                "Content-Type": "application/json", "Connection": "close"})
            response = connection.getresponse()
            body = response.read(MAX_RESPONSE + 1)
            if len(body) > MAX_RESPONSE:
                raise ValueError("Response too large")
            status = response.status
        except Exception as error:
            status, body = 503, json.dumps({"ok": False, "error": str(error)[:2000]}).encode()
        finally:
            if connection is not None:
                connection.close()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True


def main():
    # Only anonymous stdin/stdout IPC pipes survive. Credential data descriptors
    # are consumed by Bubblewrap and all remaining inherited FDs are closed here.
    os.closerange(3, 4096)
    Path("/workspace/home").mkdir(exist_ok=True)
    descriptor = os.open("/workspace/runtime.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb", buffering=0) as log:
        process = subprocess.Popen(java_command(), stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   close_fds=True, cwd="/workspace")
        threading.Thread(target=copy_logs, args=(process.stdout, log), daemon=True).start()
        while process.poll() is None and sys.stdin.buffer.peek(1):
            PipeHandler(None, ("pipe", 0), None)
        if process.poll() is None:
            process.terminate()
        try:
            code = process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            code = process.wait()
        if code:
            try:
                sys.stderr.write(Path("/workspace/runtime.log").read_text(errors="replace")[-LOG_LIMIT:])
            except OSError:
                pass
        raise SystemExit(code)


if __name__ == "__main__":
    main()
