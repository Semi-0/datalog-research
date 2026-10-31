# DRIVER RE-GATE — after bridge-stderr-log production patch

Date: 2026-10-30T16:37Z–16:43Z (UTC)
Trigger: `drivers/flash_once.clj:116` `def bridge-stderr-log` changed
`"/logs/bridge-stderr.log"` -> `"/home/lain/experiments/lain-harness-flash-once-20261027/logs/bridge-stderr.log"`
(host `/logs` does not exist: verified `ls -ld /logs` = No such file or directory, and a host `sh -c 'true 2>> /logs/bridge-stderr.log'` exits 1/2 while the new path exits 0).
`drivers/test/w19_classpath_test.clj:171` fragment expectation updated to match (pre-existing edit, not mine).
Scope: verify nothing else broke.

## Patch state as found (read-only confirmation)

| file | mtime (UTC) | content |
|---|---|---|
| `drivers/flash_once.clj:116` | 2026-10-30 ~16:34:54Z | `(def bridge-stderr-log "/home/lain/.../logs/bridge-stderr.log")` |
| `drivers/test/w19_classpath_test.clj:171` | 2026-10-30 ~16:34:54Z | `"2>> /home/lain/.../logs/bridge-stderr.log"` |

Both predate this session (session start ~16:36Z). **I made ZERO file edits** — no source file, no test file
(mtime of every file under `drivers/test/` is unchanged; step 3 was not needed, see below).

## JVM discipline

5 clojure JVMs total, **strictly one at a time**, sequentially, never overlapping. Each command was
`cd ROOT/drivers && flock -x ROOT/.gate-jvm.lock timeout 600 clojure -J-Xmx2g -M -e "(load-file ...)"`
— cwd `ROOT/drivers`, `-J-Xmx2g`, `flock` on `ROOT/.gate-jvm.lock`, `timeout 600`, invocation style identical to
`final-gate-20261030.sh` / `logs-final-driver-wNN-20261030.log`. MemAvailable 29.5–29.9 GB (RAM guard satisfied).
No leftover gate JVMs after the run. Pre-existing unrelated host JVMs (`gx10-wifi-watch`) were not touched.

## Results

| # | gate | exact result line | exit | log |
|---|---|---|---|---|
| 1 | compile check `flash_once.clj` | `#'lain.flash-once/-main` (no exception, no eval error) | **0** | `observations/recovery-20261030/logs-regate-compile.log` |
| 2 | `test/run_w17.clj` | `Ran 7 tests containing 85 assertions.` / `0 failures, 0 errors.` / `:summary {:test 7, :pass 85, :fail 0, :error 0, :type :summary}` / `:W17-TESTS-PASS` | **0** | `.../logs-regate-driver-w17.log` |
| 3 | `test/run_w19.clj` | `Ran 19 tests containing 182 assertions.` / `0 failures, 0 errors.` / `:summary {:test 19, :pass 182, :fail 0, :error 0, :type :summary}` / `:W19-TESTS-PASS` | **0** | `.../logs-regate-driver-w19.log` |
| 4 | `test/run_w23.clj` | `Ran 3 tests containing 26 assertions.` / `0 failures, 0 errors.` / `:summary {:test 3, :pass 26, :fail 0, :error 0, :type :summary}` / `:W23-TESTS-PASS` | **0** | `.../logs-regate-driver-w23.log` |
| 4b | `test/run_w23.clj` re-run (see note E) | `Ran 3 tests containing 26 assertions.` / `0 failures, 0 errors.` / `:W23-TESTS-PASS` | **0** | `.../logs-regate-driver-w23-rerun.log` |

Every total matches the expected baseline exactly (7 / 19+182 / 3+26) and matches
`logs-final-driver-wNN-20261030.log` byte-for-byte in the summary lines. The patched bridge-spawn-string
test (`bridge-spawn-config-describes-the-serve-process`, inside the 19) is green.

**Edits made under step 3: NONE.** No suite failed, so no expectation was changed anywhere.

## Cross-checks for the changed path string

- **A. No other live consumer of the old string.** `grep -rn bridge-stderr` over ROOT yields exactly three hits:
  `drivers/flash_once.clj:116` and `drivers/test/w19_classpath_test.clj:171` (both on the new absolute path), plus
  `drivers/SUPERSEDED_run_trial.clj.bak:36` (old string). The `.bak` is dead code: wrong extension so it is not on
  the `:paths` classpath, no `load-file`/require references it anywhere in `drivers/`, and
  `final-gate-20261030.sh:28` explicitly `-path '*/SUPERSEDED*' -prune`s it from the tree manifest. Left untouched
  (outside my authorised edit set). Nothing under `candidate/` or `stage/` references either path.
- **B. The sibling `/logs/agent/trial-config.json` was correctly NOT changed.** That string is an argument to
  `node` passed *inside* the container by `docker exec`, so it resolves in the **container** namespace
  (`MAIN-BRINGUP-DISSECT.md:22-24`: bind mount `Source ROOT/container-logs -> Destination /logs/agent`), whereas the
  `2>> <stderr-log>` redirect in `:shell` is evaluated by the **host** `sh -c`. So only the stderr log needed a host
  path. W19 continues to assert the container path and passes — no change warranted.
