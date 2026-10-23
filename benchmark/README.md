# GX10 benchmark harness

The operational benchmark harness is Python and is included in this repository.
The Clojure/Lain controller under `gx10-clojure` is a separate local prototype;
it has not replaced the live Pi/backend implementation.

## Source map

| Path | Responsibility |
| --- | --- |
| `gx10-context/suite.py` | Shared test lifecycle and standalone `run-cell` entry point |
| `gx10-context/lifecycle.py` | Host lock, memory reserve and lifecycle evidence |
| `gx10-context/outcomes.py`, `pi_runtime.py` | Outcome classification and stream/recovery evidence |
| `gx10-context/model_setup.py` | Model loading and configuration verification |
| `ornith-nvfp4-mtp/coding.py` | Fresh Pi coding attempts, tools, evaluator execution and artifacts |
| `qwen38-nvfp4/follow_on.py` | Sequential follow-on model/task queue and admission gates |
| `qwen38-nvfp4/repetition_recovery.py` | Bounded repetition reload and verified continuation policy |
| `qwen38-nvfp4/continuation.py` | Retain completed coding cells without reusing interrupted histories |
| `gx10-delegation/` | Solo/delegation controllers, sandbox and tool profiles |
| `qwen36-nvfp4/`, `qwen38-nvfp4/` | Pinned candidate artifacts and server configuration |
| `../scripts/run-background.sh` | Persistent user-service launcher and linger check |
| `../scripts/watch_gx10_benchmark.py`, `watch_gx10_stream.clj` | Status and stream viewers |

Read [the queue audit](QUEUE-AUDIT-20261023.md) for the most recent inspected stop.
Older progress documents are historical snapshots, not live queue status.

## Versions and execution

There are three independent identities:

1. **Controller source commit:** the datalog-research Git commit containing this harness.
2. **Task source commit:** the pinned repository baseline used for a fresh coding checkout.
3. **Executed deployment:** the particular frozen harness directory and model/runtime/extension identities recorded with a run.

Updating a checkout does not change an already frozen deployment. Existing
`/home/lain/experiments/gx10-delegation-python-harness-*` directories and raw run
results must remain unchanged. A failed result is not rewritten after a source fix.

Use the same clean controller commit on both hosts before preparing the next
run. Record `git rev-parse HEAD`, source-file hashes, the task baseline, the plan
and the existing runtime/tool locks in its deployment receipt. Do not claim two
working trees are identical merely because their HEAD commits match; local
uncommitted editor/compiler work is a separate state.

Commands below run deterministic tests, not model inference:

```sh
python3 -m unittest discover -s benchmark/gx10-context -p 'test_*.py'
python3 -m unittest discover -s benchmark/gx10-delegation -p 'test_*.py'
python3 -m unittest discover -s benchmark/ornith-nvfp4-mtp -p 'test_*.py'
python3 -m unittest discover -s benchmark/qwen38-nvfp4 -p 'test_*.py'
```

Some checks require Clojure/Node or explicitly enabled live GX10 prerequisites.
Report skipped checks separately. On this Mac the Clojure tests also require
`JAVA_HOME=/opt/homebrew/opt/openjdk`.

Model weights, caches, raw logs and run directories are ignored by Git. Preserve
those artifacts separately; this source checkout is not the results archive.
