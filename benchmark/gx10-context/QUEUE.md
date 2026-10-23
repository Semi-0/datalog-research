# Gated GX10 benchmark continuation

The latest run uses [protocol 6](PROTOCOL-6.md): explicit 128K coding, no overall wall-clock agent deadline, meaningful-progress supervision, and per-request inference throughput. Adaptive-memory plugins and the Lain delegation contract remain deferred.

## Active protocol-6 queue

Service: `gx10-datalog-benchmark-queue-5.service`.
Output: `/home/lain/experiments/gx10-datalog-benchmark-queue-5`.
Frozen source: `/home/lain/experiments/gx10-protocol6-harness-5.tar.gz`, SHA-256 `260d886b87f9916a4ded8db34303033519f16141f82218e3f3973762bada71ed`.
All 91 deterministic checks passed on GX10 with no skips. Queue 4 established successful 128K loading and effective runtime flags, then failed before Pi started due to an incorrect smoke-fixture configuration lookup in the new telemetry path. That path was fixed, preparation failures now save durable error results, and a dedicated smoke-contract regression test passed before queue 5. A preceding 128K admission attempt declined under the old 4 GiB workspace estimate, approximately 32 MiB below reserve; it was not an observed OOM. The bounded cache/batch configuration uses an explicit 3.5 GiB workspace estimate while the physical RAM reserve and 200 ms guard remain unchanged. Queue 3 was stopped during setup to align the per-request socket-idle setting; owned backend cleanup was verified in `/home/lain/experiments/gx10-protocol6-recovery-3` before deploying the final frozen snapshot. No coding attempt from that interrupted setup is retained.

Queue 5 runs fresh deterministic validation, the explicit 128K Flash smoke and three Flash tasks, then live gates and the other nine coding cells. Completed Flash cells are reused in the final 12-cell matrix rather than run twice. Context scans follow coding. There is no automatic context fallback. Throughput and progress policy details, metrics limitations and stop outcomes are specified in PROTOCOL-6.md.

Launch from `/home/lain/projects/datalog-research`:

```sh
bash scripts/run-background.sh gx10-datalog-benchmark-queue-5 -- \
  /usr/bin/env PATH=/home/lain/.local/opt/node-v24.22.1-linux-arm64/bin:/home/lain/.local/bin:/usr/local/bin:/usr/bin:/bin \
  /usr/bin/python3 benchmark/gx10-context/run_queue.py \
  --context-run /home/lain/experiments/gx10-datalog-flash-context-1 \
  --persistence /home/lain/experiments/gx10-queue-persistence-1.json \
  --output /home/lain/experiments/gx10-datalog-benchmark-queue-5
```

The local watcher uses `results/watch-queue-5.json` and preserves `results/watch-queue-5.source.json`. It collects throughput/progress diagnostics and retains the user's conditional commit/push policy after verified completion. Until the run completes, no publication is claimed.

## Archived protocol-5 policy

## Sequence and gates

1. Wait for the existing context-only run to publish `status.json` with `stage: context_complete`. An interrupted or failed scan cannot admit coding.
2. Run the current deterministic validator, including real Pi provider-wire tests, pinned Gortex graph integration and queue regression tests.
3. Run a fresh Flash context scan for the protocol-5 backend cache policy; protocol-4 context evidence remains exploratory and is not reused after a backend implementation change. Run the full Pi smoke with pinned pi-vigilant and pi-gortex before coding. If the full agent/plugin workload crosses the reserve, try lower contexts that already passed every retrieval seed, preserving each failed smoke. Unknown runtime or workflow errors stop this fallback. `selected-context.json` records the retrieval result; `coding-context.json` records the context that actually passed the full smoke.
4. Run Flash on distinct, dependency ordering and event projection. All three require normal completed agent endings, no unresolved stream/output-limit failures, and a completed independent evaluation before admitting the other configurations. The user's overnight instruction permits genuine implementation/workflow failures as meaningful results; correctness is not required to unlock reruns. Runtime errors, timeouts, resource stops and incomplete evaluations block admission.
5. Run unscored live validation across the four manifest configurations and all 16 ordered transitions, including same-model transitions. A clean affordability decline is recorded separately. Unresolved lifecycle failures stop scheduling.
6. Run a fresh scored matrix: all three tasks at the manifest's fixed 32K coding context for every configuration, followed by the existing context scans and boundary refinement. This is separate from the Flash pilot at its measured context. Therefore the queue can perform three Flash pilot attempts plus 12 scored coding cells; pilot attempts are not substituted into the scored matrix.

