# Layered premise-state transport and remaining slices

Date: 2026-10-01. Status: **slice 1 recovery and slice 2 structural-data extension gates pass**.
See [current results and boundary](TTMS_PREMISE_TRANSPORT_RESULTS.md).
The design below records the intended contract, not a claim of completion.
No model mutation is authorized by this document.

## Problem and corrected completion claim

A concrete-only activation emits no patches when its input is nothing or
contradictory. Without separate premise transport, withdrawal can stop at that
activation and leave downstream results stale.

The structural-field experiment separates guarded extraction from an unguarded
transform, but the transform still emits TTMS observations with a nothing or
contradiction base. It is **not** yet a payload-free premise-state transport.
Its passing tests establish only the cases they exercise, not end-to-end premise
propagation through all consumers.

## Proposed layered contracts

Keep dependencies of a computation distinct from current knowledge about sources.
Layer names below are proposed. Each premise retains the existing exact fields
and source-cell identity contract.

```clojure
;; Supported payload: the result was derived using A at epoch 1.
{:base 10
 :support #{{:source source-cell-id
             :timestamp 1
             :premises-status :active}}}

;; Partial layered update: A is now withdrawn. There is NO base layer.
{:premise-state #{{:source source-cell-id
                   :timestamp 2
                   :premises-status :retracted}}}
```

These are readable logical layer maps; reuse existing layered datum constructors
and slot-backed representations where required by the application contract.
Do not create a second, unrelated datum/application system.

| Layer | Procedure responsibility | Readiness |
|---|---|---|
| `:base` | Compute a new payload | Guard concrete computation, not the entire layered application |
| `:support` | Combine the actual dependencies of a newly computed result | Preserve incompatible input versions; do not normalize them away |
| `:premise-state` | Join and forward source-state knowledge | Run even when payload computation is blocked |

Use existing `support/combine` for computation dependencies and `support/join`
for source-state updates. A state about B must not make an A-only result depend
on B. No derived procedure may advance an epoch or relabel an old computation.

The generic composition seam already exists:

```clojure
;; Existing contract, not a new runner protocol.
(defn compose-publication [layered-activation value-transform]
  (prop/compose-activation
   layered-activation
   (message/lift-message value-transform)))
```

The publication transform distinguishes value observations from partial
premise-state updates. The implemented `collection/content` now accepts exactly
base/support, exactly premise-state, or the three layers together. Focused tests
verify the missing-base and three-layer publication contracts.

User clarification: conflicting payloads at the same source epoch correctly
produce a local contradiction. They do not implicitly retract that source.
The acceptance gate is repair by a newer active observation, or explicit
withdrawal followed by fresh publication. Immediate downstream withdrawal on
unchanged active source state is **not** required. The extra test imposing that
requirement was removed and replaced by recovery/stale-replay tests. Do not add
observation-invalidation machinery or manufacture epochs to satisfy it.

Logical outputs:

```text
usable input tuple       -> new payload with computation support
changed source states    -> premise-state update, regardless of payload usability
nothing / contradiction  -> no computed payload; preserve source-state transport
```

An absent base layer means "no payload update", not "publish nothing". The
strongest projection can still be nothing or contradictory because of local
evidence. Diagnostic reasons must remain inspectable; suppressing payload
computation is not permission to discard evidence or errors.

## TTMS merge, projection, and scheduling

Extend the opt-in TTMS boundary deliberately rather than disguising a state
update as an ordinary value observation:

- Keep value observations and current source states separately identifiable in
  explicit cell content. No closure-local dictionary, counter, or payload cache.
- Preserve support-set compaction for value observations. State-only updates are
  not result observations and must not accidentally participate as payloads.
- Check result eligibility against current source states without changing the
  support recorded for the original computation.
- Forward current state even if the strongest base is unchanged, nothing, or
  contradictory. Verify that state-only changes schedule the required transport.
