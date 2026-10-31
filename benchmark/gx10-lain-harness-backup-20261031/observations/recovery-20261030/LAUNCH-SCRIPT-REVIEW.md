# LAUNCH-SCRIPT-REVIEW — launch-main-20261030.sh (adversarial, read-only)

Date: 2026-10-30 ~18:1x local. Reviewer: read-only subagent. Nothing modified except this file.
Evidence sources: script + drivers/flash_once.clj read in full; live pgrep/`/proc/*/cmdline`
(read-only); `docker inspect` (read-only, via plain docker — see §6); prior-run event logs
logs/flash-once.pre-live-20261030.jsonl and logs/flash-once.attempt1.jsonl; drivers/deps.edn;
MAIN-IDENTITY.json; MAIN-BRINGUP-DISSECT.md.

VERDICT: **FIX-FIRST** — 4 must-fix items (F1–F4) before this one-shot script is run.
F1 is a live safety hazard (collateral kill of sibling agent runtimes); F2 guarantees the
run aborts *after* consuming the container start; F3 makes preflight fail spuriously *right
now*; F4 records a wrong identity pid in the audit spine.

---

## 1. Bash correctness

`set -u` (L5) without `-e`. Abort behavior: every preflight line is guarded by `|| fail` /
`&& fail` / `case ... fail`, so failures DO abort — with one exception (F-hardening-a, L33).
`bash -n` passes.

- **L30 `case "$state" in Up*` — WRONG (F2).** `docker inspect --format '{{.State.Status}} ...'`
  emits `running <ts>` (or `exited <ts>`), never `Up` ("Up" is `docker ps` decoration). Verified:
  `docker inspect lain-trial-1 --format '{{.State.Status}} {{.State.StartedAt}}'` →
  `exited 2026-10-29T18:15:19.25827535Z` today; after L26 `docker start` it returns
  `running 2026-10-30T...`. So a successful start hits `*) fail "container not Up: running ..."`
  — the script aborts **after** L26 has already started the container (consumes a fresh
  sleep-7200 StartedAt window; re-running is forbidden). Fail-safe w.r.t. the trial child
  (nothing launched), but it silently wastes the gated container start and looks like a
  policy-relevant "failure after the one mutation".
  FIX: `case "$state" in running*) ;; *) fail "container not running: $state";; esac`

- **L50 `if ! kill -0 $DRV ...; then wait $DRV; rc=$?; ... exit $rc` — correct.**
  DRV is our own child, so `wait` returns its true status (timeout passes the JVM's
  `System/exit` code through; 124 only if timeout itself fires). Zombie nuance: `kill -0`
  succeeds on an unreaped zombie child, which only delays early-exit detection to the next
  loop iteration — harmless. `exit $rc` under `set -u` is fine (rc assigned in same line).

- **tee/wait races: none material.** L43/L46/L55 `| tee -a` only appends to
  launch-preflight.log; the background job (L40-41) is never raced by them. First `grep`
  of the jsonl (L51) happens at t≈25s — after `sleep 25`; driver append is line-at-a-time
  `spit :append true` so partial tails are possible but `grep -o` is tolerant.

- **L56 `pkill -f "clojure.*flash_once"` — DANGEROUS COLLATERAL (F1).** See §1a.

- **L56 `pkill -TERM -P $DRV` scope — safe and correct.** $DRV is the `timeout` process;
  its only direct child is `flock`→(exec)→`clojure`→(exec)→java, i.e. exactly the driver
  JVM (verified shape in the sibling battery: flock 23765 → timeout 23766 → java 23767,
  exec chain, one child). Follow-up `kill $DRV` TERMs timeout itself (which forwards to its
  child). With F1 removed this is a clean "kill-once" of the intended tree.

### 1a. Collateral-kill analysis of the stall-path patterns (demonstrated live, read-only)

Editor backend (pid 22290, `/home/lain/.local/bin/java ... clojure.main -m
graph.lain-editor.protected-server`): its cmdline contains **zero** occurrences of "flash"
(relative classpath `. :editor/src:target/lain-editor:...`, all jars under ~/.m2; ROOT path
appears nowhere). `grep -c flash /proc/22290/cmdline` → **0**. It CANNOT match
`clojure.*flash_once` nor `java.*flash`. ✔ cannot be killed by this script.