Every model operation uses the shared lifecycle, host experiment lock and RAM guards. The queue itself holds a separate host controller lock. Agent attempts retain the 1,200-second limit and shell/evaluator commands the 120-second limit. No agent-specific RAM guard plugin or concurrent inference experiment has been added.

## Overnight continuation requested 2026-10-22

After Flash produces meaningful coding outcomes, rerun the earlier model configurations in fresh cells using the same termination, command timeout and output-completion checks. Monitor the queue and inspect every outcome. Commit and push only the scoped benchmark work after the requested run is complete; preserve unrelated working-tree files. This is authorization for that final commit/push, not a claim that either has happened.

Protocol 5 disables the runner's optional host prompt cache with `LLAMA_ARG_CACHE_RAM=0`, verifies the effective runner startup log, and retains full attention KV caching, model artifacts, sampling, thinking and plugins. Protocol-4 runner logs exposed an 8 GiB host prompt cache absent from the budget estimate; repeated-request memory growth is being investigated. Per-second owned-service process RSS and cgroup accounting supplement the unchanged 200 ms host RAM guard. The reserve remains `max(16 GiB, 20% physical RAM)`.

## Protocol-5 launch and final status

Live recheck after reconnecting: the queue stopped at the Flash completion/evaluation gate. All three coding attempts timed out at 1,200 seconds. The watcher collected final evidence and stopped without committing or pushing. The 12-cell matrix and other configurations did not start. Owned services and runners were cleaned up. See [the result summary and lessons](BENCHMARK-SUMMARY.md).

Service: `gx10-datalog-benchmark-queue-2.service`.
Output: `/home/lain/experiments/gx10-datalog-benchmark-queue-2`.
Source archive: `/home/lain/experiments/gx10-overnight-v5b.tar.gz`, SHA-256 `2bd770e46cc869eab188dbcf295c0a569e4da7cd3f7ab96e300c754556602d6a`.
All 77 GX10 deterministic checks passed without skips before the new queue. The preliminary full 32K smoke at `/home/lain/experiments/gx10-flash-cache0-smoke-v5b` passed with 5 own assertions, 1 independent assertion, complete ending, no stream failures, zero swap deltas and 28,150,304,768 bytes minimum available RAM. Coding took 404.67 seconds; setup, evaluation and cleanup remain separate.

```sh
cd /home/lain/projects/datalog-research
bash scripts/run-background.sh gx10-datalog-benchmark-queue-2 -- \
  /usr/bin/env PATH=/home/lain/.local/opt/node-v24.22.1-linux-arm64/bin:/home/lain/.local/bin:/usr/local/bin:/usr/bin:/bin \
  /usr/bin/python3 benchmark/gx10-context/run_queue.py \
  --context-run /home/lain/experiments/gx10-datalog-flash-context-1 \
  --persistence /home/lain/experiments/gx10-queue-persistence-1.json \
  --output /home/lain/experiments/gx10-datalog-benchmark-queue-2
```

The old completed scan supplies the initial scheduling prerequisite only. The protocol-5 pilot performs fresh context probes; it does not import protocol-4 measurements after the backend change.

The local [watcher](../../scripts/watch_gx10_benchmark.py) polls durable status every 45 seconds, collects bounded result/evaluation/patch artifacts with an archive checksum, verifies final gate evidence hashes and every coding cell, and blocks publication on runtime, interruption or cleanup failures. Its status receipt is `results/overnight-watch-queue-2.json`; its immutable source inventory is `results/overnight-watch-queue-2.source.json`. It runs under `caffeinate -i` so ordinary idle sleep does not suspend the local monitor; the display may sleep normally. Closing the lid, rebooting or losing SSH authentication can still interrupt local monitoring. GX10's persistent service and RAM guard continue independently.

The watcher commits only its explicit benchmark source list and the collected queue-2 evidence. It refuses changed source/HEAD or any unrelated staged changes. It pushes the current branch with an ordinary `git push`; it never forces a push. A connection failure is retried, while a benchmark or publication failure is saved for inspection. A failed queue is collected without committing. The source inventory is frozen when the watcher starts, so later edits require an explicit new monitor and review.

