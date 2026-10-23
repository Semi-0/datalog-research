# GX10 benchmark migration and completion plan

Status: local migration and GX10 deployment complete; Flash context scan complete; gated coding/benchmark queue running. Updated 2026-10-22.

The active continuation is documented in [the queue runbook](../benchmark/gx10-context/QUEUE.md). Flash's retrieval scan selected 128K after all three seeds passed. The deployed queue passed 75 deterministic checks on GX10 with no skips, including an actual Pi-to-private-Gortex workspace query and private-daemon cleanup. It then entered the full Pi smoke at 128K. The queue requires Flash's three coding tasks to pass before live validation and the fresh four-configuration matrix. These are current stage observations, not completed benchmark results.

## Scope and order

Finish the benchmark migration into datalog research, the Flash context pilot, and the coding benchmark. Defer the Lain delegation contract and its implementation until these deliverables are complete. The existing orchestration discussion remains in [lain-top-down-agent-orchestration.md](lain-top-down-agent-orchestration.md).

Later user instructions supersede the original unlimited coding duration: each agent has a 1,200-second deadline; shell/test commands and evaluation have a 120-second limit. Prompts should request `timeout --signal=TERM --kill-after=5s 110s` for tests. The harness enforces the outer limits independently.

## 1. Preserve and migrate

- Preserve previous exploratory runs and raw results unchanged. Transition-dependent skips are inconclusive; unloading is a suspected cause, not a demonstrated diagnosis.
- Make datalog research the canonical harness repository. Preserve the relative `benchmark/gx10-context`, required `benchmark/ornith-nvfp4-mtp` helpers, and supporting `scripts/` layout. Inventory imports, subprocess entry points, fixtures, prompts, model configuration and launch scripts before moving them.
- Record source and destination revisions, file hashes, evaluator hashes, model artifact identities, runtime versions and migration receipts. Keep model weights, private credentials, caches and full remote run directories out of Git. Keep the candidate checkout revision separate from the harness revision.
- Leave a migration pointer in KIROSHI after verifying the replacement. Do not maintain two active harness copies. Preserve unrelated edits; do not push without authorization.
- Deploy the canonical harness to GX10 using a pinned artifact or Git bundle. Do not edit Python files used by a running controller or worker.

## 2. Finish runtime integration and deterministic verification

- Finish the isolated, pinned pi-vigilant and pi-gortex profile. Verify the required Node and Gortex versions, tool registration, effective settings and cleanup. Candidate graph access must contain only the fresh candidate checkout, never independent evaluators or other attempts.
- Bound output-limit continuation and transient stream retries; retain raw incidents and distinguish recovery from a clean run. Persistent incomplete streams and exhausted continuation budgets remain explicit failures.
- Fix context continuation so inherited results referenced by `context-results.json` remain discoverable across multiple resumptions. Verify referenced artifacts and hashes; never overwrite raw interrupted records or trust an embedded result without evidence.
- Re-run lifecycle, isolation, evaluator, provider-wire, deadline and continuation tests from the migrated location. Keep unchanged evaluator assertion counts: distinct 14, order 17, events 42.
- Exercise all 16 ordered configuration transitions, including same-model transitions. Verify delayed unload, lingering owned processes, delayed RAM recovery, API/startup failures, reserve breaches and cleanup failures. Preserve an unrelated sentinel process.

## 3. Flash context pilot first

Continue verified context evidence where compatible. Probe 32K, 64K, 128K and 256K, with three seeds and 8K boundary refinement; retain the configured lower-context fallback. Record actual token occupancy, available RAM, swap deltas and explicit outcomes. The configured ceiling is not a claim that the machine supports it.

Select the largest context with successful protocol and timing evidence for every required seed. Distinguish a conservative budget decline from an observed resource failure. Before coding, run the full Pi smoke session at the selected context with both plugins: read, edit, execute an unrelated Clojure test, autonomous search/fetch and thinking. Include plugin memory in the guard. Synthetic context retrieval alone does not establish that an agent task fits.

## 4. Pilot and scored benchmark

First run Flash on all three unchanged tasks with fresh checkouts, agent directories, histories, caches, temporary directories and owned services. A pilot outcome must distinguish implementation correctness, agent workflow and timing validity. Full success requires evaluator correctness and workflow success; timing contamination excludes speed comparisons.

Only after runtime and lifecycle gates pass, execute a fresh protocol-versioned matrix: three tasks by four configurations, one attempt per cell. Preserve the original fixed 32K coding comparison separately from the Flash pilot using its measured context. Keep thinking, sampling, autonomous web access and independent evaluators unchanged unless a documented protocol revision is required. The selected pilot context must not silently change the historical matrix definition.

Every cell uses the same standalone lifecycle: cleanup, verify unloaded, clean budget check, load, configuration verification, preload, execute, evaluate, durable result, cleanup. No next model loads after uncertain cleanup. Hold a host-wide experiment lock. Require ten stable unloaded seconds within 256 MiB, compare the previous clean baseline, investigate a shortfall over 2 GiB, and stop scheduling after a 180-second transition failure. Enforce reserve `max(16 GiB, 20% of physical RAM)` at 200 ms intervals. Never kill unrelated workloads or clear OS caches.