- **C. New target is usable.** `ROOT/logs/` exists and is writable; `ROOT/logs/bridge-stderr.log` present, 0 bytes,
  unchanged by this session (verified before/after).
- **D. `sg docker` route check: INCONCLUSIVE from my shell.** `sg docker -c "true 2>> <path>"` returns 1 for BOTH
  paths with `setgroups: Operation not permitted` / `setgid: Invalid argument` — my commands run inside a bubblewrap
  sandbox that blocks setgid, so this neither confirms nor refutes the fix end-to-end. It is NOT a new failure; do
  not read exit 1 as a driver defect. Owner should re-run the one-liner un-sandboxed before launch:
  `sg docker -c "true 2>> /home/lain/experiments/lain-harness-flash-once-20261027/logs/bridge-stderr.log"; echo $?`
  (expect 0). The host-side half is confirmed: plain redirect to the new path exits 0, to `/logs/...` exits 1.

## Note E — external event during the gate (not a driver failure)

At **16:38:09Z**, coinciding with the first W23 run, host oneshot unit `gx10-lain-pull.service`
("Run local CI and deploy Lain", `ExecStart=.../lain-editor-deploy/driver.py poll`) was started; systemd reloaded and
restarted the whole Lain stack — `gx10-lain-backend` main process exited status=143 (SIGTERM from systemd stop), plus
`gx10-lain-frontend` and `gx10-lain-gateway`; backend back up at 16:38:22Z (`Protected Lain runtime:
http://127.0.0.1:4785`). This is external to the gate: no driver or test code calls `systemctl`/`pkill`/signals —
the only destroy call is `destroyForcibly` on the driver's OWN bridge Process (`flash_once.clj:163`), and the suites
use injected fake `Process` objects. Confirmed harmless: W23 has no HTTP/`ProcessBuilder`/backend dependency, and a
clean re-run after the restart (4b, 16:43:20Z) reproduced 3 tests / 26 assertions / 0F/0E / exit 0 identically.
Ports 4785 and 4782 are LISTEN again; no OOM kill in journal or kernel log.

## Note F — parallel activity inside ROOT during the gate (verified harmless, but the owner must know)

The re-gate did **not** run on a quiet tree; a concurrent actor (coordinator/owner + the host CI pipeline) was
active in ROOT while my JVMs ran:

- `candidate` HEAD moved **1596f7e -> c1ed279** ("Add high-level boundary tracer for GX10"). Timestamps: working-tree
  files of that commit at 16:31:37Z, `.git/HEAD` 16:31:06Z, `.git/index`+pack 16:37:06Z — all **before** my first JVM
  run (16:37:19Z). `find candidate -type f -not -path '*/.git/*' -newermt <16:37:19Z>` is **empty**: the working tree
  never changed during any of my 5 JVM windows, so the results above are internally consistent.
- The harness code the driver actually loads is **unchanged** — sha256/md5 byte-identical to both the 15:11Z
  `stable-tree-manifest-20261030.txt` and the 16:36:52Z `tree-recovery.txt`:
  `harness_session.clj` `8e5289bb…` / md5 `dba398a5…`; `harness_glue.clj` `dc8a24da…` / md5 `14236d63…`.
  So this gate exercised the locked harness code, not a moving target.
- A recovery script is executing in ROOT (`python3 …/observations/recovery-20261030/…`, pid 4214) and wrote
  `MAIN-IDENTITY.json`, `prelaunch-hashes-20261030.txt`, `tree-recovery.txt`, `resource-status.json` during my run.
  Those are not mine; `logs-regate-*` + this `DRIVER-REGATE.md` are my only writes.
- Protected paths confirmed untouched: `logs/flash-once.jsonl` still 0 B, mtime 15:26:06Z; nothing under
  `stage/` or `container-logs/` modified.

## Verdict

# READY-FOR-MAIN-BRINGUP

The `bridge-stderr-log` patch is isolated and clean: compile check resolves `#'lain.flash-once/-main` with no
exception, and all three driver suites reproduce their exact baseline counts with 0 failures / 0 errors and exit 0
(5/5 green JVM runs, serialized under the lock). Zero test expectations needed changing; the only stale old-path
string is in dead `SUPERSEDED_run_trial.clj.bak`, which no runner loads.

Three pre-flight items for the owner (none is a contract failure, none blocks this gate):
1. Re-verify the `sg docker` spawn route **un-sandboxed** (item D) — the only part of the fix I could not execute here.
2. Quiesce `gx10-lain-pull.service` / `.timer` (oneshot CI+deploy, `OnUnitInactiveSec=60`) before launching `-main`:
   it fires on its own and SIGTERMs the editor backend, which mid-run would kill the harness backend (event E).
3. Freeze the tree and the parallel coordinator for the bring-up run: `candidate` HEAD moved (1596f7e -> c1ed279)
   and stack restarts happened during this very gate (events E, F). Harness code hashes are still identical to the
   locked manifest, so these results stand — **but if `harness_session.clj` / `harness_glue.clj` / `flash_once.clj`
   change again, this re-gate must be repeated before launch.**
