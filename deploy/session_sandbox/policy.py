"""Fixed, trusted GX10 sandbox policy. No settings are accepted from clients."""
import json
import os
from pathlib import Path
import re
import shutil
import sys

ROOT = Path.home() / ".local/share/lain-session-sandbox"
JAVA_HOME = Path.home() / ".local/opt/java21"
PASTA = Path.home() / ".local/opt/lain-sandbox-pasta/5aadbb0812a14865eaf5c76e4babc058d9650f0258706b1d8f20560c43b9943c/usr/bin/pasta"
BROKER_PORT = 48870
MEMORY_BYTES = 1536 * 1024 * 1024
WORK_BYTES = 128 * 1024 * 1024
TMP_BYTES = 16 * 1024 * 1024
SHM_BYTES = 8 * 1024 * 1024
MAX_BODY = 2 * 1024 * 1024
MAX_RESPONSE = 16 * 1024 * 1024
MAX_RECEIPTS = 128
MAX_ACTIVE = 2
ID = re.compile(r"[0-9a-f]{32}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")


def sandbox_id(value):
    if not isinstance(value, str) or ID.fullmatch(value) is None:
        raise ValueError("Invalid sandbox identity")
    return value


def unit_name(identity):
    return "lain-sandbox-" + sandbox_id(identity) + ".service"


def instance_dir(identity):
    return ROOT / "instances" / sandbox_id(identity)


def require_platform():
    if sys.platform != "linux" or os.geteuid() == 0:
        raise RuntimeError("Requires an unprivileged Linux account")
    for tool in ("bwrap", "systemd-run", "systemctl"):
        if shutil.which(tool) is None:
            raise RuntimeError("Required isolation tool unavailable: " + tool)
    if os.stat(shutil.which("bwrap")).st_mode & 0o4000:
        raise RuntimeError("Setuid Bubblewrap is outside this policy")
    controllers = Path("/sys/fs/cgroup/cgroup.controllers").read_text().split()
    if not {"cpu", "memory", "pids"}.issubset(controllers):
        raise RuntimeError("Required cgroup v2 controllers unavailable")
    for tool in ("/usr/sbin/nft", "/usr/sbin/ip", "/usr/bin/nordvpn", "/usr/bin/resolvectl"):
        if not Path(tool).is_file():
            raise RuntimeError("Required network tool unavailable: " + tool)
    if not PASTA.is_file() or os.stat(PASTA).st_mode & 0o4000:
        raise RuntimeError("Verified unprivileged sandbox pasta helper unavailable")
    if not (JAVA_HOME / "bin/java").is_file():
        raise RuntimeError("Approved Java 21 installation unavailable")


def unit_properties():
    return ["MemoryMax=" + str(MEMORY_BYTES), "MemorySwapMax=0", "CPUQuota=100%",
            "TasksMax=128", "KillMode=control-group", "TimeoutStopSec=3s",
            "SendSIGKILL=yes", "NoNewPrivileges=yes", "OOMScoreAdjust=500",
            "StandardInput=null", "StandardOutput=null", "StandardError=null"]


def bubblewrap_command(release, private_network=False):
    """FD 3 is consumed as the own-sandbox key; stdin/stdout are anonymous HTTP pipes.

    No host workspace, home, socket directory, service credentials, GPU or cgroup
    filesystem is exposed. Writable mounts have independent hard size ceilings.
    """
    release = Path(release).resolve(strict=True)
    if release.parent != (ROOT / "releases").resolve(strict=True) or not DIGEST.fullmatch(release.name):
        raise ValueError("Runtime release is not an installed trusted artifact")
    hosts = release / "source/deploy/session_sandbox/hosts"
    if private_network:
        declaration = json.loads(Path("/run/network-policy").read_text())
        if os.geteuid() != 0 or os.readlink("/proc/self/ns/net") == declaration["hostNamespace"]:
            raise RuntimeError("Refusing host-network inheritance; private network setup is required")
        hosts = Path("/run/hosts")
    args = ["bwrap", "--unshare-all", "--unshare-user", "--unshare-cgroup", "--disable-userns",
            "--assert-userns-disabled", "--new-session", "--die-with-parent",
            "--clearenv", "--cap-drop", "ALL", "--hostname", "lain-sandbox",
            "--ro-bind", str(hosts), "/etc/hosts",
            "--ro-bind", str(release), "/opt/runtime",
            "--ro-bind", str(JAVA_HOME.resolve(strict=True)), "/opt/java",
            "--ro-bind", "/usr/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu",
            "--symlink", "usr/lib", "/lib",
            "--symlink", "aarch64-linux-gnu/ld-linux-aarch64.so.1", "/usr/lib/ld-linux-aarch64.so.1",
            "--ro-bind", "/usr/bin/python3.12", "/usr/bin/python3.12",
            "--symlink", "python3.12", "/usr/bin/python3",
            "--ro-bind", "/usr/lib/python3.12", "/usr/lib/python3.12",
            "--symlink", "usr/bin", "/bin", "--proc", "/proc", "--remount-ro", "/proc",
            "--dev", "/dev", "--size", str(SHM_BYTES), "--tmpfs", "/dev/shm",
            "--remount-ro", "/dev",
            "--size", str(TMP_BYTES), "--tmpfs", "/tmp",
            "--size", str(WORK_BYTES), "--tmpfs", "/workspace",
            "--ro-bind", str(release / "source/propagators"), "/workspace/propagators",
            "--dir", "/run", "--perms", "0400", "--ro-bind-data", "3", "/run/credential",
            "--setenv", "HOME", "/workspace/home", "--setenv", "PATH", "/opt/java/bin:/usr/bin",
            "--setenv", "JAVA_HOME", "/opt/java", "--setenv", "LANG", "C.UTF-8",
            "--setenv", "TZ", "UTC", "--chdir", "/workspace",
            "--remount-ro", "/", "--", "/usr/bin/python3", "-I", "-u",
            "/opt/runtime/source/deploy/session_sandbox/inside.py"]
    if private_network:
        args.insert(2, "--share-net")  # Only the verified outer private namespace.
        position = args.index("--chdir")
        args[position:position] = [
            "--ro-bind", "/run/resolv.conf", "/etc/resolv.conf",
            "--ro-bind", str(release / "source/deploy/session_sandbox/nsswitch.conf"), "/etc/nsswitch.conf",
            "--ro-bind", "/etc/ssl/certs/ca-certificates.crt", "/etc/ssl/certs/ca-certificates.crt",
            "--ro-bind", str((JAVA_HOME / "lib/security/cacerts").resolve(strict=True)), "/opt/java/lib/security/cacerts",
            "--setenv", "SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt"]
    return args


def verify_cgroup(path):
    root = Path("/sys/fs/cgroup") / path.lstrip("/")
    observed = {name: (root / name).read_text().strip()
                for name in ("memory.max", "memory.swap.max", "pids.max", "cpu.max")}
    quota, period = observed["cpu.max"].split()
    if (observed["memory.max"] != str(MEMORY_BYTES)
            or observed["memory.swap.max"] != "0"
            or observed["pids.max"] != "128"
            or quota == "max" or int(quota) > int(period)):
        raise RuntimeError("Isolation resource enforcement was not verified: " + repr(observed))
    return observed
