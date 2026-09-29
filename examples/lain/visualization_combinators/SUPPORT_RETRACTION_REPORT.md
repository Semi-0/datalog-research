# Supported collection, message lift, and live retraction

Design name: **TTMS — Temporary Truth Maintenance System**. The canonical
[TTMS contract](../../../propagators/doc/ttms.md) defines terminology, executable
spec locations, ordering laws, source-cell identity, and limits. Existing namespace
and layer names are unchanged; this report preserves the development history.

Status: **linear live retraction and all 8 tested diamond schedules pass after
timestamp-aware support-set compaction. Same-epoch support extension and
one-newer-source replacement pass; source premises carry injection-cell IDs.
General glitch freedom is not claimed.**
No runner, Net, cell evaluator, distributed-TMS policy, compound synchronization,
or generic reducer semantics were changed. Implementation slices below initially
made no KIROSHI mutations; the later authorized supersession is recorded next.

## Authorized model supersession — 2026-09-29

`linpandi` approved `:constraint/ttms-compacts-by-support-dominance`, replacing
the blanket retention constraint while retaining its history and the existing
distributed-TMS policy. Exact declaration: [ttms-constraint.edn](../../../propagators/doc/ttms-constraint.edn).
Supersession transaction `13194139533738`; approval transaction `13194139533740`.
No compiler-environment migration is authorized by this constraint. The compiler
extension and chain-family benchmark remain pending a separate scope decision.

## Isolated commit verification — 2026-09-29

The staged TTMS tree was exported with `git checkout-index` and tested without
unrelated working-tree edits. `clojure -M:test` passed **3,054 assertions**;
event, visualization composition, and the committed dataflow suite passed
**72 assertions**, all with zero failures/errors. The separate unfinished tracer
changes and their two known failing assertions remain unstaged, not deleted or
weakened. Earlier results below describe the full working tree at those stages.

## Implemented

- `propagators.datastructures.support`: exact three-field records, pure current
  state joins, retained equal-version conflicts, retraction detection, and
  whole-tuple timestamp/status compatibility before normalization.
- `propagators.cells.value`: existing readiness predicates are multimethods.
- `propagators.datastructures.layered-value`: readiness methods for plain and
  slot-backed layered values, independent of event tags, including nested bases.
- `propagators.datastructures.timestamp`: the existing event ordering extracted
  unchanged for reuse; event core now uses those same functions.
- `propagators.stdlib.support/procedure`: an ordinary nonbase procedure layer,
  using the existing `[current-layer & full-arguments]` contract.
- Layered application no longer bypasses procedures or collapses the result on a
  contradictory base. Base projection preserves unusable/default values too.
- Layered result assembly copies all declared layers. It uses existing accessor
  APIs to read result-bank producer cells, rather than changing generic reducers.
- Arithmetic and provenance procedures retain contradiction provenance themselves.
- Registered support algebra and composed-procedure tests in the stable suite.
- `propagators.datastructures.support-collection`: opt-in retained observations,
  freshness-aware strongest projection, deterministic projection slot identities,
  and narrow semantic classification / cell merge / strongest handlers.
- `propagators.message/lift-message`: generic message-value transformation composed
  with ordinary activation; preserves destinations, message fields and metadata,
  patch order, and non-message patches. Errors are not swallowed.
- A two-step headless example publishes, updates, retracts, and reactivates through
  existing layered application and the normal runner. No support-specific
  application installer, private executor, or hidden state was added.

The readiness extension loads with `propagators.layered` and
`propagators.stdlib.support`. Support is opt-in
on a procedure; existing event producers and compiler primitives have not yet
been migrated automatically. The broader transport-caller audit remains pending.

The data contract is one support set per conjunctive justification. `source-cell`
below is the actual injection cell's `NodeId`, not a symbolic name:

```clojure
{:base 10
 :support #{{:source source-cell :timestamp 1 :premises-status :active}}}
```

