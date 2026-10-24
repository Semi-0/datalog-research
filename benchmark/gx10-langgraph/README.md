# GX10 LangGraph / Pi delegation installation

**Status: partial implementation, not an accepted delegation installation.** The local API and UI are running. The model-free control path is tested. Live model lifecycle effects, trusted coding-tool confinement, Harbor task integration and production recovery are not implemented. `smoke` and `distinct` graph inputs explicitly decline setup. No scored trial was run, and neither Ordering nor Events was started.

This directory is a separate cohort from the older solo and delegation benchmarks. Existing benchmark code, results and evaluator files were not edited by this work.

## Installed and observed

| Component | Pinned/observed version |
| --- | --- |
| Pi | 1.3.0, isolated npm installation |
| pi-better-subagents | 0.19.1 |
| Harbor | 0.24.0, existing isolated Python environment |
| LangGraph | 1.2.16 |
| LangGraph API | 0.16.6 |
| LangGraph CLI | 0.4.38 |
| In-memory runtime | 0.36.6 |
| lgview | source commit `cb06b8e657fa03c30a9da6544bf0903e0ef97485` |
| Node used by new services/probes | 24.22.1 ARM64 |

[Python dependencies](evidence/requirements.lock), [Harbor dependencies](evidence/harbor-requirements.lock), [Pi/plugin npm lock](evidence/package-lock.json) and [lgview npm lock](evidence/lgview-package-lock.json) retain the complete installed version resolutions. Python freeze files do not contain wheel hashes. [Deployment evidence](evidence/deployment.json) records source and built CLI hashes, service definitions and health. It is an installation snapshot; it is not a model-run receipt.

On GX10 the deployed source is `/home/lain/projects/datalog-research/benchmark/gx10-langgraph`. Dependencies and raw installation evidence are under `/home/lain/experiments/gx10-langgraph-install-1`. The services are `gx10-langgraph-api.service` and `gx10-lgview.service` under `~/.config/systemd/user/`. They bind only `127.0.0.1:2024` and `127.0.0.1:4141`. Hot reload, automatic browser opening, CLI analytics and cloud tracing are disabled. No public tunnel or hosted LangSmith service is used. This is a single-user experimental development server.

## Access and repeatable commands

From a Mac on the same LAN, open **http://192.168.178.201:4141** directly. No SSH tunnel or Mac port forwarding is needed. The previous Mac forwards were removed.

`gx10-lgview-lan.service` runs `lan-proxy.mjs` on GX10's private IPv4 address. It proxies the UI and streamed API requests to local lgview, fixes the API target to `127.0.0.1:2024`, and rejects unexpected Host/Origin values and cross-site browser requests. The underlying API remains loopback-only. Devices on the LAN can use this experimental UI without authentication. If GX10's DHCP address changes, update the service's address argument and use the new URL.

Install or restore the LAN service on GX10 after deploying these files:

```sh
cp gx10-lgview-lan.service "$HOME/.config/systemd/user/"
systemctl --user daemon-reload
systemctl --user enable --now gx10-lgview-lan.service
```

The earlier loopback-only installation and restart evidence below is retained as a historical snapshot.

To reproduce dependency setup on GX10, first deploy this directory at the source location above. The Python environments already exist; their locks can also be installed into fresh environments with `pip install -r evidence/requirements.lock` and the corresponding Harbor lock.

```sh
export PATH="$HOME/.local/opt/node-v24.22.1-linux-arm64/bin:$HOME/.local/bin:$PATH"
export GX10_LG_PREFIX="$HOME/experiments/gx10-langgraph-install-1"
mkdir -p "$GX10_LG_PREFIX/packages"
cp package.json "$GX10_LG_PREFIX/packages/"
cp evidence/package-lock.json "$GX10_LG_PREFIX/packages/"
(cd "$GX10_LG_PREFIX/packages" && npm ci --ignore-scripts --no-audit --no-fund)
git clone https://github.com/younissk/lgview.git "$GX10_LG_PREFIX/lgview"
(cd "$GX10_LG_PREFIX/lgview" && git checkout --detach cb06b8e657fa03c30a9da6544bf0903e0ef97485 && npm ci --no-audit --no-fund && npm run build)
python3 install.py
```

The clone command is for a fresh prefix. `install.py` validates pins, creates only the two named services, refuses to overwrite different service definitions, enables them and records locks/hashes. It never starts inference. On subsequent deployments, copy changed task files and restart the API explicitly because reload is disabled.

```sh
python3 install.py --health
python3 check_infrastructure.py --restart --output /tmp/gx10-infrastructure.json
python3 -m unittest discover -s . -p test_outcomes.py -v
node --test test_control.mjs
python3 probe.py --output "$HOME/experiments/gx10-langgraph-control-probe-NEW"
systemctl --user status gx10-langgraph-api gx10-lgview
journalctl --user -u gx10-langgraph-api -u gx10-lgview --no-pager
```

Every probe needs a fresh output directory. The fixture uses a synthetic HTTP server on loopback, not a loaded language model. Its 30-second operation waits bound the fixture; they are not a coding deadline.