## Preserved failed launch

The completed scan at `/home/lain/experiments/gx10-datalog-flash-context-1` selected 131,072 tokens after all three seeds passed. The queue then passed all 75 deterministic checks on GX10 with no skips. Its full Pi/plugin smokes at 128K, 64K and 32K crossed the reserve and were recorded as `resource_stopped`; verified cleanup completed after each attempt. The queue stopped before any coding cell or other configuration started. The smaller full-agent context remains a separate measurement from the successful 128K synthetic retrieval scan. This is a largest tested success at the configured 8K resolution and reserve policy, not a proof of the machine's absolute limit or concurrent capacity. Full Pi/plugin capacity is checked separately by the smoke stage.

Service: `gx10-datalog-benchmark-queue-1.service`.
Output: `/home/lain/experiments/gx10-datalog-benchmark-queue-1`.

```sh
cd /home/lain/projects/datalog-research
bash scripts/run-background.sh gx10-datalog-benchmark-queue-1 -- \
  /usr/bin/env PATH=/home/lain/.local/opt/node-v24.22.1-linux-arm64/bin:/home/lain/.local/bin:/usr/local/bin:/usr/bin:/bin \
  /usr/bin/python3 benchmark/gx10-context/run_queue.py \
  --context-run /home/lain/experiments/gx10-datalog-flash-context-1 \
  --persistence /home/lain/experiments/gx10-queue-persistence-1.json \
  --output /home/lain/experiments/gx10-datalog-benchmark-queue-1
```

The launcher verified `Linger=yes`. A harmless service was observed running under its systemd user cgroup after the launch SSH channel closed. The authenticated ControlMaster connection was retained; the persistence receipt states that exact evidence scope.

The deployed working-tree source archive is `/home/lain/experiments/gx10-benchmark-queue-final-1.tar.gz`, SHA-256 `df4cb0eca94cbf544888e15173b6da27c273b92ba7e3534a3b2811c06c690eb6`. The deployment receipt records all 68 file hashes and the preceding source backup. This is an artifact pin, not a claim that the work was committed or pushed. Do not edit that deployed Python snapshot while the queue or its workers run.

## Pi/Gortex profile

Pi-gortex 0.4.0 is copied and checked against every packaged file hash. Node 24.22.1 and Gortex 0.64.8 binaries are pinned independently. Each attempt has a private Gortex HOME, XDG paths, Unix socket, daemon and candidate graph; no global Gortex installation or configuration is changed. Indexing starts inside the guarded Pi process lifecycle. The attempt's systemd cgroup owns detached daemon children, so normal lifecycle cleanup includes them.

The profile exposes a small read-only graph tool allowlist alongside the existing Pi editing, execution and web tools. Pi's CLI tool allowlist must include those names: merely registering an extension tool does not make it available. A real Pi test queried the private candidate workspace through the actual plugin/MCP bridge and verified tool exposure. The package remains unmodified; an attempt-local wrapper records registration and owns startup sequencing. The test also checks private daemon shutdown and socket removal.

## Inspect and continue

```sh
systemctl --user status gx10-datalog-benchmark-queue-1.service
journalctl --user -u gx10-datalog-benchmark-queue-1.service -n 60 --no-pager
cat /home/lain/experiments/gx10-datalog-benchmark-queue-1/status.json
cat /home/lain/experiments/gx10-datalog-benchmark-queue-1/queue.json
```

`queue.json` records each stage's command, output directory, timestamps, exit code and completed-evidence hash before the next stage is admitted. Each stage's raw stdout/stderr is retained separately. Read its child status and reports for individual probe and coding outcomes.

For an interrupted controller, use the same arguments and output with `--resume`, under a new service name after verifying the previous controller is stopped. Resume refuses a changed harness or changed continuation inputs. Completed stages are reused only when their durable evidence hash still matches. Incomplete retries get new output directories and fresh histories. An interrupted scored matrix passes its previous output through the suite's `--continue-from` recovery, retaining completed cells. An incomplete Flash pilot restarts as a new pilot attempt; old artifacts remain separate.

A modified harness requires a new pinned run and fresh validation. Do not bypass a failed gate, relabel affordability estimates as observed OOM, rerun a recognized nonterminating own test, or count setup declines as implementation failures.