**Revised, user-confirmed semantics:** collected sources are conjunctively
required, including when A and B independently assert the same value. Retracting
A invalidates the combined value even while B remains active. The earlier
alternative-justification assumption is superseded for this new collection;
existing distributed-TMS semantics are unchanged.

## Corrected interpretation

The earlier report incorrectly treated a scalar result from incompatible inputs
as a reason to change the base-handler contract. The base branch deliberately
receives scalar projections. The nonbase support branch already receives complete
datums. No base-handler signature migration was needed or implemented.

The accepted result for conflicting source versions is:

```clojure
{:base 30
 :support #{{:source source-cell :timestamp 1 :premises-status :active}
            {:source source-cell :timestamp 2 :premises-status :active}}}
;; value/unusable? => true
```

Base computation is allowed; downstream consumers decide usability. Retraction
and contradiction information must survive in the assembled datum.

Two distinct pure operations prevent accidental loss of that information:

- `support/join` projects the latest source states from updates, preserving
  equal-version conflicts.
- `support/combine` conjoins a computation's input supports without normalizing
  away incompatible versions. The procedure layer uses this operation.

The previous output support is not an additional argument premise. Including it
in a new derivation would mix an old computation's versions with fresh inputs.
Retained observations are now handled by the opt-in supported collection.
Migration of existing event producers and consumers remains a later slice.

## Retained collection and publication boundary

```clojure
{:support/observations
 #{{:base 10
    :support #{{:source source-cell :timestamp 1 :premises-status :active}}}}}
```

`collection/content` normalizes a raw or slot-backed two-layer datum without
unwrapping its base. Additional layers and malformed support are rejected, not
silently discarded. Temporary outer slot identities are not retained in the
observation key, so equivalent observations deduplicate.

`collection/merge-content` unions observations, then removes dominated candidates.
This user-approved change supersedes full-history retention for this opt-in
collection only; existing distributed TMS is unchanged. No KIROSHI model mutation
is implied. A replacement must have compatible support, cover every old premise
with an equal or newer timestamp, agree on status at equal timestamps, and
strictly extend or advance the old support (or coherently replace an incompatible
tuple). `support/covers?` and `support/dominates?` own this ordering; collection
merge delegates to them. Source-free bottom is also removed. Retractions can
dominate older active observations. Equal-support payload conflicts, equal-version
status conflicts, and incomparable observations remain until dominated.

Strongest also compacts directly supplied content, then computes
the current source frontier with `support/join`, excludes payloads with stale
source versions, and combines current supports without repairing internally
incompatible computations. For an observation with several versions of one
source, freshness compares its newest version; if still current, all its
incompatible entries remain in the projection. Base merging is injected from
the existing cell merge implementation. Coherent `43 / {A@2}` dominates mixed
`33 / {A@1, A@2}`; incompatible support alone is not silently repaired. An update
missing an old source cannot erase that source's retained information.

Empty content projects to `nothing`. Nonempty content produces a two-layer Net,
including when its base or support is unusable. Retractions therefore change
strongest and wake ordinary downstream propagation without a scheduler change.

Publication uses the existing composition contract:

```clojure
(prop/compose-activation
 activation
 (message/lift-message collection/content))
```

The lift only transforms message values. Source identities and explicit epochs
come from the example's input updates; derived computations preserve input
support. Automatic source stamping is not implemented here.

### Run the headless example

```sh
clojure -M -m examples.lain.visualization-combinators.support-retraction-demo
```

`support_retraction_demo.clj` declares `source -> (+ 20) -> (+ 5)` and prints:

| Source observation | Final base | Usable? |
|---|---|---|
| 10 at epoch 1 | 35 | true |
| 20 at epoch 2 | 45 | true |
| retracted at epoch 3 | nothing | false |
| 7 at epoch 4 | 32 | true |

