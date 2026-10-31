# BATTERY-V2 — re-gate on the tracer tree (2026-10-30)

ROOT = `/home/lain/experiments/lain-harness-flash-once-20261027`
Gate script: `observations/recovery-20261030/final-gate-20261030-v2.sh` (copy of
`final-gate-20261030.sh`; the original was NOT modified)
Run window: 2026-10-30T16:55:29Z … 17:02:55Z (UTC)
Summary log: `observations/recovery-20261030/final-battery-summary-20261030-v2.txt`

## VERDICT: **GREEN-ON-TRACER-TREE**

Full battery green on the CURRENT tree (candidate HEAD `c1ed279`), zero failures,
zero errors, no tree drift, **zero source/test edits by this gate**, no suite reruns needed.

One forensic correction to the tasking: 2 of the 4 prescribed grep marker strings do not
exist verbatim in the tree (they describe the right edits with the wrong wording), and the
4th marker (`:flush flush-on-write`) does not exist anywhere — the real socket working-tree
edit is different code. All 4 production edits themselves are present and are covered by
green suites. Details in §1.4. This does not change the verdict; it corrects the record.

---

## 1. Forensics (step 0, read-only)

### 1.1 HEAD / status

```
$ git -C ROOT/candidate rev-parse HEAD
c1ed279b095d87efc65c7fd5a60f6d34197444a7

$ git -C ROOT/candidate log --oneline -2
c1ed279 Add high-level boundary tracer for GX10
1596f7e candidate: active release backend c648858 tree (copy)      <- parent, as claimed
```

`git -C ROOT/candidate status --porcelain | head -15` (14 entries, unchanged before/after run):

```
 A editor/src/graph/lain_editor/harness_glue.clj
 M examples/lain/subagents/boundary.clj
 M examples/lain/subagents/harbor_extension.clj
 M examples/lain/subagents/harness_session.clj
 M examples/lain/subagents/pi_extension.clj
 M examples/lain/subagents/pi_transport.clj
 M examples/lain/subagents/primitives.clj
 M examples/lain/subagents/socket_extension.clj
 M test/propagators/compiler_2/runtime/session_extension_test.clj
 M test/propagators/compiler_2_harness_session_test.clj
 M test/propagators/compiler_2_pi_extension_test.clj
?? test/editor/
?? test/propagators/compiler_2_harbor_grading_contract_test.clj
?? test/propagators/compiler_2_pi_transport_test.clj
```

Commit `c1ed279` contents confirmed as described (10 files, +274/-1):
`propagators/semantic_trace/high_level.clj` (73 lines),
`propagators/compiler_2/runtime/operators/high_level_trace.clj` (33),
2-line registrations in `runtime/extensions/relationship_xr.clj`,
`runtime/lain/tracers.lain`, `experimental/visualization/extension.clj`,
`run_tests.clj` test lists, plus main.lain/README/2 test files.
It touches **no** `examples/lain/subagents/*` file → the uncommitted production edits survived it
(file mtimes: `harness_session.clj` 14:47, `boundary.clj` 14:22, `socket_extension.clj` 10-29 17:53;
tracer files 17:31).

Editor backend redeploy confirmed: `/home/lain/.local/share/lain-editor-deploy/releases/04831d9b3be346caa0650d14104d03bd`
exists, created 17:47 local; the running protected-server JVM is the editor backend (pid 22290).

### 1.2 Prescribed grep markers — as executed

| # | marker string | file | result |
|---|---|---|---|
| 1 | `no handler registered for routed effect kind` | `examples/lain/subagents/harness_session.clj` | **NO MATCH** (0 hits in that file, 0 in tree, 0 in git history) |
| 2 | `reused effect id with different payload` | `examples/lain/subagents/boundary.clj` | **NO MATCH** (0 hits in that file, 0 in tree, 0 in history) |
| 3 | `(def bridge-stderr-log "/home/lain` | `drivers/flash_once.clj` | **MATCH** — line 116 |
| 4 | `:flush flush-on-write` | `examples/lain/subagents/socket_extension.clj` | **NO MATCH** (0 hits in that file; string exists in no `.clj` anywhere in ROOT, none in git history) |

