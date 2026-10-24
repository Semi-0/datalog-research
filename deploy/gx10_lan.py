#!/usr/bin/env python3
"""Install the optional HTTPS/password gateway for trusted shared LAN operators."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess

import gx10_editor as deployment


def validate_access(address, subnet, username, port):
    address = ipaddress.ip_address(address)
    subnet = ipaddress.ip_network(subnet)
    if (address.version != 4 or not address.is_private or address.is_loopback
            or address.is_unspecified or address not in subnet or subnet.prefixlen < 16):
        raise ValueError("An explicit private IPv4 address and bounded containing LAN subnet are required")
    if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", username) or not 1024 <= port <= 65535:
        raise ValueError("Invalid sign-in username or unprivileged HTTPS port")
    return str(address), str(subnet)


def private_directory(path):
    path.mkdir(parents=True, mode=0o700, exist_ok=True)
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError("Gateway configuration directory must be private and not a symlink")


def private_file(path, content):
    with open(path, "x", opener=lambda name, flags: os.open(name, flags, 0o600)) as output:
        output.write(content)


def credential(path):
    if not path.exists():
        private_file(path, secrets.token_urlsafe(48) + "\n")
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError("Credential file must be private and not a symlink")
    value = path.read_text().strip()
    if re.fullmatch(r"[A-Za-z0-9_-]{32,}", value) is None:
        raise ValueError("Invalid existing gateway credential")
    return value


def caddy_configuration(address, subnet, port, username, password_hash, gateway):
    address, subnet = validate_access(address, subnet, username, port)
    if re.fullmatch(r"\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}", password_hash) is None:
        raise ValueError("A bcrypt password hash is required")
    if re.fullmatch(r"[A-Za-z0-9_-]{32,}", gateway) is None:
        raise ValueError("Invalid gateway credential")
    return f'''{{
    admin off
    auto_https disable_redirects
    skip_install_trust
}}
https://{address}:{port} {{
    bind {address}
    tls internal
    route {{
        @outside not remote_ip {subnet}
        respond @outside "Forbidden" 403
        basic_auth {{
            {username} {password_hash}
        }}
        reverse_proxy 127.0.0.1:{deployment.FRONTEND_PORT} {{
            header_up -Authorization
            header_up -Cookie
            header_up -Remote-User
            header_up X-Lain-Gateway {gateway}
        }}
    }}
}}
'''


def install(address, subnet, username, port, caddy):
    os.umask(0o077)
    address, subnet = validate_access(address, subnet, username, port)
    caddy = caddy.resolve(strict=True)
    root = deployment.ROOT
    if not (root / "current/backend/editor/src/graph/lain_editor/protected_server.clj").is_file():
        raise RuntimeError("Deploy and verify the protected runtime revision before opening LAN ingress")
    private = Path.home() / ".config/lain-editor-lan"
    private_directory(private)
    gateway_file, backend_file = private / "gateway", private / "backend"
    gateway = credential(gateway_file)
    backend = credential(backend_file)
    if gateway == backend:
        raise ValueError("Gateway and runtime credentials must be distinct")
    login = private / "login.json"
    if not login.exists():
        private_file(login, json.dumps({"username": username, "password": secrets.token_urlsafe(24)}) + "\n")
    if login.is_symlink() or login.stat().st_mode & 0o077:
        raise ValueError("Sign-in file must be private and not a symlink")
    account = json.loads(login.read_text())
    if account["username"] != username:
        raise ValueError("Existing sign-in username differs; preserve or deliberately rotate the private account")
    hashed = subprocess.run([str(caddy), "hash-password"], input=account["password"] + "\n",
                            text=True, capture_output=True, check=True).stdout.strip()
    config = private / "Caddyfile"
    config.write_text(caddy_configuration(address, subnet, port, username, hashed, gateway))
    config.chmod(0o600)
    subprocess.run([str(caddy), "validate", "--config", str(config), "--adapter", "caddyfile"],
                   check=True, capture_output=True)
    project = Path.home() / "projects/lain-workspace"
    project.mkdir(parents=True, exist_ok=True)
    configuration = {"origin": f"https://{address}:{port}", "project_root": str(project),
                     "gateway_credential_file": str(gateway_file), "backend_credential_file": str(backend_file)}
    # Close ingress during configuration/restart. No unauthenticated rollback is exposed.
    subprocess.run(["systemctl", "--user", "stop", "gx10-lain-gateway.service"], capture_output=True)
    deployment.save(root / "lan.json", configuration)
    (root / "lan.json").chmod(0o600)
    deployment.restart()
    state = root / "gateway-data"
    private_directory(state)
    units = Path.home() / ".config/systemd/user"
    unit = units / "gx10-lain-gateway.service"
    unit.write_text(
        "[Unit]\nDescription=Authenticated HTTPS Lain LAN gateway\n"
        "After=gx10-lain-frontend.service\nRequires=gx10-lain-frontend.service\nPartOf=gx10-lain-frontend.service\n[Service]\n"
        f"ExecStart={caddy} run --config {config} --adapter caddyfile\n"
        f"Environment=XDG_DATA_HOME={state}\nEnvironment=XDG_CONFIG_HOME={private}\n"
        "UMask=0077\nRestart=on-failure\nRestartSec=5\nKillMode=control-group\nMemoryMax=512M\n"
        "[Install]\nWantedBy=default.target\n")
    deployment.run(["systemctl", "--user", "daemon-reload"])
    deployment.run(["systemctl", "--user", "enable", "--now", "gx10-lain-gateway.service"])
    print(f"LAN editor: {configuration['origin']}")
    print(f"Read sign-in credentials privately on GX10: {login}")
    print(f"Local CA certificate: {state}/caddy/pki/authorities/local/root.crt")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", required=True)
    parser.add_argument("--subnet", required=True)
    parser.add_argument("--username", default="lain")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--caddy", type=Path, required=True)
    arguments = parser.parse_args()
    install(arguments.address, arguments.subnet, arguments.username, arguments.port, arguments.caddy)
