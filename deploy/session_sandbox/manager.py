"""Trusted, fixed-policy launcher and scoped HTTP broker. Never import workspace code."""
import argparse
import hashlib
import hmac
from http.client import HTTPConnection, HTTPResponse
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import select
import subprocess
import sys
import threading
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import policy
import network

LOCK = threading.RLock()
READ_OPS = {"sandbox-status", "list-sessions", "inspect", "sandbox-export", "sandbox-receipt"}
MUTATE_OPS = {"sandbox-create", "sandbox-stop", "sandbox-upload", "sandbox-install", "sandbox-input",
              "sandbox-rerun", "run", "extend", "reopen-session", "detach-session", "close", "restore", "layout"}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def record(identity):
    return json.loads((policy.instance_dir(identity) / "record.json").read_text())


def update(identity, **fields):
    with LOCK:
        value = record(identity)
        value.update(fields)
        save(policy.instance_dir(identity) / "record.json", value)
        return value


def systemctl(*arguments, check=True):
    return subprocess.run(["systemctl", "--user", *arguments], check=check,
                          capture_output=True, text=True, timeout=15)


def cgroup(identity):
    value = systemctl("show", policy.unit_name(identity), "--property=ControlGroup", "--value", check=False).stdout.strip()
    if value and (not value.startswith("/user.slice/") or not value.endswith("/" + policy.unit_name(identity)) or ".." in value.split("/")):
        raise RuntimeError("Unexpected owned cgroup")
    return value


def stop(identity):
    group = cgroup(identity)
    result = systemctl("stop", policy.unit_name(identity), check=False)
    if group:
        directory = Path("/sys/fs/cgroup") / group.lstrip("/")
        deadline = time.monotonic() + 5
        while directory.exists() and time.monotonic() < deadline:
            events = (directory / "cgroup.events").read_text()
            if "populated 0" in events:
                break
            time.sleep(0.1)
        if directory.exists() and "populated 0" not in (directory / "cgroup.events").read_text():
            raise RuntimeError("Owned cgroup still contains processes; cleanup NOT confirmed")
    if systemctl("is-active", policy.unit_name(identity), check=False).stdout.strip() == "active":
        raise RuntimeError("Owned unit is still active")
    proof = {"unit": policy.unit_name(identity), "cgroup": group, "empty": True,
             "stopExit": result.returncode, "checkedAt": time.time()}
    update(identity, state="stopped", stopProof=proof)
    return proof


def forward(identity, command, timeout=45):
    endpoint = json.loads((policy.instance_dir(identity) / "endpoint.json").read_text())
    pid = systemctl("show", policy.unit_name(identity), "--property=MainPID", "--value", check=False).stdout.strip()
    if not pid.isdigit() or int(pid) <= 0 or int(pid) != endpoint.get("pid"):
        raise RuntimeError("Owned worker is unavailable; never forward to a stale or reused port")
    port = endpoint["port"]
    if not isinstance(port, int) or not 1024 <= port <= 65535:
        raise RuntimeError("Invalid trusted endpoint")
    connection = HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request("POST", "/api", json.dumps(command),
                           {"Authorization": "Bearer " + record(identity)["innerToken"],
                            "Content-Type": "application/json", "Connection": "close"})
        response = connection.getresponse()
        body = response.read(policy.MAX_RESPONSE + 1)
        if len(body) > policy.MAX_RESPONSE:
            raise RuntimeError("Sandbox response exceeds broker limit")
        value = json.loads(body)
        return response.status, value
    finally:
        connection.close()


