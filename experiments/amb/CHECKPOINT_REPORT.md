# Immutable Checkpoints versus TTMS Withdrawal

Date: 2026-10-13. Status: isolated builder-level experiment; no production migration.

## Outcome

The measured hypothesis is supported on these small cases: **checkpoint DFS was
1.90-1.94 times faster than matched TTMS-withdrawal DFS**, with the same question,
candidate order, decisions, and answers. It executed roughly half as many
propagator activations and allocated about 47-48% fewer bytes.

This is evidence about restoration cost, not proof that checkpoint search is
universally faster or that learning should be removed.

Follow-up: [three network-owned solver experiments](STRATEGY_REPORT.md) reruns
these baselines alongside indexed learning, domain refinement, and a checkpoint
frontier driven by ordinary propagators. The results below remain the original
matched-restoration measurements.

## Fixed question and experimental boundary

The question is the existing AMB example: find positive integers `a`, `b`, and `c`
in `1..n` satisfying `a <= b <= c` and `a*a + b*b = c*c`.

[`checkpoint_question.clj`](../../examples/lain/amb/checkpoint_question.clj)
installs the same predicate, tuple construction, `require`, and supported switch
semantics as the existing AMB builder. The predicate executes as a real supported
primitive in the constraint network. It is not tested directly by the DFS loop.
Only an independent host oracle checks results outside inference.

The controlled pair reuses **the exact same initial immutable Net**, input cell
IDs, task queue, and finite domain. Both follow ascending depth-first enumeration
in variable order `a`, `b`, `c`; neither learns no-goods. This deliberately holds
the search policy fixed to isolate restoration. Choice/learning/control
propagators from the original AMB example are not installed in this common
question, so the original learning-enabled builder is a separate reference.

[`checkpoint_search.clj`](../../examples/lain/amb/checkpoint_search.clj) compares:

- **Checkpoint:** retain `{network tasks}` immediately before each choice, at
  quiescence. On a rejected branch, use that parent state for the next alternative.
  Persistent states share structure; there is no deep copy or field-by-field undo.
- **Withdrawal:** publish a newer retracted premise for that same choice through
  existing source stamping, collection publication, and cell evaluation. Run to
  quiescence before publishing the next active choice. Descendants unwind first.

Both use the unchanged normal continuation-constructed runner. No constructor,
cell evaluator, TTMS rule, compiler, compound synchronization, or old AMB code
was changed. New functions have no more than four explicit arguments.

The search loop is a separate synchronous experimental driver, not yet a solver
propagator. Its immutable checkpoints are explicit local search state. An atom
records diagnostic counters only; it does not own choices or deductions.

## Timing protocol

Run from the repository root:

```sh
clojure -M -m examples.lain.amb.checkpoint-benchmark 5
```

[`checkpoint_benchmark.clj`](../../examples/lain/amb/checkpoint_benchmark.clj)
warms each of the three modes once per domain, then collects five measured
samples. The controlled pair alternates execution order. Setup is outside the
timed execution. Each sample checks its answer/status against independent
enumeration. Transaction-local dispatch caches are disabled in all modes.
Execution is synchronous and single-threaded, on Java `25.0.2`.

Times include the same activation/peak-entry instrumentation. Allocation uses
the current-thread allocation counter: it is **cumulative allocated bytes**, not
peak live heap or retained checkpoint memory. This is one JVM run, not a
cross-machine study or statistical significance claim. No external effect,
clock, asynchronous callback, or concurrent network update participates.

Raw timings, allocation samples, setup measurements, and counters are retained
in [CHECKPOINT_RESULTS.edn](CHECKPOINT_RESULTS.edn).

## Controlled results

| Domain | Answer | Leaves checked | Decisions | Checkpoint median ms | Withdrawal median ms | Speed ratio |
|---|---|---:|---:|---:|---:|---:|
| `1..4` | Exhausted: no solution | 64 | 84 | 535.93 | 1037.17 | 1.94x |
| `1..5` | `[3 4 5]` | 70 | 87 | 534.41 | 1017.29 | 1.90x |
| `1..8` | `[3 4 5]` | 157 | 180 | 1126.38 | 2135.65 | 1.90x |

