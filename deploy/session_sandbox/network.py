"""Trusted rootless egress: public IPv4 plus managed NordVPN Meshnet peers.

Only the outer setup process sees the host filesystem (read-only). It installs a
namespace-local firewall before executing the inner restricted root filesystem.
No host socket, host firewall rule or VPN setting is handed to uploaded code.
"""
from contextlib import contextmanager
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import policy

BLOCKED = ("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
           "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
           "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24",
           "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4")
MESH = ipaddress.IPv4Network("100.64.0.0/10")


def run(*arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=15).stdout


def ipv4s(text):
    result = set()
    for candidate in re.findall(r"\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b", text):
        try:
            result.add(str(ipaddress.IPv4Address(candidate)))
        except ipaddress.AddressValueError:
            pass
    return sorted(result)


def declarations():
    addresses = json.loads(run("/usr/sbin/ip", "-j", "address", "show"))
    host = sorted({entry["local"] for interface in addresses for entry in interface.get("addr_info", [])
                   if entry.get("family") == "inet"})
    peers, names, hostname = set(), {}, None
    for line in run("/usr/bin/nordvpn", "meshnet", "peer", "list").splitlines():
        key, separator, value = line.strip().partition(":")
        if key == "Hostname" and separator:
            hostname = value.strip().lower()
        elif key == "IP" and separator:
            for address in ipv4s(value):
                if ipaddress.IPv4Address(address) in MESH and address not in host:
                    peers.add(address)
                    if hostname and re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,252}\.nord", hostname):
                        names[hostname] = address
    resolvers = [address for address in ipv4s(run("/usr/bin/resolvectl", "dns"))
                 if not ipaddress.IPv4Address(address).is_loopback
                 and not ipaddress.IPv4Address(address).is_unspecified and address not in host]
    if not resolvers:
        raise RuntimeError("No non-loopback host DNS upstream is available for rootless networking")
    return {"mode": "public-ipv4-and-meshnet", "meshPeers": sorted(peers), "meshHosts": names,
            "hostAddresses": host, "dnsUpstream": resolvers[0],
            "hostNamespace": os.readlink("/proc/self/ns/net"), "inboundForwarding": False}


def address_set(name, addresses):
    normalized = sorted({str(ipaddress.IPv4Address(address)) for address in addresses})
    result = " set " + name + " { type ipv4_addr;\n"
    if normalized:
        result += "  elements = { " + ", ".join(normalized) + " };\n"
    return result + " }\n"


def firewall_rules(declaration):
    peers = set(declaration["meshPeers"])
    host = set(declaration["hostAddresses"])
    dns = str(ipaddress.IPv4Address(declaration["dnsUpstream"]))
    if dns in host or ipaddress.IPv4Address(dns).is_loopback or ipaddress.IPv4Address(dns).is_unspecified:
        raise ValueError("DNS upstream cannot expose a host service")
    if peers & host or any(ipaddress.IPv4Address(address) not in MESH for address in peers):
        raise ValueError("Meshnet allowlist must exclude host addresses and non-Meshnet destinations")
    return ("table inet lain_sandbox_egress {\n"
            + address_set("host4", host) + address_set("mesh4", peers)
            + " set blocked4 { type ipv4_addr; flags interval;\n  elements = { "
            + ", ".join(BLOCKED) + " };\n }\n"
            + " chain output {\n  type filter hook output priority 0; policy drop;\n"
              "  oifname \"lo\" accept\n"
              "  ip daddr @host4 counter reject with icmpx type admin-prohibited\n"
            + "  ip daddr " + dns + " udp dport 53 accept\n"
            + "  ip daddr " + dns + " tcp dport 53 accept\n"
            + "  ip daddr @mesh4 accept\n"
              "  ip daddr @blocked4 counter reject with icmpx type admin-prohibited\n"
              "  meta nfproto ipv4 accept\n"
              "  counter reject with icmpx type admin-prohibited\n }\n"
              " chain input {\n  type filter hook input priority 0; policy drop;\n"
              "  iifname \"lo\" accept\n  ct state established,related accept\n }\n}\n")


def outer_command(release, credential, configuration, information):
    return ["bwrap", "--unshare-user", "--unshare-net", "--unshare-pid", "--new-session", "--die-with-parent",
            "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/run", "--uid", "0", "--gid", "0",
            # SETFCAP is required by Linux for the nested 0 -> 0 UID map.
            # Both capabilities exist only in the trusted outer user namespace;
            # the inner sandbox drops every capability and forbids new userns.
            "--cap-drop", "ALL", "--cap-add", "CAP_NET_ADMIN", "--cap-add", "CAP_SETFCAP", "--clearenv",
            "--setenv", "HOME", str(Path.home()), "--setenv", "PATH", "/usr/bin:/usr/sbin",
            "--ro-bind-data", str(credential), "/run/credential",
            "--ro-bind-data", str(configuration), "/run/network-policy",
            "--info-fd", str(information), "--", "/usr/bin/python3", "-I",
            str(release / "source/deploy/session_sandbox/network.py"), "inside", str(release)]