def start(identity):
    policy.require_platform()
    with LOCK:
        value = record(identity)
        if value["state"] != "reserved":
            raise ValueError("Sandbox has already been created; never replay create")
        active = [json.loads(path.read_text()) for path in (policy.ROOT / "instances").glob("*/record.json")]
        if sum(item["state"] not in {"reserved", "stopped"} for item in active) >= policy.MAX_ACTIVE:
            raise RuntimeError("Sandbox capacity reached")
        available = next(int(line.split()[1]) * 1024 for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemAvailable:"))
        if available < policy.MEMORY_BYTES + 8 * 1024**3:
            raise RuntimeError("Insufficient safe memory headroom; existing workloads are preserved")
        update(identity, state="starting")
        release = policy.ROOT / "releases" / value["release"]
        command = ["systemd-run", "--user", "--quiet", "--collect", "--unit=" + policy.unit_name(identity)]
        for setting in policy.unit_properties():
            command.extend(["--property", setting])
        command.extend(["/usr/bin/python3", "-I", str(release / "source/deploy/session_sandbox/manager.py"), "worker", identity])
        try:
            subprocess.run(command, check=True, capture_output=True, timeout=20)
        except Exception:
            stop(identity)  # Creation may have succeeded even if its CLI timed out.
            raise
    try:
        deadline = time.monotonic() + 150
        last = "not ready"
        while time.monotonic() < deadline:
            group = cgroup(identity)
            if group:
                enforcement = policy.verify_cgroup(group)
                try:
                    status, response = forward(identity, {"op": "sandbox-status"}, timeout=3)
                    if status == 200 and response.get("ok"):
                        update(identity, state="running", enforcement=enforcement)
                        return {"ok": True, "result": {**response["result"], "sandboxId": identity, "enforcement": enforcement, "network": record(identity).get("network")}}
                    last = repr(response)[:1000]
                except (OSError, ValueError, TimeoutError, RuntimeError) as error:
                    last = str(error)
            worker_error = policy.instance_dir(identity) / "startup-error.txt"
            if worker_error.exists():
                raise RuntimeError(worker_error.read_text()[:4000])
            time.sleep(0.5)
        raise RuntimeError("Sandbox readiness deadline exceeded: " + last)
    except Exception:
        stop(identity)
        raise


class DeadlinePipe(io.RawIOBase):
    """Read HTTP from an anonymous pipe with a fixed total deadline."""
    def __init__(self, descriptor, deadline):
        super().__init__()
        self.descriptor = descriptor
        self.deadline = deadline

    def readable(self):
        return True

    def readinto(self, buffer):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0 or not select.select([self.descriptor], [], [], remaining)[0]:
            raise TimeoutError("Sandbox HTTP pipe deadline exceeded")
        chunk = os.read(self.descriptor, len(buffer))
        buffer[:len(chunk)] = chunk
        return len(chunk)


class PipeSocket:
    def __init__(self, descriptor, deadline):
        self.descriptor = descriptor
        self.deadline = deadline

    def makefile(self, *_):
        return io.BufferedReader(DeadlinePipe(self.descriptor, self.deadline))


def write_pipe(descriptor, body, deadline):
    offset = 0
    while offset < len(body):
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([], [descriptor], [], remaining)[1]:
            raise TimeoutError("Sandbox HTTP pipe write deadline exceeded")
        try:
            written = os.write(descriptor, body[offset:offset + 65536])
        except BlockingIOError:
            continue
        if written == 0:
            raise BrokenPipeError("Sandbox HTTP pipe closed")
        offset += written


def pipe_exchange(process, token, body):
    deadline = time.monotonic() + 40
    headers = ("POST /api HTTP/1.1\r\nHost: sandbox\r\nAuthorization: Bearer " + token
               + "\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
               + str(len(body)) + "\r\n\r\n").encode("ascii")
    write_pipe(process.stdin.fileno(), headers + body, deadline)
    response = HTTPResponse(PipeSocket(process.stdout.fileno(), deadline))
    try:
        response.begin()
        lengths = response.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or response.headers.get("Transfer-Encoding"):
            raise ValueError("Invalid sandbox HTTP response framing")
        length = int(lengths[0])
        if not 0 <= length <= policy.MAX_RESPONSE:
            raise ValueError("Sandbox HTTP response too large")
        result = response.read(length)
        if len(result) != length:
            raise ValueError("Truncated sandbox HTTP response")
        return response.status, result
    finally:
        response.close()


def worker_handler(process, token):
    exchange_lock = threading.Lock()
    broken = threading.Event()

    class WorkerHandler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            acquired = False
            try:
                self.connection.settimeout(15)
                authorization = self.headers.get_all("Authorization", [])
                if self.path != "/api" or len(authorization) != 1 or not hmac.compare_digest(authorization[0], "Bearer " + token):
                    raise PermissionError("Unauthorized")
                lengths = self.headers.get_all("Content-Length", [])
                if len(lengths) != 1 or self.headers.get("Transfer-Encoding"):
                    raise ValueError("Invalid request framing")
                length = int(lengths[0])
                if not 0 <= length <= policy.MAX_BODY:
                    raise ValueError("Invalid bounded request")
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError("Truncated request")
                acquired = exchange_lock.acquire(timeout=40)
                if not acquired or broken.is_set() or process.poll() is not None:
                    raise RuntimeError("Sandbox transport unavailable; reconcile receipts before another mutation")
                try:
                    status, result = pipe_exchange(process, token, body)
                except Exception:
                    broken.set()  # A partial HTTP exchange cannot be safely replayed.
                    raise
            except PermissionError:
                status, result = 403, b'{"ok":false,"error":"Unauthorized"}'
            except Exception as error:
                status, result = 503, json.dumps({"ok": False, "error": str(error)[:2000]}).encode()
            finally:
                if acquired:
                    exchange_lock.release()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(result)))
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                self.wfile.write(result)
            except OSError:
                pass

    return WorkerHandler


