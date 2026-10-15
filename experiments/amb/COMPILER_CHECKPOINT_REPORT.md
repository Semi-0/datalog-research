# Checkpoint search through Lain versus pure builder

## Scope and prior evidence

2026-10-13, additive experiment on the current workspace. No compiler, runner,
TTMS, compound synchronization, or existing solver activation was changed.

Repair **was already tested through Compiler 2** in
`propagators.experimental.amb-lain-test`: the Lain Pythagorean program finds
`[3 4 5]`, reports domain-4 exhaustion, and gives separate choice occurrences to
two function calls. Its original whole-compound switch failure remains a
characterization test, not successful solver evidence.

The recent all-answer repair/checkpoint/partitioned benchmarks were builder
experiments. They did not establish compiled all-answer parity. This follow-up
tests **first-answer checkpoint DFS** through the current functional compiler.
It does not yet compare compiled repair against compiled checkpoint timing or
enumerate all answers through compiled Lain.

## Responsibilities and invariant contracts

- Lain declares the question and installs existing primitives via the standard
  opt-in session extension API.
- The experiment exposes three real source cells through `env/cell-binding`.
  The existing `require` primitive publishes its certificate into a cell known
  to the solver; the adapter changes only where that output is installed.
- `checkpoint-search/run` is reused unchanged: finite DFS, immutable quiescent
  checkpoints, original normal runner, no withdrawal or learning.
- All variants use the same bounds, ordered choices, TTMS source publication,
  require semantics, and final supported vector. The vector is deliberately an
  opaque base; no ordinary compound-list switch boundary is involved.
- Profiles are observational counters outside Net. Caches are disabled.
- No new function has more than four explicit arguments.

### Matched primitive comparison

The builder's `checkpoint-question/build` and two compiled forms share the same
host predicate and vector primitive, each installed through existing supported
procedures. This isolates declaration/application overhead, not arithmetic
decomposition.

```clojure
(let []
  (define valid (predicate a b c))
  (require valid)
  (switch (tuple a b c) valid))
```

The second form wraps that body in `(network (a b c) ...)` and calls it with the
same externally supplied cells. The existing functional application path is
therefore exercised, not bypassed.

### Fully compiled arithmetic comparison

```clojure
((network (a b c)
   (let []
     (define valid
       (and (<= a b) (<= b c)
            (= (+ (* a a) (* b b)) (* c c))))
     (require valid)
     (switch (tuple a b c) valid))) a b c)
```

Here the question is arithmetically equivalent but has **different topology**:
the arithmetic and Boolean expression becomes individual supported primitives.
The tuple remains the same opaque vector primitive. The installed `predicate`
binding is unused in this variant; its closure is not executed.

## Method

Java 25.0.2, single-thread, one warmup per mode/bound, three measured samples,
alternating mode order. Every run is checked against the independent chronological
oracle. Settled-search medians exclude environment construction, compilation,
and initial quiescence. A separate cold-run sample includes initial propagation
but still excludes compilation. All later runtime declaration work is included.

Setup timings are single samples and include JVM/class initialization for the
first case. They are not steady-state compilation benchmarks. The first compiled
setup took about 1.7–1.8 seconds; later compiled setups about 80–146 ms.

Two JVM runs were used: matched-primitive modes together, and arithmetic versus
its own builder control together. Do not form precise cross-run ratios from the
two different builder controls. Allocation counts are cumulative bytes, not
resident heap.

## Results: matched primitive, milliseconds

| Bound | Expected | Builder | Lain direct | Lain network application |
|---|---|---:|---:|---:|
| 4 | exhausted | 542.29 | 724.75 | 836.30 |
| 5 | `[3 4 5]` | 517.46 | 696.99 | 794.13 |
| 8 | `[3 4 5]` | 1062.84 | 1435.93 | 1646.70 |

Direct compiled calls are 1.34–1.35× the builder time; network application is
1.54–1.55×. All **27 measured samples** returned the oracle result. Decisions,
leaves, restores, and depth matched across modes:

| Bound | Decisions | Leaves | Restores | Maximum depth |
|---|---:|---:|---:|---:|
| 4 | 84 | 64 | 84 | 3 |
| 5 | 87 | 70 | 84 | 3 |
| 8 | 180 | 157 | 177 | 3 |

## Results: compiled arithmetic, milliseconds

| Bound | Builder control | Lain arithmetic network | Ratio |
|---|---:|---:|---:|
| 4 | 506.42 | 1781.79 | 3.52× |
| 5 | 502.53 | 1812.80 | 3.61× |
| 8 | 1043.93 | 3744.40 | 3.59× |

All **18 measured samples** returned the oracle result, with the same search
path as the builder. This DFS checks certificates at complete assignments; it
does not exploit earlier partial contradictions for pruning.

At bound 5, settled topology and measured activations were:

| Representation | Cells | Propagators | Search activations | Cumulative allocation |
|---|---:|---:|---:|---:|
| Builder | 20 | 13 | 6873 | ~794 MB (matched run) |
| Lain direct | 272 | 211 | 8178 | ~1104 MB |
| Lain network application | 305 | 249 | 9396 | ~1270 MB |
| Lain arithmetic network | 365 | 326 | 23961 | ~2912 MB (separate run) |

Final outer topology equaled settled topology in all measured modes/bounds.
This is not proof that temporary internal layered evaluation allocates nothing.

Conclusion: checkpoints work with compiled functional Lain and preserve the
tested question's answers and DFS ordering. The compiler-generated environment,
accessor/application topology, and arithmetic decomposition add overhead.
Activation and allocation differences support that interpretation, but no
component-level CPU attribution was measured. No universal solver ranking follows.

## Verification

The new tests cover exhaustion and first answers, repeated use of the immutable
template, source-cell existence, equivalent DFS paths, failed/accepted branches
from the same checkpoint, untouched source cells in the saved root, malformed
bounds/shapes, unresolved predicates, and evaluator errors. Both direct and
network-application variants are tested; arithmetic has answer/restoration tests.

Expanded new tests: **3 tests, 63 assertions, zero failures/errors**.
Existing compiled-Lain repair tests: **5 tests, 27 assertions, zero failures/errors**;
three of those assertions characterize the known whole-compound switch error.
Full focused AMB regressions: **50 tests, 790 assertions, zero failures/errors**
across compiler checkpoints, enumeration, parallel workers, strategy, checkpoint,
learning, runtime, search, and Lain integration namespaces. This is not the full
repository suite; that suite was not run in this slice.

Static checks: clj-kondo reported zero errors/warnings for the three new Clojure
files; declared arities were checked; both saved EDN datasets were parsed;
`git diff --check` passed; the production Clojure diff was empty.

## Reproduce

```sh
clojure -M -m examples.lain.amb.compiler-checkpoint-benchmark 3
clojure -M -e '(require (quote [examples.lain.amb.compiler-checkpoint-benchmark :as b])) (with-redefs [b/modes [:builder :lain-arithmetic]] (b/-main "3"))'
clojure -M:test propagators.experimental.amb-compiler-checkpoint-test propagators.experimental.amb-lain-test
clojure -M:test propagators.experimental.amb-compiler-checkpoint-test propagators.experimental.amb-enumeration-test propagators.experimental.amb-parallel-test propagators.experimental.amb-strategy-test propagators.experimental.amb-checkpoint-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

Code: [compiled question](../../examples/lain/amb/compiler_checkpoint.clj),
[benchmark](../../examples/lain/amb/compiler_checkpoint_benchmark.clj),
[tests](../../test/propagators/experimental/amb_compiler_checkpoint_test.clj).
Raw data: [matched modes](COMPILER_CHECKPOINT_RESULTS.edn),
[arithmetic versus builder](COMPILER_ARITHMETIC_CHECKPOINT_RESULTS.edn).

Nothing committed or pushed. Existing document moves and experiments preserved.
