# Transitive graph and dataflow knowledge: swappable solvers

2026-10-13. Additive second scenario for the AMB experiment. “Three strategies”
means premise repair, checkpoint DFS, and concurrent checkpoint DFS, not the
opaque/decomposed/compiled representation comparison.

## Declaration and boundaries

Two independent Lain networks separate inference from query strategy.

The [knowledge declaration](../../examples/lain/amb/knowledge.lain) computes the
least positive transitive fixed point:

```clojure
(let-cell [reachable]
  (-> edges reachable)
  (-> (extend-reach reachable edges) reachable)
  reachable)
```

`extend-reach` is one relational composition step, not a host-side closure loop.
The existing opt-in relation runner uses set-union content and equality-based
scheduling. Repeated inference steps add facts until the ordinary continuation
iterator reaches quiescence. No production cell merge, compiler, runner,
TTMS, or compound synchronization contract changes.

The [query declaration](../../examples/lain/amb/transitive_solver.lain) supplies
the same callable and three missing cells to each solver:

```clojure
(let-cell [a b c]
  (define constraint
    (network (a b c)
      (and (reachable? facts a b)
           (reachable? facts b c))))
  (solve solver constraint DOMAIN a b c))
```

`DOMAIN` is replaced by the fixture's explicit finite Lain list. `facts` is the
immutable relation inferred by the first network. `reachable?` uses the existing
layered base/support/premise-state installer. Unknown guesses remain pending;
support and premise-state transport stay in the existing runtime.

| Component | Responsibility |
|---|---|
| Knowledge declaration | Positive transitive inference and feedback topology |
| Relation primitive | One-step tuple composition |
| Existing opt-in relation runner | Union observations and schedule changed facts |
| Query declaration | Two reachability constraints joined through missing `b` |
| Membership primitive | Inspect the inferred relation; preserve TTMS computation support |
| Existing `ConstraintSolver` implementations | Repair or enumerate immutable checkpoint worlds |
| Benchmark observer | Measure completion, activations, allocations and worker threads outside Net |
| BFS oracle | Independently verify closure and complete answers; never drive inference |

The graph domain is fixed and finite. Reachability means a positive-length path,
not unconditional reflexivity. A cycle can therefore infer self-reachability;
an isolated node does not. `(a,b,c)` is a transitive composition witness, not a
complete path or an enumeration of simple paths.

## Fixtures

1. **Graph chain:** `1 -> 2 -> 3 -> 4`. Six reachable pairs; four witness triples.
   At least one query leg can span multiple original edges.
2. **Graph cycle:** `1 -> 2 -> 3 -> 1`. Nine reachable pairs, including reflexive
   pairs; all 27 witness triples. Duplicate derivations terminate by set union.
3. **Actual dataflow fork/join:** five cells and four installed arithmetic
   propagators. Source `1` feeds `2` and `3`; both feed `4`; `4` feeds `5`.
   The subject computes `[3,4,6,10,11]`. The experiment projects edges from actual
   declared propagator input/output ports, not labels, screenshots or a hand-drawn
   graph. Nine reachable pairs; seven witness triples.

Dataflow ordinals map to the real fixture cells in this order:
`[:input :left :right :sum :output]`. The fixture retains its actual immutable
Net and cell IDs; benchmark metadata emits their UUID strings. This is potential
structural dependency, not an activation timeline or a proof of dynamic dependence
through arbitrary conditional/effectful propagators.

The seven dataflow answers are:

```clojure
[[1 2 4] [1 2 5] [1 3 4] [1 3 5] [1 4 5] [2 4 5] [3 4 5]]
```

## Measurement

One JVM; Java 25.0.2, ten available processors; caches disabled. One warmup and
three measured samples per strategy/fixture, alternating strategy order. One
shared four-thread pool is created outside timing and closed in `finally`.
The three-node cycle creates three partitions and uses three worker threads;
the other fixtures use four.

Question compilation, common relation inference, and outer preparation are
outside timed query execution and reported separately. Timed execution includes
solver installation, delayed callable-body topology, search, complete answer
collection, worker joining and outer publication. This measures complete answers,
not time to first answer. No optimized compiler mirror removal is applied.

Each run is checked against an independent per-node BFS closure and exhaustive
finite witness oracle. Runtime inference is separately tested with the BFS oracle
disabled. Checkpoint variants must visit exactly `n^3` complete leaves. Repair
uses its existing learned no-goods and request-scoped answer exclusions; its
instrumentation does not expose a comparable leaf counter (`nil`, not zero).

### Results

Median complete-query time, seconds:

| Fixture | Answers | Repair | Checkpoint | Concurrent checkpoint |
|---|---:|---:|---:|---:|
| Four-node graph chain | 4 | 2.322 | 1.304 | 0.376 |
| Three-node graph cycle | 27 | 0.909 | 0.659 | 0.247 |
| Five-cell dataflow fork/join | 7 | 4.426 | 2.323 | 0.995 |

All **27 measured runs** complete and match the full independent answer oracle.
All execute the solver exactly once. Checkpoint variants visit 64, 27, and 125
complete leaves respectively. Concurrent checkpoint is fastest on all three
fixtures: approximately 6.17x, 3.68x, and 4.45x faster than repair. These are
bounded-fixture observations, not general complexity results.

