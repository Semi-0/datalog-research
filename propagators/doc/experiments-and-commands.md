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
clj -M:gur-accumulating-bench
clj -M:gur-accumulating-bench 0 1
```

The chain benchmark harness is `propagators_chain_bench.clj`. The generic and
layered procedure dispatch benchmark harness is `propagators_dispatch_bench.clj`.
The accumulating GUR HOP benchmark harness is
`propagators_gur_accumulating_bench.clj`; it reuses the focused accumulating
GUR test scenarios and checks each observed result.

Recorded local dispatch baseline on 2026-06-08:

| Command | Generic | Layered |
| --- | ---: | ---: |
| `clj -M:dispatch-bench` | 50 handlers / 1 dispatch median 342.616 ms | base+provenance / 1 dispatch median 2.203 ms |
| `clj -M:dispatch-bench 50 51` | 50 handlers / 51 dispatches median 2516.185 ms | base+provenance / 51 dispatches median 81.630 ms |

The default dispatch benchmark checks that a single 50-handler generic dispatch
does not drift into multi-second territory. The 51-round form is a pressure run,
not part of `clj -M:test`.

## Native Semantic Dispatch Experiment

Compiler-2 event, behavior, and distributed-TMS values now use the kernel's
Clojure-native merge/strongest multimethods before the network-local generic
fallback. The representation and retained merge algorithms are unchanged.

```bash
clojure -M:compiler-2-tms-chain-bench event-updates 100 1 3
clojure -M:compiler-2-tms-chain-bench updates 100 1 3
clojure -M:compiler-2-tms-chain-bench behavior-updates 100 1 3
clojure -M:compiler-2-tms-chain-bench conflicts 1000 3 10
clojure -M:wired/tui-bench slider-cache-profile
```

Recorded local results on 2026-07-12:

| Workload | Before | Native kernel dispatch | Result |
| --- | ---: | ---: | --- |
| TMS, 100 retract/bring updates | 7624.527 ms total median | 7739.544 ms | no improvement |
| Behavior, 100 retained updates | 2670.245 ms total median | 3054.322 ms | slower in this run |
| Event, exact `(-> (+ (- a b) c) d)`, 100 updates | not recorded | 123.228 ms | correct; 1.008 ms median update |
| TUI direct slider arithmetic, 60 updates | 3.315 ms average/update | 3.109 ms | approximately unchanged |

All update benchmarks retained fixed topology. Dispatch is not the dominant
TMS/behavior cost; retained slot scans and behavior history reconstruction are
the next optimization boundary. The native TUI variant recorded no generic
dispatch activity; the forced generic comparison recorded six generic applies,
while its retained variant ran 363 retained generic frames.

For 1000 provenance-bearing strongest projections, event conflicts took 5.844
ms and carried two evidence tokens. TMS conflicts took 17.912 ms and carried
two claim plus two premise tokens.

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