Marker search was widened to the whole ROOT tree (`grep -r`, excluding `observations/`, `.git/`)
and to every revision in the repo (`git grep -F <marker> $(git rev-list --all)` over all 367
revisions) — markers 1, 2, 4 exist nowhere as literal text, in the worktree or in history.
(The only hits are prose inside `observations/recovery-20261030/coordinator-sessions/*.jsonl`, i.e. the tasking text itself.)

### 1.3 Proof the driver edit is present (marker 3, verbatim)

```
drivers/flash_once.clj:116:(def bridge-stderr-log "/home/lain/experiments/lain-harness-flash-once-20261027/logs/bridge-stderr.log")
drivers/flash_once.clj:128:        stderr-log  (or stderr-log bridge-stderr-log)
```
Covered green by driver suite W19 (19/182), which contains the bridge-spawn-string test.

### 1.4 The other 3 production edits — present, with different wording than the tasking claims

**(a) `open!` registration assert — PRESENT** in `candidate/examples/lain/subagents/harness_session.clj`
(working-tree edit, lines 222-228 + 254-262; message text is
`"Harness effect handler was not registered"`, not `"no handler registered for routed effect kind"`):

```clojure
;; added lines from `git diff examples/lain/subagents/harness_session.clj`
+(def effect-routes
+  {:harness/pi-request {:boundary :pi :current :pi-intents}
+   :harness/harbor-request {:boundary :harbor :current :harbor-intents} ...
+        _ (doseq [wanted (keys effect-routes)]
+            (when-not (some (fn [[[_ kind]]] (= kind wanted))
+                            (:environment/handlers state))
+              (throw (ex-info "Harness effect handler was not registered"
+                              {:kind wanted
+                               :registered (vec (keys (:environment/handlers state)))}))))
```
File:line evidence — `harness_session.clj:257-262` (`grep -n "registered"` → lines 255, 260, 262).
Covered green by `propagators.compiler-2-harness-session-test` (5/62) and W23.

**(b) boundary reused-id guard — PRESENT** in `candidate/examples/lain/subagents/boundary.clj`
(working-tree edit; the payload comparison is exactly the "reused effect id with different payload"
check, message text `"Conflicting reused effect identity"`):

```clojure
;; git diff examples/lain/subagents/boundary.clj
-        (:observations prior)
+        (do
+          (when-not (= request (:request prior))
+            (throw (ex-info "Conflicting reused effect identity" {:id (:id request)})))
+          (:observations prior))
```
File:line evidence — `boundary.clj:44-45`. Covered green by
`propagators.compiler-2-harness-effect-boundary-test` (9/59).

**(c) socket edit — PRESENT but NOT "v3 framing"/flush-on-write.**
`git diff examples/lain/subagents/socket_extension.clj` (+59/-7) adds the native socket-write
intent effect: `current-write-intents`, `socket-write-kind` (`:harness/socket-write`),
`deliver-intent` (identity check `[kind intent-id]` vs `:boundary/id`, deferred receipt when the
transport is not armed), `socket-write-effect`, and the `socket:current-writes` /
`socket:declare-write` bindings. There is no `:flush` option in that file (`grep -c flush` → 0);
the actual stream flush is `(.flush writer)` in `socket_transport.clj:59`, which is committed and
unmodified (`git diff` for that file is empty). No file in the tree contains the string `v3`.
Covered green by `propagators.compiler-2-socket-extension-test` (10/85).

---

## 2. Gate script V2

Created `observations/recovery-20261030/final-gate-20261030-v2.sh`; `final-gate-20261030.sh` untouched.
Deltas vs the reference (documented in its header):

