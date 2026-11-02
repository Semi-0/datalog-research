"""Scoped CLI and non-owning editor client: scoped HTTP only, explicit mutations, no automatic mutation replay."""
import argparse
import difflib
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import threading
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid

READ_OPS = {"sandbox-status", "list-sessions", "inspect", "sandbox-export", "sandbox-receipt"}
MAX_BODY = 2 * 1024 * 1024
MAX_RESPONSE = 16 * 1024 * 1024
ASSETS = {"/": ("index.html", "text/html"), "/style.css": ("style.css", "text/css"),
          "/compiled/app.js": ("compiled/app.js", "text/javascript"),
          "/compiled/app.js.map": ("compiled/app.js.map", "application/json")}


def save(path, value):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


class Client:
    def __init__(self, configuration, local=False):
        self.path = Path(configuration)
        if self.path.is_symlink() or self.path.stat().st_mode & 0o077:
            raise ValueError("Scoped configuration must be a private regular file (chmod 600)")
        self.config = json.loads(self.path.read_text())
        base = urlsplit(self.config["baseUrl"])
        if (base.scheme != "http" or base.hostname != "127.0.0.1" or base.port != (48870 if local else 19580)
                or base.path or base.query or base.fragment or base.username or base.password):
            raise ValueError("This client only uses the fixed loopback SSH forwarding endpoint")
        if re.fullmatch(r"[0-9a-f]{32}", self.config["sandboxId"]) is None:
            raise ValueError("Invalid scoped sandbox ID")
        self.url = self.config["baseUrl"] + "/v1/" + self.config["sandboxId"] + "/api"
        self.pending_path = self.path.with_suffix(".pending.json")
        self.lock = threading.RLock()

    def pending(self):
        return json.loads(self.pending_path.read_text()) if self.pending_path.exists() else {}

    def request(self, command):
        request = Request(self.url, data=json.dumps(command).encode(), method="POST",
                          headers={"Authorization": "Bearer " + self.config["token"], "Content-Type": "application/json"})
        try:
            response = urlopen(request, timeout=180 if command.get("op") == "sandbox-create" else 55)
        except HTTPError as error:
            response = error
        with response:
            body = response.read(MAX_RESPONSE + 1)
            if len(body) > MAX_RESPONSE:
                raise RuntimeError("Response too large")
            return json.loads(body)

    def reconcile(self, request_id):
        response = self.request({"op": "sandbox-receipt", "requestId": request_id})
        if response.get("result", {}).get("status") in {"committed", "failed"}:
            with self.lock:
                pending = self.pending()
                pending.pop(request_id, None)
                save(self.pending_path, pending)
        return response

    def command(self, command):
        command = dict(command)
        if command.get("op") in READ_OPS:
            if command["op"] == "sandbox-receipt":
                return self.reconcile(command["requestId"])
            return self.request(command)
        with self.lock:
            if self.pending() and command.get("op") != "sandbox-stop":
                raise RuntimeError("Unresolved mutation receipt; inspect pending request IDs before proceeding")
            request_id = str(uuid.uuid4())
            command["requestId"] = request_id
            if command.get("op") not in {"sandbox-create", "sandbox-stop"} and command.get("generation") is None:
                status = self.request({"op": "sandbox-status"})
                command["generation"] = status["result"]["generation"]
            pending = self.pending()
            pending[request_id] = {"op": command.get("op"), "sha256": hashlib.sha256(json.dumps(command, sort_keys=True).encode()).hexdigest()}
            save(self.pending_path, pending)  # Before sending, even if the connection drops.
            try:
                response = self.request(command)
                receipt = self.reconcile(request_id)
                response["requestId"] = request_id
                response["receiptStatus"] = receipt.get("result", {}).get("status", "unknown")
                return response
            except Exception as error:
                raise RuntimeError("Outcome uncertain; do NOT retry. Query receipt " + request_id) from error


class Viewer:
    """Read/lease-only browser surface; the CLI remains the explicit program owner."""
    def __init__(self, client, pane):
        if re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", pane) is None:
            raise ValueError("Invalid view pane")
        self.client, self.pane = client, pane
        self.lock = threading.RLock()

    def status(self):
        response = self.client.command({"op": "sandbox-status"})
        if not response.get("ok"):
            raise RuntimeError("Cannot observe sandbox owner")
        return response["result"]

    def command(self, command):
        with self.lock:
            if not isinstance(command, dict):
                raise ValueError("Expected command object")
            op = command.get("op")
            if op == "workspace-poll":
                return {"ok": True, "result": {"command": None, "nonOwningViewer": True}}
            if op == "restore":
                # Browser refresh carries a document. Never forward its program to the owner.
                workspace = command.get("workspace") or {}
                panes = workspace.get("panes", []) if isinstance(workspace, dict) else []
                if not isinstance(panes, list) or len(panes) > 64:
                    raise ValueError("Invalid workspace view")
                before = self.status()
                if self.client.pending():
                    raise RuntimeError("Reconcile pending receipts before reconnecting")
                if self.pane not in before.get("views", []):
                    lease = self.client.command({"op": "reopen-session", "pane": self.pane,
                                                 "sessionId": before["sessionId"],
                                                 "generation": before["generation"]})
                    if not lease.get("ok") or lease.get("receiptStatus") != "committed":
                        raise RuntimeError("View lease did not commit")
                after = self.status()
                if any(before[k] != after[k] for k in ("sessionId", "identity", "generation")):
                    raise RuntimeError("Owner changed while reconnecting; refresh again")
                generations = {p["id"]: after["generation"] for p in panes
                               if isinstance(p, dict) and isinstance(p.get("id"), str)
                               and re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", p["id"])
                               and isinstance(p.get("source"), str) and p["source"].strip()}
                return {"ok": True, "result": dict(after, generations=generations, displayModes={})}
            if set(command) & {"source", "workspace", "content", "program"}:
                raise ValueError("Viewer cannot forward program material")
            if op not in READ_OPS | {"reopen-session", "detach-session"}:
                raise ValueError("Viewer only supports reads and view leases")
            forwarded = {k: v for k, v in command.items() if k != "requestId"}
            if op in {"reopen-session", "detach-session"}:
                # The browser cannot detach another observer or the owner's pane.
                forwarded["pane"] = self.pane
                before = self.status()
                forwarded.setdefault("generation", before["generation"])
                if op == "reopen-session":
                    forwarded.setdefault("sessionId", before["sessionId"])
            response = self.client.command(forwarded)
            result = response.get("result")
            if isinstance(result, dict) and isinstance(result.get("extensions"), list):
                response = dict(response, result=dict(result, extensions=[
                    item["source"] if isinstance(item, dict) else item for item in result["extensions"]]))
            return response


