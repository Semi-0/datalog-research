# LAUNCH-SCRIPT-RECHECK — launch-main-20261030.sh (patched, read-only re-verification)

Date: 2026-10-30. Reviewer: read-only subagent. Nothing modified except this file.
Input: patched script (3900B, mtime 18:12) vs prior FIX-FIRST review LAUNCH-SCRIPT-REVIEW.md.
Evidence: full script read; drivers/flash_once.clj -main/bridge-spawn/tick-decision read;
live /proc scans (read-only); `docker inspect lain-trial-1` (read-only) = `exited
2026-10-29T18:15:19.25827535Z`; host ledger mirrors under ROOT/container-logs/trial-a1/;
clojure launcher script (exec java at line 503); empirical bash-semantics probes in /tmp (removed).
`bash -n launch-main-20261030.sh` → passes (rc=0).

## 1. FIX-FIRST items — all four correctly implemented

### F1 (collateral kill) — RESOLVED ✔
- Script-wide grep: no `pkill -f`, no `pgrep -f`, no `killall` anywhere (only occurrences of
  pgrep/kill are L18/L19/L48 `pgrep -x` + /proc scans, and L53/L59/L60 kills). 
- Stall-kill is exactly two scoped sites:
  - L59 `[ -n "$JPID" ] && kill -TERM "$JPID" 2>/dev/null` — JPID comes solely from L48, a scan
    restricted to `pgrep -x java` (comm exactly "java") + /proc/$p/cmdline grep `flash_once.clj`.
    Live scan NOW: java = 11193 (gx wifi, m=0) and 22290 (editor protected-server; cmdline
    `/home/lain/.local/bin/java ... -classpath .:editor/src:target/lain-editor:...` — zero
    "flash" substrings, m_L18=0, m_L48=0). No java on the box currently matches; the only java
    that CAN match is a flash_once.clj driver JVM. 22290 cannot enter JPID.
  - L60 `pkill -TERM -P $DRV 2>/dev/null; kill $DRV 2>/dev/null` — $DRV is OUR OWN `timeout`
    (L42). timeout's only direct child is flock→(exec)→clojure→(exec→java, verified `exec
    "$JAVA_CMD" ...` at clojure:503) = exactly the driver JVM pid. Semicolon (not &&) ensures
    `kill $DRV` runs even with no children. Both signals target only our spawned tree.
- No path signals node/agent processes: the only kills are java-pattern-filtered (L59) or
  children of our own timeout (L60). Sibling agent runtimes (28789/28857 node, bwrap) are
  unreachable from every kill site.
- Lock mutual-exclusion reinforces L59: L42 holds flock .gate-jvm.lock for the JVM's whole
  lifetime, so no other gate-jvm java can exist in the kill window; L37 proves the lock free
  pre-launch. L48 takes the last loop match (highest pgrep order), but only one flash_once.clj
  java can exist in-window → unambiguous.

### F2 (state match) — RESOLVED ✔
- L30 `case "$state" in running*) ;; *) fail "container not running: $state";; esac` — matches
  `docker inspect` output `running <ts>` (lowercase, verified emitting `exited <ts>` today).
  No `Up*` remnant. L28 inspect itself is `|| fail`-guarded.

### F3 (preflight liveness) — RESOLVED ✔
- L18/L19 use `pgrep -x java` / `pgrep -x node` + `/proc/$p/cmdline` greps only; no `pgrep -f`
  remains anywhere. Live dry-run of both exact loops: 0 matches (agent runtimes 28789/28857 and
  the reviewer sandbox bwrap — which DO contain "pi_bridge"/"flash" substrings in argv — are
  excluded because they fail `-x java`/the exact `pi_bridge.mjs --serve` string). Preflight
  passes today, unlike the pre-patch version.
- Residual (fail-safe, non-blocking): an agent whose argv embeds the literal
  "pi_bridge.mjs --serve" or `flash_once.clj` in a java comm would trip L18/L19 → aborts BEFORE
  any mutation; kills nothing.

### F4 (identity capture) — RESOLVED ✔
- L47 `JPID=""` init, L48 same java-cmdline scan as F3, L49 logs `driver-jvm-pid
  ${JPID:-unknown} <ts>`. The recorded pid is the real JVM (clojure exec's java, so the JVM is
  also timeout's direct child — identical pid either way), never the wrapper and never an agent
  (per F1 analysis). Also the ONLY kill target derivation — scan and kill use the same predicate.

## 2. Recommended extras — implemented as specified

- R2 ✔ L42 `timeout 1500`. Consistent with driver: guard-ms 1200000 (flash_once.clj:30),
  tick-decision → `{:action :stop :exit 3 :reason :guard-timeout}` (flash_once.clj:374) →
  stop.nonzero → hs/close! (pi.dispose) → stop-bridge → System/exit 3 (:420); no
  addShutdownHook (grep=0). In-JVM guard wins with ~300s cleanup slack (observed dispose
  completes in ~1.7s), so the SIGTERM path that skips dispose is now truly last-resort.
- R3 ✔ L37 `flock -n "$ROOT/.gate-jvm.lock" -c true || fail ...` — same lock file/semantics as
  L42 (`-n` probe exits 0 today); placed before backgrounding (L37 < L42). Lock-wait can no
  longer masquerade as STALL.
- R1 ✔ L33 `ins=$(sg docker -c "docker exec ...") || fail "in-container probe failed"` —
  exec-route breakage now aborts. Control receipts: L36 `case "$ins" in *launch*) fail` asserts
  no launch receipt in control/. Reconciles the 2-vs-0 discrepancy: host mirror
  container-logs/trial-a1/control/ (bind of in-container /logs/agent/trial-a1/control) holds
  exactly the 2 Oct-28 files bridge-inventory.json + bridge-workers.json — matches
  MAIN-IDENTITY "controlDirEntries": "2"; neither name matches `*launch*`; DISSECT's "EMPTY" was
  stale. L22-23 bridge-owned children currently `[]` ✔.
- R4 NOT applied (L55 keeps dead `*finish*`/`*assessed*` branches) — harmless: grep -o captures
  only `"event":"..."` values and no such events exist (verified against
  flash-once.pre-live-20261030.jsonl: all 643 event values are classpath.ok/bridge.started/
  pi.call/pi.reconcile/pi.dispose/harbor.*/socket.submitted/tick/probe.*/done/stop.nonzero).
- R5 NOT applied (no FIRST-PROGRESS caveat line in the log) — documentation-only; exit 0 means
  "hooks fired ~t25s", not trial healthy. Restate in the operator's launch order.

## 3. Hazard-by-hazard

- `set -u` without `-e`: every command whose failure would matter is guarded (L15-L23 `||/&& fail`;
  L26/L28/L33/L37 `|| fail`; L40 `cd || fail`; L53 rc via `$?`; L59 `[ -n ] &&`). The deliberately
  unguarded ones (L18/19/48 greps in loops, L54 grep -o) are non-match-tolerant by design; with
  no `-e` a failing `&&` tail or empty for-list is inert (verified: empty `$(pgrep ...)` loop is
  a no-op; L47 initializes JPID, so no unbound-var exit).
- Quoting: L59 `"$JPID"` quoted; L53/L60 `$DRV` unquoted but always numeric from `$!`; all path
  vars quoted (`"$ROOT"`, `"$OBS"`, `$ins`/`$state` in cases quoted).
- Newline-less JSONL: driver `spit :append true` writes no newline — file is one line (proved:
  pre-live file = 1 line/102KB, last byte `}`). `grep -o` on newline-less and mid-append partial
  tails returns complete `"event":"..."` matches (empirically re-tested; `[a-z.:]*` covers all
  observed names incl. `stop.nonzero`); `2>/dev/null` covers the pre-creation window;
  `sort -u | tr '\n' ' '` flattens correctly into the case.
- `wait $DRV`: correct in both races — zombie: `kill -0` succeeds → detected next iteration,
  `wait` reaps true status (rc=7 in probe); already-reaped: `kill -0` fails, `wait $DRV` still
  returns the stored exit (rc=3 in probe) → `exit $rc` propagates the JVM's code (124 only if
  the outer timeout itself fired). Probes removed after use.
- Cannot docker-start twice: L26 is the ONLY docker mutation (no run/rm/restart anywhere),
  executed once, start-only. If re-entered with the container already running, `docker start`
  on a running container is a documented no-op (rc 0, StartedAt unchanged — no new sleep-7200
  window), and L18 (JVM alive)/L20 (jsonl non-empty) abort any real re-run before L26. The
  existing launch-preflight.log line "PRE-FLIGHT-FAIL: docker start failed" is from an 18:15
  attempt whose `sg docker` died in an agent sandbox — no mutation occurred (fail-safe).
- Case patterns: all matched against fixed-lowercase producer output (docker status, DIRTY-ANSWER
  echo, lowercase control filenames) — verified against actual producer formats; no case
  ambiguity in practice.
- Driver cross-check: bridge spawn = `sh -c "sg docker -c 'docker exec -i lain-trial-1 node
  /app/pi_bridge.mjs --serve /logs/agent/trial-config.json 2>> ROOT/logs/bridge-stderr.log'"`
  (flash_once.clj:116,135) — ROOT/logs exists as a directory (0B bridge-stderr.log present,
  append target OK). deps.edn paths absolute/candidate-first; driver `root` absolute (:24);
  logs/flash-once.jsonl is 0B fresh; stage/app/answer.txt absent; resource-status.json
  `reserve_ok: true`; model 18310 /health returns 200 NOW; logs/driver-trial-recovery20261030.log
  does not exist yet; .gate-jvm.lock free NOW.

