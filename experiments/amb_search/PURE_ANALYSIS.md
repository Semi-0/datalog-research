# Why pure AMB loses to checkpoints

Measured 2026-10-19, Java 27, against `b19c877`. This investigation concerns
builder-level `p:amb` and the existing transitive-query benchmark. It does not
include semantic capture, replay, scene compilation, or browser rendering.
Production search behavior is unchanged.

## Pythagorean search, domain 1 through 5

The existing strategy benchmark was run with one warmup and three measured
samples per mode, alternating mode order, caches disabled, setup excluded.
Every run returned `[3 4 5]`. Measured JVMs ran sequentially; an earlier
overlapping exploratory run is excluded from the saved results.

| Mode | Median ms | Predicate checks | Activations | Resolution calls | Allocated MB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Checkpoint DFS | 510.0 | 70 | 6,946 | 0 | 724.9 |
| Withdrawal DFS | 1,015.5 | 70 | 13,582 | 0 | 1,367.3 |
| Existing learning AMB | 1,035.3 | 55 | 12,223 | 384 | 1,410.8 |
| Indexed learning AMB | 991.8 | 55 | 12,114 | 190 | 1,415.6 |
| Network-owned checkpoints | 585.4 | 70 | 7,211 | 0 | 831.4 |

Allocations are cumulative current-thread bytes, not retained heap. These small
sample counts describe this fixture; they do not establish universal speedups.

There are two separate causes.

**The predicate exposes no small conflict.** `builder/build` installs one
supported three-input predicate. Even when `a > b` suffices to reject a tuple,
the Boolean returned by that opaque primitive carries all three input premises.
All 54 distinct observed failure reasons had width three. Final resolution
compressed them to two unary reasons and four three-literal reasons, but those
still block exactly the 54 rejected tuples already tested. Checking all 125
possible assignments against that final knowledge finds **zero blocked untested tuples**.
Thus smaller stored knowledge is not evidence of eliminating new combinations.

AMB tests 55 tuples instead of chronological DFS's 70 because its repair policy
retains other selected values when changing a participating choice. The traversal
orders differ. The 15-check difference alone cannot be credited to pruning
untested branches; first-solution position is order-dependent.

**Live transport costs exceed the saved predicate work.** In the detailed
ordinary FIFO profile, AMB took 1,119 ms. Patch evaluation consumed 618 ms of
self time; anonymous propagator activation consumed another 243 ms of self time.
There were 109 constraint activations for 55 usable tuples: withdrawal states
also propagate. Learning consumed about 17 ms inclusive, and resolution about
7 ms inclusive. These nested times must not be added together. Indexed learning
halves resolution calls without changing candidates or no-good meaning and
does not remove the main propagation cost.

The matched checkpoint/withdrawal pair has identical 87 decisions and 70 leaves.
Restoring a checkpoint avoids propagating the withdrawal through the old world;
the withdrawal variant roughly doubles activations. The full AMB comparison
also changes control topology (55 peak entries versus 33) and search order, so
it is not an isolated measurement of restoration alone.

## Transitive queries: the failure gate loses dependency precision

The benchmark infers reachability once during setup, outside timed query search.
The repeated work here is searching the compiled query, not repeatedly computing
the transitive closure.

`solver-question/build` installs `::complete` on `[a b c raw-valid]`. It waits for
all three choices, then forwards the predicate result. The existing repair
learner observes `require` **after** that gate. A failure depending only on
`(reachable? facts b c)` consequently acquires an irrelevant `a` premise.
Exact certificate conversion faithfully retains that support; the learner
cannot legitimately guess which premise to delete.

The existing learning-checkpoint experiment already observes the required
callable result before the completion gate. The current independent rerun used
one warmup and two alternating measured samples per mode:

| Four-node chain query | Repair | Plain checkpoint | Early-only checkpoint | Learning checkpoint |
| --- | ---: | ---: | ---: | ---: |
| Prefix: reachable(a,b) | 1,340 ms | 769 ms | 520 ms | 523 ms |
| Suffix: reachable(b,c) | 1,867 ms | 1,237 ms | 1,273 ms | 862 ms |

Every run returned all 24 independent-oracle answers. In the suffix case,
learning skips **30 proposals before network propagation**, reduces propagated
complete leaves from 64 to 34, and reduces activations from 14,869 for plain
checkpoint to 9,733. Coverage remains all 64 assignments. There are zero early
failures in this case: the saved work comes from learned conflict reuse.

In the prefix case, early rejection already avoids expanding the invalid prefix.
Each bad prefix is encountered once in this DFS order. Learning has no later
cache hits and adds bookkeeping without further branch elimination.

## Controlled repair ablation

`support-probe` keeps the same repair solver, question, success gate and answer
collector. It adds an ordinary `p:require` at the required callable's raw result
and directs only the learner's failure input there. It does not strip support,
change timestamps, replace the runner, or accept incomplete answers.

After one warmup per variant, one diagnostic sample produced:

| Query / failure observation | Observed failure reasons | Activations | Time |
| --- | --- | ---: | ---: |
| Prefix / completion gate | 40 three-choice reasons | 14,459 | 1,491 ms |
| Prefix / required predicate | 10 two-choice reasons | 8,700 | 841 ms |
| Suffix / completion gate | 40 three-choice reasons | 21,458 | 2,087 ms |
| Suffix / required predicate | 10 two-choice reasons | 11,385 | 968 ms |

All four samples return the same 24 answers and pass the no-good soundness check.
This isolates a real explanation-boundary problem in existing repair composition.
Single-sample timings are diagnostic; the activation and explanation counts are
the more direct evidence. Success still requires the original complete tuple.

## Repair indicated by the evidence

Keep the original complete success gate, and acquire failure certificates at
the actual required predicate. Compose learning with checkpoint restoration to
avoid withdrawal propagation. For the Pythagorean builder, expose separately
required constraints or sound predicate-specific reasons; a black-box Boolean
with all inputs as support cannot supply that information to the learner.
An arbitrary false intermediate expression is not sufficient evidence: under
`or`, for example, another branch might satisfy the required predicate.

Optimizing the no-good table alone targets a small cost in this reproduction.
More resolution cannot recover dependencies lost by the observation boundary,
and logical learning does not imply fewer propagator activations.

## Reproduce and verify

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -e "(require '[examples.lain.amb.strategy-benchmark :as b])(prn (b/compare-domain 5 3))(shutdown-agents)"
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m examples.lain.amb.learning-checkpoint-benchmark 2 prefix suffix
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.pure-profile
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.support-probe
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:test propagators.experimental.amb-learning-checkpoint-test propagators.experimental.amb-strategy-test propagators.experimental.amb-learning-test
```

Run the diagnostics in isolated JVMs; profiling uses process-global redefinitions
and temporary counters. No production no-good logger or evaluator was added.
The focused tests passed **25 tests / 447 assertions**, zero failures/errors.
They cover smaller required-result support, actual cached proposals avoiding
propagation, unchanged answer coverage, context/retraction guards, unsafe false
intermediates, and incremental/full resolution parity. The full suite was not run.

[PURE_RESULTS.edn](PURE_RESULTS.edn) retains the measured samples and phase data.
The benchmark source's embedded date is historical; the measurement date above
and in the saved artifact is the current run. No production fix is applied by
this investigation.
