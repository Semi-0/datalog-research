# Bounded model reload recovery

User-authorized change, 2026-10-23: a terminal repetitive-reasoning stop may trigger a model reload. Existing corrective continuation inside pi-anti-repeat remains the first response. Each logical smoke/coding cell then permits at most one full model reload and fresh retry. A second terminal repetition stop exhausts recovery and stops the queue.

The controller composes the unchanged `suite.run_cell` lifecycle. It does not reload inside Pi or bypass admission. Every attempt gets a fresh model service, checkout, Pi process, agent directory, session history, caches and temporary directory. Failed candidates, patches, streams and evaluator output remain under `attempt-1`; the retry is `attempt-2`. The host experiment lock spans both attempts.

## Conditions

The failed attempt must have only aborted stream failures, an explicit anti-repeat `corrected` event followed by terminal `stopped` reasoning-repetition evidence, and execution outcome `runtime_error`. Arbitrary runtime errors, incomplete streams without repetition evidence, ordinary task failures, evaluation timeouts, swap activity and resource failures do not trigger reloads.

Before retry, require a durable completed result/checkpoint, no cleanup-failure marker, a final `transition_verified` lifecycle event, empty model inventory, no old owned processes, an unavailable endpoint and available RAM above reserve. The new lifecycle repeats cleanup, memory settlement, budget, artifact/configuration checks and preloading. An interruption or uncertain cleanup prevents retry.

The opt-in declaration is:

```json
"repetition_recovery": {"maximum_model_reloads": 1}
```

Other policies are rejected. Without this declaration the existing scheduling path is unchanged. The policy is in the manifest; the existing model artifact, 128K context capacity, temperature 1.0, thinking, MTP, tool profile, guards, independent evaluators and tool/request budgets remain unchanged. Reloading may clear runtime state; it does not establish or fix the underlying cause of degeneration.

## Evidence and timing

`recovery.json` records decisions and every attempt path before another lifecycle starts. The logical cell's final result points to the selected raw result. `durations`, `coding_wall_s` and inference records aggregate all attempts; other result fields describe the selected attempt, explicitly declared in `recovery.measurement_scope`. Total duration includes failed work, unloading, model loading and preloading. `recovery.completion_wall_s` is populated only after a correct, normally completed final execution. A recovered attempt is excluded from uninterrupted speed comparisons. Failed attempts are never overwritten or silently converted to passes.

## Deployment

- Frozen snapshot: `/home/lain/experiments/gx10-delegation-python-harness-18` (parent 17).
- Plan: `/home/lain/experiments/gx10-ornith-follow-on-plan-4.json`.
- Active output: `/home/lain/experiments/gx10-ornith-follow-on-5`.
- Unit: `gx10-ornith-follow-on-5.service`, invocation `45adeb17bb874d22aa779621403c7ec8`; launcher verified `Linger=yes`.
- Cohort: `ornith-nvfp4-mtp-reload`; smoke precedes Distinct, Ordering and Events.
- `repetition-reload-deployment.json` in the snapshot seals 296 benchmark files, the plan hash, validation results and the old smoke's eligibility/cleanup check.

A preliminary unit 4 exited before creating an attempt because its plan was not yet written: the validation helper initially lacked its import path. That helper was corrected, validation completed, and fresh unit/output 5 was launched. No inference was launched by unit 4.

```sh
systemctl --user status gx10-ornith-follow-on-5.service
cat /home/lain/experiments/gx10-ornith-follow-on-5/status.json
cat /home/lain/experiments/gx10-ornith-follow-on-5/ornith-nvfp4-mtp-reload/smoke/recovery.json
```

Validation before launch: local and GX10 unittest suites each executed 31 tests: 30 passed, one optional real-Pi payload test skipped. Fourteen focused recovery tests cover the reload bound, fresh lifecycle dispatch, preservation, timing aggregation, unsafe transition rejection, interruptions, unrelated failures and opt-in scheduling. The existing failed Ornith smoke was checked against the actual policy and cleanup verifier without loading a model. Live recovery evidence is recorded by the new run; launch alone is not a successful recovery claim.