| Fixture | Repair activations | Checkpoint activations | Concurrent activations | Repair logical no-goods | Answer exclusions |
|---|---:|---:|---:|---:|---:|
| Graph chain | 28,665 | 17,034 | 17,033 | 9 | 4 |
| Graph cycle | 11,709 | 8,662 | 8,660 | 0 | 27 |
| Dataflow fork/join | 55,999 | 30,952 | 30,951 | 14 | 7 |

The one/two activation differences between checkpoint modes include differing
partition/controller scheduling. Both modes still visit the same complete
assignment set. The cycle has no logically invalid assignment, so it learns no
logical no-goods; request-scoped answer exclusions still enumerate every answer
and terminate. This distinguishes answer enumeration from failure learning.

Median cumulative allocation in the dataflow case is 7.304 GB for repair and
3.757 GB for each checkpoint mode. Parallelism reduces elapsed time, not the
overall allocation/work burden. The largest individual env observed is 697
entries for repair and 675 for checkpoints; this is not a deep heap measurement.

Common inference/setup, single samples outside query timing:

| Fixture | Knowledge compilation | Knowledge propagation | Inference activations | Query compilation | Outer preparation |
|---|---:|---:|---:|---:|---:|
| Graph chain | 1,821.945 ms | 24.351 ms | 176 | 223.096 ms | 38.042 ms |
| Graph cycle | 65.332 ms | 5.384 ms | 180 | 88.153 ms | 9.943 ms |
| Dataflow fork/join | 61.847 ms | 4.729 ms | 176 | 85.371 ms | 10.398 ms |

The first knowledge compilation includes cold JVM/compiler initialization.
These setup samples are not medians or a compilation-performance benchmark.
The unchanged inference topology has 183 cells / 143 propagators in every case,
including its standard environment. The actual dataflow subject has only five
cells / four propagators; do not confuse those two networks.

The initial benchmark also passed 27 measured runs before the UUID metadata
correction. The committed raw data is the final refreshed 27-run measurement;
the two JVM runs are not pooled into one median.

## Verification and scope

Final focused checks: **7 tests / 85 assertions**, zero failures/errors. They
cover positive chains, cycles, empty/disconnected graphs, duplicate proofs,
late bridge insertion, inference quiescence, fixed inference topology,
malformed facts, real dataflow-port projection, full answer parity, worker
counts, swapping strategies in one compiled environment, unchanged parent
topology, no guesses written into the parent, and duplicate-request idempotence.

The same-environment test proves all three strategies receive the identical
prepared question object. Neither background runtime nor compiler changes
when the solver cell changes. The oracle-disabled test proves the transitive
closure is produced by network propagation rather than injected host answers.

Broader workspace AMB checks: **66 tests / 980 assertions**, zero failures/errors,
before the extra oracle-independence test was added. The final focused rerun
includes that additional assertion. Static lint: zero errors/warnings in the
three new Clojure files. The full repository suite is not claimed green.

A separate export of the exact staged code tree passed **62 tests / 888
assertions**, zero failures/errors, including the final seven new tests and all
included shared solver dependencies. The export could not load unrelated
untracked workspace files. The earlier compiler-representation comparison tests
were tested in the workspace but are outside this commit's dependency scope.

The commit includes the required previously uncommitted AMB solver/benchmark
dependencies and their focused tests, so the new scenario does not depend on
untracked code. Unrelated document moves and other experiments are excluded.

## Limits and interpretation

- Inference is insertion-only and its relation result is fixed during a solver
  request. Late edge insertion is tested in the inference network separately;
  it does not update an already prepared solver query. Rebuild the query for
  changed knowledge. No live fact retraction or proof-level provenance claim.
- One-step inference uses a naive relational join, not an indexed/semi-naive
  optimization. This is a three-missing-variable finite query, not an arbitrary Datalog or
  general graph optimizer. It reuses the existing solver contract unchanged.
- Checkpoints still enumerate candidate triples; they do not contract domains
  using reachability indexes. Concurrency partitions the first variable, not
  individual propagator activations; workers do not share mutable Net state.
- The comparison measures search/query strategy over common inferred knowledge.
  It does **not** show that checkpoint search computes transitive closure faster
  than relation propagation. For this purely positive query, a direct indexed
  relational join could avoid enumeration altogether; that is not benchmarked.
- Tiny fixed graphs cannot establish large-graph scalability, optimality or a
  universal strategy ranking. Allocation is cumulative bytes, not resident heap.
- No domain branches in the kernel, compiler implementation changes, or runner constructor changes.
  No KIROSHI mutation and no push are included.

## Reproduce

```sh
clojure -M -m examples.lain.amb.transitive-solver 3
clojure -M:test propagators.experimental.amb-transitive-solver-test
clojure -M:test propagators.experimental.amb-transitive-solver-test propagators.experimental.amb-swappable-solver-test propagators.experimental.amb-lain-brute-force-test propagators.experimental.amb-enumeration-test propagators.experimental.amb-parallel-test propagators.experimental.amb-strategy-test propagators.experimental.amb-checkpoint-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

[Implementation](../../examples/lain/amb/knowledge.clj),
[benchmark driver](../../examples/lain/amb/transitive_solver.clj),
[tests](../../test/propagators/experimental/amb_transitive_solver_test.clj),
[raw measurements](TRANSITIVE_REASONING_RESULTS.edn).