## Verified behavior and limits

[Infrastructure evidence](evidence/infrastructure.json) records API health, graph discovery, lgview-to-API proxy health and an infrastructure checkpoint surviving a real API service restart. User-service linger is enabled. Separate SSH command sessions ended while the services continued running. The graph exposes the requested stage names, but the live effects behind those stages are not implemented. Viewing that topology is not proof that Flash/Qwen transitions occurred.

`companion.ts` loads the pinned plugin through a small registration facade. `/gx10-control` invokes only captured plugin control definitions and writes durable receipts; it never sends a parent prompt. It forces `callback:false`, `allow_nested:false`, an explicit tool list and independent workspace paths. Duplicate completed control requests reuse their receipts; failed or uncertain requests require reconciliation. A parent restart refuses phase advancement pending recovery. Automated reconciliation/retry is still missing.

[Control probe evidence](evidence/control-probe.json) records two real Pi child processes, two synthetic worker provider requests, zero parent provider requests, durable result collection and explicit stop commands. Raw RPC and provider logs, normalized child execution receipts, task policies and retained plugin logs remain at `/home/lain/experiments/gx10-langgraph-control-probe-3/`. The earlier `-1` and `-2` probes remain intact. Probe 1 caught a PATH mismatch between the active SDK CLI and globally installed Pi; the probe now puts the isolated pinned package's `.bin` directory first.

Seven Python outcome tests and five Node control tests passed on both the Mac and GX10. The outcome tests cover authentication errors despite exit zero/settlement/reward, truncated responses, interruption, zero assertions, verifier failure, incorrect implementations and a two-retry transient budget. These tests do not establish the missing reserve-breach, uncertain host cleanup or interrupted-worker recovery gates. No Clojure, clj-kondo or LSP worker assessment occurred.

## Authentication diagnosis

The earlier pilot's saved `backend/server-command.json` uses `--api-key local-benchmark`. Its Harbor command supplied `OPENAI_API_KEY=local-gx10-no-cloud` to `http://127.0.0.1:8001/v1`. Its transcript reports provider `harbor-endpoint`, model `qwen36-35b-a3b-nvfp4`, an assistant `401 Unauthorized`, then `agent_settled`. Those records are under `/home/lain/experiments/gx10-harbor-pi-pilot-1/` and were not changed.

The credential mismatch was subsequently fixed in the standalone [Harbor pilot runner](../harbor-pilot/validate.py). It derives the client key from the actual backend command and checks the live endpoint before launching Pi. The fresh `gx10-harbor-pi-authfix-1` trial accepted the correct key (200), rejected an invalid key (401), generated 4,426 tokens and passed all four independent assertions with Harbor reward 1.0. Model/process/container cleanup was verified. The original failed trial and task files were preserved. See the [fix report](../harbor-pilot/AUTHENTICATION-FIX-2026-10-24.md) and [result](../harbor-pilot/evidence/authfix-1/result.json).

The installed Harbor package itself was not modified. Both the standalone pilot checks and `outcomes.py` reject assistant/provider errors even if process exit or settlement appears successful, retaining correctness and execution separately. Production retries must use separate attempt directories. This successful unscored four-assertion pilot does not complete the LangGraph delegation integration or the original 14-assertion Distinct assessment.

## Remaining acceptance work

The original Distinct contract assigns `benchmark/stable_distinct.clj` to implementation and `test/benchmark/stable_distinct_test.clj` to the test worker. Its original 14-assertion evaluator remains outside this new directory and unchanged; observed SHA-256 is `33145fc956b41791ad33580c778f1ef1edf20c695b98f14275687066c39ee7cf`. It was not supplied to either synthetic worker.

Before any live assessment, implement and verify workspace confinement for trusted structural-editing, clj-kondo, Lens/LSP, web and private Gortex tools. The plugin's trusted extensions execute outside its built-in file sandbox; the companion currently admits no trusted extensions. Do not enable them merely by adding names to an allowlist. An actual rejected-write/tool-availability fixture is still required.

Then connect the existing GX10 supervisor through explicit lifecycle effects, including the 20% reserve, 200 ms monitoring, API/UI/tool overhead, verified unloading and settlement. Configure one pinned Qwen3.6 NVFP4 server with two request slots, 128K per-request context, MTP disabled, thinking and temperature 1.0. Verify cache capacity and actual overlap before accepting that phase. Keep the parent Pi process alive across weight transitions and deny parent provider requests during worker execution.

Finally add Harbor queuing/environment/independent verification, the one-worker smoke, Flash-to-Qwen-to-Flash Distinct assessment, persisted plan/patch integration and restart reconciliation. Never feed the hidden evaluator's output into the same attempt. No next model may load after uncertain cleanup. Report wrong task results directly, preserve this cohort's independent outcomes and do not start Ordering or Events automatically.

Assessment result for this installation attempt: **not run; installation acceptance incomplete**.

Upstream references: [lgview source](https://github.com/younissk/lgview), [pi-better-subagents package](https://pi.dev/packages/pi-better-subagents), [LangGraph CLI](https://reference.langchain.com/python/langgraph-cli/cli/dev).
