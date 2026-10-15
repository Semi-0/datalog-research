# Concurrent checkpoints: compiled Lain versus builder

2026-10-13. Follow-up to the same-environment solver experiment. Compare complete
answer sets in one JVM with the same four-worker checkpoint enumeration strategy.
No production compiler, runner, Net, TTMS, or compound code changed.

## Three representations

1. **Original opaque builder:** existing `checkpoint-question/build`; one
   supported host predicate checks ordering and the Pythagorean equation. This
   is the reference used in the previous builder enumeration benchmark.
2. **Decomposed builder:** new `decomposed-question/build`; nine separate
   supported arithmetic/Boolean operations matching `solver.lain`, plus the
   same all-input completion gate, tuple construction, require, and switch.
   It uses existing primitive installers and does not parse or compile Lain.
3. **Compiled Lain:** the unchanged `solver.lain` declarative constraint and
   `(solve solver constraint domain a b c)` interface. Select the concurrent
   implementation in its solver cell; use the existing prepared question and
   standard Compiler 2 application path.

The decomposed builder represents:

```clojure
(and (and (<= a b) (<= b c))
     (= (+ (* a a) (* b b)) (* c c)))
```

Both decomposed forms use TTMS-aware layered base/support/premise-state
procedures. The builder and Lain graphs are **not identical**: Lain retains its
environment, lookup, application, and boundary topology. The comparison measures
the representation/execution cost, not isolated compiler CPU time. The opaque
reference also has different arithmetic granularity and no separate completion
gate; it is not a like-for-like compiler overhead measurement.

## Method

- Positive ordered triples in `1..n`, satisfying `a*a+b*b=c*c`.
- All answers, sorted; independent exhaustive oracle checks every run.
- Identical first-source partition policy, four workers, all partitions finish.
- Existing `checkpoint-enumeration` and worker execution driver for every mode.
- Java 25.0.2, 10 available processors; caches disabled.
- One warmup and three measured samples per mode/bound; alternating mode order.
- One shared pool, created outside timing and closed in `finally`.
- Declaration/build, compilation, and outer Lain preparation outside timing.
- Solver/control installation, initial question propagation (including delayed
  compiled body topology), worker dispatch, search, collection, joining, and
  publication remain inside timing. Builder control templates are not prebuilt
  outside the timed call.

Setup figures are single samples. The first Lain compilation includes cold
initialization (1.817 seconds); later compilations are approximately 86–88 ms.
Outer preparation is approximately 10 ms after warmup. These are not a repeated
compilation benchmark. Allocation counts are cumulative bytes, not heap size.

## Median complete-enumeration times, seconds

| Domain | Answers | Opaque builder | Decomposed builder | Compiled Lain | Lain / decomposed |
|---|---:|---:|---:|---:|---:|
| `1..4` | 0 | 0.158 | 0.481 | 0.613 | 1.27× |
| `1..5` | 1 | 0.405 | 1.201 | 1.479 | 1.23× |
| `1..8` | 1 | 0.989 | 2.883 | 3.517 | 1.22× |

All **27 measured samples** match the exhaustive oracle. Domain 4 is exhausted
with no answers; domains 5 and 8 return exactly `[[3 4 5]]`. All modes execute
four worker threads and visit 64, 125, and 512 complete leaves respectively.

The compiled version is 3.56–3.87× the old opaque builder time, but only
1.22–1.27× the matching decomposed builder time. Much of the larger observed
gap disappears when arithmetic granularity is matched. This supports separating
decomposition costs from environment/application overhead, rather than saying
the compiler itself is 3.6× slower. There is no component-level CPU attribution.

## Domain-5 work and allocation

| Representation | Activations | Complete leaves | Median cumulative allocation | Largest observed individual env |
|---|---:|---:|---:|---:|
| Opaque builder | 12,944 | 125 | 1.63 GB | 33 |
| Decomposed builder | 42,931 | 125 | 4.75 GB | 114 |
| Compiled Lain | 47,779 | 125 | 5.68 GB | 760 |

The decomposed builder initially has 65 cells and 49 propagators, versus 20/13
for the opaque builder. The compiled outer environment has 277 cells and 212
propagators; its observed branch env is larger because it contains the inherited
environment and instantiated body. It is not directly comparable to a minimal
builder-only Net. Actual physical memory retained through nested snapshots was
not separately profiled.

The controlled leaf counts show the speed difference is not fewer assignments
or a different partition policy. The decomposed builder avoids compiler wiring
but still pays for multiple supported applications. No range contraction or
cross-branch no-good learning is used; no universal performance ranking follows.

## Verification

New focused comparison tests: **2 tests, 30 assertions, zero failures/errors**.
They check complete answer parity, `n^3` leaves, actual worker threads, explicit
operation count, initially empty source cells, and malformed bound/mode rejection.
The arithmetic builder has nine expression operations plus one completion gate;
tuple construction is installed separately.

Full focused AMB regressions: **60 tests, 896 assertions, zero failures/errors**,
including the new comparison, swappable Lain solvers, brute force, earlier
compiler checkpoints, enumeration, parallel workers, strategy, checkpoint,
learning, runtime, search, and Lain namespaces. Three passing assertions preserve
the existing whole-compound error characterization, not solver success. The
full repository suite is not run or claimed green. Existing whole-compound
switch characterization assertions are preserved.

Static lint: zero errors/warnings on the three new Clojure files. Production
Clojure diff is empty; no existing solver or compiler activation was changed.
The workspace's unrelated document moves and earlier experiments remain intact.
Nothing committed or pushed.

## Reproduce

```sh
clojure -M -m examples.lain.amb.concurrent-compiler-builder 3
clojure -M:test propagators.experimental.amb-concurrent-compiler-builder-test
clojure -M:test propagators.experimental.amb-concurrent-compiler-builder-test propagators.experimental.amb-swappable-solver-test propagators.experimental.amb-lain-brute-force-test propagators.experimental.amb-compiler-checkpoint-test propagators.experimental.amb-enumeration-test propagators.experimental.amb-parallel-test propagators.experimental.amb-strategy-test propagators.experimental.amb-checkpoint-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

[Raw measurements](CONCURRENT_COMPILER_BUILDER_RESULTS.edn),
[comparison driver](../../examples/lain/amb/concurrent_compiler_builder.clj),
[decomposed builder](../../examples/lain/amb/decomposed_question.clj),
[Lain declaration](../../examples/lain/amb/solver.lain),
[tests](../../test/propagators/experimental/amb_concurrent_compiler_builder_test.clj).
