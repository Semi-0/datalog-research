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
- high-level recursive map/reducer forms still need to use the new subenv/name
  dispatch substrate for automatic output assembly
- wide fan-out is still expensive because each accessor gets its own parent
  message, even though the optimized path avoids repeated subnet execution
- dependence tracking and backtracking remain merge-time work, not scheduler
  work

## 2026-06-14 Lexical Pointer Dispatch Follow-Up

The follow-up adds the evaluator pointer layer that was missing from the first
lexical compound experiment:

- `io/cell-ref scope cell` targets a concrete cell in a lexical subenv
- `io/name-ref scope key` resolves a cell through the subenv `dict`
- evaluator IO carries a sparse `:lexical-envs` table
- messages to lexical refs are dispatched into the referenced child network
- normal activation/merge views hide `:lexical-envs` so evaluator state does
  not become semantic cell content

The focused dynamic recursion regression is in
`propagators.kernel-io-test/lexical-name-ref-supports-dynamic-recursion-over-nested-compound`.
It walks a nested compound object and creates child lexical frames at paths such
as `[:right :b 1]`. The assertions read the generated lexical env table, proving
that recursive frames can now be addressed by pointer rather than by a fixed
manual `reality.in/out` route list.

Test and benchmark output after the follow-up:

| Command | Result |
| --- | ---: |
| `clojure -M:test propagators-kernel-io-test propagators-lexical-compound-test` | 38 pass, 0 fail |
| `clojure -M:test propagators` | 966 pass, 0 fail |
| `clojure -M:kernel-io-bench` | lexical IO bisync median 0.928 ms |
| `clojure -M:propagators-bench 10 100` | chain 10/100 middle inject median 1.436 ms / 8.701 ms |
| `clojure -M:compound-object-bench wide 10` | optimized median 2.463 ms, baseline median 11.245 ms |

This should be read as a kernel substrate result. It solves recursive subenv
addressability. It does not yet replace the higher-level declared nested
map/reducer forms, and it does not yet provide automatic arbitrary nested output
assembly.

## 2026-06-14 Lexical Nested Recursive Map

The first higher-level form on the lexical pointer substrate is
`obj/p:nested-recursive-map`.

- the installed outer propagator keeps the live graph fixed
- root and child frames are fixed-shape lexical network values
- frame inputs are sent through `io/name-ref`
- child outputs return to the active caller through concrete caller-owned
  child-result cells, avoiding stale stored lexical frame updates
- each parent frame accumulates child output fragments as a named network and
  assembles its immediate compound-object output once children are available
- leaf frames run `recursive/p:accumulating-recursive-compound` and forward
  monotone frame facts to the explicit accumulator cell

Test output after the implementation:

| Command | Result |
| --- | ---: |
| `clojure -M:test propagators-recursive-compound-test` | 150 pass, 0 fail |
| `clojure -M:test propagators-kernel-io-test propagators-lexical-compound-test` | 38 pass, 0 fail |
| `clojure -M:test propagators-compound-object-test propagators-compound-object-network-slot-test` | 132 pass, 0 fail |
| `clojure -M:test propagators` | 966 pass, 0 fail |

## 2026-06-14 Accessor Recursive List Map

`obj/p:accessor-recursive-map` is the accessor-native comparison path for live
`p:cons` / `p:car` / `p:cdr` lists.

- declaration walks `core/slot-declarations-key`, not nested slot payloads
- each list node maps its `:car`; scalar cars become
  `recursive/p:accumulating-recursive-compound` leaves, and cars with visible
  `:car`/`:cdr` topology become nested accessor maps
- recursion follows the visible `:cdr` cell
- output assembly is `p:cons` accessor topology, so consumers read it through
  `p:car` / `p:cdr`
- a terminal cdr installs a lazy `closure/p:when-apply-network` continuation
  that emits the next frame when a later shell update installs `:car`/`:cdr`
  topology
- the emitted branch is a network value; `gur/p:run-frame` runs it through
  `reality.in` / `reality.out` and translates child outbox records to parent
  messages instead of rewriting the live graph during activation
- accessor-network outputs are synchronized with source-slot snapshots for
  branch-local route values, so parent readers can observe nested computed
  slots without installing child branch cells into the parent graph

This is the current GUR path. The kernel changes are already committed as
`d1cce22` and `444cdc6`; the GUR runner and accessor-recursive map changes are
the current uncommitted experiment on top of that substrate.

Focused test output:

| Command | Result |
| --- | ---: |
| `clojure -M:test propagators-recursive-compound-test` | 150 pass, 0 fail |
| `clojure -M:test propagators-compound-object-network-slot-test propagators-kernel-io-test propagators-lexical-compound-test propagators-recursive-compound-test` | 217 pass, 0 fail |
| `clojure -M:test propagators` | 966 pass, 0 fail |

Local benchmark output from `2026-06-14`, after the GUR experiment:

| Command | Case | Median |
| --- | --- | ---: |
| `clojure -M:kernel-io-bench` | legacy runtime compound bisync | 0.927 ms |
| `clojure -M:kernel-io-bench` | lexical IO compound bisync | 0.807 ms |
| `clojure -M:kernel-io-bench` | lexical IO nested accessor read | 1.323 ms |
| `clojure -M:propagators-bench 10 100` | chain 10 middle inject | 1.545 ms |
| `clojure -M:propagators-bench 10 100` | chain 100 middle inject | 11.223 ms |
| `clojure -M:compound-object-bench wide 10` | optimized wide 10 | 2.533 ms |
| `clojure -M:compound-object-bench wide 10` | baseline wide 10 | 9.970 ms |

These timings are subsystem evidence, not a dedicated GUR benchmark. They show
that the continuation and accessor substrate remain in the expected performance
range while the GUR correctness regressions pass.

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