Sibling battery JVM (flock .gate-jvm.lock; pids 23765/23766/23767 at review time): java
23767 likewise has no "flash" substring (uses `env -C`, relative `-cp`) → **0 matches**.
The timeout wrapper 23766 contains `.../lain-harness-flash-once-20261027/candidate` —
hyphenated "flash-once"; the regex `flash_once` needs an underscore → **no match**
(verified: `tr '\0' ' ' </proc/23766/cmdline | grep -Eo 'clojure.*flash'` → empty). ✔ the
clojure JVMs themselves are safe from the pattern.

**BUT**: `pkill/pgrep -f` scan ALL cmdlines, and long agent-task prompts embed both words.
Demonstrated right now:
```
$ pgrep -af "clojure.*flash_once"
23830 node .../pi-better-subagents/task-runtime.mjs ... <prompt: "...clojure JVMs...
      drivers/flash_once.clj...">        ← reviewer agent runtime
24673 /usr/bin/bwrap ... <same prompt>
```
and the sibling battery agent (pid 23108) prompt contains "every clojure command: cwd=ROOT"
followed by "(def bridge-stderr-log ...)" plus "drivers/flash_once.clj" → it also matches
`clojure.*flash_once`. **So at the 4-minute stall, L56 would SIGTERM the battery agent's
and this reviewer's node runtimes (and their bwrap wrappers)** — touching processes far
outside ROOT, and potentially detonating the agent that owns the JVM lock. Same for
`pkill -f "clojure.*flash_once|java.*flash"`: `flash` alone matches the hyphenated ROOT
name in ANY cmdline, so `java.*flash` will also hit any future java process launched with
an absolute ROOT path (e.g. `env -C .../lain-harness-flash-once-.../candidate java ...`).
The editor/battery JVMs escape only by accident of their relative-path cmdlines.

FIX (F1): delete the broad pkill; rely on the process-tree kill, which is exact:
```bash
pkill -TERM -P $DRV 2>/dev/null; kill $DRV 2>/dev/null
```

## 2. Cross-check vs -main in drivers/flash_once.clj

Event names actually logged (`(log "<name>" ...)` → `{"t":..,"event":"<name>",...}`; note
`spit :append true` writes WITHOUT trailing newline, so the jsonl may be one giant line —
`grep -o` handles that fine): `classpath.ok|classpath.mismatch`, `abort.memory`,
`abort.classpath`, `bridge.started` (L~216), `pi.call` (243), `pi.call.reply` (246),
`pi.reconcile`, `pi.dispose`, `harbor.prepare` (294), `harbor.grade`, `harbor.dispose`,
`socket.submitted` (350 — **this is the submit event**), `tick`, `probe.initial`,
`probe.tick`, `bridge.frame`, `done`, `stop.nonzero`, `fatal`.

- L51-52 FIRST-PROGRESS `case ... *pi.call*|*harbor.prepare*|*finish*|*assessed*`:
  `finish` and `assessed` are **dead branches** — those strings occur only as *values*
  (`"action":"finish"`, `"assessed":true` inside `tick`), never as an `"event":"..."` value,
  and the grep captures only event values. Harmless but misleading.
- Would a healthy run match within ~4 min? **Yes, ~t=16s after JVM start**, i.e. at the
  loop's first iteration (t≈25s). Measured from the last real live segment
  (logs/flash-once.pre-live-20261030.jsonl, first-seen offsets):
  `classpath.ok, bridge.started @t=0s → socket.submitted @1s → pi.call, pi.reconcile,
  harbor.prepare @16s → (stall) → stop.nonzero, pi.dispose, harbor.dispose @1201.7s`.
  Caveat: pi.call/harbor.prepare fire at 16s **even in the runs that then stall for 20 min**
  (both prior attempts show exactly this signature, MAIN-BRINGUP-DISSECT:16 — 301
  assignments:0 ticks). So `exit 0` from L52 means "hooks fired", not "trial healthy" — fine
  as a first-submit gate, but do not read the script's exit 0 as trial success.
- 20-min wall-clock guard **lives in the driver**: `(def guard-ms 1200000)`
  (flash_once.clj:30) enforced by `tick-decision` → `{:action :stop :exit 3 :reason
  :guard-timeout}` (flash_once.clj:374) → `stop.nonzero` log → `hs/close!` (pi.dispose) →
  `stop-bridge` → `System/exit 3`. Observed at t=1201.7s. So `timeout 1300` (L40) is a pure
  outer backstop with ~98s of cleanup slack — correct ordering.
