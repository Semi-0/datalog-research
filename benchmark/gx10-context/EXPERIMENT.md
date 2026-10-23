# GX10 coding and context experiment, protocol v3

Current work: [protocol-4 Flash context-first Pi pilot](PI-PILOT.md). The protocol-3 material below is retained evidence; its full-matrix launcher is stopped.

`config.json` is authoritative for the four configurations, sampling, reserve,
three coding tasks, 32K coding context, and subsequent context scan. Task contracts,
prompts and independent evaluator hashes remain in `tasks.json`, `prompts/` and
`evaluator-manifest.json`. Coding has no wall-clock deadline. Web access is
autonomous, thinking is high, and each attempt starts with fresh Pi state.
No LSP or clj-kondo is added; compiler/test feedback remains the common baseline.

## Shared lifecycle

Standalone and suite cells call the same operation:

```
cleanup → verified settlement → budget → load/configuration checks → preload
→ execute/evaluate → durable result → verified cleanup
```

`lifecycle.py` owns transitions and Linux service ownership; `model_setup.py`
owns artifact/configuration checks and budget estimates; `coding.py` owns the
isolated agent/evaluators; `outcomes.py` separates correctness, workflow and timing.
`run-cell` needs no prior attempt or adopted result:

```sh
python3 benchmark/gx10-context/suite.py run-cell \
  --model ornith-q4 --task distinct --output /absolute/new/cell
```

One host-wide advisory experiment lock covers standalone, suite and validation
controllers. The ownership registry is under `~/.local/state/gx10-benchmark/`.
Each backend and attempt has a uniquely named user service, launched through
`scripts/run-background.sh` with verified `Linger=yes` and `KillMode=control-group`.
Nested shell/JVM groups remain in that service's cgroup. Unrelated services and
resident models are refused, never stopped. This is cooperative isolation under
one Unix account, not a filesystem security sandbox.

Ollama unload acknowledgements are followed by `/api/ps` polling and PID/start-time
checks for the original runner. vLLM endpoints and owned cgroups must disappear.
Settlement requires ten full seconds of clean observations at one-second
intervals, available-RAM variation within 256 MiB, and no more than 2 GiB shortfall
from the preceding clean unloaded baseline. Owned services get five seconds to stop normally, then cgroup-scoped SIGKILL;
process emptiness is still verified. A 180-second transition expiry or
uncertain cleanup records `transition_failed` and stops scheduling. No OS cache
clearing, swap changes or unrelated process killing is used.

Budget checks run only after that gate. They record weights, cache estimates,
workspace allowance and estimate limitations. Reserve is computed from current
physical RAM: max(16 GiB, 20%). Load, preload, generation and evaluation are sampled
every 200 ms; a breach stops owned services. Backend readiness and HTTP startup
requests have 30-minute infrastructure bounds, separate from coding time.

Each attempt freezes the manifest, harness hashes, evaluator/prompt hashes,
source commit, model identity, runtime versions, configuration checks, lifecycle
samples, raw agent/test output and patches. Hidden reasoning and fetched pages
stay outside Git.

## Outcomes and continuation

A full pass requires unchanged independent evaluation (14 distinct, 17 ordering,
42 events assertions), successful own tests with assertions and zero failures or
errors, thinking/search/fetch evidence, a normal complete agent ending and allowed
file changes. Exit zero alone never proves success. Correctness is independent
of workflow confinement. Timing validity excludes swap transfers, resource stops,
interruption, incomplete streams and nontermination.

Terminal outcomes are `passed`, `task_failed`, `setup_declined`,
`transition_failed`, `runtime_error`, `resource_stopped`, `nonterminating`, and
`interrupted`. Cleanup failure is retained separately from the primary result.
Recognized infinite-input tests are stopped only with structural test evidence
and an active matching test command. The conservative detector is not a general
termination checker. Unknown stalls produce diagnostics after ten minutes without
progress and do not impose a coding deadline. A recognized infinite own test is
never rerun by verification.

Completed cells are durable before the next cell begins. `--continue-from` accepts
protocol-v3 coding or context runs with the same manifest. It recovers completed
cell records even if index publication was interrupted. Interrupted/incomplete
attempts are retried in new directories, never with old histories. Uncertain
cleanup must pass a fresh transition gate before further work.

## Validation before scoring

Run deterministic checks on GX10, then unscored Pi smoke sessions and all 16
ordered configuration pairs, including same-model pairs. Smoke uses unrelated
arithmetic and tests read/edit/execute/search/fetch/thinking. A complete terminal
`length` response after all primitives and assertions succeed is retained as an
output-token-exhaustion workflow failure; it does not erase primitive evidence.
It never becomes a full pass or valid speed result. Unknown stream errors,
resource stops and failed assertions still block the validation gate. Correct implementation
fixtures live only in `validation-fixtures/`, outside agent checkouts/prompts.

```sh
python3 benchmark/gx10-context/validate.py --output /absolute/new/deterministic
python3 benchmark/gx10-context/suite.py validate-live \
  --output /absolute/new/live \
  --deterministic /absolute/new/deterministic/deterministic.json \
  --persistence /absolute/persistence.json
```

`persistence.json` records a harmless service surviving its launching SSH session
closing, verified `Linger=yes`, service identity and job evidence. Validation is
bound to exact harness hashes. Any harness change invalidates approval to score.
A clean budget decline may be retained without blocking other affordable models;
any unresolved lifecycle, ownership or evaluation failure blocks scoring.

After all gates pass:

```sh
bash scripts/run-background.sh gx10-v3-scored-UNIQUE -- \
  /usr/bin/python3 /home/lain/projects/KIROSHI/benchmark/gx10-context/suite.py run \
  --output /absolute/new/scored \
  --validation /absolute/new/live/validation.json
```

The run schedules exactly three tasks × four configurations, one attempt per
coding cell, then 32K/64K/128K/256K retrieval with seeds 42/43/44 and resource-boundary
refinement at 8K resolution. Frozen tokenizer hashes, actual prompt occupancy and
RAM samples are retained. A semantic or infrastructure failure is not a resource
boundary. A conservative budget decline differs from an observed reserve breach.
No speed median is calculated from one attempt. Setup declines do not enter
correctness denominators; interrupted/nonterminating durations are censored.

## Exploratory preservation

The previous r3 controller was stopped during NVFP4 128K context setup with no
active test subprocess. The controller was suspended first; process/service state
and backend journal were captured, then its owned services were stopped. Original
r1/r2/r3 directories remain unchanged and were archived on GX10 under
`~/experiments/gx10-protocol-v3-preservation/exploratory-runs.tar`, SHA-256
`7e1b0c3e4b54f2b61be4c03779177855af08b20fd1c874beeacee34f5a221412`.
The stopping point is `stopping-point.json`; setup was interrupted, not scored.
Older transition-dependent skips/declines are inconclusive: delayed unload is a
hypothesis, not an established cause. Previous reports remain exploratory.

API references: [Ollama inventory](https://docs.ollama.com/api/ps),
[unload requests](https://docs.ollama.com/api/generate),
[systemd cgroup cleanup](https://www.freedesktop.org/software/systemd/man/latest/systemd.kill.html).
