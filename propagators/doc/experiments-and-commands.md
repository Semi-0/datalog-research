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
clj -M:dispatch-bench
clj -M:dispatch-bench 50 1
clj -M:dispatch-bench 50 51
```

The chain benchmark harness is `propagators_chain_bench.clj`. The generic and
layered procedure dispatch benchmark harness is `propagators_dispatch_bench.clj`.

Recorded local dispatch baseline on 2026-06-08:

| Command | Generic | Layered |
| --- | ---: | ---: |
| `clj -M:dispatch-bench` | 50 handlers / 1 dispatch median 342.616 ms | base+provenance / 1 dispatch median 2.203 ms |
| `clj -M:dispatch-bench 50 51` | 50 handlers / 51 dispatches median 2516.185 ms | base+provenance / 51 dispatches median 81.630 ms |

The default dispatch benchmark checks that a single 50-handler generic dispatch
does not drift into multi-second territory. The 51-round form is a pressure run,
not part of `clj -M:test`.

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
