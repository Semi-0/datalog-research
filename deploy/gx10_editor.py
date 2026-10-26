#!/usr/bin/env python3
"""Run CI on GX10, report commit statuses, and deploy a verified editor pair."""
import argparse
import contextlib
import fcntl
import json
import os
import re
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
RELEASE_RETENTION = int(os.environ.get("LAIN_RELEASE_RETENTION", "3"))
RELEASE_NAME = re.compile(r"[0-9a-f]{32}")


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


def source_pair():
    """Only configured repositories' main heads are eligible for local CI."""
    pair = {}
    for role, repo in REPOS.items():
        sha = json.loads(run(["gh", "api", f"repos/{repo}/commits/main"], capture=True).stdout)["sha"]
        if not isinstance(sha, str) or re.fullmatch(r"[0-9a-f]{40}", sha) is None:
            raise ValueError("GitHub returned an invalid source revision")
        pair[role] = {"repository": repo, "sha": sha}
    return pair


def report_status(pair, context, state, description):
    """Report a commit status without letting the report gate local CI or a deploy.

    GitHub rejects a status when the revision already holds the maximum number of
    statuses, which happens routinely to a repository that has not advanced while
    the other one keeps moving. The deploy decision belongs to CI and the
    integration probe, never to the status API.
    """
    for source in pair.values():
        endpoint = f"repos/{source['repository']}/statuses/{source['sha']}"
        try:
            run(["gh", "api", "--method", "POST", endpoint, "-f", f"state={state}",
                 "-f", f"context=gx10/{context}", "-f", f"description={description}"], capture=True)
        except Exception as error:
            print(f"warning: could not report gx10/{context}={state} for "
                  f"{source['repository']}@{source['sha'][:7]}: {error}", flush=True)


def checkout(repo, sha, destination):
    run(["git", "clone", "--no-checkout", f"https://github.com/{repo}.git", str(destination)])
    run(["git", "checkout", "--detach", sha], cwd=destination)
    actual = run(["git", "rev-parse", "HEAD"], cwd=destination, capture=True).stdout.strip()
    if actual != sha:
        raise RuntimeError("Checkout revision mismatch")


def service_environment():
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join([str(Path.home() / ".local/bin"),
                                    str(Path.home() / ".cargo/bin"), env.get("PATH", "")])
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


def lan_configuration():
    path = ROOT / "lan.json"
    if path.exists():
        return json.loads(path.read_text())
    return None


def probe_headers(url):
    # Never send production credentials to candidate or external URLs.
    if url.rstrip("/") != f"http://127.0.0.1:{FRONTEND_PORT}":
        return {}
    configuration = lan_configuration()
    if configuration is None:
        return {}
    return {"X-Lain-Gateway": Path(configuration["gateway_credential_file"]).read_text().strip(),
            "Origin": configuration["origin"]}


def api(url, command):
    request = urllib.request.Request(url + "/api", data=json.dumps(command).encode(),
                                     headers={"Content-Type": "application/json", **probe_headers(url)})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = json.load(response)
    if not body.get("ok"):
        raise RuntimeError(f"Editor API rejected probe: {body}")
    return body["result"]


def probe(url):
    request = urllib.request.Request(url, headers=probe_headers(url))
    with urllib.request.urlopen(request, timeout=15) as response:
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


def run_ci(release):
    env = service_environment()
    run(["bash", "deploy/ci.sh"], cwd=release / "backend", env=env, timeout=3600)
    run(["bash", "scripts/ci.sh"], cwd=release / "frontend", env=env, timeout=1800)


def build(release):
    env = service_environment()
    frontend = release / "frontend"
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


def directory_bytes(path):
    total = 0
    for directory, _directories, files in os.walk(path, onerror=lambda _error: None):
        for name in files:
            try:
                total += os.lstat(os.path.join(directory, name)).st_size
            except OSError:
                pass
    return total


def prune_releases(root=ROOT, keep=None):
    """Delete superseded release directories, keeping the live one plus the newest few.

    Every poll clones both repositories and builds inside the checkout, so one
    activated release costs hundreds of megabytes and nothing else ever removes
    them. Only directories the driver created itself (32 hex characters) are
    eligible, and whatever `current` points at is never touched, so a rollback
    stays possible and a hand-made directory in releases/ survives.
    """
    root = Path(root)
    keep = RELEASE_RETENTION if keep is None else max(1, int(keep))
    releases = root / "releases"
    if not releases.is_dir():
        return []
    live = (root / "current").resolve(strict=False)
    candidates = [path for path in releases.iterdir()
                  if path.is_dir() and RELEASE_NAME.fullmatch(path.name)]
    removed = []
    for index, path in enumerate(sorted(candidates, key=lambda item: item.stat().st_mtime, reverse=True)):
        if index < keep or path.resolve() == live:
            continue
        try:
            size = directory_bytes(path)
            shutil.rmtree(path)
        except OSError as error:
            print(f"warning: could not prune release {path.name}: {error}", flush=True)
            continue
        removed.append((path, size))
        print(f"pruned release {path.name} ({size // 1024 // 1024} MB reclaimed)", flush=True)
    return removed


