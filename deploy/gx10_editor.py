#!/usr/bin/env python3
"""Pull CI-tested main revisions; build, probe, promote or roll back one editor pair."""
import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time
import urllib.request
import uuid

REPOS = {"backend": "Semi-0/datalog-research", "frontend": "Semi-0/lain-editor"}
DEPS = '{:deps {lain-editor/contracts {:local/root "../frontend/contracts"}}}'
ROOT = Path(os.environ.get("LAIN_DEPLOY_ROOT", str(Path.home() / ".local/share/lain-editor-deploy")))
BACKEND_PORT = 4785
FRONTEND_PORT = 4782
UNITS = ["gx10-lain-backend.service", "gx10-lain-frontend.service"]


def run(args, cwd=None, env=None, timeout=1800, capture=False):
    options = {}
    if capture:
        options["stdout"] = subprocess.PIPE
    return subprocess.run(args, cwd=cwd, env=env, timeout=timeout, check=True,
                          text=True, **options)


def save(path, value):
    temporary = path.with_name(path.name + ".new")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def successful_run(repo, sha, runs):
    """Only this repository's successful main push at the exact requested SHA."""
    valid = [r for r in runs if r.get("head_sha") == sha
             and r.get("head_branch") == "main" and r.get("event") == "push"
             and r.get("status") == "completed" and r.get("conclusion") == "success"
             and r.get("head_repository", {}).get("full_name") == repo]
    return max(valid, key=lambda r: (r["run_number"], r.get("run_attempt", 1)), default=None)


def approved_pair():
    pair = {}
    for role, repo in REPOS.items():
        sha = json.loads(run(["gh", "api", f"repos/{repo}/commits/main"], capture=True).stdout)["sha"]
        endpoint = f"repos/{repo}/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha={sha}&per_page=100"
        runs = json.loads(run(["gh", "api", endpoint], capture=True).stdout)["workflow_runs"]
        passed = successful_run(repo, sha, runs)
        if passed is None:
            return None
        pair[role] = {"repository": repo, "sha": sha, "ci_run": passed["html_url"]}
    return pair


def checkout(repo, sha, destination):
    run(["git", "clone", "--no-checkout", f"https://github.com/{repo}.git", str(destination)])
    run(["git", "checkout", "--detach", sha], cwd=destination)
    actual = run(["git", "rev-parse", "HEAD"], cwd=destination, capture=True).stdout.strip()
    if actual != sha:
        raise RuntimeError("Checkout revision mismatch")


def service_environment():
    env = dict(os.environ)
    env["PATH"] = str(Path.home() / ".local/bin") + os.pathsep + env.get("PATH", "")
    env.setdefault("JAVA_TOOL_OPTIONS", "-Xmx4g")
    return env


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def backend_command(port):
    return ["clojure", "-Sdeps", DEPS, "-M", "-m", "graph.lain-editor.server", str(port)]


@contextlib.contextmanager
def processes(release, backend_port, frontend_port):
    """Reap only candidate process groups, including their descendants."""
    owned = []
    env = service_environment()
    try:
        owned.append(subprocess.Popen(backend_command(backend_port), cwd=release / "backend",
                                      env=env, start_new_session=True))
        frontend_env = dict(env, LAIN_EDITOR_PORT=str(frontend_port),
                            LAIN_EDITOR_BACKEND=f"http://127.0.0.1:{backend_port}")
        owned.append(subprocess.Popen(["node", "scripts/dev.mjs"], cwd=release / "frontend",
                                      env=frontend_env, start_new_session=True))
        deadline = time.monotonic() + 180
        for port in (backend_port, frontend_port):
            while True:
                if any(p.poll() is not None for p in owned):
                    raise RuntimeError("Candidate service exited during startup")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=1):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Candidate service startup timed out")
                    time.sleep(0.25)
        yield
    finally:
        for process in reversed(owned):
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=15)


