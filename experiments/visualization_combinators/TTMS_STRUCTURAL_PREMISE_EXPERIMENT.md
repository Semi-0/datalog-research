# Supported struct data and persistent slot topology

Current follow-up: [slice checkpoint, publication integration, benchmark, and
remaining migration boundary](TTMS_SLICE_PROGRESS.md).

## Main promotion verification — 2026-09-30

The source and test were verified in an isolated export of `f986aca`, without
unrelated dirty tracer/compiler changes. The candidate passed **1,973 assertions**
(235 experimental plus 1,738 regressions). Its full default suite reproduced the
existing baseline exactly: **3,524 passed, 11 failed, 2 errors**. That suite does
not auto-discover this opt-in experiment; its 235 assertions ran explicitly.

This promotion adds the opt-in experiment to Git `main`; it does not install a
new default accessor or claim that the inherited failed migration is complete.
Earlier "not run" and "no commit" notes below describe the original experiment
stage, not this subsequent promotion.

## Current experiment — 2026-09-30

Implemented in `propagators/experimental/structural_field.clj`, tested by
`test/propagators/structural_field_test.clj`. This replaces the one-way
observation experiment below as the current experiment, without changing the
default runtime or registering experimental tests in the default suite.

```text
TTMS struct data {x: value} / A
          |
          | structural-field extracts x and preserves A
          v
actual participant <-> ordinary compound slot <-> other participants
```

The data source and topology owner are separate cells. The source's base is an
immutable map, possibly containing nested maps or supported field collections.
The topology owner remains an ordinary compound object. Existing accessors keep
their bidirectional relation. Returning participant data does not modify the
source struct, acquire extra premises, or cause re-publication with a new epoch.

`p:structural-field [source key participant]` returns a normal installer.
`field-content [field premises]` is pure: it preserves supported field observations
and combines their premises with the struct premises. Missing or empty fields
publish supported nothing, so a newer missing field invalidates old field data.
Unsupported source formats fail explicitly. No implicit plain-to-TTMS conversion
is installed.

Concrete field extraction uses `prop/concrete-propagator`. Its activation is
composed with an unusable-state transport step: a retracted or contradictory
source must still publish its support without attempting to extract a field.
This does not modify the concrete guard or generic layered application.

Independent writes merge by existing TTMS rules. `10/A` and `20/B` produce
contradiction with both supports. Equal-valued A/B evidence remains conjunctive;
retracting B makes the result unusable, not an automatic fallback to A. A fresh
compatible publication can restore usability. Retraction never removes topology.

### Verification

```sh
clojure -M:test propagators.structural-field-test
```

**9 tests, 235 assertions, all passing.** Covers pure support composition,
incompatible input epochs, nested fields, real accessor chains/cycles and
middle injection, reverse writes at either endpoint, conflicting/equal values,
source and field retraction, belief-only non-resurrection, fresh recovery,
missing fields, empty supported fields, late accessors, data-before-access,
batched freshness and independent writes in both orders, stale replay, duplicates,
quiescent reruns, topology stability, and malformed inputs.

Existing regression suites: **86 tests, 1,738 assertions, all passing** across
support, support-collection, support-transport, support-glitch,
compound-object-network-slot, named-network, network-patch, event, and TMS.
An initial combined command stopped after 1,617 passing assertions because the
planned `propagators.network-test` namespace does not exist; the remaining suites
were run successfully with the current named-network/network-patch namespaces.

The unchanged `propagators.ttms-real-accessor-test` diagnostic still reports
**5 tests, 185 assertions: 175 pass, 10 fail**. Seven failures concern publishing
updated compound snapshots with supported inner cells; three concern wrapping
the entire compound in TTMS. This implementation neither patches nor claims to
fix those paths. No test assertions were weakened.

### Bounded measurements

`propagators.structural-field-test/diagnostic` runs four publications: initial,
updated, retracted, restored. Transition counts include runner completion.

| Topology | Transitions per publication | Outer node growth | Final observations per participant | Rerun unchanged |
|---|---|---|---|---|
| Three-participant chain | 6, 6, 6, 6 | 0 | 1, 1, 1 | yes |
| Three-participant cycle | 8, 8, 8, 8 | 0 | 1, 1, 1 | yes |

