# Fourth test: bounded delegation on GX10

This is a separate, unscored orchestration pilot after the protocol-6 twelve-cell coding matrix and context scan. Existing results, evaluators, active manifests, and model settings remain frozen. This test measures the combined system; it does not add a fourth solo task to correctness denominators.

## Contract

Flash plans a small four-module Clojure event-batch implementation. The host validates exactly four fixed worker assignments and disjoint file ownership. Flash's plan, task prompt, visible response, usage and tool activity are durable before its model is unloaded. The integrator starts a fresh Pi session with that saved plan and worker handoffs; this pilot does not claim to resume the original native Pi session. The existing backend lifecycle verifies process exit, model inventory, memory settlement, and reserve before NVFP4 loads. One NVFP4 server serves four workers at 128K maximum context, concurrency four; weights are shared. A two-worker unscored admission/isolation smoke must pass first.

Each worker has a private checkout, Pi configuration, temporary files and sandbox process tree. Install @erichll/pi-sandbox 0.28.0 in an experiment-local prefix, not the active benchmark's extension discovery. The pilot uses that pinned package's ProcessBackedSubagentManager embedding interface, cap four and depth one. Pin the npm dependency lock and all installed source hashes; reject drift. The embedding seam is version-specific and must pass a real compatibility smoke.

Worker 1 owns event validation/normalization; worker 2 owns event reduction; worker 3 owns deterministic dependency ordering; worker 4 owns batch composition and integration tests. Worker 4 may observe missing implementations in the untouched base; final independent evaluation runs only after patches are assembled. This limitation is explicit, not a passing test claim. A separate verification wave can be added later; verification cannot prove implementations that have not been supplied yet.

Capture patches and scope checks before worker cleanup. Stop all workers, unload NVFP4, and establish the clean-state gate. Reload Flash with the saved plan and worker reports. The host applies validated disjoint patches in an integration checkout; Flash then reviews and repairs the assembled result. Require combined tests plus a host-owned independent evaluator, run in its own owned sandbox. A worker's self-report is never the pass gate.

## Bounds and evidence

No overall coding deadline. Each Bash/evaluation command is limited to 120 seconds. Reuse protocol-6 progress supervision: warn/steer/stop after 10/15/30 minutes without qualifying evidence, bounded initial evidence, at most 128 requests and 200000 generated tokens per agent. Preserve pi-vigilant 0.8.1 in each model session. Keep physical reserve max(16 GiB,20 percent), sampled every 200 ms. Stop the entire owned batch on reserve breach. No recursive delegation, unrelated process killing, cache clearing, swap changes, automatic publishing, or live protocol-6 edits.

Record setup, load, preload, planning, worker overlap, patch collection, integration, evaluation, cleanup, and model-transition times; per-agent tokens/cache hits/requests; request latency; phase aggregate backend prefill/decode counters; memory minima and swap deltas. Concurrent backend counter deltas cannot be attributed to an individual worker. Report aggregate throughput with that limitation, not four invented per-agent rates. Maximum context capacity is not actual occupancy.

## Admission gates

1. Deterministic tests: plan schema and ownership; malformed/missing worker output; failed transitions; interruption; no fifth worker; private directories; patch traversal/symlink rejection; independent evaluator known-good and negative fixtures.
2. Real sandbox smoke: workspace writes succeed; a sibling sentinel is unreadable/unwritable through shell and direct Pi file tools; nested tool processes are confined and cleaned up; unrelated sentinel survives.
3. Two concurrent NVFP4 workers: normal endings, pi-vigilant/runtime acknowledgements, bounded command timeout, no swap/reserve breach, overlapping active requests. Then four.
4. The scored delegation run is admitted only after these gates pass and the host-wide experiment lock is free. Do not compete with queue 5 or change its publication inventory.

## Current host blocker

On 2026-10-22, /usr/bin/bwrap exists but its real launch fails with `setting up uid map: Permission denied`; kernel.apparmor_restrict_unprivileged_userns=1. Docker is installed but lain cannot access its socket. The pilot must remain blocked rather than dropping sandboxing. An administrator must install socat and load the supplied narrow Bubblewrap AppArmor profile; retain the global user-namespace restriction. Re-run the real sandbox smoke after that change. No sudo password is collected by the harness.

## Commands

On GX10, from /home/lain/projects/datalog-research:

```bash
python3 -m unittest discover -s benchmark/gx10-delegation -p 'test_*.py' -v
# To include real positive/negative evaluator checks, set GX10_CLOJURE to the installed CLI.
GX10_CLOJURE=/home/lain/.local/bin/clojure python3 -m unittest discover -s benchmark/gx10-delegation -p 'test_*.py' -v
/home/lain/.local/opt/node-v24.22.1-linux-arm64/bin/node benchmark/gx10-delegation/test_manager.mjs /home/lain/experiments/gx10-delegation-packages-1
python3 benchmark/gx10-delegation/run.py check --output /home/lain/experiments/gx10-delegation-admission-NEW
python3 benchmark/gx10-delegation/run.py run --output /home/lain/experiments/gx10-delegation-pilot-NEW
```

Outputs must be new directories. Failed/interrupted runs are retained and never silently resumed or reused. Read failure.json, phase result.json, lifecycle.jsonl, agent activity.jsonl, metrics-before/after.txt, concurrency.jsonl, handoffs.json, patches, own-tests.txt, and acceptance.txt. Activity records omit private thinking text. Full native session resumption is intentionally deferred; integration receives durable explicit handoffs.

The separate queue command waits for queue 5's durable completion and OS sandbox availability, then invokes run.py. The child still rechecks sandbox admission and acquires the original host experiment lock. An infrastructure failure in queue 5 blocks it for inspection. It never changes queue 5 or its publisher:

```bash
python3 benchmark/gx10-delegation/schedule.py --after-unit gx10-datalog-benchmark-queue-5.service --after-root /home/lain/experiments/gx10-datalog-benchmark-queue-5 --output /home/lain/experiments/gx10-delegation-queue-1
```

The installed prefix pins pi-sandbox 0.28.0, pi-auto-review 0.27.2 and jiti 2.7.0; its npm lock SHA256 is bf85d29f78ceb7fc06472e556903568e7d6e60e46b04130550464447602b06bf. The Pi peer points to the existing Pi 1.3.0 installation. Native socat/libwrap were extracted into the prefix without administrator changes. No global Pi extension settings were changed.

The AppArmor profile was parsed successfully with --skip-kernel-load --skip-cache. Its administrator installation commands are:

```bash
sudo install -m 644 /home/lain/projects/datalog-research/benchmark/gx10-delegation/gx10-bwrap.apparmor /etc/apparmor.d/gx10-delegation-bwrap
sudo apparmor_parser -r /etc/apparmor.d/gx10-delegation-bwrap
```

Validation so far: known-correct independent fixture passes 7 tests/32 assertions; wrong-result and syntax-error fixtures fail. Real pinned-manager admission tests pass with process startup mocked: caps two/four, overflow rejected, nesting rejected, cleanup releases slots. The actual outer-sandbox check is recorded as setup_declined at /home/lain/experiments/gx10-delegation-admission-1 because of the user-namespace denial. These deterministic checks do not establish live sandbox or model-backed concurrency success. No delegation score is available until all live gates complete.

Delegation does not change the later Lain delegation contract; that remains separate work.
