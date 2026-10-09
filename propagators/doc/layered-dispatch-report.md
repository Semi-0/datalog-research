# Layer-blind procedure dispatch

## Contract and ownership

The dispatcher no longer distinguishes base computation from metadata layers.
Every layer procedure uses the existing closure declaration shape:

```clojure
{:net declaration-network
 :f (fn [closure-net inputs outputs application-net]
      ;; Return an immutable Net containing the layer's chosen topology.
      application-net)}
```

Inputs are `[operator previous-output live-result & arguments]`; outputs contain
one private layer-result cell. `procedure/ports` gives these roles explicit names.
Previous output is a materialized snapshot; live result is the current application's
bank. Merely receiving either reference does not install a dependency edge.

`propagators.layered.dispatcher` declares all available procedures and assembles
only published layers. It has no named-layer eligibility, usability, support,
provenance, or scope policy. Procedures select their readers and publication.
Unknown layer names work without changes to dispatch.

`propagators.layered.procedure` supplies explicit compositions: `base` adapts a
scalar declaration, and `argument-layer` composes whole-datum argument observation,
caller-owned eligibility, combination, and publication. Combining receives one
argument vector rather than a new variadic contract. Original closure metadata is
preserved by the base adapter.

`propagators.layered.runtime` owns datum preparation, intentional legacy-slot
materialization, and explicit scope publication composition. Materialization was
retained rather than replaced with repeated live projection. Assembly preserves
the previous named-network representation; adding slot-index metadata changed
diff behavior and was rejected during verification.

Arithmetic, provenance, intensity, support, premise-state, and visualization
source procedures declare their own semantics. Support observes arguments only;
it does not inherit previous-output support. Provenance explicitly opts into its
historical union. Empty-but-present visualization source layers remain eligible.

## Preserved boundaries

No changes to Net shape, runner constructor, cell evaluator, generic application,
compound synchronization, TTMS merge policy, or production compiler. Existing
public installer signatures remain. Custom layer closures must follow the new
uniform declaration inputs; there is no layer-name compatibility dispatch.

All newly introduced or modified functions accept at most four arguments. The
arity regression test walks their definitions, including anonymous functions;
untouched older declarations are not silently refactored.

## Verification

Tests cover unfamiliar/omitted layers, separate previous/live output observation,
unused reference topology, bounded feedback, invalid declarations, stable reruns,
plain layered applications, support propagation and withdrawal/recovery, branching,
premise-state transport, and visualization composition.

The socket-enabled full suite reported **4,618 pass, 3 fail, 0 error**. All failures
were missing publications in `graph.ttms-view-live-test`. Original-runtime
comparison and final focused results are recorded below.

Final focused verification passed **1,301 assertions, zero failures/errors**:
dispatcher 19; arity 83; layered procedure 48; layered support 28; premise transport
80; support collection 826; glitch 41; TTMS branching 81; TTMS primitives 62;
visualization composition 33. The full-suite count above preceded the final
metadata-preservation correction and its two additional assertions; the focused
checks include that correction.

One initial focused command stopped after seven successful namespaces because
the remaining namespace names were misspelled. Those three were subsequently run
under their correct names and passed. `git diff --check` passed.

Reproduce focused checks:

```sh
clojure -M:test propagators.layered-dispatcher-test propagators.layered-arity-test propagators.layered-procedure-test propagators.layered-support-test propagators.premise-transport-test propagators.support-collection-test propagators.support-glitch-test
clojure -M:test propagators.ttms-branching-test propagators.ttms-primitives-test propagators.experimental.visualization-composition-test
```

Run `clojure -M:test` with local socket permission for the full suite. The original
comparison overrides only the pre-refactor layered runtime and its migrated
procedure factories, retaining pre-existing unrelated working-tree changes.

## Benchmark method

`propagators_layered_bench.clj` runs base, provenance, support, two-level nested,
and reactive support chains at lengths 10 and 100. Each implementation uses three
fresh JVM groups, three warmups and twenty timed samples per scenario. Initial
and quiescent rerun are measured separately; reactive runs measure update,
withdrawal, and recovery together. Every run asserts its semantic result.

Construction is reported separately. Activation/frame-size profiling runs outside
timing samples, with counters outside Net. Original implementation files are
isolated on a temporary first-priority classpath, preserving the dirty working
tree. The same final fixture is used for both implementations; its nested base
declaration is adapted to each implementation's public contract.

No universal speedup or statistical significance is inferred from this local
benchmark. Medians and p95s expose observed latency; activation counts and topology
sizes expose the mechanism. Raw samples accompany the completed report.

### Results

All 60 JVM runs completed successfully: 108 scenario records and 2,160 timed
samples (60 samples per implementation/scenario/length). Values below pool the
three JVM groups. Latency is in milliseconds; ratio is revised/baseline median.
Activations and maximum frame sizes were identical across the three groups.