def run_worker_transport(identity, token, process):
    os.set_blocking(process.stdin.fileno(), False)
    server = ThreadingHTTPServer(("127.0.0.1", 0), worker_handler(process, token))
    server.daemon_threads = True
    save(policy.instance_dir(identity) / "endpoint.json", {"port": server.server_address[1], "pid": os.getpid()})
    threading.Thread(target=server.serve_forever, daemon=True).start()
    error = bytearray()
    try:
        while True:
            chunk = process.stderr.read(4096)
            if not chunk:
                break
            if len(error) < 65536:
                error.extend(chunk[:65536 - len(error)])
        code = process.wait()
        if code:
            (policy.instance_dir(identity) / "startup-error.txt").write_bytes(bytes(error))
    finally:
        server.shutdown()
        server.server_close()
        process.stdin.close()
        process.stdout.close()
        process.stderr.close()
        if process.poll() is None:
            process.kill()
            process.wait()
    raise SystemExit(code)


def worker(identity):
    value = record(identity)
    credential = os.memfd_create("own-sandbox-key", 0)
    if credential != 3:
        raise RuntimeError("Unexpected inherited descriptors")
    os.write(credential, json.dumps({"sandboxId": identity, "token": value["innerToken"]}).encode())
    os.lseek(credential, 0, os.SEEK_SET)
    try:
        release = policy.ROOT / "releases" / value["release"]
        with network.launch(release, credential, identity) as (process, declaration):
            os.close(credential)
            credential = -1
            update(identity, network=declaration)
            run_worker_transport(identity, value["innerToken"], process)
    except Exception as error:
        (policy.instance_dir(identity) / "startup-error.txt").write_text(str(error)[:16000])
        raise
    finally:
        if credential >= 0:
            os.close(credential)


def receipt_summary(response):
    result = response.get("result")
    summary = {"ok": response.get("ok")}
    if isinstance(result, dict):
        summary["result"] = {key: result[key] for key in ("sessionId", "identity", "generation", "name", "postCommitError", "empty", "unit") if key in result}
    if "error" in response:
        summary["error"] = str(response["error"])[:3000]
    return summary


def journal(identity):
    path = policy.instance_dir(identity) / "receipts.json"
    return json.loads(path.read_text()) if path.exists() else {}


def write_receipt(identity, request_id, value):
    with LOCK:
        entries = journal(identity)
        entries[request_id] = value
        save(policy.instance_dir(identity) / "receipts.json", entries)


def reconcile(identity, request_id):
    item = journal(identity).get(request_id, {"status": "unknown"})
    if item["status"] in {"started", "uncertain"} and item.get("op") not in {"sandbox-create", "sandbox-stop"}:
        try:
            status, reply = forward(identity, {"op": "sandbox-receipt", "requestId": request_id}, timeout=3)
            terminal = reply.get("result", {})
            if status == 200 and terminal.get("status") in {"committed", "failed"}:
                item = {"op": item["op"], "status": terminal["status"], "response": receipt_summary({"ok": terminal["status"] == "committed", "result": terminal.get("result"), "error": terminal.get("error")})}
                write_receipt(identity, request_id, item)
        except (OSError, ValueError, TimeoutError):
            pass
    return {"ok": True, "result": item}


def stop_and_export(identity):
    value = record(identity)
    if value["state"] == "stopped":
        return value["stopProof"]
    try:
        _, archive = forward(identity, {"op": "sandbox-export"}, timeout=5)
        save(policy.instance_dir(identity) / "export.json", archive)
    except Exception as error:
        save(policy.instance_dir(identity) / "export.json", {"incomplete": True, "reason": str(error)[:2000]})
    return stop(identity)