- Retain equal-version conflicts; reject stale replay through existing ordering.
- `A@3 active` does not revive `10/A@1`. Fresh publication or recomputation is
  required; do not recover deleted payloads from hidden history.

Focused probes now cover retained-content shape, partial-layer assembly/publication,
legacy two-layer observations, and state-only wakeup behavior. Do not silently
change generic layered application, cell equality, or the scheduler when extending
this verification. Domain-key ambiguity is handled locally by the explicit
`p:structural-data-field` extension; shared readiness remains unchanged.

## Ownership and fixed boundaries

Layered procedures own computation; TTMS owns evidence/state merge and strongest;
publication owns source epochs and message packaging. The runner continues to
apply ordinary cell messages. No new runner operation is proposed.

Supported struct **data** remains separate from persistent slot **topology**.
Field publication carries struct premises into the corresponding real slot
participants. Existing bidirectional accessors remain unchanged. Independent
participant evidence merges under ordinary TTMS rules; field publication does
not automatically add struct premises to that independent evidence.

This proposal extends the previously fixed TTMS content/projection boundary.
Use the grounding/evolution review before that implementation. Preserve the
September 24 bidirectional-slot constraint and September 9 explicit-projection
constraint. Review the September 29 TTMS constraint for the new state/value
distinction rather than silently rewriting its meaning or approval history.

## Remaining slices, in order

| Slice | Deliverable | Completion gate |
|---|---|---|
| 1. Independent layered premise transport | Premise-state datum/procedure, state-only publication, TTMS merge/projection integration | Withdrawal and bring-in cross multi-step computations, branches, and cycles despite nothing/contradiction; unchanged-base state updates wake transport; no stale relabeling or unrelated dependency contamination |
| 2. Verify structural premises | Apply slice 1 to supported struct data feeding persistent slots; no new accessor mechanism | Nested premises, conflicting sources, late arrival, field removal/reappearance, ordinary `:base`/`:support` fields, withdrawal/recovery, stable topology |
| 3. Visualization consumer migration | Trace/dataflow/map/filter/composed views use verified transport | Preserve references, predicate dependencies, excluded/pending candidate invalidation, and diagnostic reasons; prove parity before removing old graph merging |
| 4. Selection without its reducer | Dedicated TTMS selection source for selection/clear | Ordered updates, stale-command rejection, same-epoch conflicts, and reload isolation; retain reducer-cell for other reachable callers |
| 5. Live XR and reload | Publish migrated declarative views; renderer remains interpretation-only | Withdrawn data clears with visible status; desktop/mobile interaction and external-file full-environment reload pass |
| 6. Cleanup and final verification | Retire obsolete experiments, remove replaced paths only after parity, run regressions and compiled benchmarks | Full-suite results classified; 10/100/1,000-stage arithmetic/branching initial/update/withdrawal/recovery measured separately |

For slice 1, directly test that concrete base callbacks do not run on unusable
input while premise procedures still run. Exercise duplicate/stale updates,
multiple sources, contradictory states, both update orders, branch changes, and
bounded cyclic quiescence. A local forwarding test is not sufficient evidence.

## Existing results and historical failures

Already verified: structural-field publication through actual bidirectional
accessors, nested supported-map fields, headless source stamping, and identity
forwarding benchmarks. These do not establish the proposed state-only contract.

Committed baseline failures are not all current migration requirements:

- Ten assertions belong to the old two-switch experiment: two expect retained
  dominated evidence; eight concern its recovery/support behavior. Preserve the
  diagnostic history, but do not use that abandoned mechanism as the new gate.
- One assertion concerns nested retraction readiness in the old layered slot
  operator. The semantic requirement remains relevant.
- Two errors concern domain fields being mistaken for metadata layers. Preserve
  those semantic regressions when testing the new structural-premise path.

Deferred: `when` topology withdrawal; unrelated clock/TUI/event migrations;
cross-repository ports. No implementation, test run, KIROSHI mutation, commit, or
push is implied by this documentation update.