1. `HEAD_EXPECT=c1ed279b095d87efc65c7fd5a60f6d34197444a7` (was hardcoded 1596f7e), checked at
   baseline AND at end of run.
2. Fresh manifest baseline written to `stable-tree-manifest-20261030-v2.txt` (699 lines) before the
   run and an after-snapshot `stable-tree-manifest-20261030-v2-after.txt` (699 lines) after it;
   drift = `diff` of the two (`.cpcache/*` JVM cache churn excluded from hashing, all other
   modified/untracked candidate files + all `drivers/**.clj|.mjs|deps.edn` hashed).
3. Every clojure call: `flock -x $ROOT/.gate-jvm.lock timeout --foreground 900 env -C <dir> clojure`
   with `-J-Xmx3g` (integrated) / `-J-Xmx2g` (drivers), both ≤ the 3g ceiling, plus a resource guard
   that pauses 60 s while `resource-status.json` has `reserve_ok: false` (or MemAvailable < 20 %).
   Suite list, expectations and log conventions are byte-identical to the reference.

## 3. JVM discipline

| suite | cwd for clojure | command shape | wall time (UTC) |
|---|---|---|---|
| integrated 12-suite | `ROOT/candidate` | `flock … timeout --foreground 900 env -C candidate clojure -J-Xmx3g -Scp <path>:test:editor/test -M -e …` | 16:55:29 → 17:02:03 |
| driver w17 | `ROOT/drivers` | `flock … timeout --foreground 900 env -C drivers clojure -J-Xmx2g -M -e (load-file "test/run_w17.clj")` | 17:02:03 → 17:02:11 |
| driver w19 | `ROOT/drivers` | same, `run_w19.clj` | 17:02:11 → 17:02:18 |
| driver w23 | `ROOT/drivers` | same, `run_w23.clj` | 17:02:18 → 17:02:55 |
| node | `ROOT/candidate` | `timeout 180 node --test examples/lain/subagents/pi_bridge_test.mjs` (no lock) | 17:02:55 |

* One JVM at a time, always inside `flock /home/lain/experiments/lain-harness-flash-once-20261027/.gate-jvm.lock`;
  the lock was never bypassed. A sibling agent held/queued on the same lock during this window
  (its driver re-gate JVM was visible); no bypass, no concurrent JVM from this gate.
* `timeout 900` on every clojure call; no call came close to the cap.
* RAM guard never tripped: `reserve_ok: true` before/after, MemAvailable samples
  29 843 / 29 885 / 30 281 / 30 459 MB (~24 % of 124 544 MB, `a*5 >= t` satisfied); 0 `GUARD` lines in the logs.

## 4. Per-suite results (all first-run, no reruns)

```
integrated-12-suite: exit=0 :RESULT {:test 91, :pass 648, :fail 0, :error 0}
driver-w17:          exit=0 [Ran 7 tests containing 85 assertions.]
driver-w19:          exit=0 [Ran 19 tests containing 182 assertions.]
driver-w23:          exit=0 [Ran 3 tests containing 26 assertions.]
node-suite:          exit=0 [pass 9 fail 0]
```

Integrated per-suite breakdown (`logs-v2-integrated.log`, 12/12 suites loaded, 0 FAIL/ERROR lines):

| suite | :RESULT |
|---|---|
| compiler-2-harness-session-test | `{:test 5, :pass 62, :fail 0, :error 0}` |
| compiler-2-harness-effect-boundary-test | `{:test 9, :pass 59, :fail 0, :error 0}` |
| compiler-2-subagent-harness-test | `{:test 3, :pass 36, :fail 0, :error 0}` |
| compiler-2-harness-primitives-test | `{:test 4, :pass 23, :fail 0, :error 0}` |
| compiler-2-pi-extension-test | `{:test 6, :pass 60, :fail 0, :error 0}` |
| compiler-2-pi-transport-test | `{:test 4, :pass 56, :fail 0, :error 0}` |
| compiler-2-harbor-extension-test | `{:test 7, :pass 41, :fail 0, :error 0}` |
| compiler-2-harbor-grading-contract-test | `{:test 4, :pass 35, :fail 0, :error 0}` |
| compiler-2-socket-extension-test | `{:test 10, :pass 85, :fail 0, :error 0}` |
| compiler-2.runtime.session-extension-test | `{:test 20, :pass 65, :fail 0, :error 0}` |
| compiler-2.runtime.functional-network-effects-test | `{:test 8, :pass 33, :fail 0, :error 0}` |
| editor.harness-glue-attach-detach-test | `{:test 11, :pass 93, :fail 0, :error 0}` |
| **sum** | **`{:test 91, :pass 648, :fail 0, :error 0}`** = expectation |

