# Queue audit: 23 October 2026

This is an inspected snapshot, not a live status page. No benchmark was restarted
as part of source synchronization.

## Confirmed stopping point

`gx10-qwen36-priority-1.service` exited with status 1. Its `status.json` says:

```
stage: stopped
cohort: qwen36-35b-a3b-nvfp4
task: order
error: Failure('Unresolved predecessor outcome: runtime_error')
```

Raw evidence remains under:

```
/home/lain/experiments/gx10-qwen36-priority-1/
  qwen36-35b-a3b-nvfp4/solo/order/attempt-1/
```

The attempt's `result.json` records:

| Evidence | Observed value |
| --- | --- |
| Independent evaluator | 4 tests, 17 assertions, zero failures/errors, exit 0 |
| Implementation correctness | true; logical outcome `passed` |
| Pi exit | 0 |
| Agent end and final response | both observed |
| Execution classification | `runtime_error` |
| Unresolved error | `Request was aborted` |
| Stream failures | one assistant response with stop reason `aborted` |
| Recovery events | empty |
| Resource stop / tool deadline / supervision stop | none recorded |
| Swap-in / swap-out delta | both zero |
| Verified cleanup | true in the recovery receipt |

`attempt/artifacts/anti-repeat.jsonl` contains a reasoning-repeat detection and
one `corrected` intervention. It contains no terminal `stopped` intervention.
These fields establish a classification gap after an intervention; they do not
prove which model/backend setting caused the repetitive reasoning itself.

## Why the queue stopped

`qwen38-nvfp4/follow_on.py::require_complete` deliberately checks
`execution_outcome`, not just `correct`. A passed implementation cannot override
unknown execution or cleanup failures.

`repetition_recovery.py::can_continue_model_stop` permits a specifically evidenced
terminal repetition stop after correction and verified cleanup. This attempt did
not have that terminal stop. Its nonterminal abort also remained unresolved in
stream recovery, despite the later normal ending, so neither continuation path
admitted Events. No model reload was used.

The safety gate is doing what it was configured to do. The missing case is a
**verified recovered response abort**. That needs a regression based on the raw
ordered Pi and anti-repeat events, followed by normalization of only the matched
abort. It must retain the abort in diagnostics and must reject unrelated errors,
unfinished streams, resource stops and uncertain cleanup. Removing the runtime
error gate wholesale would hide real failures.

The Clojure pilot includes a deterministic example of this policy; it is not a
deployed Python/Pi fix. This synchronization preserves the Python behavior and
the original results for review.

## Why the overall experiment has been hard to follow

Before this synchronization:

- The harness existed as untracked files on both hosts. Git did not own its
  updates, and the copies had diverged.
- The local repository HEAD was `2e2e26a6f6e5a1e4cb57ae4e69d10e569365f959`;
  the GX10 project checkout was detached at
  `85010635cf887cf5e3eba1179b5181a5c31ed389`.
- The actual Qwen3.6 controller ran from frozen harness 20, independently of the
  project checkout. The latest local model-capability changes matched most of
  the unscored harness-21 copy and had not changed harness 20.
- Older progress documents still described earlier queues as running.
- Correctness, workflow and execution status are separate fields. The queue
  follows execution status, while a summary may prominently display correctness.

These are separate issues: source/version drift obscures which code ran; the
unresolved-abort classification explains this specific queue stop; model
repetition remains a separate reliability observation. None of this attempt's
recorded evidence indicates a RAM-reserve stop or swap contamination.

## What synchronization establishes

The Python controllers, test harnesses, tool configuration and model descriptors
are now intended to be versioned together. Both project checkouts are synchronized
to the same controller commit, with source hashes checked separately. Old remote
untracked copies are archived before checkout, and frozen deployments/results
are preserved. The exact resulting commit and verification receipt are reported
when synchronization completes.

Existing local uncommitted editor/compiler changes remain local. A matching HEAD
is not a claim that those unrelated working-tree edits were copied to GX10.
