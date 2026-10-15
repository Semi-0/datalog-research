# Immutable Range Partitions on Concurrent Worker Runners

Date: 2026-10-13. Status: isolated additive experiment, not a default-runner change.

## Outcome

The hypothesis is supported for sufficiently large independent workloads. Four
workers accelerated the TTMS no-solution case by **3.55x** and the larger plain
finite-domain case by **3.49x**, relative to the unsplit network-owned runner.
The tiny 512-combination domain case got slower. Thread count alone is not a
guarantee of acceleration, especially when partitions do extra first-answer work.

All **150 measured runs** matched the canonical first solution or exhaustion.
Sequential and concurrent runs over the same partitions had identical activation,
predicate-check, decision, and leaf counts. All worker solutions satisfied the
constraint and remained inside their assigned range.

## Ownership and unchanged topology

The question remains positive `a <= b <= c`, `a*a + b*b = c*c`, all in `1..n`.
Split only `a` into balanced, disjoint, contiguous ranges; `b`, `c`, constraints,
and propagator definitions remain unchanged.

TTMS rows use the previous network-owned **checkpoint** solver, not the
learning-enabled AMB solver. No no-good learner is added or shared across workers.

| Module | Owns | Does not own |
|---|---|---|
| [worker_driver.clj](../../examples/lain/amb/worker_driver.clj) | Executor dispatch, caller binding capture, ordered results, failure delivery, pool lifecycle | Range choice, constraints, Net merging |
| [range_search.clj](../../examples/lain/amb/range_search.clj) | Range partitioning, immutable frontier preparation, deterministic answer selection | Thread mechanics, scheduler kernel, premise policy |
| Existing network-search propagators | Select a job, evaluate one choice, collect its result | Parallel scheduling |
| [parallel_benchmark.clj](../../examples/lain/amb/parallel_benchmark.clj) | Per-worker measurements and independent correctness checks | Logical solver state |

Construct the existing solver topology once. Every worker state reuses the same
outer graph, cells, propagator IDs, and identical activation functions. Embedded
question networks also share the same graph/ID sets at dispatch. TTMS initial
quiescence runs once before partition dispatch, so normal initial materialization
is shared rather than recompiled per worker. Ordinary runtime materialization
inside each branch is still allowed; this is not a claim that arbitrary
activations can never declare topology.

For TTMS, each frontier contains the permitted first-source choice jobs. Each job
starts from the shared immutable quiescent question checkpoint. For plain domains,
the worker's initial question state intersects the first domain with its assigned
range. All later choices use the existing network-owned solver.

```text
one declared question + one declared solver topology
  -> immutable range states
  -> normal constructed runner on each worker
  -> ordered completed branch results
  -> canonical answer and its selected branch Net
```

The coordinator selects the lexicographically earliest tuple after **all**
partitions finish. No branch networks, TTMS observations, or same-ID source
epochs are merged. A branch failure remains an error, not an exhausted range.
Only completed solver results can prove exhaustion. Logical frontiers stay in
ordinary cells; executor queues and benchmark counters remain outside Net.

This is automatic worker dispatch for prepared independent range states. It is
**not** automatic parallel firing of arbitrary propagators that write a shared
Net. Such firing would need separate rules for dependencies and patch commits.
No production constructor, scheduler, compiler, cell evaluator, TTMS rule, or
compound behavior was modified. No KIROSHI mutation, commit, or push is included.

## Reproduction

From the repository root:

```sh
clojure -M -m examples.lain.amb.parallel-benchmark 5
clojure -M:test propagators.experimental.amb-parallel-test propagators.experimental.amb-strategy-test propagators.experimental.amb-checkpoint-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

Minimal use of the concurrent runner specialization:

```clojure
(require '[examples.lain.amb.domain-refinement :as domain]
         '[examples.lain.amb.range-search :as ranges]
         '[examples.lain.amb.worker-driver :as workers])

(let [template (ranges/template (domain/build 64))
      pool (workers/pool 4)]
  (try
    (select-keys (ranges/run template 4 pool) [:status :solution :ranges])
    (finally (workers/close! pool))))
```

For the TTMS-supported question use `checkpoint-question/build` instead of
`domain/build`. Supplying `nil` instead of the pool runs the same partitions
sequentially. The generic driver accepts an evaluation function and knows
nothing about domains or Pythagorean triples.

## Measurement protocol

Java `25.0.2`; the JVM reported **10 available processors**. Two reusable pools
have two and four threads. Pool creation is outside timing; one warmup per mode
and case precedes five samples. Mode order alternates forward/reverse. The
original unsplit network-owned runner is rerun in this same JVM; historical
times from [STRATEGY_REPORT.md](STRATEGY_REPORT.md) are not used for speed ratios.

Timed work includes range preparation, shared initial TTMS quiescence, frontier
publication, dispatch, propagation/search, waiting for all branches, and answer
selection. Question/template construction is measured separately once. The
plain-domain contractor's Cartesian enumeration is inside timed activations,
not setup. Caches are disabled consistently.

Every worker has local activation/predicate counters, avoiding a shared hot
counter during execution. The coordinator aggregates completed counters. Worker
names are recorded; a barrier test independently proves actual overlap. Current-
thread allocation is summed across the coordinator and workers for threaded
runs; sequential allocation is counted only once, avoiding double counting.
It is cumulative allocation, not peak heap or total retained branch memory.

This is an exploratory single-machine/JVM benchmark, not a statistical or
cross-machine scalability claim. No external effects, live premise changes,
shared session caches, or asynchronous evaluator callbacks are involved.

## Results versus the unsplit previous runner

Median milliseconds:

| Question | Unsplit | 2 workers | 4 workers | Unsplit / 4 workers |
|---|---:|---:|---:|---:|
| TTMS, `1..4` | 530.85 | 273.88 | 149.73 | 3.55x |
| TTMS, `1..5` | 536.99 | 549.94 | 395.21 | 1.36x |
| TTMS, `1..8` | 1101.42 | 1798.93 | 943.44 | 1.17x |
| Plain domain, `1..8` | 1.426 | 1.756 | 2.620 | 0.54x; slower |
| Plain domain, `1..32` | 21.20 | 12.25 | 8.09 | 2.62x |
| Plain domain, `1..64` | 156.64 | 84.38 | 44.84 | 3.49x |

The two-worker TTMS `1..8` run is slower than unsplit: its slowest partition
exhausts 256 tuples while the unsplit runner needs only 157 tuples to find the
answer. More concurrency can still lose to better early stopping.

## Controlled concurrency comparison

Run the **exact same four partitions** sequentially versus concurrently:

| Question | Sequential partitions ms | Concurrent partitions ms | Speedup |
|---|---:|---:|---:|
| TTMS, `1..4` | 530.97 | 149.73 | 3.55x |
| TTMS, `1..5` | 920.96 | 395.21 | 2.33x |
| TTMS, `1..8` | 2883.16 | 943.44 | 3.06x |
| Plain domain, `1..8` | 4.636 | 2.620 | 1.77x |
| Plain domain, `1..32` | 25.38 | 8.09 | 3.14x |
| Plain domain, `1..64` | 157.91 | 44.84 | 3.52x |

This is the stronger evidence for concurrency itself: work counters match
exactly for sequential and threaded execution. The unsplit comparison also
changes partitioning and stopping behavior.

Predicate checks illustrate those differences:

| Question | Unsplit checks | Four-partition checks |
|---|---:|---:|
| TTMS, `1..4` | 64 | 64 |
| TTMS, `1..5` | 70 | 120 |
| TTMS, `1..8` | 157 | 413 |
| Plain domain, `1..8` | 514 | 514 |
| Plain domain, `1..32` | 33730 | 33016 |
| Plain domain, `1..64` | 273071 | 265128 |

TTMS solution cases do extra work because all partitions finish even after one
has a solution. In the domain case, partitioning changes subsequent projection
sizes; it can reduce cross-candidate recombinations, so its unsplit speedup is
not attributable exclusively to threading.

Threading does not reduce matched total work or allocation. The domain variant
is still the earlier plain finite-domain prototype, without live TTMS domain
provenance/retraction. Its performance must not be presented as a fully supported
TTMS replacement. All samples, branch results, allocation and work counters,
thread names, setup times, and ranges are in [PARALLEL_RESULTS.edn](PARALLEL_RESULTS.edn).

## Verification and remaining limits

- New focused suite: **7 tests, 233 assertions, zero failures/errors**.
- Combined seven AMB suites: **40 tests, 662 assertions, zero failures/errors**,
  including the previous strategy prototypes and compiled Lain AMB example.
- Tests prove balanced disjoint range coverage, dynamic binding capture, real
  worker overlap, ordered delivery, unchanged outer and embedded dispatch
  topology, identical original activations, branch isolation, answer parity,
  domain-12 multiple-solution selection, failure/rejection delivery, and cleanup.
- Lint reports zero errors/warnings. New functions have at most four explicit
  arguments, verified from namespace metadata. Whitespace and local-link checks
  passed; no production Clojure file under `propagators/` differs from HEAD.
  The saved answers/statuses and matched work counters were independently
  rechecked. The full repository suite was not run.

The next bounded experiment could add cooperative cancellation or dynamic
work distribution. For canonical first-answer search, a later successful range
cannot terminate earlier ranges without proving they lack a smaller solution.
“Any valid solution” would be a different stopping contract. Neither policy is
implemented here.

This experiment is for independent pure branches and fresh, fixed questions.
It does not prove thread safety for arbitrary effectful/stateful closures,
safe live retraction, shared learned knowledge across branches, a single shared
parallel Net, or universal linear scaling. The solver strategies remain opt-in
and the kernel remains unchanged.

Follow-up: [all-answer enumeration](ENUMERATION_REPORT.md) reuses these worker
and range primitives to exhaust every partition, alongside a repair comparison.
