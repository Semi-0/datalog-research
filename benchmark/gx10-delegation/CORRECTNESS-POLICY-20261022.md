# Correctness-based coding outcomes

User-approved policy: a coding task passes when the unchanged independent evaluator passes its expected nonzero assertion count. Own tests, explicit tool usage, normal completion, and file-change compliance remain separate workflow evidence. Execution failures, incomplete streams, interruptions, swap, and resource stops remain recorded and can invalidate speed comparisons even when a saved implementation passes.

`classify` records `pass_policy: independent-correctness-v1`, `correct`, `workflow_pass`, `protocol_pass`, and the prior execution classification as `execution_outcome`. Startup admission, RAM monitoring, timeouts for commands/evaluation, sandbox enforcement, and cleanup gates remain enforced.

Post-run Gortex/tooling verification failures are captured as workflow diagnostics and no longer prevent independent evaluation. Unexpected infrastructure exceptions still propagate; unevaluated correctness is null.

## Same standard for orchestration

The final integrated implementation uses the same `independent-correctness-v1` policy. Worker test failures are retained for Flash to repair during integration; integrated own-test failures and missing tool/reviewer checks remain workflow diagnostics. Independent evaluator failure or zero assertions cannot pass. A later review or cleanup failure cannot erase an already measured correctness result; it remains a separate failure, invalidates timing, and stops scheduling when cleanup or execution is uncertain. Owned-path and sandbox boundaries remain enforced.

## Continuation

The old queue 5 controllers were paused. The active Ordering attempt was interrupted and its patch/diagnostics retained; its attempt and backend service groups were cleaned up. Completed Distinct evidence remains unchanged.

The new solo controller accepts `GX10_SOLO_CONTINUE_FROM` pointing to the previous solo directory. It retains completed records and their provenance, verifies any supplemental correctness assessment, and schedules interrupted cells into fresh directories. Distinct is retained as passed from its separate independent result: 3 tests, 14 assertions, zero failures/errors. Its original workflow rejection remains available and its timing stays ineligible for speed comparisons.

Deployment: `gx10-delegation-python-harness-10`, based on harness 9 plus the orchestration policy and validation-release checkpoint fix. New queue: `gx10-delegation-python-queue-7`; context tail: `gx10-nvfp4-context-tail-4`. Queue 6 stopped at the predecessor validation checkpoint before any model admission. Order remains NVFP4 solo, Flash/NVFP4 orchestration, then NVFP4 context continuation.

Validation evidence is stored on GX10 under `/home/lain/experiments/gx10-correctness-policy-validation*.json`. The opt-in real Pi regression deliberately omits the post-run Lens workflow check and requires the actual runner to execute the unchanged evaluator, produce 14 passing assertions, record workflow failure, and return a correctness pass. Model responses in that integration fixture are scripted; it validates harness behavior, not model quality.
