# Composable AMB with TTMS

Experiment documentation is collected here. Runnable code and data remain in
[`examples/lain/amb/`](../../examples/lain/amb/).

Follow-up: [dependency-directed backtracking](../../propagators/doc/dependency-directed-backtracking.md)
shows how an opt-in compiler adds file premises to ordinary Lain procedures,
and how AMB selects a procedure file fitting a separately loaded target. The
[session composition example](../../examples/lain/amb/file_premise_selection/composition.lain)
declares the module loads and query directly in Lain.

An opt-in finite-domain experiment. The search policy is a propagator, not a
runner modification. The unchanged normal runner reaches quiescence after
learning and repairing choices.

Follow-up: [immutable checkpoints versus TTMS withdrawal](CHECKPOINT_REPORT.md)
compares matched DFS restoration policies and records a separate existing-AMB
reference. It does not replace this learning-enabled solver.

Follow-up: [three network-owned solver experiments](STRATEGY_REPORT.md) compares
indexed learning, domain contractors, and a cell-owned checkpoint frontier with
the previous examples in one benchmark run. These remain opt-in builder prototypes.

Follow-up: [concurrent immutable range workers](PARALLEL_REPORT.md) measures two
and four worker threads without changing the question or runner constructor.

Follow-up: [all-answer enumeration](ENUMERATION_REPORT.md) extends the existing
first-answer checkpoint network and composes a paper-inspired repair consumer.
It compares complete sets, not first-answer latency.

Follow-up: [compiled Lain versus builder checkpoints](COMPILER_CHECKPOINT_REPORT.md)
verifies the same first-answer DFS through direct primitive calls, functional
network application, and a fully compiled arithmetic constraint.

Follow-up: [swappable solvers in one Lain environment](SWAPPABLE_SOLVER_REPORT.md)
compares all-answer repair, checkpoint, and concurrent checkpoint strategies
through one protocol and one solver cell, plus a pure-Lain brute-force baseline.

The executable Lain example is `pythagorean.lain`. Domain `1..5` returns the
ordinary compound list `(3 4 5)`. Domain `1..4` has no solution and retains an
explicit `{:action :unsatisfiable}` repair result plus the empty no-good.

## Same-environment solver experiment, 2026-10-13

The newer [`solver.lain`](../../examples/lain/amb/solver.lain) declares the
constraint as a normal network and attaches a solver to its missing cells:

```clojure
(solve solver constraint (list 1 2 3 4 5) a b c)
```

Repair, checkpoint DFS, and concurrent checkpoint DFS implement the same
`ConstraintSolver/solve-question` protocol. Tests compile once, change the solver
cell in the same environment, and prove identical prepared-question identity,
unchanged outer topology, no outer source writes, and duplicate-request
idempotence. Neither compiler nor background runner changes.

Three-sample medians for complete enumeration, excluding compilation/preparation:

| Domain | Repair | Checkpoint | Concurrent checkpoint | Pure-Lain brute force |
|---|---:|---:|---:|---:|
| `1..2` | 0.428 s | 0.385 s | 0.220 s | 3.185 s |
| `1..4` | 2.914 s | 1.853 s | 0.546 s | incomplete at 60 s |
| `1..5` | 5.716 s | 3.365 s | 1.466 s | incomplete at 60 s |

All 39 completed measurements match the exhaustive oracle. Domain 5 returns
`[[3 4 5]]`; the other two domains have no answers. At domain 5, checkpoint is
1.70× faster and concurrent checkpoint 3.90× faster than repair. Four workers
are used where the domain permits; domain 2 has only two partitions.

The brute-force baseline is separate Lain list enumeration, not a fourth protocol
implementation. It has the identical arithmetic declaration but ordinary scalar
bindings rather than the solvers' TTMS-aware bindings. Incomplete runs are not
interpreted as empty answers or completion-time samples.

Focused AMB regressions passed 58 tests / 866 assertions. This does not claim
the whole repository suite is green. The question is fixed and pure; these
measurements do not establish live context retraction or a universal solver
ranking. [Full report](SWAPPABLE_SOLVER_REPORT.md) and
[raw data](SWAPPABLE_SOLVER_RESULTS.edn).

```sh
clojure -M -m examples.lain.amb.solver-benchmark 3
```

### Concurrent compiled Lain versus builder

A paired four-worker benchmark now compares complete enumeration in compiled
Lain with both the original opaque builder predicate and a builder expressing
the identical arithmetic decomposition:

| Domain | Opaque builder | Decomposed builder | Compiled Lain |
|---|---:|---:|---:|
| `1..4` | 0.158 s | 0.481 s | 0.613 s |
| `1..5` | 0.405 s | 1.201 s | 1.479 s |
| `1..8` | 0.989 s | 2.883 s | 3.517 s |

All 27 measured runs match the oracle and visit the same complete assignments.
Compiled Lain is **22–27% slower than the decomposed builder**. Its 3.56–3.87×
gap against the opaque predicate also includes the cost of decomposing arithmetic
into separate supported applications; it is not isolated compiler overhead.
Compilation and outer preparation are excluded; runtime solver installation and
delayed compiled-body topology are included.

The combined focused AMB suite now passes **60 tests / 896 assertions** with
zero failures/errors. No production runtime/compiler code changed; the full
repository suite was not run for this comparison.

[Detailed comparison](CONCURRENT_COMPILER_BUILDER_REPORT.md) and
[raw results](CONCURRENT_COMPILER_BUILDER_RESULTS.edn).

```sh
clojure -M -m examples.lain.amb.concurrent-compiler-builder 3
```

## Four bindings

```clojure
(amb domain repairs choice-record) ; implicit result: supported chosen value
(require predicate)                ; implicit result: current failure certificate
(learn failure choice-records)     ; implicit result: learned no-good set
(backtrack choice-records knowledge) ; implicit result: addressed repair request
```

`amb` has one explicit metadata output and the usual implicit expression result.
All supplied arguments have the declared roles; the last argument is not guessed
to be the ordinary result. Each choice's source is its real injection cell.

The example declares three choices, checks `a <= b <= c` and `a*a + b*b = c*c`,
then feeds the strategy result back through `repairs`. `require` distinguishes
false from unknown/unusable information. Source withdrawal propagates before a
matching choice request brings in the next alternative.

No-good literals are `[source-cell domain-index]`, independent of transport
epochs. Otherwise returning to a rejected alternative at a later timestamp
would incorrectly escape the learned constraint. No-good context contains exact
external premise versions. Logical minimization/resolution does not use TTMS
support dominance.

Knowledge lives in an ordinary dedicated source cell. It contains logical
implications with their conditions as data, rather than active support on the
failed guesses themselves. Withdrawing a guess consequently does not delete
what was learned from it. There is no mutable choice registry or private executor.

## Running

In a checkout containing these files and the published runtime at
`e3d394441a6bfa2b5c8ea86d841940d73254dfc1`:

```sh
clojure -M -m examples.lain.amb.experiment 5
clojure -M -m examples.lain.amb.builder 5
clojure -M -m examples.lain.amb.benchmark
clojure -M:test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

This workspace was subsequently fast-forwarded to that published runtime and
the experiment reverified directly in the primary checkout. No classpath overlay
or integration checkout is needed:

```sh
cd /Users/linpandi/cloj-leapfrog
clojure -M -m examples.lain.amb.experiment 5
```

The existing default suite registry is unchanged; explicitly name the four
experimental test
namespaces. The default suite run reported below is the existing suite only.

Session installation uses `install-session-extension`, first for the existing
TTMS scalar/branching bundle, then for the four search bindings. It does not
concatenate overriding bindings into one root scope or change compiler defaults.

## Debugging and topology

`experiment/run` returns `:compiled`, `:result`, `:profile`, `:trace`, `:solution`
and `:metrics`. It uses the existing activation profiler and compiled semantic
graph builder. The profiler is observation outside Net, not search state.

```clojure
(require '[examples.lain.amb.experiment :as example]
         '[propagators.debug :as debug])
(let [run (example/run example/source)]
  (select-keys run [:solution :metrics :trace]))
```

For individual failing profile rows use the existing
`debug/describe-profiled-propagator`; for step-by-step logging use
`debug/run-tasks-debug` with the compiled `:props` and `:net`.

## Preserved runtime gaps and equivalent wiring

Two original sketch expressions exposed existing limitations:

1. In this setup `(let-cell [r s] (let [x (+ 1 2)] x))` quiesces at `nothing`,
   while the same-scope `define` form returns `3`. The executable example uses
   same-scope definitions. Nested-let/compiler internals were not patched.
2. `(switch (list a b c) valid)` enters the existing layered/compound boundary
   path and fails with `Expected supported collection content`. The profiler
   identified the TTMS switch activation and nested fast bidirectional sync;
   the rejected object was a set containing a projected layered datum. The
   topology tracer produced 137 nodes and 132 edges for that failed declaration.

The equivalent supported composition is:

```clojure
(list (switch a valid) (switch b valid) (switch c valid))
```

It keeps ordinary compound structure outside the scalar TTMS wrapper and retains
supported members through normal list topology. It returned `(3 4 5)` without
changing compound synchronization, TTMS merge, layered dispatch or the compiler.
The original whole-compound failure has a named characterization test. Its three
assertions are NOT solver acceptance evidence, and its runtime path remains
stopped pending separately approved repair.

## Verification, 2026-10-12

- Experimental: **18 tests, 98 assertions, 0 failures/errors**. Of these,
  95 assertions cover functional contracts/search/transport/integration;
  3 characterize the known whole-compound runtime gap.
- Relevant existing TTMS/layered/branching/compiler/runner/debug regressions:
  **1,557 assertions, 0 failures/errors**.
- Both focused runs were repeated directly in the updated primary checkout;
  their results matched the earlier clean-integration verification.
- Full existing default suite: **4,568 pass, 4 fail, 16 error**. These reproduce
  the published baseline: one nested TTMS compound-boundary failure, and deferred
  TUI/environment fixture failures/errors. No existing assertion was weakened.
- Three builder task orders reached `(3 4 5)`. An observer checked every usable
  outer result it saw; all were valid triples. Nested scratch networks without
  the result cell are explicitly outside that observation.
- Two Lain calls in one environment produced six distinct choice sources and
  two independent triples. Sources resolve to actual cells in Net.
- Withdrawal reached two downstream computations; reactivation restored them.
  Pending constraints retain incoming premise-state information without learning
  falsehood from retraction. Stale certificates and unusable predicates do not
  generate no-goods.
- Duplicate repair requests and quiescent reruns change neither epochs nor
  topology. Initial immutable builder Net values remain unchanged.
- Static checks: clj-kondo reported zero errors/warnings; 62 new named functions
  had maximum declared arity 4. Tracked diff and all untracked additions produced
  no whitespace diagnostics.

## Benchmark

One warmed JVM sample per size, using the existing activation profiler. These
are exploratory measurements, not isolated statistical performance claims;
verification was also running on the machine. Builder topology was 22
propagators initially and 24 after finite watcher installation at every size.

| Domain | Result | Repairs | Retained no-goods | Activations | Run ms | Host chronological tests | Host ms |
|---|---|---:|---:|---:|---:|---:|---:|
| 1..4 | unsatisfiable | 63 | 1 | 14,239 | 1,790.64 | 64 | 0.316 |
| 1..5 | 3,4,5 | 54 | 6 | 12,223 | 1,343.11 | 70 | 0.176 |
| 1..20 | 3,4,5 | 885 | 11 | 198,367 | 20,881.43 | 865 | 1.147 |

The host loop is an answer/search-cost baseline, NOT an equivalent propagator
runtime. This experiment demonstrates composable search, source-linked failure
learning and fixed outer topology. It does not establish a speed advantage;
even repair-count improvement is workload dependent. The builder's single base
constraint procedure is also not the same topology as Lain's composed arithmetic.
The Lain 1..20 case was not benchmarked. Its example harness has a diagnostic
activation cap, independent of strategy.

## Boundaries and remaining work

Preserved: Net shape, runner/constructor, compiler, cell evaluator, TTMS policy,
generic layered dispatcher, ordinary compound synchronization and effect paths.
New code consists only of experimental contracts, pure learning/strategy,
ordinary activations, an opt-in binding bundle, examples and tests. No KIROSHI
facts or runtime contracts were changed.

Grounding reviewed the current runner-semantic-composition constraint (approved
2026-09-25), three-field TTMS constraint (approved 2026-09-29; its model evidence
still requires independent verification), and immutable-Net decision (approved
2026-09-09). Runtime tests, not model approval, establish the results above.

V1 uses distinct positive integer alternatives, static finite domains, fixed
background context and pure arithmetic constraints. It finds one solution.
Logical context matching has pure tests; changing external context during a live
search is not claimed supported. Enumeration, arbitrary effectful alternatives,
changing domains, unrestricted resolution scalability and general compound-valued
choices are deferred. Source-first withdrawal is not whole-Net rollback.

The approved slices are complete for the equivalent per-member Lain composition.
The direct whole-compound switch path and original nested-let sketch remain
documented gaps; repairing either must reopen the fixed-runtime boundary.

## Local workspace publication receipt

With user approval, all prior tracked/untracked changes were preserved in local
stash `615b5d5a9ab4d38d039512542e215aac69500b8f`, named
`codex/amb-before-main-update-2026-10-12`. The workspace's `main` fast-forwarded
from `2b6eba8` to `e3d3944`; only these 16 AMB files were restored for verification
and the experiment commit. Unrelated work remains recoverable in that stash and
was not mixed into the experiment. No remote push is part of this operation.
