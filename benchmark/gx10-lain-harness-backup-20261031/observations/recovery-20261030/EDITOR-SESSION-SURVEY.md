# Editor backend restart / session survival survey

Read-only. On-disk + process inspection only (no HTTP to editor ports, no signals, no evals).
Date: 2026-10-30, ~19:10 CET. ROOT=/home/lain/experiments/lain-harness-flash-once-20261027.

## VERDICT
**SESSION-LOST** — protected session `9bd02e31…` did not survive the 17:38:11 restart.
The protected backend keeps its entire session registry in per-process Clojure atoms and mints each
`sessionId` with `random-uuid` at prepare time; nothing is written to or read from disk at boot.

## 1) Restart timeline + which release runs
- `ps -o lstart= -p 22290` -> `Fri Oct 30 17:38:10 2026`; cmdline tail `clojure.main -m graph.lain-editor.protected-server`.
  pid 2389 no longer exists (`ps -p 2389` empty) -> 2389 -> 22290 handover confirmed.
- Unit backing it: **`gx10-lain-backend.service`** (user unit `~/.config/systemd/user/gx10-lain-backend.service`),
  ExecStart `/usr/bin/python3 ~/.local/share/lain-editor-deploy/driver.py serve-backend`.
  No system-level lain/editor unit (`/etc/systemd/system` grep lain|editor: nothing).
- `journalctl --user -u gx10-lain-backend.service`:
  - `Oct 30 13:27:02 Started` (lifetime that held pid 2389)
  - `Oct 30 17:38:11 Stopping … Main process exited, code=exited, status=143` (SIGTERM by systemd)
  - `Oct 30 17:38:11 Started` (new instance) — one clean restart, no crash loop, no later restarts.
  - `Oct 30 17:38:22 Protected Lain runtime: http://127.0.0.1:4785` — cleartext loopback port; the public
    `100.83.165.122:8443` / `192.168.178.201:8443` listeners belong to the Caddy gateway
    (`gx10-lain-gateway.service`, pid 22266), which was restarted alongside (pid 22266).
- Which release runs: `driver.py serve()` (driver.py:336, :352) resolves `ROOT/current` then
  `os.chdir(release/role)` and execs `graph.lain-editor.protected-server`.
  `current -> releases/04831d9b3be346caa0650d14104d03bd` (symlink mtime 17:38); `current/release.json`
  status `verified`, branch `gx10/high-level-tracer-20261030`, backend sha `c1ed279b…`, ci `gx10-focused-tracer`,
  `previous -> releases/0c90179ffc5a…`. So the **new** release is live. (Task brief named `cb857dcc…` as prior;
  the deploy record's `previous` is `0c90179f…`, and `cb857dcc…` still exists on disk — a redeploy chain
  cb857dcc -> 0c90179f -> 04831d9b.)
- Anomaly A: `releases/04831d9b…/backend` mtime 17:43 and the `current/` dir 17:47, i.e. writes into the release
  tree continued 5–9 min *after* the JVM started at 17:38:10 (checkout/retention landing after the symlink flip).
- Anomaly B: the live JVM classpath contains an absolute path into the **prior** release
  (`…/releases/0c90179f…/frontend/contracts/src`) while relative entries (`editor/src`, `target/lain-editor`, `.`)
  resolve against the new release cwd — contracts sources come from the old release.
- Gap: `/proc/22290/cwd` and `/proc/22290/fd/*` readlinks are **Permission denied** here (Yama
  `ptrace_scope=1`, no passwordless sudo in this sandbox). cwd was inferred from driver.py + journal instead.
  `ls /proc/22290/fd | wc -l` = 50 (table count only; targets not resolvable without ptrace/root).

## 2) Session store location
- `~/.local/share/` contains no `lain-editor*` data dir at all — only `lain-editor-deploy/` (deploy root).
  `~/.local/state`, `~/.cache`: no lain/editor entries.
- Under `lain-editor-deploy`: no file matching `*session*` (find -maxdepth 4); `gateway-data/` is Caddy
  certs/pki only; `lan.json`, `last-attempt.json` are config/deploy records.
- `grep -l 9bd02e31` over the deploy root: **no hit**.
- Filesystem-wide hits for `9bd02e31` are ours, not the backend's: `/tmp/pi-*` agent logs and
  `observations/recovery-20261030/{RECOVERY-STATUS.md,MAIN-BRINGUP-DISSECT.md,editor-*-attempt.json,
  coordinator-sessions/*.jsonl}`.
- `journalctl --user -u gx10-lain-backend.service | grep -c 9bd02e31` = **0** — the backend never logged that id,
  so the journal gives no post-restart sighting of it either.
=> No session store exists on disk anywhere in the expected data locations.

## 3) Backend code: persistence vs in-memory (running release `current/backend/editor/src/graph/lain_editor/`)
- `protected_server.clj:26` `start!` just calls `server/start!` with an authorizer; `-main` (:30-34) builds config
  from env and blocks. Only IO in the whole ns is `slurp` of the credential file (:18) and `io/file` checks (:14-16).
- `server.clj:118`: `(let [editors (atom {}) layouts (atom {}) developments (atom {}) commands (atom {}) detached (atom {}) …]`
  — the session registry is **five fresh in-memory atoms created at process start**, no `defonce` rehydration, no load call.
- Detached/session-list state lives in the `detached` atom only: `server.clj:65` `detach!`, `:66` `list-sessions` ->
  `list-detached`, `:67` `reopen-session` -> `reopen!`, `:68` `stop-session`. All plain atom reads/writes.
- `runtime/session.clj:4` docstring says "Backend-owned live sessions"; mutation is all `swap!`/`reset!`
  (:48, :69, :95-96, :123, :129, :148, :167, :174, :179, :184). `sessionId` is minted at prepare:
  `runtime/session.clj:51` `:sessionId (str (random-uuid))` — ids are generated, never loaded.
- No session persistence primitives anywhere: no `session-store`, no `sessions.json`, no `spit` in this tree;
  the only `Files/write` in `editor/src` is `workspace/project_files.clj:36` (user workspace file editing),
  and `runtime/session.clj:26-28` `loader/load-source!` reads *workspace source/extension .clj text*, not session state.
- `restore!` (`runtime/session.clj:117`) is invoked **only** from the `"restore"` API command
  (`server.clj:53`) with a document sent by a live client — it is not a boot-time store read, and it needs a
  client that still holds the old document.
=> **In-memory only. A JVM restart drops every session, pane mapping, generation token and detached entry.**

## 4) What our attachment proof can honestly claim
After the 17:38 redeploy, the protected backend is a **fresh process on a new release with an empty session
registry**, so proof of "our" work must be framed as *re-establishment against a restarted backend* (new session id,
re-prepared source, new generation token) — it cannot claim continuity of session `9bd02e31…`, and any 403/404 or
"Run this pane first"/`stale` response now reflects a genuinely cold registry rather than authorisation alone.

## Gaps / limits
- `/proc/22290/cwd`, `/proc/22290/fd` targets: unreadable without ptrace/root (noted above); cwd taken from driver.py logic.
- No HTTP probes run, so "registry is empty" is established from code + restart evidence, not from an API call.
- `systemctl show` timestamps and Caddy-side auth logs were not opened (not needed for the verdict).
