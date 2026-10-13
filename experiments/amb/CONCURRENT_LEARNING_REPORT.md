# Concurrent checkpoints with partition-local no-good learning

## Question and boundary

Can the existing checkpoint/no-good hybrid compose with concurrent execution,
while keeping one compiled Lain question and the same solver protocol?

The solver remains a propagator subnet inside the constraint network. Each
partition runs the hybrid's ordinary select/evaluate/learn/collect propagators
using the production continuation-constructed runner. The existing worker
driver dispatches independent immutable states; it does **not** split individual
propagator activations across threads. Only the first choice source is
partitioned into disjoint contiguous ranges. Within a partition, search remains
depth-first, in source order `a`, `b`, `c`.

No production compiler, Net, runner constructor, merge, TTMS, or compound code
changes. Thread pools and profiling/budget counters stay outside Net. Learned
no-goods live in each solver's knowledge cell, outside the child checkpoints
restored during search. There is no shared worker knowledge or mutable search
cache. The observations below therefore test **partition-local learning**, not
cross-worker learning exchange.

## Exact composition

```clojure
(let [built (enumeration/extend-all-answers
             (hybrid/build question {:learn? true}))]
  (enumeration/run-partitions
   {:question question :built built}
   workers executor profile-worker))
```

The worker evaluator reuses `enumeration/evaluate` and reads the final knowledge
cell. The executor is supplied by the existing runtime-owned dynamic binding.
The same record works without an executor for the matched sequential partition
control. Both implement `ConstraintSolver/solve-question`.

An opt-in session extension adds `partitioned-learning-solver` and
`concurrent-learning-solver`. They are normal values accepted by the existing
Lain operator:

```clojure
(let-cell [a b c]
  (define constraint (network (a b c) (reachable? facts b c)))
  (solve concurrent-learning-solver constraint (list 1 2 3) a b c))
```

The external benchmark selection cell swaps policies without modifying the
question, compiler, background runtime, or argument plumbing.

## Comparisons and measurements

Seven policies: repair, checkpoint, concurrent checkpoint, early-refutation
checkpoint without learning, single-worker learning checkpoint, sequentially
partitioned learning, and concurrently partitioned learning.

The last two do identical partition-local work. Comparing them isolates thread
execution; comparing partitioned and unpartitioned learning exposes redundant
proof discovery caused by separated caches.

Three larger fixtures:

- Eight-node directed graph chain, suffix predicate: 512 candidate tuples.
- Twelve-node directed graph chain, suffix predicate: 1,728 candidate tuples.
- Nine-cell dataflow comprising two chained fork/join diamonds, conjunctive
  predicate: 729 candidate tuples. Edges are read from real installed propagator
  input/output ports, not guessed from labels. The terminal base value is 35.

Positive transitive facts are still inferred by the existing Lain feedback
network and checked against an independent BFS. Query answers are checked
against exhaustive enumeration. Inference, query compilation, and preparation
are outside solver timing and reported separately. Selection, solver/control
installation, child topology materialization, search, and publication are timed.

One warmup plus three measured samples per completed policy, alternating policy
order. One JVM, Java 25, four reused workers, caches disabled, 2 GiB maximum heap.
Instrumentation and a cooperative budget wrapper are applied equally through
the experimental profiler. The production runner's public transform composition
is reused; its constructor is unchanged.

Each solve is limited to 30 seconds or one million aggregate runner activations,
including nested and worker profiling scopes. Checks happen between transitions;
an indivisible activation is not preempted. A budget failure is reported as
`:incomplete`, with no answer set or completion-time median. A warmup that
exhausts its budget is retained but not repeated. The heap cap is separate from
the cumulative allocation metric, which is **not peak memory**.

All completed samples must return the exact full sorted oracle answer set,
invoke the outer solver once, cover `n^3` assignments for the hybrid variants,
and have no learned no-good that matches any valid answer. Every partition must
finish before the answer union is published. Branch networks are never merged.

Counters sum local frontiers; `:nogood-count` sums worker-local stores, so a
duplicate proof learned by four workers counts four times. `:nogood-widths`
uses their deduplicated logical union. Allocations from sequential workers are
already included in the parent; threaded worker allocations are added once.

## Results

Three measured samples per completed policy. Median elapsed seconds:

| Policy | Chain 8 | Chain 12 | Dataflow 9 |
|---|---:|---:|---:|
| Repair | incomplete | incomplete | incomplete |
| Checkpoint | 7.611 | 25.475 | 11.980 |
| Concurrent checkpoint | 2.110 | 6.841 | 4.418 |
| Early checkpoint, no learning | 8.024 | 26.973 | 6.387 |
| Learning checkpoint | 4.939 | 15.989 | 6.450 |
| Partitioned learning, sequential | 6.283 | 19.043 | 6.443 |
| Partitioned learning, concurrent | **1.746** | **5.119** | **3.598** |

Exact complete answer counts were respectively 224, 792, and 70. There were
54 completed measured solves, 18 completed warmups, and three incomplete repair
warmups. The repair attempts stopped at approximately 30.096, 30.076, and 30.011
seconds. They do not establish repair's completion time or correctness at these
sizes, and no speed ratio against repair is reported. The raw incomplete
summary retains the failed warmup in its `:samples` field for visibility; it is
not counted as a measured completed sample.