| Domain | Checkpoint activations | Withdrawal activations | Checkpoint allocated MB | Withdrawal allocated MB |
|---|---:|---:|---:|---:|
| `1..4` | 6709 | 13345 | 760.04 | 1463.84 |
| `1..5` | 6946 | 13582 | 795.15 | 1499.82 |
| `1..8` | 14293 | 28276 | 1658.28 | 3140.04 |

MB means decimal megabytes. Both modes reached the same peak of **33 Net env
entries**. Checkpoint search retains up to three parent choice states plus the
current state at this depth; the experiment does not measure a BFS frontier.
Checkpoint restores and withdrawals matched exactly: 84, 84, and 177.

The activation reduction is consistent with avoiding downstream withdrawal
propagation, not taking a shorter search path. New alternatives still propagate;
checkpoint restoration does not eliminate forward evaluation.

## Existing learning-enabled AMB reference

The existing builder, learning, repair ordering, and supported publication remain
unchanged. The reference uses the same lightweight instrumentation, rather than
the older full activation profiler. It returns the same answer/status here:

| Domain | Median ms | Activations | Repairs | Final no-goods |
|---|---:|---:|---:|---:|
| `1..4` | 1218.91 | 14239 | 63 | 1 |
| `1..5` | 1036.51 | 12223 | 54 | 6 |
| `1..8` | 3150.71 | 37087 | 165 | 11 |

This reference has different choice ordering, learning/resolution work, control
topology (55 peak env entries), and scheduling. Its timing difference from the
checkpoint mode **cannot be attributed solely to restoration**. The controlled
pair above is the causal comparison; removing learning is not the conclusion.

## Correctness verification

```sh
clojure -M:test propagators.experimental.amb-checkpoint-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

The new focused namespace initially passed **5 tests / 55 assertions**. It checks
oracle parity and equal traversal for bounds 1, 4, 5, and 8; saved-parent/sibling
isolation; dependency source identities; active support on the accepted tuple;
unknown inputs remaining pending; withdrawal before reactivation; empty pending
queues at checkpoints; a quiescent rerun; invalid domains/modes; and evaluator
errors remaining errors rather than becoming a proof of unsatisfiability.

The combined regression command passed **23 tests / 153 assertions, zero failures
and errors**, including all four existing AMB namespaces and the compiled Lain
example. Static checking of the four new Clojure files reported zero errors and
warnings; `git diff --check` passed. The full repository suite was not run.

## Limits and next comparison

- **No learning in the matched pair.** Snapshot restoration and no-good learning
  are orthogonal. A later experiment should retain justified knowledge across
  discarded branches and compare the same learning/repair policy in both modes.
- **No BFS, parallelism, or frontier memory measurement.** DFS's small retained
  path cannot establish the memory cost of many simultaneous branches.
- **Quiescent checkpoints only.** Mid-activation continuations, outstanding
  callbacks, effects, and closure-local mutable state are not captured. Do not
  generalize branch isolation to arbitrary production activations.
- **No range contractor or early partial pruning.** This concrete Boolean
  question waits for all three inputs; it does not demonstrate the broader
  propagation-first knowledge/graph research goal.
- **No compiled checkpoint solver.** Existing compiled Lain AMB is regression
  coverage; the new driver is builder-level. Encapsulating it as a composable
  solver subnetwork remains future work.
- **No unsafe cross-branch evidence merging.** Epochs may be reused in isolated
  snapshots; transferring deductions/no-goods needs explicit branch/context
  interpretation. TTMS stays unchanged.

This supports adding **state restoration policy** as an independent variable in
[the search topology research](../research/propagator-search-topology-research.md),
while preserving the fixed question and keeping domain policy out of the kernel.
