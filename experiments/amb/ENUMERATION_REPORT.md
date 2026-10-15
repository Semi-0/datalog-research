# Composable All-Answer Enumeration: Repair versus Checkpoints

Date: 2026-10-13. Status: additive builder experiment, not a production migration.

## Outcome

All strategies enumerated the complete answer set and explicitly exhausted their
remaining search space. No first-answer solver, existing AMB primitive, runner,
compiler, cell evaluator, TTMS rule, or compound synchronization was patched.

For `1..12`, all modes returned exactly:

```clojure
[[3 4 5] [6 8 10]]
```

Three-sample medians: repair **46.58 s**, checkpoint **11.60 s**, four-worker
partitioned checkpoint **3.19 s**. These are full-enumeration times, not first-
answer timings. The controlled sequential-partition baseline took **11.71 s**.

The experiment demonstrates extension through composing propagators. It does
not establish that checkpoint search universally beats dependency-directed
repair, nor that a different data representation is faster: all compared modes
reuse the same TTMS-supported question declaration.

## Grounding in the paper

Section 6.3 of Radul and Sussman's
[The Art of the Propagator](https://groups.csail.mit.edu/mac/users/gjs/6.945/readings/art.pdf)
describes hypothetical guesses, premise withdrawal, reasons against alternatives,
and resolution when alternatives are exhausted. Search occurs through network
activity rather than a special search scheduler. The
[Revised Report](https://groups.csail.mit.edu/mac/users/gjs/propagators/)
also describes the `amb`/`require`/`forbid` mechanism.

Our repair variant reuses the repository's existing finite-nary AMB adaptation.
It has explicit TTMS source epochs and a deterministic repair propagator; it is
not a literal reproduction of the paper's binary guessers or global premise
store. Recording a solution and adding a request-scoped exclusion is our
enumeration extension, not an all-answer routine copied from the paper.

## Fixed question and ownership

One immutable question is constructed per bound: positive `a`, `b`, `c` in
`1..n`, with `a <= b <= c` and `a*a + b*b = c*c`. It uses the existing supported
predicate, tuple, `require`, and switch topology. The repair and checkpoint
compositions share those original cell IDs and activation objects.

| Module | Owns | Does not own |
|---|---|---|
| [enumeration_data.clj](../../examples/lain/amb/enumeration_data.clj) | Existing snapshot publication and explicit retained-set reads | New merge/strongest rules |
| [checkpoint_enumeration.clj](../../examples/lain/amb/checkpoint_enumeration.clj) | Answer observation, frontier resumption, union of completed partition answers | Replacement of first-answer activations |
| [repair_enumeration.clj](../../examples/lain/amb/repair_enumeration.clj) | Record observation, answer exclusions, derived search knowledge | Changes to AMB, learning, repair policy, or question truth |
| [enumeration_benchmark.clj](../../examples/lain/amb/enumeration_benchmark.clj) | Timings, counters, allocation, independent oracle | Logical execution state |

Logical solver state is in normal cells and immutable checkpoints. No closure-
local logical atom, private executor, compatibility facade, or component
reparenting was introduced. The existing generic worker driver owns threads.
Benchmark atoms measure work only and remain outside Net. New named functions
have at most four explicit arguments.

## Checkpoints: add an observer, retain the original solver

The first-answer solver already keeps unexplored jobs in its frontier when it
publishes a solution. `extend-all-answers` installs one additional ordinary
propagator and one answer cell:

```text
unchanged first-answer solver
  -> solution frontier (still contains pending alternatives)
  -> collect-and-resume propagator
       -> record the answer
       -> resume that frontier, or mark exhaustion if no jobs remain
  -> unchanged first-answer solver continues
```

The original select, evaluate, and collect activations remain identical objects.
The observer removes only terminal answer/snapshot fields, preserves pending
jobs and counters, and republishes the frontier through the existing source
snapshot mechanism. It does not turn a valid answer into a failed constraint.
The original standalone first-answer network continues to stop at its first
answer when this extension is absent.

Partitioned enumeration reuses the previous range-state preparation and generic
worker dispatch. It splits only `a`; all workers run this same extended topology
to exhaustion. The coordinator unions answer tuples, never branch Net state or
TTMS evidence. Disjoint ranges and complete branch answer sets are verified.

## Repair: compose an all-answer consumer with AMB

`extend-question` adds the unchanged `p:amb`, `p:learn`, and `p:backtrack` to the
existing question. It also adds a choice-record observer, answer observer, and
search-exclusion composition propagator:

```text
constraint failures -> existing learn -> logical no-goods ----+
                                                            |
valid tuples -> answer observer -> answer exclusions --------+-> resolution
                  |                                              |
                  +-> retained answer set                        v
                                                    existing backtrack
                                                         |
                                             withdraw -> choose -> question
```

The answer observer accepts only a usable, currently supported tuple matching
the active choice records. It rejects an old answer whose source version has
been withdrawn, even before downstream retraction tasks have been drained.

Keep three stores distinct:

```clojure
logical-no-goods ; deductions from actual failed constraint evaluations
answer-exclusions
;; #{ {:literals #{[source-a index-a] [source-b index-b] [source-c index-c]}
;;     :context #{{:source enumeration-request-cell
;;                 :timestamp 1 :premises-status :active}}} }
search-exclusions ; closure of the above union, used only for remaining search
```

The context source is a real injection cell. A recorded valid answer is excluded
only under this fixed enumeration request; it is not declared a contradiction
of the original question. Resolution over the union can prove that the remaining
query has no alternatives. An empty no-good with the request context therefore
means enumeration is complete, not that the original question had no solutions.
Only zero recorded answers plus exhaustion means no answer was found.

The unchanged repair policy still names its exhausted result `:unsatisfiable`.
The enumeration adapter interprets this as exhaustion of the **remaining** query.
Original logical knowledge is kept separately and tested not to block valid
answers. Resolution caches its immutable inputs/result in an explicit cell;
choice epochs are not cache keys because logical resolution uses stable domain
indices, not transport timestamps.

## Reproduction and example

From the repository root:

```sh
clojure -M -m examples.lain.amb.enumeration-benchmark 3
clojure -M:test propagators.experimental.amb-enumeration-test
```

```clojure
(require '[examples.lain.amb.checkpoint-question :as question]
         '[examples.lain.amb.checkpoint-enumeration :as checkpoints]
         '[examples.lain.amb.repair-enumeration :as repair]
         '[examples.lain.amb.worker-driver :as workers])

(let [q (question/build 12)
      template (checkpoints/template q)
      pool (workers/pool 4)]
  (try
    {:checkpoint (:answers (checkpoints/run template))
     :partitioned (:answers (checkpoints/run-partitions template 4 pool))
     :repair (:answers (repair/run (repair/extend-question q)))}
    (finally (workers/close! pool))))
```

Each returns the two tuples above. These are builder-level compositions, not
new compiler syntax or new default bindings. The existing compiled Lain AMB
example is regression coverage, not evidence of compiling these new adapters.

## Measurement protocol

One JVM, Java `25.0.2`, four worker threads, one warmup per mode/bound and three
measured samples. Mode order alternates forward/reverse. Pool creation and
question/extension construction are outside execution timing and recorded
separately. Dispatch caches are disabled.

Every sample must equal an independent exhaustive oracle, not merely contain
valid answers or find the first one. All **36 measured samples** passed. The
four-partition sequential and threaded modes perform identical activation,
predicate-check, and leaf counts. Local worker counters avoid shared hot-counter
contention. Allocation sums worker/coordinator bytes for threaded runs, counting
sequential execution only once.

## Results

Median seconds:

| Bound | Answers | Repair | Checkpoint | Sequential four partitions | Concurrent four partitions |
|---|---:|---:|---:|---:|---:|
| `1..4` | 0 | 0.901 | 0.560 | 0.579 | 0.155 |
| `1..8` | 1 | 8.095 | 3.770 | 3.772 | 1.001 |
| `1..12` | 2 | 46.580 | 11.600 | 11.709 | 3.193 |

Work and allocation for `1..12`:

| Mode | Activations | Predicate calls | Repairs | Allocated GB |
|---|---:|---:|---:|---:|
| Repair | 317627 | 1736 | 1735 | 102.75 |
| Checkpoint | 156454 | 1728 | n/a | 19.82 |
| Sequential partitions | 156453 | 1728 | n/a | 19.82 |
| Concurrent partitions | 156453 | 1728 | n/a | 19.83 |

GB is decimal **cumulative allocated bytes**, not resident/peak heap. The
largest individual Net env had 55 entries for repair and 33 for checkpoints;
this is not a measurement of total retained frontier memory.

Checkpoints tested all `n^3` leaves. Repair predicate calls were 64, 516, and
1736, compared with 64, 512, and 1728 for checkpoints. Repair retained 0/1/2
answer exclusions and 1/21/36 final logical no-goods. The extra predicate calls
are measured runtime calls, not additional unique answers.

The Boolean constraint requires all three inputs and attaches all their support.
It supplies no early local inconsistency to prune here. No-good learning thus
did not reduce predicate evaluation on this topology. Repair adds withdrawal/
bring-in propagation and learning/control work. Its higher activation/allocation
counts support that observation; this is not a component-level CPU profile, so
exact time attribution is not claimed.

Four workers give roughly **3.6–3.8x** speedup over single checkpoint execution
on these cases. Matching the sequential partition workload isolates concurrency
more directly. Unlike first-answer partitioning, every partition's completion
is needed to prove a complete set, so there is no avoidable later-range search
relative to that partitioned enumeration contract.

Raw samples, ranges, worker profiles, setup times, counters, answer sets and
allocation are in [ENUMERATION_RESULTS.edn](ENUMERATION_RESULTS.edn). Compare
first-answer results separately in [PARALLEL_REPORT.md](PARALLEL_REPORT.md).

## Verification and limits

Focused enumeration tests: **7 tests, 65 assertions, zero failures/errors**.
All eight AMB namespaces together: **47 tests, 727 assertions, zero
failures/errors**, including existing first-answer strategies and compiled Lain
AMB. Static lint reported zero errors/warnings. The full repository suite was
not run; existing unrelated whole-compound/TUI limitations are unchanged.
Whitespace, local documentation links, and maximum explicit function arity
were verified. All 36 saved answer sets were rechecked against the exhaustive
oracle, as were matched partition work counts. No production Clojure file
under `propagators/` differs from HEAD.

Focused tests cover complete sets for bounds 1, 4, 5, and 12; preservation of the
standalone first-answer solver and original activation objects; frontier
resumption; all eight answers of an always-true `1..2` question with zero logical
failures; request-scoped exclusions; rejection of delayed stale answers;
idempotent quiescent reruns; and callback/unresolved-value errors not becoming
proofs of exhaustion. Existing AMB, checkpoint, parallel, and compiled-Lain tests
are run separately from the benchmark.

This is bounded, pure, fixed-context enumeration. Request retraction/restarting,
changing domains, live background changes, streaming backpressure, arbitrary
effectful branches, and cross-worker learned knowledge are not supported.
Source epochs may be reused in independent checkpoint states; those states are
never merged. Trampoline stack safety is not a general termination guarantee.

The performance conclusion is scoped to this fully concrete predicate network.
A follow-up should compare genuinely local constraints that expose small
conflicting supports or partial domain restrictions; the current benchmark
cannot decide whether no-good learning wins on those networks. No strategy
was made the default, and no KIROSHI mutation, commit, or push is included.
