# Swappable solvers over one Lain constraint question

## Acceptance condition

2026-10-13. This experiment strengthens the previous compiler comparison:

1. Declare the arithmetic question as an ordinary Lain network definition.
2. Attach a solver to three missing cells from Lain.
3. Give repair, checkpoint DFS, and concurrent checkpoint DFS one protocol.
4. Compile once and switch the solver cell in the same environment, without
   changing the constraint, source code, compiler, background runner, or wiring.
5. Enumerate complete answer sets, not just compare first-answer latency.

All code is additive under `examples/lain/amb/` and focused experimental tests.
No production runtime/compiler/TTMS/compound code was changed. No commit, push,
or KIROSHI mutation is included. Existing unrelated document moves are preserved.

## Lain declaration

The runnable [solver.lain](../../examples/lain/amb/solver.lain) is:

```clojure
(let-cell [a b c]
  (define constraint
    (network (a b c)
      (and (and (<= a b) (<= b c))
           (= (+ (* a a) (* b b)) (* c c)))))
  (solve solver constraint (list 1 2 3 4 5) a b c))
```

`solver` is a cell containing an immutable protocol implementation. The opt-in
library also exposes `repair-solver`, `checkpoint-solver`, and `concurrent-solver`
as values. The question is not lowered to a host predicate or specialized by
strategy. The result is a sorted vector of all satisfying tuples.

The solver uses the supplied cells in a branch snapshot. It does **not** commit
guesses back to the outer missing cells or write multiple incompatible answers
into them. The answer collection is the explicit output of `solve`.

## Shared protocol and contextual composition

[solver_strategy.clj](../../examples/lain/amb/solver_strategy.clj) defines:

```clojure
(defprotocol ConstraintSolver
  (solve-question [solver question context]))
```

All implementations return at least:

```clojure
{:status :completed :answers [[3 4 5]]}
```

The shared question contract is:

```clojure
{:network immutable-net
 :tasks initial-tasks
 :sources [a-id b-id c-id]
 :domain finite-values
 :cells {:valid valid-id :failure certificate-id
         :tuple tuple-id :output output-id}
 :callable constraint-cell-id}
```

The immutable context is `{:workers 4}`. The execution boundary curries the
selected solver and prepared question:

```clojure
(let [solve (partial strategy/solve-question selected built)]
  (solve context))
```

The thread pool is dynamically supplied by the runtime outside Net. Neither a
pool nor a mutable choice registry is put into a cell or captured in a Net
activation. Observational counters are likewise outside Net.

## Boundaries

- **Question preparation:** one ordinary propagator reads the Lain callable and
  domain, uses `application/install-application`, and stores the immutable
  question in an ordinary cell. Lexical capture uses the existing compiler
  contract. Preparation is independent of the selected strategy.
- **Solver execution:** a second propagator reads the selected implementation
  and prepared question, invokes the protocol, and publishes ordinary messages.
  Its receipt cell suppresses duplicate selection. A different solver runs from
  the same saved question; it does not inherit another solver's learned state.
- **Repair:** reuses existing AMB/require/learn/backtrack and the previously tested
  all-answer observer. Logical no-goods and request-scoped answer exclusions remain
  distinct.
- **Checkpoint:** reuses the existing cell-owned frontier and answer/resume
  observer. The solver's internal subnet runs with the ordinary constructed
  runner; the background runner is unchanged.
- **Concurrent checkpoint:** partitions the first source's finite range, runs
  immutable branch states through the caller-owned executor, and unions answer
  tuples. It never merges branch Net values or fires activations concurrently
  against a shared Net.

A shared completion gate ensures a true constant constraint still enumerates
all required assignments, instead of accepting an incomplete tuple. This gate
currently waits for all three inputs for both success and failure. Consequently
this benchmark does not measure early partial-failure pruning or range-domain
contraction. No domain branch enters the runner constructor.

## Brute force written in Lain

[brute_force.lain](../../examples/lain/amb/brute_force.lain) defines recursive
`append` and `flat-map`, then nests three `flat-map` calls. For each assignment it
calls the same Lain constraint and returns either a singleton tuple list or an
empty list. A structural test compares the two constraint declarations exactly.

There is no host-side tuple enumeration or predicate callback in that baseline.
The Clojure host only compiles, executes, bounds the experiment, and reads lists.
The independent host oracle is used only to check results.

This is a separate baseline program, **not** a fourth interchangeable protocol
implementation. It uses ordinary list/scalar primitives, while the three solvers
use TTMS-aware scalar bindings. The default primitive environment lacks `and`,
so the baseline adds the existing scalar `bool4/and` via a normal session
extension. Compiler and kernel code remain identical. This makes the baseline a
semantic/representation comparison, not an isolated TTMS algorithm comparison.

## Measurement

One warmed JVM, Java 25.0.2, 10 available processors, caches disabled, three
samples per completed mode after one warmup. Mode order alternates. Compilation,
initial question preparation, pool construction, and warmup are excluded from
search times. Solver/control installation, branch propagation, learning,
collection, exhaustion, dispatch, joining, and result publication are included.

The three solvers and a sequential partition control share the same compiled
environment and prepared immutable question. Measured repetitions start from
that common prepared snapshot, avoiding order-dependent receipts or histories.
A separate test switches strategies sequentially in a single evolving environment.

Brute force includes all its runtime list traversal and generated topology. It
does not receive precomputed tuples. Its limits are cooperative: 60 seconds or
200,000 activations. A bounded run supplies no complete answer set and no median
completion time. Cumulative allocation is not resident heap usage.