- **timeout 1300 vs dispose**: SIGTERM would only land if the JVM itself wedges past 1300s
  (e.g. `hs/close!` blocking). flash_once.clj registers **no shutdown hooks** (grep
  addShutdownHook → rc=1), so that SIGTERM kills the JVM skipping verified cleanup
  (pi.dispose never sent; host-side bridge process sees stdin EOF and dies, container
  self-reaps at 7200s, child left reconcile-able by durable launch-effect-id — never
  relaunched, per contract). Acceptable residual; optional: `timeout 1500` to guarantee the
  in-JVM guard always wins (still ≪ sleep-7200).

## 3. Preflight soundness

- L20 `test -s "$ROOT/logs/flash-once.jsonl" && fail` — correct vs the driver's append-only
  `spit`; live file is 0B (rotated 15:26 to pre-live-20261030.jsonl). ✔
- L18-19 `pgrep -f "lain.flash-once"` / `pgrep -f "pi_bridge"` — **FALSE POSITIVE TODAY
  (F3)**, demonstrated: `pgrep -af "lain.flash-once"` → 23830 (node task-runtime whose
  argv-embedded prompt literally contains "ns lain.flash-once"; regex `.` matches the
  real dot) and 24673 (its bwrap); `pgrep -af "pi_bridge"` → 23108 (battery agent whose
  prompt contains "pi_bridge_test.mjs"). No real JVM/bridge runs (real java JVMs are only
  11193 wifi-watch and 22290 editor; both cmdlines shown clean above). The script dies at
  L18/L19 with "already running" while nothing is running — fail-safe, but it burns
  operator confidence and (worse, see F1) the same pattern philosophy returns at L56.
  FIX: restrict to executable scope, e.g.
  `for p in $(pgrep -x java); do tr '\0' ' ' </proc/$p/cmdline | grep -q "flash_once\.clj" && fail "a flash-once JVM is already running"; done`
  `for p in $(pgrep -x node); do tr '\0' ' ' </proc/$p/cmdline | grep -q "/app/pi_bridge\.mjs --serve" && fail "a pi_bridge process is already running"; done`
- L22-23 bridge-owned children: verified `{'session': 'lain-bridge-a1', 'children': []}` →
  check passes; python3 error ⇒ empty string ⇒ fail (fail-closed). ✔
- L26 start-only ✔ (no `restart`/`run`/`rm`; container exists, currently exited).
- **L33 (hardening-a)**: `ins=$(sg docker -c "docker exec ...")` has no `|| fail` — if the
  exec route breaks, `$ins` is empty, the DIRTY-ANSWER case passes vacuously, and an
  unverified container reaches the launch line. Also the control-dir count
  (`ls /logs/agent/trial-a1/control | wc -l`) is only logged, never asserted, while
  MAIN-IDENTITY.json records `"controlDirEntries": "2"` — stale control files exist and
  the script waves them through. FIX:
  `ins=$(...) || fail "in-container check failed"` and
  `case "$ins" in *" 0"*|*DIRTY-ANSWER*|*) ...`-style assert, or split the probe so the
  count is a separate `ctl=$(...) || fail` + `[ "$ctl" = 0 ] || fail "stale control files"`.
  (Decide explicitly whether the 2 stale entries are admissible; DISSECT §46 says control/
  was empty Oct 28 — the identity snapshot and the log disagree.)
- L16-17, L15 RAM/model/health checks: fail-closed, fine.
- Stale evidence: **no part of the script reads flash-once.pre-live-20261030.jsonl** — only
  MAIN-IDENTITY.json / MAIN-BRINGUP-DISSECT.md cite it as history. Keep it that way: that
  file mixes 2 live segments with ~3 W23 fake-hook scratch segments (DISSECT §46), so any
  future FIRST-PROGRESS-style grep must target only the fresh live jsonl (as L51 does).
- **L26/L28/L33 route caveat**: in this sandboxed agent shell `sg docker -c` fails
  (`setgroups: Operation not permitted`, rc=1) while plain `docker` works. Not a script bug
  if — as in prior attempts — the script is run from a normal host shell with docker group
  membership; running it from inside an unshared agent sandbox would fail at L26 *before*
  any mutation (fail-safe). Confirm the intended executor.

## 4. Identity capture (L42-46)

`DRV=$!` is the **timeout wrapper**, not the JVM. `JPID=$(pgrep -f "clojure.*flash_once|java.*flash" | head -1)` is unreliable three ways:
1. The wrapper's own cmdline contains `clojure ... (load-file "...flash_once.clj")` →
   DRV itself matches the first branch and, having the smallest fresh pid among intended
   matches, often wins `head -1` → JPID == DRV (the wrapper, not the JVM).
