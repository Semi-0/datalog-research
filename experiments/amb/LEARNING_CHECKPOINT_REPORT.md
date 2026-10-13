# Checkpoints with no-good learning

Follow-up: [concurrent learning and larger benchmarks](CONCURRENT_LEARNING_REPORT.md)
now compose the hybrid with the existing partition driver. Results below remain
the original single-threaded-hybrid comparison; they are not replaced by the
follow-up measurements.

2026-10-13. Additive experiment following
[transitive reasoning](TRANSITIVE_REASONING_REPORT.md). No production compiler,
runner, Net shape, TTMS, cell evaluator, or existing solver implementation changes.
No commit, push or KIROSHI mutation is included.

## Hypothesis and matched controls

Immutable checkpoints avoid withdrawal/repropagation when abandoning a branch.
Sound no-goods might additionally avoid propagating later assignments sharing
the same inconsistent subset. Those are separate mechanisms.

Five policies implement the existing `ConstraintSolver/solve-question` protocol:

1. Existing premise repair, with its existing learning/resolution.
2. Existing checkpoint DFS, no learning.
3. Existing four-worker concurrent checkpoint DFS, no learning.
4. **Early checkpoint control:** observes the required predicate before the
   all-input completion gate; no learned knowledge.
5. **Learning checkpoint:** identical early-checkpoint machinery, plus retained
   no-goods and a pre-propagation assignment check.

The early-checkpoint control is necessary: otherwise improved runtime could be
mistakenly attributed to learning when it actually comes from early rejection.

## Question and proof boundary

The experiment compiles the same Lain question once per fixture. Only the solver
cell changes. The new policies are ordinary first-class values installed through
the standard session-extension API; neither compilation nor background execution
changes when they are selected.

```clojure
(let-cell [a b c]
  (define constraint (network (a b c) REQUIRED-PREDICATE))
  (solve solver constraint DOMAIN a b c))
```

The existing harness waits for all three inputs before publishing a complete
tuple. That gate can add an irrelevant variable to a failure's support. The
hybrid does **not** remove it or weaken its success condition. Instead, public
application metadata identifies the required callable's result before that gate.

Only a current, usable Boolean `false` at that required result can refute a
branch early. An intermediate `false` under an `or`, for example, does not prove
failure and is never used. Unknown, contradictory and stale values are not
interpreted as failed choices. Complete success still uses the original gate
and complete tuple.

The pre-implementation probe verified that `reachable?(facts,a,b)` was false
with exactly the two real `a`/`b` source premises while `c` was unknown and the
existing completion gate remained pending. No IR heuristic or manually deleted
premise was needed.

## Explicit state and ownership

Four ordinary solver propagators form a control network:

```text
frontier -> select -> job -> evaluate -> evaluated
                                         |
                                         v
                           knowledge <- learn -> processed-result
                                                    |
                                                    v
                                            collect -> frontier
```

Branch jobs contain immutable `{network tasks}` snapshots. Knowledge is a
separate solver-owned cell outside those snapshots, so restoring a world does
not undo learning. There is no semantic atom, private executor, global registry,
or search branch in the runner constructor.

Learning publishes knowledge before the processed result. Collection cannot
dispatch the next job with stale knowledge. Job IDs and value guards prevent
duplicate evaluation or manufactured source epochs.

No-good contract is reused unchanged:

```clojure
{:literals #{[actual-guess-source-cell domain-index] ...}
 :context  #{actual-external-premise-version ...}}
```

Choice literals describe logical alternatives, not their temporary transport
epochs. External context still contains exact `:source`, `:timestamp`, and
`:premises-status` entries. Choice records obtain their active versions from
actual source-cell state; the learner does not invent timestamps.

The existing certificate converter rejects stale versions. Matching and
subsumption reuse existing learning operations. Contexts must match actual
branch state; a changed/retracted external premise prevents reuse. Storage is
fresh per question and is not carried across queries or changed domain order.

This first prototype inserts and subsumes observed no-goods incrementally.
It does not add finite-domain resolution, conflict minimization probes,
backjumping, watched literals, or cross-worker learning exchange. The hybrid is
single-threaded; the existing concurrent policy remains the parallel baseline.

## Fixtures and complete-answer semantics

- **Conjunction, actual five-cell dataflow fork/join:**
  `(and (reachable? facts a b) (reachable? facts b c))`, seven answers.
  Some failures have two-source support from the first conjunct; others include
  all three choices. Such reasons may have no reuse under ordered DFS.
- **Prefix, four-node graph chain:** `(reachable? facts a b)`, with `c` free.
  Twenty-four answers. An invalid prefix can reject all values of `c` immediately;
  early rejection might already capture the benefit without learning.
- **Suffix, the same chain:** `(reachable? facts b c)`, with `a` free.
  Twenty-four answers. A learned bad `b,c` pair can be reused under later `a`
  choices, giving a direct test of learning beyond early refutation.

Each inferred relation is produced by the existing Lain knowledge network and
checked against independent BFS. Positive reachability is fixed during a query.
The oracle computes the complete expected tuple set independently of search.
Every learned no-good is checked against every valid tuple after timing.

Pruned prefixes represent multiple assignments. `covered-assignments` counts
that coverage and must equal `n^3`; it is not the number of physically propagated
leaves. `pruned-jobs` counts proposals blocked before `tell`; `propagated-leaves`
counts actual complete candidate evaluations. These quantities are reported
separately to avoid calling unvisited branches evaluated leaves.

## Verification so far

Focused tests: **9 tests / 65 assertions**, zero failures/errors. Static lint:
zero errors/warnings on four new Clojure files. Tests establish:

- precise required-result support despite a pending completion gate;
- all five policies swapping in one environment with identical prepared-question
  identity, unchanged parent topology and no guesses written into the parent;