The opt-in cells begin with a supported bottom (`:base nothing`, `:support #{}`).
This matters because existing layered application installs a nonbase procedure
only when an input declares that layer. It avoids a missing-support startup
result without changing generic application or guarding retraction transport.

## Composing the procedure

```clojure
(require '[propagators.ids :as ids]
         '[propagators.layered :as layered]
         '[propagators.network :as net]
         '[propagators.network-builder :as nb]
         '[propagators.stdlib.provenance-arithmetic :as arithmetic]
         '[propagators.stdlib.support :as support])

(let [{network :net procedure :proc} (arithmetic/+ net/empty-net)
      closure-id (ids/new-node-id)
      prepared (nb/install-cell network closure-id
                                support/procedure support/procedure)
      installed (layered/install-layered-procedure!
                 prepared procedure :support closure-id)]
  {:net (:net installed) :proc procedure})
;; Use that procedure with layered/p:apply-layered as usual.
```

## Previous-slice verification

Focused run:

```sh
clojure -M:test propagators.layered-support-test propagators.support-test propagators.layered-procedure-test propagators.dispatch-test
```

Passed **519 assertions, 0 failures, 0 errors**.

Event and stable propagator regressions:

```sh
clojure -M:test propagators.event-test propagators
```

Passed **1,621 assertions, 0 failures, 0 errors**.

Default suite (`clojure -M:test`): **2,183 assertions, 0 failures, 0 errors**,
including the TUI, clock, and runtime boundary suites. `git diff --check` passed.

Additional regression check (not included in the default suite):

```sh
clojure -M:test propagators.experimental.visualization-composition-test propagators.layered-support-test propagators.relationship-dataflow-test
```

Visualization composition passed **33 assertions** and layered support passed
**20 assertions**. The existing dataflow suite passed **36 assertions** and
failed **2** in `errors-merge-with-reasons-and-do-not-abort-other-work`: the
existing semantic-graph merge drops a reason-bearing contradiction. These were
already failing before this slice; that merge rule was not changed.

Removing generic application readiness initially exposed a visualization
regression: a partial application published incomplete dependency layers before
all arguments arrived. The scalar visualization installer now composes the
existing `concrete-propagator` guard around its own application activation.
Generic layered application and the support procedure remain unguarded. The
unchanged visualization assertions verify late inputs, lexical capture, equal
values with distinct origins, and dependency retention through distributed TMS.
No dependency merge policy change was needed.

The composed tests establish:

- Compatible supports accompany the correct base result.
- Incompatible versions remain attached to a computed base and make it unusable.
- Already-retracted support reaches a second connected layered computation.
- That computation may retain base 35 while its concrete consumer remains blocked.
- Nothing and contradiction bases retain support; provenance remains inspectable.
- Existing procedure extension, nesting, lexical provenance, and dispatch tests
  continue to pass with the new layered contradiction result shape.

Those earlier tests established composition of supported datums, not live
withdrawal. The new collection test below additionally establishes withdrawal
of an already-published result through two connected computations.

## Initial collection-slice verification (before dominance compaction)

```sh
clojure -M:test propagators.support-collection-test propagators.message-lift-test propagators.support-test propagators.layered-support-test
```

Passed **636 assertions, 0 failures, 0 errors**: collection/live-chain tests
161, message lift 10, support algebra 445, existing layered support 20.
Checks include merge laws, retained conflicts and history, stale updates,
equal-version conflicts and recovery, conjunctive equal-valued sources,
incompatible derivations, unusable bases, message preservation/error delivery,
two-step withdrawal/reactivation, live conflict recovery, delayed stale updates,
support-only changes, and idempotent reruns.

Additional regressions:

```sh
clojure -M:test propagators.event-test propagators.experimental.visualization-composition-test propagators.relationship-dataflow-test
```

Event **22/22** and visualization composition **33/33** passed. Dataflow remained
**36 passed, 2 failed**, with the same pre-existing error-provenance failures
described above; no assertions were weakened. The combined command exits 1
because of those known failures.