## Results: median seconds, complete enumeration

| Domain | Answers | Repair | Checkpoint DFS | Sequential partitions | Concurrent checkpoints | Lain brute force |
|---|---:|---:|---:|---:|---:|---:|
| `1..2` | 0 | 0.428 | 0.385 | 0.381 | 0.220 | 3.185 |
| `1..4` | 0 | 2.914 | 1.853 | 1.848 | 0.546 | bounded at 60 s |
| `1..5` | 1 | 5.716 | 3.365 | 3.446 | 1.466 | bounded at 60 s |

All **39 measured completed samples** match the exhaustive oracle. Domain 5
returns exactly `[[3 4 5]]`. The bounded brute-force warmups are not part of those
39 samples and are not interpreted as empty results.

At domain 5, checkpoint DFS is **1.70×** faster than repair; concurrent checkpoints
are **3.90×** faster than repair and **2.35×** faster than the sequential partition
control. Four workers executed at domains 4 and 5; domain 2 has only two partitions.

| Domain 5 strategy | Activations | Completed leaves | Cumulative allocation | Largest observed individual env |
|---|---:|---:|---:|---:|
| Repair | 79,807 | not separately counted | 9.73 GB | 782 |
| Checkpoint | 47,780 | 125 | 5.68 GB | 760 |
| Sequential partitions | 47,779 | 125 | 5.69 GB | 760 |
| Concurrent partitions | 47,779 | 125 | 5.68 GB | 760 |

Sequential and concurrent partitions have identical activation and leaf counts
in every measured sample. Repair retains 12 logical no-goods and one answer
exclusion at domain 5. Outer topology remains at 277 cells and 212 propagators
for all four solver modes. Internal search state is not installed into the parent.

The domain-2 brute-force result expands to 4,487 cells and 5,601 propagators, with
13,009 activations. Its median cumulative allocation is 4.18 GB. At bounds 4 and
5 it reaches the time limit with 31,738 and 34,816 env entries respectively. These
are partial runs, not complete topology sizes or exact candidate counts.

The evidence supports faster checkpoint execution **for this compiled question
under these conditions**. More activations and allocations accompany repair's
withdrawal/bring-in and learning machinery, but no component-level CPU profile
isolates their individual costs. More selective constraints and early no-good
learning could change the comparison. No universal strategy ranking is claimed.

## Verification and limitations

Focused tests prove compile-once same-environment switching, identical prepared
question identity, unchanged outer graph, no outer source writes, duplicate
selection idempotence, all three protocol implementations, eight answers for a
true constant predicate, zero-answer exhaustion, lexical capture, late solver
arrival, malformed inputs, and missing-executor failure. Baseline tests prove
exact declaration correspondence, valid/invalid tuple evaluation, and bounded
execution distinct from exhaustion.

Full focused AMB regressions: **58 tests, 866 assertions, zero failures/errors**.
Of these, the two new namespaces contribute 8 tests and 76 assertions. Three
existing assertions characterize the known whole-compound switch error rather
than successful solver behavior. No existing assertion was weakened.

The post-benchmark callback-count check ran repair, checkpoint, and concurrent
checkpoint at bounds 2, 4, and 5: all nine calls executed exactly once and
returned the oracle answer set. It was not used for timing medians. Static lint
reported zero errors/warnings for all eight new Clojure files; production Clojure
diff and whitespace checks were clean.

The complete repository suite is not claimed green or run in this slice. The
existing whole-compound switch error remains an explicit characterization test.

Limitations: three independent initially empty required cells, one shared finite
domain, fixed pure context, and historic answer snapshots. Rebuild the environment
when the callable/domain changes. Live context retraction, retracted solver
selection clearing historic answers, arbitrary effects in branch snapshots,
partial preassigned cells, and general graph-reasoning performance are not proved.

## Reproduce

```sh
clojure -M -m examples.lain.amb.solver-benchmark 3
clojure -M:test propagators.experimental.amb-swappable-solver-test propagators.experimental.amb-lain-brute-force-test
clojure -M:test propagators.experimental.amb-swappable-solver-test propagators.experimental.amb-lain-brute-force-test propagators.experimental.amb-compiler-checkpoint-test propagators.experimental.amb-enumeration-test propagators.experimental.amb-parallel-test propagators.experimental.amb-strategy-test propagators.experimental.amb-checkpoint-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

Same-environment selection uses existing cell publication, not recompilation:

```clojure
(let [compiled (lain/compile-source (lain/source-for 5))
      initial (lain/prepare compiled)
      checkpoint (lain/select-and-run compiled (:network initial) :checkpoint)
      repair (lain/select-and-run compiled (:network checkpoint) :repair)]
  (:answers repair))
```

For concurrent selection, bind `strategy/*executor*` to a caller-owned
`worker-driver/pool` and close it in `finally`, as the benchmark does.

[Raw results](SWAPPABLE_SOLVER_RESULTS.edn),
[shared operator](../../examples/lain/amb/solver_operator.clj),
[question instantiation](../../examples/lain/amb/solver_question.clj),
[protocol implementations](../../examples/lain/amb/solver_strategy.clj),
[same-environment tests](../../test/propagators/experimental/amb_swappable_solver_test.clj).

The initial raw `:solver-calls` field records completion presence (0 or 1).
Instrumentation was subsequently tightened to count actual callbacks; the
same-environment test and a separate post-benchmark verification check exact
one-call behavior. That instrumentation verification is not included in the
timing medians above.