| Mode | Length | Scenario | Baseline median / p95 | Revised median / p95 | Ratio | Activations | Max frame nodes |
|---|---:|---|---:|---:|---:|---:|---:|
| base | 10 | initial | 18.76 / 27.65 | 11.62 / 19.26 | 0.62 | 220 → 100 | 25 → 25 |
| base | 10 | rerun | 12.69 / 15.09 | 8.19 / 10.29 | 0.65 | 220 → 100 | 25 → 25 |
| base | 100 | initial | 96.87 / 129.00 | 60.73 / 80.15 | 0.63 | 2200 → 1000 | 205 → 205 |
| base | 100 | rerun | 86.59 / 92.35 | 46.73 / 52.31 | 0.54 | 2200 → 1000 | 205 → 205 |
| nested | 10 | initial | 30.27 / 46.98 | 20.63 / 29.90 | 0.68 | 450 → 220 | 30 → 25 |
| nested | 10 | rerun | 24.03 / 26.43 | 14.51 / 17.19 | 0.60 | 450 → 220 | 30 → 25 |
| nested | 100 | initial | 184.60 / 237.29 | 119.27 / 150.47 | 0.65 | 4500 → 2200 | 205 → 205 |
| nested | 100 | rerun | 176.28 / 180.03 | 102.84 / 111.35 | 0.58 | 4500 → 2200 | 205 → 205 |
| provenance | 10 | initial | 28.08 / 41.48 | 19.76 / 25.66 | 0.70 | 400 → 180 | 39 → 27 |
| provenance | 10 | rerun | 21.29 / 23.73 | 12.92 / 15.83 | 0.61 | 400 → 180 | 39 → 27 |
| provenance | 100 | initial | 162.90 / 204.52 | 103.07 / 133.57 | 0.63 | 4000 → 1800 | 207 → 207 |
| provenance | 100 | rerun | 154.60 / 161.36 | 90.76 / 96.13 | 0.59 | 4000 → 1800 | 207 → 207 |
| support | 10 | initial | 27.92 / 42.61 | 20.47 / 29.34 | 0.73 | 370 → 180 | 35 → 27 |
| support | 10 | reactive | 65.12 / 80.14 | 42.86 / 62.38 | 0.66 | 1100 → 540 | 35 → 27 |
| support | 10 | rerun | 21.17 / 23.81 | 13.61 / 17.27 | 0.64 | 370 → 180 | 35 → 27 |
| support | 100 | initial | 175.49 / 205.72 | 104.42 / 137.94 | 0.60 | 3700 → 1800 | 207 → 207 |
| support | 100 | reactive | 483.95 / 515.38 | 296.09 / 320.77 | 0.61 | 11000 → 5400 | 207 → 207 |
| support | 100 | rerun | 156.19 / 162.25 | 97.74 / 104.73 | 0.63 | 3700 → 1800 | 207 → 207 |

Median latency was 27–46% lower in these fixtures. Activation reductions were
49–55%. Persistent outer topology was unchanged: 25/205 nodes for base and nested
chains, and 27/207 for provenance and support chains. Profiled transitions
introduced zero persistent nodes. Maximum frame size is a node count, not an
allocation or peak-memory measurement; large chains' outer frames dominate it.

Raw per-JVM timings, separate construction times, and profiles are in
[layered-dispatch-benchmark.edn](layered-dispatch-benchmark.edn). Sequential runs,
short warmup, JIT/GC, and machine load limit performance generalization. No
performance threshold was used to override semantic correctness.

### Remaining full-suite failures

The original-runtime classpath reproduced the same three WebSocket failures
at `ttms_view_live_test.clj:49`, `:51`, and `:58` (2 pass, 3 fail, 0 error).
Thus they are pre-existing relative to this refactor in the current working tree.
They were not weakened or repaired here; unrelated XR work remains untouched.

## KIROSHI status

Grounding used the reviewed explicit-projection, retained-evidence, immutable-Net,
compound-bidirectionality, and semantic-composition boundaries. This implementation
moves layer policy out of dispatch without moving scheduler/domain ownership.
The implementation slice performed no model mutation. On 2026-10-09 the user
requested publication and a layer-system constraint. The complete constraint
inventory contained general projection/composition rules but no layer-dispatch
rule. `:constraint/layered-dispatch-is-layer-blind` was validated and persisted
as a **candidate**, with explicit session/task provenance. No approval or
supersession was performed. Its portable proposal is
`system-model/layered-dispatch-constraint.edn`; the database remains local.

Stored candidate constraint for separate model review: layer dispatch may validate
declaration shape and assemble declared results, but must not select behavior by
layer name, inspect computation usability, combine dependencies, or add scope
policy. Each procedure owns reader topology, eligibility, combination, and
publication. Previous-output and live-result references are distinct; unused
references must not imply read edges. Evidence: the unfamiliar-layer, omitted-layer,
previous/live, unused-reference, bounded-feedback, and reactive support tests.