Use explicit outcomes: passed, task_failed, setup_declined, transition_failed, runtime_error, resource_stopped, nonterminating and interrupted. Retain assertion/failure/error counts and raw output. Zero assertions or exit zero alone does not establish success. Preserve structural nontermination evidence and never rerun a recognized nonterminating own test. Warn and capture diagnostics after ten minutes without progress; elapsed time alone does not prove nontermination.

## 5. Completion evidence

Download compact reports and raw-result references for local inspection. Publish commands, effective configuration, migration receipts, validation results and explicit outcomes for all 12 coding cells. Report setup/loading/preload/execution/evaluation/cleanup/transition durations separately. Exclude setup declines from correctness denominators and censor interrupted/nonterminating durations. Do not calculate speed medians from one attempt. Keep previous exploratory results separate.

## Migration progress and verified stopping points

On 2026-10-22, GX10 reported `gx10-v4-flash-pilot-2.service` with MainPID 0 and inactive execution (`ActiveState=failed`, `SubState=failed`). Its `gortex-handoff.json` records that the controller was paused while the active 64K seed-43 worker finished, then stopped. It references the durable worker result at `context/65536-43/attempt/result.json`. A failed controller unit is not itself a failed context result; validate the durable worker record before adopting it.

The user explicitly authorized a Clojure migration script after native cross-workspace reads continued to fail. The local transfer is complete: 64 files (288,510 bytes), each verified with SHA-256, with originals preserved in KIROSHI. The receipt is `benchmark/gx10-migration-manifest.edn`. Copied source is now accessible through this repository's native Gortex view. Results, caches, model weights and private files were excluded. The initial local transfer is complete. Subsequent destination changes are recorded separately from this baseline receipt; the KIROSHI copy is preserved for provenance. Plugin integration and the completed context/coding benchmark remain pending.

Run the script from the destination repository:

```sh
# Inventory and conflict checks only; this is the default.
clojure -M scripts/migrate_gx10_benchmark.clj
# Copy missing files and verify every selected file; never overwrite differing files.
clojure -M scripts/migrate_gx10_benchmark.clj --apply
```

Optional `--source DIRECTORY` and `--destination DIRECTORY` select explicit roots. The script rejects overlapping trees and symbolic links. Repeating it against unchanged trees is safe; after destination development begins, a conflict is expected and protects the new work. Do not use the script to synchronize two independently edited copies.

Verification observed on 2026-10-22: five disposable migration safety tests passed (`python3 scripts/test_migrate_gx10_benchmark.py`). The migrated harness validator completed successfully: 53 context/lifecycle/evaluator/runtime tests with 11 platform/runtime skips, and eight worker/nontermination tests with two platform skips. Raw validation evidence is at `/tmp/gx10-migration-validation-20261022` on this Mac. These Mac results do not substitute for Linux and real-Pi verification.

Subsequent verification and deployment:

- Fixed context recovery across repeated handoffs: recover local cells and verified references from `context-results.json`; reject changed recovered evidence, mismatched cell identities and ambiguous duplicate completed attempts. Four new regression tests cover this behavior and the context-only gate.
- Added `pilot.py --context-only`, which completes context selection and returns before Pi smoke or coding. This permits useful context work while plugin integration is pending.
- Local validation after the continuation fix passed: 57 harness tests and eight worker tests, with 13 platform/runtime skips. The migrated deployment then passed all 65 checks on GX10 with no skips, including actual Pi wire tests under Node 24. Raw results were downloaded to `benchmark/gx10-context/results/gx10-migrated-deterministic-1/` (ignored runtime evidence).
- Deployed 69 files to `/home/lain/projects/datalog-research` after preflighting every destination for conflicts and verifying every copied SHA-256. Archive SHA-256: `377300c9e671bd46c2cd08200654b6c52dc7b994370e70c8a1589863465a8c5f`. Remote receipt: `/home/lain/experiments/gx10-migration-deployment-20261022.json`. This is a verified working-tree artifact, not a claim of a committed or pushed revision.
- Started `gx10-datalog-flash-context-1.service` using `scripts/run-background.sh`, with `Linger=yes` verified, Node 24 on PATH, output `/home/lain/experiments/gx10-datalog-flash-context-1`, deterministic proof `/home/lain/experiments/gx10-migrated-deterministic-1/deterministic.json`, `--context-only`, and continuation from `gx10-v4-flash-pilot-2`.
- Observed successful recovery of five completed probes: all three 32K seeds and 64K seeds 42/43. The next scheduled probe was 64K seed 44. This is a progress snapshot, not a completed context capacity result. Inspect `status.json`, `context-results.json`, `selected-context.json` and any `failure.json` for current outcomes.
- Keep the deployed Python snapshot unchanged while that service runs. Full Pi smoke and coding remain gated on completing pi-gortex integration and validating the selected context. No delegation contract has been implemented.
