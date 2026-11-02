"""Trusted SSH-side installer for only the experimental sandbox broker.

Run with Python -I beside the matching trusted policy.py. No HTTP client can
choose releases, units, mounts, commands or cgroup policy through this installer.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import policy

UNIT = "lain-session-sandbox-broker.service"
MARKER = "# Lain experimental session sandbox broker\n"
MAX_FILES = 10000
MAX_BYTES = 768 * 1024 * 1024


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def safe_name(value):
    if not isinstance(value, str) or "\\" in value or any(part in {"", ".", ".."} for part in value.split("/")):
        raise ValueError("Invalid artifact path")
    path = PurePosixPath(value)
    if path.is_absolute() or path.parts[0] not in {"source", "deps", "manifest.json"}:
        raise ValueError("Artifact path outside curated roots")
    return path


def verify_manifest(root):
    path = root / "manifest.json"
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Manifest too large")
    manifest = json.loads(path.read_text())
    files = manifest.get("files")
    if manifest.get("version") != 1 or not isinstance(files, dict) or len(files) > MAX_FILES:
        raise ValueError("Invalid artifact manifest")
    actual = set()
    for item in root.rglob("*"):
        if item.is_symlink():
            raise ValueError("Symlink in frozen release")
        if item.is_file():
            actual.add(item.relative_to(root).as_posix())
    if actual != set(files) | {"manifest.json"}:
        raise ValueError("Manifest file set differs from release")
    for name, expected in files.items():
        safe_name(name)
        if not isinstance(expected, str) or not policy.DIGEST.fullmatch(expected) or digest(root / name) != expected:
            raise ValueError("Artifact digest mismatch: " + name)
    entries = manifest.get("classpath")
    if not isinstance(entries, list) or not entries or entries[0] != "source":
        raise ValueError("Invalid classpath")
    for entry in entries[1:]:
        if not isinstance(entry, str) or re.fullmatch(r"deps/[0-9a-f]{64}\.jar", entry) is None:
            raise ValueError("Unapproved classpath entry")
        if files.get(entry) != Path(entry).stem:
            raise ValueError("Jar name differs from digest")
    for required in ("manager.py", "policy.py", "inside.py", "runtime.clj", "network.py", "hosts", "nsswitch.conf"):
        if not (root / "source/deploy/session_sandbox" / required).is_file():
            raise ValueError("Missing launcher component")
    if digest(root / "source/deploy/session_sandbox/policy.py") != digest(Path(__file__).with_name("policy.py")):
        raise ValueError("Installer policy differs from frozen launch policy")
    return manifest


def extract_verified(archive_path, expected):
    if archive_path.is_symlink() or not archive_path.is_file() or not policy.DIGEST.fullmatch(expected):
        raise ValueError("Expected a regular archive and SHA256")
    if digest(archive_path) != expected:
        raise ValueError("Archive SHA256 mismatch")
    releases = policy.ROOT / "releases"
    releases.mkdir(parents=True, exist_ok=True, mode=0o700)
    stage = Path(tempfile.mkdtemp(prefix=".install-", dir=releases))
    try:
        names, total = set(), 0
        with tarfile.open(archive_path, "r|gz") as archive:
            for member in archive:
                name = safe_name(member.name).as_posix()
                if name in names or not member.isreg() or member.sparse or member.size < 0 or member.size > 256 * 1024 * 1024:
                    raise ValueError("Unsafe or duplicate archive member")
                names.add(name)
                total += member.size
                if len(names) > MAX_FILES + 1 or total > MAX_BYTES:
                    raise ValueError("Artifact exceeds installation budget")
                target = stage / name
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                with os.fdopen(descriptor, "wb") as output, archive.extractfile(member) as source:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                if target.stat().st_size != member.size:
                    raise ValueError("Truncated archive member")
        manifest = verify_manifest(stage)
        target = releases / expected
        if target.exists():
            if target.is_symlink() or verify_manifest(target) != manifest:
                raise ValueError("Existing frozen release differs")
            return target
        os.replace(stage, target)
        for item in target.rglob("*"):
            if item.is_file():
                item.chmod(0o400)
            elif item.is_dir():
                item.chmod(0o500)
        target.chmod(0o500)
        return target
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def control(*arguments, check=True):
    return subprocess.run(["systemctl", "--user", *arguments], check=check,
                          capture_output=True, text=True, timeout=20)


def install_broker(release):
    instances = policy.ROOT / "instances"
    instances.mkdir(exist_ok=True, mode=0o700)
    for path in instances.glob("*/record.json"):
        if json.loads(path.read_text()).get("state") not in {"reserved", "stopped"}:
            raise RuntimeError("An experimental sandbox is active; preserve it before updating its broker")
    directory = Path.home() / ".config/systemd/user"
    directory.mkdir(parents=True, exist_ok=True)
    unit = directory / UNIT
    prior = unit.read_text() if unit.exists() else None
    if unit.is_symlink() or (prior is not None and not prior.startswith(MARKER)):
        raise RuntimeError("Refusing to replace an unowned unit")
    if control("is-active", UNIT, check=False).stdout.strip() != "active":
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", policy.BROKER_PORT))
    if any(character in str(release) for character in " \n\r\t%"):
        raise ValueError("Unexpected fixed release path")
    contents = MARKER + "\n".join([
        "[Unit]", "Description=Lain isolated experimental session broker", "[Service]",
        "Type=simple", "ExecStart=/usr/bin/python3 -I " + str(release / "source/deploy/session_sandbox/manager.py") + " serve",
        "MemoryMax=256M", "MemorySwapMax=0", "CPUQuota=50%", "TasksMax=48",
        "NoNewPrivileges=yes", "KillMode=control-group", "TimeoutStopSec=3s",
        "OOMScoreAdjust=500", "UMask=0077", "StandardInput=null", "StandardOutput=null", "StandardError=null", ""
    ])
    if prior is not None:
        backup = policy.ROOT / "backups"
        backup.mkdir(exist_ok=True, mode=0o700)
        (backup / (UNIT + "." + str(time.time_ns()))).write_text(prior)
    temporary = unit.with_suffix(".new")
    temporary.write_text(contents)
    temporary.chmod(0o600)
    os.replace(temporary, unit)
    try:
        control("daemon-reload")
        control("restart", UNIT)
        if control("is-active", UNIT).stdout.strip() != "active":
            raise RuntimeError("Experimental broker failed to start")
    except Exception:
        control("stop", UNIT, check=False)
        if prior is None:
            unit.unlink()
        else:
            unit.write_text(prior)
            unit.chmod(0o600)
        control("daemon-reload", check=False)
        raise


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("sha256")
    args = parser.parse_args()
    policy.require_platform()
    release = extract_verified(args.archive, args.sha256)
    install_broker(release)
    print(json.dumps({"release": release.name, "broker": UNIT, "port": policy.BROKER_PORT,
                      "scope": "experimental sandbox only; production services untouched"}))


if __name__ == "__main__":
    main()