Compared with concurrent checkpoint, the concurrent hybrid reduced median
elapsed time by **17.3%, 25.2%, and 18.6%**. Compared with its identical-work
sequential partition control, threads accelerated it approximately **3.60x,
3.72x, and 1.79x**. These are local measurements, not confidence intervals or
proof of general superiority.

### Learning versus early refutation

| Fixture | Single-worker cache hits | Partition-local cache hits | Hybrid propagated complete leaves | Concurrent checkpoint complete leaves |
|---|---:|---:|---:|---:|
| Chain 8 | 252 | 144 | 368 | 512 |
| Chain 12 | 858 | 624 | 1,104 | 1,728 |
| Dataflow 9 | 0 | 0 | 306 | 729 |

The chain predicates require only `b` and `c`. A rejected pair remains invalid
for other `a` choices, permitting learning to skip propagation. Without
learning, the matched early-only control has no such benefit. Single-worker
learning evaluates fewer worlds than partition-local learning: the four workers
rediscover the same 36 or 78 logical no-goods, storing 144 or 312 local entries
in total. Nevertheless, concurrent execution wins on elapsed time here.

In the dataflow conjunction, the first predicate can reject a prefix before
assigning `c`. There are 47 such early failures, covering 423 full assignments;
the other 306 are propagated. The learned explanations do not recur under this
DFS order, so the store's 283 no-goods produce **zero hits**. The early-only and
learning controls have essentially the same runtime. Its concurrent advantage
must not be credited to learning. The dataflow partitions are also uneven:
their complete propagated leaves are 180, 81, 36, and 9, explaining why ideal
fourfold scaling should not be expected.

### Propagation and allocation

| Fixture | Concurrent checkpoint activations | Concurrent hybrid activations | Checkpoint allocation, GB | Hybrid allocation, GB |
|---|---:|---:|---:|---:|
| Chain 8 | 104,604 | 80,508 | 12.798 | 10.701 |
| Chain 12 | 339,812 | 233,612 | 41.849 | 31.751 |
| Dataflow 9 | 162,044 | 85,357 | 19.887 | 10.766 |

Allocation is summed **cumulative thread allocation**, decimal GB, not resident
or peak memory. The JVM ran with a 2 GiB heap cap. Measured peak environment
entry counts were 697, 713, and 743, respectively, across the completed policies.
This does not mean the controller topologies are identical: the hybrid adds a
learning step and knowledge transport below the larger child's measured peak.

Common setup is included in the raw artifact, outside these timing tables.
The first fixture's inference compilation took 1.887 seconds including cold
loading, versus about 0.059 seconds for subsequent fixtures; these setup figures
are not a size-only scaling comparison.

## Verification

- New concurrent tests: **8 tests, 76 assertions, zero failures/errors**.
- Same sequential/threaded partition answers, proofs, search counters and actual
  activation counts; immutable outer sources remain unassigned.
- Literal Lain solver binding and all seven policies swapped sequentially within
  the same prepared environment; identical question object and unchanged outer
  graph; duplicate selection does not rerun the solver.
- Empty answers, workers exceeding domain size, missing executor rejection,
  nested worker budget failure and correct incomplete reporting.
- Independent raw-artifact verification: 54 complete measured samples; all
  coverage, no-good soundness flags, solver-call counts and aggregate activation
  accounting valid. Sequential/threaded partition work is identical throughout.
- Static audit: no new function exceeds four parameters; report links valid.

Broader AMB regression: **84 tests, 1,122 assertions, zero failures/errors** across
15 namespaces covering concurrent learning, the prior hybrid, transitive
questions, swappable solvers, compiled/builder parity, Lain brute force,
checkpoint enumeration, workers, domain strategies, no-good learning, runtime,
search, and Lain integration. Lint reported zero errors/warnings; `git diff
--check` passed. No production Clojure files changed. The full repository suite
was not run and is not claimed green by this experiment.

Pre-publication verification also exported the staged Git snapshot into a clean
temporary directory, excluding every unrelated unstaged file. Its 13 available
AMB test namespaces passed **79 tests, 1,029 assertions, zero failures/errors**.
The five tests from two older, unstaged compiler-comparison slices were omitted
from that clean-snapshot run. Report links, raw result accounting, partition-work
parity and four-parameter limits passed again in the clean snapshot.

## Reproduce

```sh
clojure -J-Xmx2g -M -m examples.lain.amb.learning-scale-benchmark 3
clojure -M:test propagators.experimental.amb-concurrent-learning-test propagators.experimental.amb-learning-checkpoint-test
```

[Concurrent policy](../../examples/lain/amb/concurrent_learning_checkpoint.clj),
[larger benchmark](../../examples/lain/amb/learning_scale_benchmark.clj),
[budget wrapper](../../examples/lain/amb/search_budget.clj),
[tests](../../test/propagators/experimental/amb_concurrent_learning_test.clj),
[raw results](CONCURRENT_LEARNING_RESULTS.edn).

## Limits

Finite, pure, three-source Boolean questions with fixed facts during a solve.
No live knowledge replacement, unbounded recursion, resolution, backjumping,
work stealing, watched literals, shared proof exchange, or general optimality
claim. A sufficient but nonminimal TTMS explanation can reduce reuse. The
existing context-check helper rejects reuse when a branch carries changed or
inactive external premise versions; this experiment does not test concurrent
context mutation or broadcasting external changes into running checkpoints.

No KIROSHI mutation is included. Commit and push were separately authorized
after the experiment and its verification completed.