- restored checkpoints preserving solver-owned learning;
- an already blocked proposal performing no network propagation;
- changed or retracted external contexts preventing reuse;
- intermediate falsehood under `or` producing no global no-good;
- constant false learning an empty no-good, distinct from unusable results;
- idempotent insertion and compatible-context subsumption.
- direct selection of `learning-checkpoint-solver` as a literal Lain binding.

The three-node suffix test learns six two-literal no-goods, skips twelve
propagations, and reduces propagated complete assignments from 27 to 15 with
identical answers and coverage of all 27 assignments. The prefix test has six
early failures and no later cache hits: its pruning is due to early refutation,
not learning. Both observations are tested, not inferred from timings.

The broader focused AMB run passed **76 tests / 1,046 assertions**, zero
failures/errors, including the prior compiler/builder, transitive, swappable,
parallel, enumeration, checkpoint, learning and runtime checks. The full
repository suite is not run or claimed green.

Final static audit confirmed at most four explicit parameters for every new
function, valid report links, parseable raw EDN, all 45 saved samples' answer
parity and coverage checks, and no production Clojure diff. The seven new
experiment/code/test/report files remain uncommitted; existing work is preserved.

## Benchmark

One JVM, caches disabled; one warmup and three measured samples per policy/fixture,
alternating policy order. A shared four-thread pool is created outside timing.
Compilation, common inference and outer preparation are reported separately.
Timed work includes solver installation, delayed predicate topology, complete
enumeration, collection and publication. Correctness/proof checks are untimed.

Final three-sample medians, complete enumeration in seconds:

| Required predicate | Answers | Repair | Checkpoint | Concurrent checkpoint | Early-only control | Learning checkpoint |
|---|---:|---:|---:|---:|---:|---:|
| Conjunction, dataflow | 7 | 4.406 | 2.305 | 0.988 | 1.238 | 1.257 |
| Prefix, graph chain | 24 | 1.180 | 0.716 | 0.211 | 0.471 | 0.474 |
| Suffix, graph chain | 24 | 1.690 | 1.085 | 0.304 | 1.146 | 0.771 |

All **45 measured runs** complete, invoke the selected solver exactly once,
match the complete independent answer oracle, and pass the logical no-good
soundness check. Only repair's logical no-goods are checked as failures, not its
answer-exclusion blockers. New policies cover exactly the full `n^3` assignment
space while sometimes avoiding physical evaluation of excluded assignments.

| Predicate | Baseline complete leaves | Early-only propagated leaves | Learning propagated leaves | Learned widths | Pre-propagation cache hits |
|---|---:|---:|---:|---|---:|
| Conjunction | 125 | 45 | 45 | 16 two-literal; 38 three-literal | 0 |
| Prefix | 64 | 24 | 24 | 10 two-literal | 0 |
| Suffix | 64 | 64 | 34 | 10 two-literal | 30 |

**The learning hypothesis is supported for repeated conflicts, not universally.**
For the suffix case, learning reduces elapsed time by approximately 29% versus
plain checkpoint and 33% versus the matched early-only control. Its thirty cache
hits are independent of early rejection (there are zero early failures there).
The solver propagates 34 complete candidates instead of 64, while still returning
all 24 answers. Activations fall from 14,869 for plain checkpoint to 9,733 for the
hybrid. Median cumulative allocation falls from about 1.79 GB to 1.29 GB.

Prefix pruning is already achieved by early refutation. Each bad `(a,b)` prefix
is encountered once in this ordering, so the learned prefix reasons have no
future hits. Learning adds roughly 0.7% median time to the matched control.

The conjunction also gains primarily from early refutation: sixteen false
prefixes avoid propagating `c`. The current required-result support is sufficient,
but not necessarily a minimal logical conflict. First-conjunct failure can have
two-source support; a second-conjunct failure can retain the first conjunct's
condition support. The hybrid learns 54 reasons but has no future cache hits;
its median is approximately 1.6% slower than the early-only control. It is not
correct to credit that case's large improvement over plain checkpoint to learning.

Concurrent checkpoint remains fastest on these small fixtures. This prototype
does not yet combine learning with workers or establish that learning beats
parallelism. It demonstrates a measured reduction in single-threaded work when
the same bad subset recurs under irrelevant choices.

Setup is outside these medians and is preserved in raw data. The first knowledge
compilation includes cold initialization. These are single-JVM, three-sample,
bounded-fixture observations; allocation is cumulative bytes, not resident heap.

The initial 45-run benchmark also passed, but its tool-output capture was
truncated. The saved raw artifact contains only the complete refreshed 45-run
benchmark after the untimed repair-proof reporting update. The two runs are not
pooled into one median.

## Limitations

Pure, fixed, finite, three-variable queries only. This is not a general SAT/CDCL
solver, optimal graph engine or arbitrary effect-isolation guarantee. Context
matching is tested, but live knowledge reload and query retraction are not added.

No-goods can reduce work only when they exclude unvisited combinations. Coarse
full-assignment reasons may add storage and lookup cost without saving anything.
Small graphs and single-JVM samples cannot establish universal speedups.

## Run

```sh
clojure -M -m examples.lain.amb.learning-checkpoint-benchmark 3
clojure -M:test propagators.experimental.amb-learning-checkpoint-test
```

[Lain declaration](../../examples/lain/amb/learning_checkpoint.lain),
[proof boundary](../../examples/lain/amb/checkpoint_proofs.clj),
[solver control](../../examples/lain/amb/learning_checkpoint.clj),
[benchmark](../../examples/lain/amb/learning_checkpoint_benchmark.clj),
[tests](../../test/propagators/experimental/amb_learning_checkpoint_test.clj),
[raw measurements](LEARNING_CHECKPOINT_RESULTS.edn).