The standalone example completed successfully and printed the four expected
states in the table above, including retracted support at the final output.

Before adding the diamond regression, the default suite (`clojure -M:test`)
passed **2,354 assertions, 0 failures, 0 errors**.
`git diff --check` passed. This default suite does not include the separately
reported dataflow regression namespace.

The original implementation retained all history. The user subsequently approved
dropping dominated observations, as documented below. Compaction uses pairwise
comparison; incomparable observations can still grow. No bounded-memory or
performance claim is made.

## Diamond glitch check: historical counterexample and correction

```sh
clojure -M:test propagators.support-glitch-test
```

The test uses the existing constructor with a test-only pending-task selection
policy, production propagator/patch evaluators, and an actual concrete consumer.
It enumerates all **8** legal outer-task schedules for a single source update:
`A -> (+ 1)` and `A -> (+ 2)`, joined by addition. The initial source 10 publishes
23; updating it to 20 should eventually publish 43.

- **Original safety passed:** no consumer published the mixed value 33. Mixed results
  retain both `A@1` and `A@2`, and concrete consumption is blocked.
- **Original convergence failed:** 6 schedules evaluated the join before both branches
  update. Their final join is unusable and the consumer retains its old 23.
  Only the 2 schedules that update both branches before the join publish 43.

Minimal failing schedule:

```text
left -> join -> consumer -> right -> join -> consumer
```

The mixed observation `33 / {A@1, A@2}` remains current under the approved
newest-version-per-source rule. The subsequent coherent `43 / {A@2}` therefore
merges with 33 and produces a contradictory base while retaining incompatible
support. This is a strongest-candidate selection defect, not evidence of a
runner failure. This was recorded as a failing regression before changing
production semantics.

Verification: diamond test **39 passed, 2 failed, 0 errors**. Running it with
the four existing support/message suites produced **675 passed, 2 failed,
0 errors**; all 636 existing assertions remained green. `git diff --check`
passed. The full default suite was not rerun during this test-only follow-up.

The user then approved dropping dominated observations from content. The
collection now removes the mixed observation when its coherent replacement
arrives, rather than merely hiding old evidence in strongest projection.

### Verification after dominance compaction

```sh
clojure -M:test propagators.support-glitch-test propagators.support-collection-test
clojure -M:test
clojure -M:test propagators.event-test propagators.experimental.visualization-composition-test propagators.relationship-dataflow-test
```

- Focused suites: **715 assertions passed**, no failures/errors: diamond 41,
  collection 674. All **8 schedules** converge to 43; no mixed 33 is published.
- Expanded collection checks cover associativity, commutativity, idempotence,
  coherent-over-mixed dominance, stale arrival, retraction/reactivation,
  equal-version conflicts, and preservation of uncovered/concurrent premises.
- Default suite: **2,908 assertions passed**, no failures/errors.
- Separate regressions: event **22/22**, visualization **33/33**; dataflow
  **36 passed, 2 known failures**, unchanged error-reporting assertions.

These tests establish the tested diamond's safety and convergence, not general
glitch freedom, independent-source atomicity, or XR behavior. No runner, cell
evaluator, layered application, or distributed-TMS changes were needed for this
correction.

## Timestamp-aware support sets and source-cell identity

The subsequent independent-source probe originally produced **758 passed,
18 failed**: same-epoch joint support did not replace singleton observations.
After the reviewed plan, support now owns a timestamp-aware set order. Those
previously failing assertions pass without weakening their expected results.

`covers? new old` matches every old premise by source, accepting a newer timestamp
or equal timestamp/status. `dominates?` requires compatible new support, coverage,
and either non-reciprocal coverage or replacement of an incompatible old tuple.
It never normalizes a mixed computation first. A/B below abbreviate real cell IDs.

