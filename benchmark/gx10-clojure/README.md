# GX10 harness in Clojure and Lain

This is a local, executable controller implementation. The high-level lifecycle and queue admission are Lain; the controller, evidence classification, persistence, memory settlement, command supervision and telemetry calculations are Clojure. It does not invoke Python.

**Status:** local controller and session-extension pilot verified. It is not yet a replacement for the live GX10 Pi/vLLM/Ollama harness. The GPU/backend adapters, Pi stream normalization, model configuration verification, unchanged independent evaluators, context probing, and restart continuation must be connected and validated before scored use. Existing Python snapshots and remote runs were left intact.

## Start here

From this directory on this Mac:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:test
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:demo
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:editor
```

The editor is loopback-only at http://127.0.0.1:4790/. It uses a separate browser origin from the user's editor at port 4784. `workspace.json` retains the high-level program and its applied extension. Use Cmd+K → Open workspace JSON to replay it; Cmd+K → Toggle extension editor exposes the refinement controls. Compiling, replaying or editing the Lain program never starts a model.

The demo performs **simulated model/agent operations**, followed by real Java/Clojure test subprocesses. Its three cells exercise queue continuation; they are not scored executions of the three original GX10 coding tasks. Output explicitly records `:mode :simulated` and `:model-inference false`, and prints a fresh temporary output directory.

## Top-down construction

1. `workflow.lain` declares four unresolved outer cells, composes `benchmark-cell`, and defines `continue-queue?`. The unresolved cells are deliberate: declaring them before the network captures their shared identities.
2. `src/gx10_benchmark/primitives.clj` supplies pure native operators through the existing `load-primitive-environment` API. This API installs a session extension and records the imported file digest. The reserve calculation is `max(16 GiB, ceil(physical RAM / 5))`.
3. `refinement.lain` fills those four cells in the same session. It resolves the actual stage sequence without replacing the high-level specification. `session/prepare!` calls the existing editor extension operation and retains the extension source.
4. `controller.clj` interprets the resulting stage sequence. Queue continuation calls the Lain predicate with three explicit facts; it does not duplicate that predicate in Python or Clojure.

The resulting sequence is:

```
cleanup-before → verify-unloaded → settle-memory → check-budget
→ load-model → verify-configuration → preload → execute → evaluate
→ save-result → cleanup-after
```

The host rejects a plan that changes the required ordering. Effects are supplied as focused stage handlers. Missing handlers, missing verified gate evidence, unsuccessful receipts and declined budgets cannot silently admit the next stage.

## Evidence and recovery

`outcome.clj` separates implementation correctness, workflow evidence, execution completion and timing eligibility. Correctness requires a positive assertion count, zero failures/errors, evaluator exit zero, and the expected assertion total when supplied. A zero-exit agent or zero-assertion evaluator does not establish correctness.

A response abort is recovered only when an explicit reasoning-repeat correction identifies the same request, a later assistant response finishes with stop/tool-use, the agent ends normally, and no unrelated error or terminal repeat stop remains. The abort stays in raw evidence. Unknown aborts, length exhaustion and incomplete endings stop scheduling. The regression tests cover the queue failure that motivated this implementation.

An explicit terminal repetition stop after correction permits one full fresh attempt, after verified cleanup. The decision is saved before retrying. Each attempt gets separate workspace, agent, history, web-cache and temporary directories. Reloaded timing is excluded from uninterrupted speed comparisons. Unknown failures never qualify for a reload.

`transition.clj` requires ten seconds of clean samples, no resident model, no old owned process, an unavailable old endpoint, RAM above reserve, variation within 256 MiB and no unexplained baseline deficit over 2 GiB. It polls every second with a 180-second deadline. The sampler is a backend port; an unload acknowledgement is not a sample. Deterministic tests exercise all 16 directed configuration labels, including same-model transitions. These are simulated transitions, not live GPU validation.

## Effects and records

`controller/run-cell!` runs an independently initialized cell into a new directory. `controller/run-queue!` runs sequential cells under the same host lock. `controller/cell` constructs the public cell declaration. Each effect receives the cell, directory, fresh paths and accumulated evidence. Return EDN receipt data with `:ok? true`; transition/configuration/cleanup gates also require `:verified? true`, and budget admission requires `:approved? true`.

The execute receipt supplies normalized `:execution`, `:workflow` and `:timing`; evaluation supplies `:evaluation`. Raw command and evaluator output belongs in the attempt directory. A backend adapter must establish its facts with actual API/process/memory observations; receipt booleans alone are not a substitute for implementing that adapter.

Each attempt saves `cell.edn`, `provenance.edn`, `events.edn`, `checkpoint.edn` and `result.edn`. Provenance includes the Lain sources and SHA-256 identities plus JVM/Clojure versions. Results are flushed and atomically replaced before cleanup and before the next cell. Cleanup failure is recorded separately and blocks scheduling. Reusing an output directory is refused. Restart continuation is not yet implemented: do not rerun into an existing directory or interpret an incomplete record as completed.

`process/run!` executes an argument vector without a shell, retains stdout/stderr, bounds tool calls (120 seconds by default), and can sample an injected RAM guard every 200 ms. Agent execution may use a nil deadline. It stops only processes it created and observed descendants. **This local process helper does not establish Linux cgroup ownership and cannot guarantee cleanup of reparented or detached children.** GX10 admission must wait for the systemd ownership adapter and nested-process validation; this helper must not be substituted for that gate.

`telemetry/request-metrics` preserves raw measurements, labels cached/uncached/unknown prefill, and refuses to invent rates without duration counters. Failed/truncated requests are excluded from completion rates. No real model rate has been measured by this local pilot.

## Validation

Observed on 2026-10-23: 23 tests, 120 assertions, zero failures/errors; three-cell local demo completed. The saved editor workspace also replayed successfully through the standard runtime. Coverage includes recovered/unresolved aborts, assertion validation, all 16 simulated transitions, lingering processes, delayed RAM recovery, clean-baseline deficits, persistence before cleanup, bounded fresh retries, host-lock conflicts, interruptions during setup/load/coding/evaluation, cleanup failure, command timeout, reserve breach and an unrelated sentinel process surviving cleanup.

A live GX10 backend migration, actual Pi event adapter, unchanged 14/17/42-assertion evaluator integration, context scan, and interruption/resume pilot remain required before this controller can produce scored benchmark results. Earlier Python results must retain their own protocol identities.
