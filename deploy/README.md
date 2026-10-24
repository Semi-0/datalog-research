# GX10 local CI and editor deployment

GX10 polls the main branches of Semi-0/datalog-research and Semi-0/lain-editor, checks out an exact pair of revisions into an isolated release directory, and runs both repositories' CI scripts locally. GitHub Actions workflows are removed; this pipeline consumes no GitHub-hosted runner minutes. GitHub remains the source host and receives `gx10/ci` and `gx10/deploy` commit statuses.

The GX10 account must have `gh` authentication with repository read and commit-status write access, Git, Python 3, Java 21, Clojure CLI, Node 22/npm, and Rust/Cargo. Put user tools in `~/.local/bin` and Rust in `~/.cargo/bin`. No token belongs in source files. The former Actions secret LAIN_READ_TOKEN is unused by this pipeline.

## Install

From an up-to-date backend checkout on GX10:

```sh
python3 deploy/gx10_editor.py install
systemctl --user start --no-block gx10-lain-pull.service
journalctl --user -u gx10-lain-pull.service -f
```

The user timer checks for updates every minute after each completed attempt. User lingering must be enabled for unattended operation. Only these owned units are managed: `gx10-lain-pull.service`, `gx10-lain-pull.timer`, `gx10-lain-backend.service`, and `gx10-lain-frontend.service`.

## Verification and activation

Backend `bash deploy/ci.sh` runs Clojure, ClojureScript/Node, Rust, deployment-controller tests, offline benchmark tests and pinned Pi fixture tests. Frontend `bash scripts/ci.sh` runs its Clojure/ClojureScript checks, server and gateway tests, deployment renderer tests, build and controlled authentication browser tests. These checks do not invoke live model inference.

The candidate pair then runs focused backend editor integration tests, starts temporary backend/frontend processes, verifies an arithmetic pane through the API, and runs real-backend browser acceptance. Candidate children are reaped on completion or failure. The driver rechecks both main revisions before promotion, so a superseded candidate cannot activate.

Activation atomically replaces the `current` symlink, restarts the owned editor services and verifies the API. Failed health checks restore the previous release. Failed source pairs remain recorded without repeated expensive automatic retries; run `python3 ~/.local/share/lain-editor-deploy/driver.py poll --retry` to retry deliberately. A newer main revision starts a fresh attempt automatically.

Release checkouts and receipts live under `~/.local/share/lain-editor-deploy/`. Inspect `last-attempt.json` for the pair, CI/deployment phase and failures, and `current/release.json` for the active pair. Build output is in the pull-service journal. The installed driver is copied at installation; changes to the controller itself require rerunning install from the updated backend checkout.

## Access

This deployment uses local editor mode: frontend listens on GX10 loopback port 4782 and backend on loopback 4785. Forward it privately:

```sh
ssh -N -L 4792:127.0.0.1:4782 lain@gx10-ac8b.local
```

Open http://127.0.0.1:4792/. This does not install the frontend repository's authenticated LAN gateway templates or expose the editor on LAN interfaces.

## Optional authenticated LAN access

After deploying a revision containing `graph.lain-editor.protected-server`, install a pinned, checksum-verified ARM64 Caddy binary under the GX10 account and run:

```sh
python3 deploy/gx10_lan.py --address 192.168.178.201 --subnet 192.168.178.0/24 --caddy ~/.local/opt/caddy-2.11.7/caddy
```

This separate deployment uses HTTPS on port 8443 and browser username/password sign-in through Caddy. It does not install or weaken the frontend repository's Authelia/two-factor templates. Only clients in the configured LAN subnet are accepted. The frontend stays on loopback in protected mode and requires a distinct gateway credential; the runtime stays on loopback and validates exactly one bearer header before body parsing or command dispatch. Editor file commands use `~/projects/lain-workspace`. This is a shared trusted operator runtime with the account's code execution permissions, not per-user isolation or an OS sandbox.

The installer generates independent credentials and a strong initial sign-in password in mode-0600 files under `~/.config/lain-editor-lan/`. Read `login.json` privately on GX10; do not paste credentials into chat or commit them. The `gx10-lain-gateway.service` user service restarts with the frontend on deployments. Protected production health probes receive only the gateway credential and exact public Origin; candidate checks never receive production credentials.

Open `https://192.168.178.201:8443/`. Trust the public local CA certificate from `~/.local/share/lain-editor-deploy/gateway-data/caddy/pki/authorities/local/root.crt` on each client, after transferring it over authenticated SSH and verifying its fingerprint. Never transfer `root.key`. No system trust store is modified automatically. A browser may initially show an untrusted-certificate warning. The LAN address is DHCP-assigned; reserve it in the router or reinstall the configuration if it changes. Basic authentication uses the browser's credential cache; close the private browsing session to discard that session's cached sign-in.

Stop LAN ingress with `systemctl --user stop gx10-lain-gateway.service`. Removing LAN configuration requires first disabling ingress and deliberately restoring local configuration; failures never expose an unauthenticated LAN fallback.

Stop automatic updates with `systemctl --user stop gx10-lain-pull.timer`. Existing releases remain available for inspection and rollback; the driver does not prune them automatically.