Driver runner tails (`:W17/W19/W23-TESTS-PASS`, `# clojure exit 0` in each log):

```
w17: Ran 7 tests containing 85 assertions.  / 0 failures, 0 errors.  / {:test 7, :pass 85, :fail 0, :error 0}
w19: Ran 19 tests containing 182 assertions. / 0 failures, 0 errors. / {:test 19, :pass 182, :fail 0, :error 0}
w23: Ran 3 tests containing 26 assertions.  / 0 failures, 0 errors.  / {:test 3, :pass 26, :fail 0, :error 0}
```

Node: `# tests 9 / # pass 9 / # fail 0` (`logs-v2-node.log`).

Reruns: none required — every gate was green on its first execution, so step 4's
single-rerun clause was never invoked, and nothing was BLOCKED.

## 5. Tree drift check

```
baseline HEAD : c1ed279b095d87efc65c7fd5a60f6d34197444a7   (== expected)
final HEAD    : c1ed279b095d87efc65c7fd5a60f6d34197444a7   (unchanged during run)
manifest before: stable-tree-manifest-20261030-v2.txt        699 lines, 2026-10-30T16:55:29Z
manifest after : stable-tree-manifest-20261030-v2-after.txt  699 lines, 2026-10-30T17:02:55Z
diff           : logs-v2-drift.diff -> 0 bytes  => tree-drift: none
```

`git status --porcelain` is identical before and after (14 entries, §1.1), i.e. the uncommitted
production edits are byte-identical after the battery and this gate changed nothing outside
`observations/recovery-20261030/`.

## 6. Files written by this gate (nothing else)

```
observations/recovery-20261030/final-gate-20261030-v2.sh
observations/recovery-20261030/stable-tree-manifest-20261030-v2.txt
observations/recovery-20261030/stable-tree-manifest-20261030-v2-after.txt
observations/recovery-20261030/logs-v2-integrated.log
observations/recovery-20261030/logs-v2-driver-w17.log
observations/recovery-20261030/logs-v2-driver-w19.log
observations/recovery-20261030/logs-v2-driver-w23.log
observations/recovery-20261030/logs-v2-node.log
observations/recovery-20261030/logs-v2-runner.log
observations/recovery-20261030/logs-v2-drift.diff
observations/recovery-20261030/final-battery-summary-20261030-v2.txt
observations/recovery-20261030/BATTERY-V2.md
```

## 7. Verdict

**GREEN-ON-TRACER-TREE** — integrated 91 tests / 648 assertions / 0 fail / 0 error,
W17 7, W19 19/182, W23 3/26, node 9/0, no tree drift, HEAD = `c1ed279` (tracer commit on
parent `1596f7e`) with the harness production edits intact in the working tree.

Follow-up for the owner (documentation hygiene, not a blocker): the marker strings for edits
(a), (b), (c) in the tasking are stale/wrong — replace them in the runbook with
`"Harness effect handler was not registered"` (harness_session.clj:260),
`"Conflicting reused effect identity"` (boundary.clj:45), and
`socket-write-kind`/`deliver-intent` (socket_extension.clj) as the socket-edit marker;
`:flush flush-on-write` is not a property of this tree and should be dropped from the checklist.