| Old evidence | Replacement | Result |
|---|---|---|
| `10/{A@1}`, `20/{B@7}` | `30/{A@1,B@7}` | Both singletons removed by strict extension. |
| `10/{A@1}`, `20/{B@7}` | `30/{A@2,B@7}` | Both removed; A advances and B support extends. |
| `10/{A@1}`, `20/{B@7}` | none | Contradiction with exact support `{A@1,B@7}`. |
| `10/{A@1,A@2}`, `20/{B@7}` | none | Contradiction retains all three premises. |
| `10/{A@1}`, `20/{A@1}` | none | Equal-support conflict remains. |

Retraction of A removes its old payload but retains its retracted premise;
the result remains unusable under the conjunctive rule. Reactivating A with a
value agreeing with B restores usability. Equal-valued independent observations
combine their source dependencies in strongest.

Public specs cover premise, support, observation, and evidence sets. `:source`
must satisfy `ids/node-id?`; symbolic labels and malformed IDs are rejected.
Derived computations preserve injection IDs. The two-step example's middle and
final results both resolve their source through:

```clojure
(net/network-cell-strongest network (:source premise))
```

This identifies a cell in its owning live network, not a historical value or an
occurrence address across copied nested networks. Validation of ID shape cannot
prove network membership; the integration test establishes membership for the
actual injected source. Full reload clears the owning environment as before.

### Verification of this implementation

Focused support, collection, diamond, layered-support, and message-lift suites:
**1,336 assertions passed, 0 failures/errors** (473 + 792 + 41 + 20 + 10).
Checks include 32 support sets and 32,768 triples with active/retracted entries,
merge laws, all six replacement arrival orders, delayed stale replay, all eight
diamond schedules, exact contradiction support, and injection-cell lookup.
The old retained-count check now verifies the exact two surviving observations,
because joint support correctly subsumes the B-only observation.

Default suite: **3,054 passed, 0 failures/errors**. Separate regressions: event
**22/22**, visualization composition **33/33**, and dataflow **36 passed, 2 known
failures** in unchanged error-reporting assertions. These dataflow failures are
not included in the default suite. `git diff --check` passed.

Grounding preserved the approved support/collection boundary: no runner, cell
evaluator, generic layered application, compound synchronization, or existing
distributed-TMS changes were needed for this implementation. The September 9
retained-evidence constraint's exception remains limited to the user-approved
opt-in compaction policy; no model facts were changed.

## Remaining slices

| Slice | Status and completion gate |
|---|---|
| 1. Collection, message lift, live retraction | Headless linear and all 8 diamond schedules pass after approved dominance compaction; default suite passes. Two independent dataflow error-reporting failures remain. |
| 2. Whole-datum transport audit | Pending. Distinguish unusability for computation from information transport in forwarding and compound access. Obtain renewed approval before changing compound synchronization. |
| 3. Complete tracer and reactive publication | Pending. Keep tracing pure; compose source/epoch stamping and message lifting separately. Stable source identities; duplicate activation must not manufacture epochs. |
| 4. Consumer migration | Pending. Migrate trace/dataflow/view consumers, preserve error reasons, and keep XR interpretation separate. Remove graph-specific snapshot merging only after parity. Review clock/TUI/event producers individually. |
| 5. Cleanup and end-to-end verification | Pending. Audit reducer-cell TMS reachability before removal; verify headless views, desktop/mobile interaction, and full-environment reload. Report runtime, visualization, and regression results separately. |

This is not an automatic migration of existing events or distributed TMS. No
browser/reload verification or reducer-cell TMS removal is claimed. The original
implementation slices did not include commits or model mutation; the subsequently
authorized model supersession and isolated commit checks are recorded above.

The earlier whole-datum network-slot probe still identifies a transport audit
item: computation-unusable layered datums must not be discarded merely because
their bases/support are unusable. Compound synchronization has not been patched.
The pre-existing tracer work remains untouched.