def pasta_command(pid, declaration):
    return [str(policy.PASTA), "-f", "-q", "-4", "--config-net", "--no-map-gw",
            "-I", "tap0", "-a", "10.0.2.100", "-n", "24", "-g", "10.0.2.2",
            "-D", declaration["dnsUpstream"],
            "-t", "none", "-u", "none", "-T", "none", "-U", "none",
            "--netns", "/proc/" + str(pid) + "/ns/net",
            "--userns", "/proc/" + str(pid) + "/ns/user"]


def namespace_identity(process, descriptor):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        try:
            pid = json.loads(os.pread(descriptor, 8192, 0))["child-pid"]
            if not isinstance(pid, int) or pid <= 0:
                raise RuntimeError("Invalid namespace process identity")
            return pid
        except (ValueError, KeyError):
            if process.poll() is not None:
                raise RuntimeError("Outer namespace failed: " + process.stderr.read(16000).decode(errors="replace"))
            time.sleep(0.02)
    raise TimeoutError("Outer namespace identity deadline exceeded")


def terminate(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def capture_errors(pipe, buffer):
    while True:
        chunk = pipe.read(4096)
        if not chunk:
            return
        if len(buffer) < 16000:
            buffer.extend(chunk[:16000 - len(buffer)])


@contextmanager
def launch(release, credential, identity):
    declaration = declarations()
    firewall_rules(declaration)  # Validate before creating any namespace.
    configuration = os.memfd_create("own-sandbox-network-policy", 0)
    information = os.memfd_create("own-sandbox-network-identity", 0)
    process, helper, reader = None, None, None
    errors = bytearray()
    try:
        os.write(configuration, json.dumps(declaration).encode())
        os.lseek(configuration, 0, os.SEEK_SET)
        process = subprocess.Popen(outer_command(release, credential, configuration, information),
                                   pass_fds=(credential, configuration, information),
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        pid = namespace_identity(process, information)
        helper = subprocess.Popen(pasta_command(pid, declaration), stdin=subprocess.DEVNULL,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        reader = threading.Thread(target=capture_errors, args=(helper.stderr, errors), daemon=True)
        reader.start()
        yield process, declaration
    finally:
        os.close(configuration)
        os.close(information)
        terminate(process)
        terminate(helper)
        if reader is not None:
            reader.join(timeout=1)
        if helper is not None:
            helper.stderr.close()
        if errors:
            path = policy.instance_dir(identity) / "network-error.txt"
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(errors)


def namespace_main(release):
    declaration = json.loads(Path("/run/network-policy").read_text())
    if os.readlink("/proc/self/ns/net") == declaration["hostNamespace"]:
        raise RuntimeError("Network setup must never run in the host namespace")
    subprocess.run(["/usr/sbin/nft", "-f", "-"], input=firewall_rules(declaration),
                   text=True, check=True, capture_output=True, timeout=10)
    observed = json.loads(run("/usr/sbin/nft", "-j", "list", "table", "inet", "lain_sandbox_egress"))
    policies = {item["chain"]["name"]: item["chain"].get("policy")
                for item in observed["nftables"] if "chain" in item}
    if policies != {"input": "drop", "output": "drop"}:
        raise RuntimeError("Namespace firewall enforcement was not verified")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        result = subprocess.run(["/usr/sbin/ip", "-j", "address", "show", "dev", "tap0"],
                                capture_output=True, text=True, timeout=2)
        if result.returncode == 0 and "10.0.2.100" in result.stdout:
            break
        time.sleep(0.05)
    else:
        raise TimeoutError("Rootless network interface was not configured")
    hosts = (release / "source/deploy/session_sandbox/hosts").read_text()
    for name, address in sorted(declaration["meshHosts"].items()):
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,252}\.nord", name) or address not in declaration["meshPeers"]:
            raise ValueError("Invalid managed Meshnet hostname")
        hosts += address + " " + name + "\n"
    Path("/run/hosts").write_text(hosts)
    resolver = str(ipaddress.IPv4Address(declaration["dnsUpstream"]))
    Path("/run/resolv.conf").write_text("nameserver " + resolver + "\noptions timeout:2 attempts:2\n")
    credential = os.open("/run/credential", os.O_RDONLY | os.O_NOFOLLOW)
    if credential != 3:
        raise RuntimeError("Unexpected credential descriptor in network namespace")
    os.set_inheritable(credential, True)
    command = policy.bubblewrap_command(release, private_network=True)
    os.execvp(command[0], command)


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "inside":
        raise SystemExit("Trusted namespace setup only")
    namespace_main(Path(sys.argv[2]))
