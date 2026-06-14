# Experiments And Commands

This file keeps operational notes that used to live in `propagators/NOTES.md`.

## Test Commands

```bash
clj -M:test
clj -M:propagators-test
clj -M:test propagators
clj -M:test propagators-named-network-test
clj -M:test propagators-compound-data-test
clj -M:test propagators-linked-list-access-test
clj -M:test propagators-linked-list-schedule-test
clj -M:test propagators-compound-diagnosis-test
clj -M:test propagators-kernel-io-test
clj -M:test propagators-lexical-compound-test
clj -M:test propagators-network-protocol-test
clj -M:bench-test
```

`clj -M:test` intentionally excludes benchmark suites. Use `clj -M:bench-test`
for the recorded benchmark correctness checks, and use the
benchmark aliases below for timing runs.

The schedule suite may contain experiments that are expected to fail honestly.
Use the focused suites when checking a narrow change.

## Benchmark Commands

```bash
clj -M:propagators-bench
clj -M:propagators-bench 10 100 1000 10000
clj -M:propagators-profile propagate 1000
clj -M:kernel-io-bench
clj -M:compound-object-bench
clj -M:compound-object-bench wide 200
clj -M:dispatch-bench
clj -M:dispatch-bench 50 1
clj -M:dispatch-bench 50 51
```

The chain benchmark harness is `propagators_chain_bench.clj`. The generic and
layered procedure dispatch benchmark harness is `propagators_dispatch_bench.clj`.
The lexical IO boundary harness is `propagators_kernel_io_bench.clj`.

Recorded local dispatch baseline on 2026-06-08:

| Command | Generic | Layered |
| --- | ---: | ---: |
| `clj -M:dispatch-bench` | 50 handlers / 1 dispatch median 342.616 ms | base+provenance / 1 dispatch median 2.203 ms |
| `clj -M:dispatch-bench 50 51` | 50 handlers / 51 dispatches median 2516.185 ms | base+provenance / 51 dispatches median 81.630 ms |

The default dispatch benchmark checks that a single 50-handler generic dispatch
does not drift into multi-second territory. The 51-round form is a pressure run,
not part of `clj -M:test`.

## 2026-06-14 Kernel IO / Lexical Compound Refactor

Setup:

- `Net` carries evaluator IO: queue, queued prop set, inbox, and outbox.
- `core/continue` drains network-carried deliveries.
- `runtime/*continue*` exposes the evaluator continuation to propagators.
- `reality/p:reality-in` and `reality/p:reality-out` define network IO ports
  outside the core.
- `lexical/p:compound` runs a child network through those ports and stores the
  updated child network back into its cell.

Expected semantics:

- declaration remains graph/env/dict topology plus durable cell content
- evaluation owns activation-local queue/inbox/outbox work
- child outputs leave as ordinary parent messages
- activation-local scheduler state is stripped from activation views
- old runtime compound and legacy slot paths remain available

Observed pre-fix failure:

- propagator activations originally saw the parent network with its pending IO
  queue still attached
- network-valued messages could therefore copy pending parent work into child or
  recursive networks
- recursive nested compound runs became explosively slow

Implementation result:

- `eval-cell` merge/strongest policy and `eval-propagator` activation views now
  use `io/clear-queue`
- lexical compound tests cover value IO, bidirectional child topology, nested
  compound reads, nested bidirectional writes through the legacy slot
  compatibility path, and recursive nested compound map inside a child
  continuation
- `compile/default-installers` includes `reality/p:reality-in`,
  `reality/p:reality-out`, and `lexical/p:compound`

Test command output summary from 2026-06-14:

| Command | Result |
| --- | ---: |
| `clojure -M:test propagators-kernel-io-test` | 18 pass, 0 fail |
| `clojure -M:test propagators-network-protocol-test` | 22 pass, 0 fail |
| `clojure -M:test propagators-network-test` | 102 pass, 0 fail |
| `clojure -M:test propagators-compound-diagnosis-test` | 10 pass, 0 fail |
| `clojure -M:test propagators-compound-data-test` | 42 pass, 0 fail |
| `clojure -M:test propagators-compound-object-test` | 103 pass, 0 fail |
| `clojure -M:test propagators-compound-object-network-slot-test` | 29 pass, 0 fail |
| `clojure -M:test propagators-recursive-compound-test` | 115 pass, 0 fail |
| `clojure -M:test propagators` | 919 pass, 0 fail |

Local benchmark output from 2026-06-14:

| Command | Case | Median |
| --- | --- | ---: |
| `clojure -M:kernel-io-bench` | legacy runtime compound bisync | 0.785 ms |
| `clojure -M:kernel-io-bench` | lexical IO compound bisync | 0.722 ms |
| `clojure -M:kernel-io-bench` | lexical IO nested accessor read | 1.021 ms |
| `clojure -M:propagators-bench 10 100` | chain 10 middle inject | 1.455 ms |
| `clojure -M:propagators-bench 10 100` | chain 100 middle inject | 9.029 ms |
| `clojure -M:compound-object-bench` | optimized deep 10 | 2.161 ms |
| `clojure -M:compound-object-bench` | baseline deep 10 | 591.856 ms |
| `clojure -M:compound-object-bench` | optimized wide 100 | 59.937 ms |
| `clojure -M:compound-object-bench` | baseline wide 100 | 570.719 ms |
| `clojure -M:compound-object-bench wide 200` | optimized wide 200 | 253.259 ms |
| `clojure -M:compound-object-bench wide 200` | baseline wide 200 | 3363.173 ms |

Remaining limits:

- lexical compound is experimental and explicit-port based
- nested bidirectional network-slot writer over arbitrary unbounded recursion is
  not solved by this refactor alone
- general subenv/named-cell dispatch remains future kernel work
- wide fan-out is still expensive because each accessor gets its own parent
  message, even though the optimized path avoids repeated subnet execution
- dependence tracking and backtracking remain merge-time work, not scheduler
  work

## Compound Chain Benchmark Context

The historical benchmark compares compound `bi-sync` chain propagation across
several implementation eras:

1. snapshot inner nets
2. parent-network execution without avatars
3. avatar boundary execution
4. alternate boundary link modes
5. boundary cache with `closure-out`
6. current runtime without `closure-out`

The important result from the current era is not that runtime compounds are the
final fast representation. The important result is that removing `closure-out`
made the runtime model acceptable for experiments again.

Current prototype guidance:

- use runtime compounds for inspection and hot reload experiments
- expect linear behavior on long chains
- do not treat avatar runtime as the final steady-state lowering
- prefer a future compiled expansion for stable hot paths

## File Map

```text
propagators/compile.clj
  quoted DSL -> {:graph :env :cells :props}

propagators/core.clj
  run-tasks, eval-propagator, eval-cells, eval-cell

propagators/network.clj
  Net record and cell/propagator installation helpers

propagators/propagator.clj
  primitive and compound propagator constructors

propagators/closure.clj
  runtime compound activation

propagators/stdlib.clj
  p:id, p:switch, p:nothing, bi-sync, bi-sync-closure

propagators/cells/
  cells, merge, value, Bool4

propagators/datastructures/
  compound data, named networks, evidence sets

propagators/helpers/task_queue.clj
  immutable FIFO task queue
```

## Open Work

See [Four Core Features](four-core-features.md) for the canonical four-feature
checklist and progress matrix.

- dependence tracking (feature 4)
- contradiction policy beyond the current stub
- compiled compound form
- promotion/demotion between runtime and compiled compounds
- less fragile compound-data dispatch
- port-order discipline for set-backed graph inputs/outputs
