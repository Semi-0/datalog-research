# RECOVERY-PLAN — 2026-10-30 (post power-cycle, fresh focused context)

## State at resume (verified, not assumed)
- Supervisor fresh run of `drivers/test/run_w23.clj` from the real drivers cwd is IN FLIGHT
  (started 13:39, flock ROOT/.gate-jvm.lock, -Xmx2g):
  log `w23-current-test.log`, result `w23-current-test-result.json`. Use THIS failure evidence,
  not stale W23 status. No duplicate JVM while the lock is held.
- RAM guard watch.py pid 4214 active, reserve_ok=true (32.9GiB avail / >=20%); do not restart.
- Protected editor server running (pid 2389); main-session independence to be preserved.
- Model container: only gx10-flash-next-autoround restored; all others stay stopped.
- Candidate partial fix (declaration-root via io/resource, declaration-source expansion,
  load-result retention, declaration-failures, open! fail-fast) is in the working tree;
  91/648 suite was green on it. W23 contract suite (3 tests) is the live unknown.

## Work decomposition
1. [DELEGATED A] Finish W23 production-configuration contract tests —
   owner writes ONLY `drivers/test/**`. Uses fresh supervisor log first, then fix-run loop with
   absolute flock + heap<=3GiB, one JVM, from the real drivers cwd. Positive: submit ->
   assignments -> Harbor prepare -> one launch -> assessed via scripted newline-JSON-RPC fake
   bridge. Negative: broken/missing declaration fails explicitly BEFORE any external launch,
   real `:class` diagnostics. No observe-mode/override shortcuts; production config unchanged.
   Production seam changes are REQUESTS to coordinator, never edits to candidate/.
2. [DELEGATED B] Bounded read-only contract review of candidate harness, native effects,
   source loader, editor ownership (writes only
   `observations/recovery-20261030/contract-review-A2.md`). Checklist: no host eligibility
   recompute, 3 handler registrations, distinct per-intent receipts (not slot-label-only),
   zero external transport on load/open/observe/replay, claim-before-call, cancellation
   validation, replay/collision identity, uncertain-launch reconciliation without duplicate
   launch, framework grading + verified cleanup, editor reuses existing session/RPC attach with
   independent main lifetime/clocks. Names the exact missing live seam if any.
3. [COORDINATOR, after A+B] Coupled integration: review production-seam requests, run strict
   final gates (12 suites 91/648, driver 19/182 + W23, Node 9/9; absolute flock, 3GiB, one JVM,
   RAM>=20%, candidate-first absolute classpath; failed raw logs preserved).
4. [COORDINATOR] Drivers-cwd production-faithful propagation proof (load result checked, 3
   handlers registered, one submit -> assignments + prepare) BEFORE any real bridge/model launch.
5. [COORDINATOR, gated] Check claim/control ledger; resume MAIN environment; ONE same-AutoRound
   child via MAIN harness (/app/task.txt 3+4 -> /app/answer.txt 7), framework-owned
   harbor/assessed provenance, cleanup verified, receipts + hashes saved; editor untouched and
   independent. STOP-WATCH only at verified completion.

## Constraints
No commit/push/deploy/global-net/security/model/provider/KV changes. No replay of dead job IDs
(W18-W23), no killing PIDs from old files, no container restarts, guard stays up. Both workers:
qwen3.8-flash-next-autoround @ medium, soft 25min / grace 5 / max 30.