2. Prompt-contaminated agent runtimes (23830 above; also lower pid than anything spawned at
   launch) match `clojure.*flash_once` → **right now head -1 returns 23830 — a node agent
   process would be recorded as "driver-jvm-pid"**.
3. `java.*flash` is unanchored on "flash" → matches hyphenated ROOT in any java cmdline.
The clojure launcher facts check out (`-J-Xmx2g` → `-Xmx2g` right after the java binary,
`-M` consumed by the launcher; the JVM cmdline ends `clojure.main -e (load-file
".../drivers/flash_once.clj") -m lain.flash-once`, which legitimately matches branch 1) —
the pattern's anchor is fine for the JVM, but not exclusive to it. JPID is only echoed
(JPID is never used to kill — good), but launch-preflight.log is the incident spine, so
record the *derived* child identity instead (F4):
```bash
sleep 25
JPID=$(pgrep -P $DRV | head -1)   # timeout's direct child: flock-exec'd JVM (or flock if still lock-waiting)
echo "driver-jvm-pid ${JPID:-unknown} jvm-cmd=$(tr '\0' ' ' </proc/${JPID:-0}/cmdline 2>/dev/null | cut -c1-120) $(date -Is)" | tee -a "$OBS/launch-preflight.log"
```
launch-preflight.log currently does not exist; a run appends: `container: <state>`,
`in-container: <probe echo>`, `launch-at <ts>`, `driver-shell-pid <pid> <ts>`,
`driver-jvm-pid <pid> <ts>` — plus `PRE-FLIGHT-FAIL: ...` lines on abort.

Operational note (lock-wait masquerade): L40 holds `flock .gate-jvm.lock` — if the battery
still owns the lock at launch time, the driver blocks pre-exec, emits **zero events**, and
the script kills it at ~4min as "STALL" with the one-shot gate burned. Add a
`flock -n "$ROOT/.gate-jvm.lock" true 2>/dev/null || fail "JVM lock busy (battery running?)"`
preflight, or check `pgrep -f gate-jvm.lock` — distinguishable, cheap, fail-closed.

---

## VERDICT: FIX-FIRST

Required minimal patches (all inside this one file; no invariant violations found in the
launch/relaunch/container policy itself — one launch, no relaunch, start-only, no
out-of-ROOT writes, kill-once-and-exit(99), no blind 20-min wait):

- **F1 (safety, L56)** — delete `pkill -f "clojure.*flash_once" 2>/dev/null;`
  Keep only: `pkill -TERM -P $DRV 2>/dev/null; kill $DRV 2>/dev/null`
  (Rationale §1a: the pattern demonstrably matches agent runtime processes; collateral
  kill of the battery agent violates "must not touch anything outside ROOT".)
- **F2 (correctness, L30)** — `case "$state" in running*) ;; *) fail "container not
  running: $state";; esac` (docker inspect emits `running`, never `Up`; current code aborts
  AFTER the container-start mutation).
- **F3 (preflight, L18-19)** — replace cmdline-regex pgrep with per-`/proc` scans restricted
  to `pgrep -x java` / `pgrep -x node` (snippet §3). Current code aborts *today* on agent
  argv prompts with zero real JVMs running.
- **F4 (identity, L45)** — capture `JPID=$(pgrep -P $DRV | head -1)` (+ cmdline snippet) so
  launch-preflight.log records the real driver JVM, not the wrapper/agent (snippet §4).

Recommended (not blocking):
- R1 L33: add `|| fail "in-container check failed"`; decide and assert the control-dir
  emptiness question (MAIN-IDENTITY says 2 stale entries; DISSECT says empty — reconcile).
- R2 L40: `timeout 1500` (let in-JVM `guard-ms`/`tick-decision :guard-timeout` always win;
  SIGTERM at 1300 has no shutdown hooks and would skip verified dispose — §2).
- R3 preflight: `flock -n` probe so lock-wait is never misdiagnosed as first-tick STALL.
- R4 L52: drop the dead `*finish*`/`*assessed*` branches or grep values too
  (`grep -oE '"(event|reason|action)":"[a-z.:]*"'`), so a fast :finish exit isn't
  misreported as STALL-kill in a pathological fast run.
- R5 record the FIRST-PROGRESS caveat in launch-preflight.log: `pi.call@~16s` also occurs in
  20-min stall runs; exit 0 = hooks fired ≠ trial healthy.