def export(client, destination):
    response = client.command({"op": "sandbox-export"})
    if not response.get("ok"):
        raise RuntimeError(str(response))
    root = Path(destination)
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    save(root / "export.json", response)
    data = response["result"]
    if "archive" in data:
        data = data["archive"]
        if "result" in data:
            data = data["result"]
    sources = dict(data.get("files", {}))
    for name, content in data.get("declarations", {}).items():
        base = Path(name).name
        if base.startswith("turn_"):
            sources[base] = content
    patches = []
    (root / "sources").mkdir(mode=0o700)
    for name, content in sources.items():
        if not isinstance(name, str) or re.fullmatch(r"[a-z][a-z0-9_]{0,80}\.(clj|lain)", name) is None or not isinstance(content, str):
            raise RuntimeError("Unsafe exported source entry")
        (root / "sources" / name).write_text(content)
        patches.extend(difflib.unified_diff([], content.splitlines(keepends=True), fromfile="/dev/null", tofile="b/" + name))
    (root / "sources.patch").write_text("".join(patches))
    print(json.dumps({"export": str(root), "sourceFiles": len(sources)}))


def editor_proxy(client, port, assets, pane="sandbox-view"):
    viewer = Viewer(client, pane)
    host = "sandbox-" + client.config["sandboxId"] + ".localhost:" + str(port)
    origin = "http://" + host
    assets = Path(assets).resolve(strict=True)

    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, mime, body):
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            self.end_headers()
            try:
                self.wfile.write(body)
            except OSError:
                pass

        def do_GET(self):
            if self.headers.get("Host") != host or self.path not in ASSETS:
                self.reply(404, "text/plain", b"Not found")
                return
            name, mime = ASSETS[self.path]
            self.reply(200, mime, (assets / name).read_bytes())

        def do_POST(self):
            try:
                if self.headers.get("Host") != host or self.headers.get("Origin") != origin or self.path != "/api":
                    raise ValueError("Cross-origin request rejected")
                length = int(self.headers.get("Content-Length", "-1"))
                if not 0 <= length <= MAX_BODY or self.headers.get("Transfer-Encoding"):
                    raise ValueError("Invalid bounded request")
                self.connection.settimeout(15)
                command = json.loads(self.rfile.read(length))
                response = viewer.command(command)
                self.reply(200, "application/json", json.dumps(response).encode())
            except Exception as error:
                self.reply(400, "application/json", json.dumps({"ok": False, "error": str(error)}).encode())

    print(origin, flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Proxy).serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--local", action="store_true", help="Use the on-device loopback broker at port 48870")
    sub = parser.add_subparsers(dest="operation", required=True)
    sub.add_parser("create")
    sub.add_parser("status")
    sub.add_parser("stop")
    sub.add_parser("pending")
    command = sub.add_parser("command")
    command.add_argument("json_file", help="Explicit HTTP/JSON editor command")
    upload = sub.add_parser("upload")
    upload.add_argument("file")
    receipt = sub.add_parser("receipt")
    receipt.add_argument("request_id")
    extraction = sub.add_parser("export")
    extraction.add_argument("destination")
    browser = sub.add_parser("editor")
    browser.add_argument("--port", type=int, default=19581)
    browser.add_argument("--pane", default="sandbox-view")
    browser.add_argument("--assets", default=str(Path(__file__).resolve().parents[2] / "target/lain-editor/public/lain-editor"))
    args = parser.parse_args()
    client = Client(args.config, local=args.local)
    if args.operation == "pending":
        print(json.dumps(client.pending(), indent=2))
        return
    if args.operation == "editor":
        editor_proxy(client, args.port, args.assets, args.pane)
        return
    if args.operation == "export":
        export(client, args.destination)
        return
    if args.operation == "command":
        command = json.loads(Path(args.json_file).read_text())
    elif args.operation == "upload":
        file = Path(args.file)
        command = {"op": "sandbox-upload", "name": file.name, "content": file.read_text()}
    elif args.operation == "receipt":
        command = {"op": "sandbox-receipt", "requestId": args.request_id}
    else:
        command = {"op": "sandbox-" + args.operation}
    response = client.command(command)
    print(json.dumps(response, indent=2))
    if not response.get("ok") or response.get("result", {}).get("postCommitError") or response.get("receiptStatus") in {"uncertain", "started", "unknown"}:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