## 4. Preconditions / residuals (non-blocking, for the operator's single run)

1. EXECUTOR CONTEXT (must-check immediately before running): from the SAME shell that will run
   the script, `sg docker -c 'docker ps -q' >/dev/null; echo $?` must be 0. `sg docker` fails in
   agent sandboxes (setgroups EPERM — see the 18:15 log line); a sandbox run aborts at L26
   BEFORE any mutation, so this can only cost a retry, not the gate. Re-verify L15/L17/L37
   equivalents (RAM, /health, flock -n) at run time; all pass at 18:4x now.
2. exit 0 at ~t=25s orphans the still-running driver by design (FIRST-PROGRESS); monitor
   logs/flash-once.jsonl + stop.nonzero, do not read exit 0 as trial success. Stall path fires
   at ~265s (sleep 25 + 24x10s), kills once (L59+L60), exit 99, no relaunch.
3. If the probe's `cat /app/task.txt` fails while `ls control` succeeds, rc=0 and task content
   goes unchecked (answer/control checks are pattern-based and still valid; total exec failure
   correctly fails). Consider asserting "3 4" in `ins` in a future revision — not required.
4. L48's last-match-wins is unambiguous ONLY because L42's flock excludes other gate JVMs; the
   editor JVM is excluded by pattern (live-verified). Do not run any absolute-path
   `load-file flash_once.clj` java inside ROOT during the window.

No new defect introduced by the patch was found; F1-F4 and extras R1-R3 are correctly
implemented, R4/R5 (documentation-grade) were knowingly not applied and are harmless.

VERDICT: SAFE-TO-RUN