def poll(retry=False):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / "pull.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        pair = source_pair()
        current = ROOT / "current/release.json"
        if current.exists() and json.loads(current.read_text())["pair"] == pair:
            report_status(pair, "deploy", "success", "Verified editor pair is active on GX10")
            print("Current release already matches locally tested main revisions", flush=True)
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
        phase = "ci"
        save(attempt, dict(receipt, status="running", phase=phase))
        try:
            report_status(pair, "ci", "pending", "Tests running locally on GX10")
            for role, source in pair.items():
                checkout(source["repository"], source["sha"], release / role)
            run_ci(release)
            report_status(pair, "ci", "success", "Backend and frontend CI passed on GX10")
            phase = "deploy"
            save(attempt, dict(receipt, status="running", phase=phase))
            report_status(pair, "deploy", "pending", "Verifying editor integration on GX10")
            build(release)
            if source_pair() != pair:
                save(attempt, dict(receipt, status="superseded", finished_at=time.time()))
                report_status(pair, "deploy", "error", "Main advanced during local verification")
                print("Main changed during preflight; leaving the current release unchanged", flush=True)
                return
            save(release / "release.json", dict(receipt, status="verified", ci="gx10-local"))
            activate(ROOT, release)
            run(["systemctl", "--user", "enable", *UNITS])
        except Exception as error:
            save(attempt, dict(receipt, status="failed", phase=phase, error=str(error), finished_at=time.time()))
            report_status(pair, phase, "failure", f"GX10 {phase} failed; inspect the pull-service journal")
            raise
        save(attempt, dict(receipt, status="active", finished_at=time.time()))
        report_status(pair, "deploy", "success", "Verified editor pair is active on GX10")
        print(f"Activated {release}", flush=True)
        try:
            prune_releases(ROOT)
        except Exception as error:
            print(f"warning: release retention pass failed: {error}", flush=True)


def serve(role):
    release = (ROOT / "current").resolve(strict=True)
    env = service_environment()
    configuration = lan_configuration()
    if role == "backend":
        args = backend_command(BACKEND_PORT)
        if configuration is not None:
            env.update(LAIN_RUNTIME_HOST="127.0.0.1", LAIN_RUNTIME_PORT=str(BACKEND_PORT),
                       LAIN_RUNTIME_PROJECT_ROOT=configuration["project_root"],
                       LAIN_RUNTIME_BEARER_CREDENTIAL_FILE=configuration["backend_credential_file"])
            args = ["clojure", "-Sdeps", DEPS, "-M", "-m", "graph.lain-editor.protected-server"]
    else:
        args = ["node", "scripts/dev.mjs"]
        env.update(LAIN_EDITOR_PORT=str(FRONTEND_PORT),
                   LAIN_EDITOR_BACKEND=f"http://127.0.0.1:{BACKEND_PORT}")
        if configuration is not None:
            env.update(LAIN_EDITOR_MODE="protected", LAIN_EDITOR_PUBLIC_ORIGIN=configuration["origin"],
                       LAIN_EDITOR_GATEWAY_CREDENTIAL_FILE=configuration["gateway_credential_file"],
                       LAIN_EDITOR_BACKEND_CREDENTIAL_FILE=configuration["backend_credential_file"])
    os.chdir(release / role)
    os.execvpe(args[0], args, env)


def install():
    if any(c.isspace() for c in str(ROOT)):
        raise ValueError("Deployment root must not contain whitespace")
    ROOT.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).resolve(), ROOT / "driver.py")
    units = Path.home() / ".config/systemd/user"
    units.mkdir(parents=True, exist_ok=True)
    common = f"Environment=LAIN_DEPLOY_ROOT={ROOT}\nEnvironment=PATH={Path.home()}/.local/bin:{Path.home()}/.cargo/bin:/usr/local/bin:/usr/bin:/bin\n"
    for role in REPOS:
        (units / f"gx10-lain-{role}.service").write_text(
            f"[Unit]\nDescription=GX10 Lain {role}\n[Service]\n{common}"
            f"ExecStart=/usr/bin/python3 {ROOT}/driver.py serve-{role}\n"
            "Restart=on-failure\nRestartSec=5\nKillMode=control-group\nTimeoutStopSec=20\nMemoryMax=8G\n"
            "[Install]\nWantedBy=default.target\n")
    (units / "gx10-lain-pull.service").write_text(
        f"[Unit]\nDescription=Run local CI and deploy Lain\n[Service]\nType=oneshot\n{common}"
        f"ExecStart=/usr/bin/python3 {ROOT}/driver.py poll\nTimeoutStartSec=7200\nMemoryMax=12G\n")
    (units / "gx10-lain-pull.timer").write_text(
        "[Unit]\nDescription=Check GitHub for Lain source updates\n[Timer]\n"
        "OnBootSec=45\nOnUnitInactiveSec=60\n[Install]\nWantedBy=timers.target\n")
    run(["systemctl", "--user", "daemon-reload"])
    run(["systemctl", "--user", "enable", "--now", "gx10-lain-pull.timer"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["poll", "prune", "install", "serve-backend", "serve-frontend"])
    parser.add_argument("--retry", action="store_true")
    parser.add_argument("--keep", type=int, default=None,
                        help="how many releases a prune retains (default: 3, or LAIN_RELEASE_RETENTION)")
    arguments = parser.parse_args()
    if arguments.operation == "install":
        install()
    elif arguments.operation == "poll":
        poll(arguments.retry)
    elif arguments.operation == "prune":
        prune_releases(ROOT, arguments.keep)
    else:
        serve(arguments.operation.removeprefix("serve-"))