One local sample including publication processing and rerun took approximately
31 ms for the chain and 24 ms for the cycle. These un-warmed single samples are
diagnostics, not a comparative performance benchmark or general convergence proof.

### Boundaries

The implementation uses no hidden state, private executor, global Var replacement,
runtime merge extension, or accessor replacement. Only explicit field installers
are supported: no automatic whole-struct enumeration, Compiler 2 integration,
or full-environment retraction is claimed. The full repository suite and UI were
not run. No KIROSHI mutation, commit, or push was performed.

Grounding preserves the approved September 24 bidirectional-slot constraint,
September 9 explicit-projection constraint, and September 29 TTMS dominance rule.

## Historical withdrawn experiment

**Withdrawn as evidence for bidirectional compound integration.** The user
requested removal of the experimental test namespace after review established
that its passing cases used a one-way supported projection after the accessor.
`test/propagators/ttms_structural_premise_test.clj` has been removed. The results
below are historical only; the listed experimental command is no longer runnable.
Existing real-accessor tests and runtime behavior are unchanged.

This corrects the substrate of `STRUCTURAL_PREMISE_EXPERIMENT.md`: that earlier
experiment used distributed-TMS claims and is not evidence for TTMS behavior.
This experiment uses only existing TTMS support collections for values and
belief transport. No default runtime, merge, runner, or compound synchronization
changes are installed.

## Composition

`propagators.experimental.ttms-structural-premise` installs ordinary propagators:

```text
ordinary compound accessor -> stable raw slot -> structural-premise -> inc -> inc
                                                   ^                  ^      ^
belief source -------------------------------------+ -> belief relay -+ -> relay
```

The compound owner remains ordinary topology. A separate supported output
carries the observed slot value with structural premises. This output is an
explicit projection, not the original slot becoming retractable.

`belief-message` publishes a TTMS observation with a `nothing` base and a
source-cell ID, explicit timestamp, and active/retracted status. Belief relays
forward retained content even when there is no usable value. The computation
publishes belief states independently of usable payloads.

Current belief states use `support/join`; computed payloads use
`support/combine` and reject incompatible epochs. The latter deliberately does
not relabel an old result when a fresh belief arrives. Reactivation recomputes
from the separate source; it does not resurrect a compacted payload from hidden
history. The local host `inc` callbacks exercise transport, not Compiler 2 or
the layered procedure application contract.

## Results

Command: `clojure -M:test propagators.ttms-structural-premise-test`

**8 tests, 60 assertions: 58 pass, 2 fail, 0 errors.** The command exits nonzero;
these were results from the now-removed experimental namespace.

Passing cases:

- Accessor installed before the owner value; both value/belief arrival orders.
- Two computation steps: active value, structural withdrawal, reactivation.
- Data-source payload update and data-source withdrawal/recovery through both
  computations, preserving data and structural source IDs and timestamps.
- Nothing and contradiction do not block belief updates or publish a value.
- Multiple structural premises are conjunctively required.
- Real bidirectional accessor triangle reaches quiescence and preserves values.
- Duplicate publication and quiescent rerun preserve the network.
- Stale computation input plus fresh belief emits only a state observation,
  never a falsely refreshed payload.

An initial data-source retraction test exposed omitted input belief transport.
The experimental activation now forwards input and structural source states;
the test passes without changing TTMS.

Remaining failures: using the same cell as raw input and supported output fails
in **both arrival orders**, with `Expected supported collection content`.
Ordinary accessor publication and TTMS observations meet in the same cell under
incompatible content contracts. Separate-output success must not be described
as fixing this case or as proving safe withdrawal of an entire environment.
No default merge conversion was added to conceal this boundary.

Regression command:

```sh
clojure -M:test propagators.support-test propagators.support-collection-test propagators.support-transport-test propagators.support-glitch-test propagators.compound-object-network-slot-test propagators.event-test
```

**60 tests, 1,639 assertions, all passing.** The full repository suite, Compiler
2 environment retraction, UI interaction, and exhaustive update-order testing
were not run. These results establish a bounded headless experiment, not a
general glitch-freedom proof or runtime migration. No commit, push, or KIROSHI
mutation is included.