def dispatch(identity, command):
    op = command.get("op")
    if op == "sandbox-receipt":
        return 200, reconcile(identity, command.get("requestId"))
    if op in READ_OPS:
        if record(identity)["state"] == "stopped":
            if op == "sandbox-export":
                path = policy.instance_dir(identity) / "export.json"
                archive = json.loads(path.read_text()) if path.exists() else {"incomplete": True}
                return 200, {"ok": True, "result": {"archive": archive, "receipts": journal(identity), "stopProof": record(identity).get("stopProof")}}
            return 200, {"ok": True, "result": {"state": "stopped", "stopProof": record(identity).get("stopProof")}}
        status, response = forward(identity, command)
        if response.get("ok") and isinstance(response.get("result"), dict):
            if op in {"sandbox-status", "sandbox-export"}:
                response["result"]["network"] = record(identity).get("network")
            if op == "sandbox-export":
                response["result"]["transportReceipts"] = journal(identity)
        return status, response
    if op not in MUTATE_OPS:
        raise ValueError("Unsupported operation")
    request_id = command.get("requestId")
    if not isinstance(request_id, str) or str(uuid.UUID(request_id)) != request_id:
        raise ValueError("Mutation requires a canonical UUID requestId")
    with LOCK:
        entries = journal(identity)
        if request_id in entries:
            raise ValueError("Duplicate mutation rejected; query its receipt")
        if len(entries) >= policy.MAX_RECEIPTS:
            stop_reserve = (op == "sandbox-stop" and len(entries) == policy.MAX_RECEIPTS
                            and record(identity)["state"] != "stopped")
            if not stop_reserve:
                raise ValueError("Receipt capacity reached; export or use trusted cleanup")
        if op != "sandbox-stop" and any(item["status"] in {"started", "uncertain"} for item in entries.values()):
            raise ValueError("Previous mutation needs receipt reconciliation; no automatic replay")
        write_receipt(identity, request_id, {"status": "started", "op": op, "sha256": hashlib.sha256(json.dumps(command, sort_keys=True).encode()).hexdigest(), "time": time.time()})
    try:
        if op == "sandbox-create":
            response = start(identity)
            status = 200
        elif op == "sandbox-stop":
            response = {"ok": True, "result": stop_and_export(identity)}
            status = 200
        else:
            if record(identity)["state"] != "running":
                raise ValueError("Sandbox is not running")
            status, response = forward(identity, command)
        write_receipt(identity, request_id, {"status": "committed" if response.get("ok") else "failed", "op": op, "response": receipt_summary(response)})
        return status, response
    except Exception as error:
        write_receipt(identity, request_id, {"status": "uncertain", "op": op, "error": str(error)[:2000]})
        raise


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never record credentials, arbitrary input or unbounded native output.

    def do_POST(self):
        try:
            parts = self.path.split("/")
            if len(parts) != 4 or parts[1] != "v1" or parts[3] != "api":
                raise PermissionError("Unauthorized")
            identity = policy.sandbox_id(parts[2])
            value = record(identity)
            supplied = self.headers.get_all("Authorization", [])
            candidate = ""
            if len(supplied) == 1 and supplied[0].startswith("Bearer "):
                candidate = supplied[0][len("Bearer "):]
            if not hmac.compare_digest(hashlib.sha256(candidate.encode()).hexdigest(), value["tokenHash"]):
                raise PermissionError("Unauthorized")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1:
                raise ValueError("Expected one Content-Length")
            length = int(lengths[0])
            if not 0 <= length <= policy.MAX_BODY or self.headers.get("Transfer-Encoding"):
                raise ValueError("Invalid bounded request")
            self.connection.settimeout(15)
            command = json.loads(self.rfile.read(length))
            if not isinstance(command, dict):
                raise ValueError("Expected command object")
            status, response = dispatch(identity, command)
        except (FileNotFoundError, PermissionError):
            status, response = 403, {"ok": False, "error": "Unauthorized"}
        except Exception as error:
            status, response = 400, {"ok": False, "error": str(error)[:4000]}
        body = json.dumps(response).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass  # The receipt remains authoritative after a disconnected client.


def issue(release):
    if not policy.DIGEST.fullmatch(release) or not (policy.ROOT / "releases" / release / "manifest.json").is_file():
        raise ValueError("Unknown trusted release")
    identity, token = uuid.uuid4().hex, secrets.token_hex(32)
    value = {"identity": identity, "release": release, "state": "reserved", "tokenHash": hashlib.sha256(token.encode()).hexdigest(), "innerToken": secrets.token_hex(32)}
    save(policy.instance_dir(identity) / "record.json", value)
    print(json.dumps({"sandboxId": identity, "token": token, "baseUrl": "http://127.0.0.1:19580"}))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["serve", "worker", "issue"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    if args.operation == "issue":
        issue(args.value)
    elif args.operation == "worker":
        worker(policy.sandbox_id(args.value))
    else:
        policy.require_platform()
        ThreadingHTTPServer(("127.0.0.1", policy.BROKER_PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