def api(url, command):
    request = urllib.request.Request(url + "/api", data=json.dumps(command).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = json.load(response)
    if not body.get("ok"):
        raise RuntimeError(f"Editor API rejected probe: {body}")
    return body["result"]


def probe(url):
    with urllib.request.urlopen(url, timeout=15) as response:
        if b'id="app"' not in response.read():
            raise RuntimeError("Editor HTML is missing its mount point")
    pane = "deployment-probe-" + uuid.uuid4().hex
    try:
        result = api(url, {"op": "run", "pane": pane, "generation": 0,
                           "source": "(define result (+ 2 3)) (display result)"})
        inspected = api(url, {"op": "inspect", "pane": pane, "generation": result["generation"],
                              "display": {"cell": "@display", "mode": "value"}})
        if inspected.get("status") != "ready" or inspected.get("value") != "5":
            raise RuntimeError("Frontend/backend computation probe did not produce 5")
    finally:
        api(url, {"op": "close", "pane": pane})


def build(release):
    env = service_environment()
    frontend = release / "frontend"
    run(["clojure", "-M:editor/cljs"], cwd=frontend, env=env)
    run(["npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=frontend, env=env)
    run(["npm", "run", "test:server"], cwd=frontend, env=env)
    run(["npx", "playwright", "install", "chromium"], cwd=frontend, env=env)
    run(["clojure", "-Sdeps", DEPS, "-M:test", "graph.lain-editor.runtime.slot-spawn-test",
         "graph.lain-editor.runtime.extension-error-test", "graph.lain-editor.workspace.source-files-test",
         "graph.lain-editor.inspection.retained-relationship-test"],
        cwd=release / "backend", env=env)
    backend_port, frontend_port = free_port(), free_port()
    while frontend_port == backend_port:
        frontend_port = free_port()
    with processes(release, backend_port, frontend_port):
        url = f"http://127.0.0.1:{frontend_port}"
        probe(url)
        run(["npm", "run", "test:browser"], cwd=frontend,
            env=dict(env, LAIN_EDITOR_URL=url), timeout=1800)


def restart():
    run(["systemctl", "--user", "restart", *UNITS])
    deadline = time.monotonic() + 180
    while True:
        try:
            probe(f"http://127.0.0.1:{FRONTEND_PORT}")
            return
        except (OSError, RuntimeError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


def point_current(root, release):
    temporary = root / "current.new"
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(release)
    temporary.replace(root / "current")


def activate(root, release, restart_services=restart):
    current = root / "current"
    previous = None
    if current.is_symlink():
        previous = current.resolve()
    point_current(root, release)
    try:
        restart_services()
    except Exception:
        if previous is not None:
            point_current(root, previous)
            restart_services()
        else:
            current.unlink(missing_ok=True)
            run(["systemctl", "--user", "stop", *UNITS])
        raise


def poll(retry=False):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / "pull.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        pair = approved_pair()
        if pair is None:
            print("Waiting for successful CI on both current main revisions", flush=True)
            return
        current = ROOT / "current/release.json"
        if current.exists() and json.loads(current.read_text())["pair"] == pair:
            print("Current release already matches tested main revisions", flush=True)
            return
        attempt = ROOT / "last-attempt.json"
        if attempt.exists() and not retry:
            prior = json.loads(attempt.read_text())
            if prior.get("pair") == pair and prior.get("status") == "failed":
                print("This pair previously failed; use poll --retry after inspection", flush=True)
                return
        release = ROOT / "releases" / uuid.uuid4().hex
        release.mkdir(parents=True)
        receipt = {"pair": pair, "release": str(release), "started_at": time.time()}
        try:
            for role, source in pair.items():
                checkout(source["repository"], source["sha"], release / role)
            build(release)
            if approved_pair() != pair:
                save(attempt, dict(receipt, status="superseded", finished_at=time.time()))
                print("Main or its CI changed during preflight; leaving the current release unchanged", flush=True)
                return
            save(release / "release.json", dict(receipt, status="verified"))
            activate(ROOT, release)
            run(["systemctl", "--user", "enable", *UNITS])
            save(attempt, dict(receipt, status="active", finished_at=time.time()))
            print(f"Activated {release}", flush=True)
        except Exception as error:
            save(attempt, dict(receipt, status="failed", error=str(error), finished_at=time.time()))
            raise


def serve(role):
    release = (ROOT / "current").resolve(strict=True)
    env = service_environment()
    if role == "backend":
        args = backend_command(BACKEND_PORT)
    else:
        args = ["node", "scripts/dev.mjs"]
        env.update(LAIN_EDITOR_PORT=str(FRONTEND_PORT),
                   LAIN_EDITOR_BACKEND=f"http://127.0.0.1:{BACKEND_PORT}")
    os.chdir(release / role)
    os.execvpe(args[0], args, env)


def install():
    if any(c.isspace() for c in str(ROOT)):
        raise ValueError("Deployment root must not contain whitespace")
    ROOT.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).resolve(), ROOT / "driver.py")
    units = Path.home() / ".config/systemd/user"
    units.mkdir(parents=True, exist_ok=True)
    common = f"Environment=LAIN_DEPLOY_ROOT={ROOT}\nEnvironment=PATH={Path.home()}/.local/bin:/usr/local/bin:/usr/bin:/bin\n"
    for role in REPOS:
        (units / f"gx10-lain-{role}.service").write_text(
            f"[Unit]\nDescription=GX10 Lain {role}\n[Service]\n{common}"
            f"ExecStart=/usr/bin/python3 {ROOT}/driver.py serve-{role}\n"
            "Restart=on-failure\nRestartSec=5\nKillMode=control-group\nTimeoutStopSec=20\nMemoryMax=8G\n"
            "[Install]\nWantedBy=default.target\n")
    (units / "gx10-lain-pull.service").write_text(
        f"[Unit]\nDescription=Pull CI-tested Lain releases\n[Service]\nType=oneshot\n{common}"
        f"ExecStart=/usr/bin/python3 {ROOT}/driver.py poll\nTimeoutStartSec=3600\nMemoryMax=12G\n")
    (units / "gx10-lain-pull.timer").write_text(
        "[Unit]\nDescription=Check GitHub for tested Lain updates\n[Timer]\n"
        "OnBootSec=45\nOnUnitInactiveSec=60\n[Install]\nWantedBy=timers.target\n")
    run(["systemctl", "--user", "daemon-reload"])
    run(["systemctl", "--user", "enable", "--now", "gx10-lain-pull.timer"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["poll", "install", "serve-backend", "serve-frontend"])
    parser.add_argument("--retry", action="store_true")
    arguments = parser.parse_args()
    if arguments.operation == "install":
        install()
    elif arguments.operation == "poll":
        poll(arguments.retry)
    else:
        serve(arguments.operation.removeprefix("serve-"))
