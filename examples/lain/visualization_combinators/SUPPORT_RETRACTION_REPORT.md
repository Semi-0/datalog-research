# Supported collection, message lift, and live retraction

Design name: **TTMS — Temporary Truth Maintenance System**. The canonical
[TTMS contract](../../../propagators/doc/ttms.md) defines terminology, executable
spec locations, ordering laws, source-cell identity, and limits. Existing namespace
and layer names are unchanged; this report preserves the development history.

Status: **linear live retraction and all 8 tested diamond schedules pass after
timestamp-aware support-set compaction. Same-epoch support extension and
one-newer-source replacement pass; source premises carry injection-cell IDs.
General glitch freedom is not claimed.**
Latest slice (September 30): strongest projection and branch recovery are fixed;
opt-in scalar procedures and headless source-stamped tracing are implemented.
Whole-TTMS-wrapped compound access has a separately failing migration gate;
collection/XR migration is not complete. See the final section for current results.
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
| 2. Whole-datum transport audit | Shared identity publication implemented after renewed user approval. Identity, compiler forwarding, application boundaries, and compound-slot chains preserve TTMS updates/withdrawal. Scoped-address and other consumer transport coverage is not exhaustive. |
| 3. Complete tracer and reactive publication | Pending. Keep tracing pure; compose source/epoch stamping and message lifting separately. Stable source identities; duplicate activation must not manufacture epochs. |
| 4. Consumer migration | Pending. Migrate trace/dataflow/view consumers, preserve error reasons, and keep XR interpretation separate. Remove graph-specific snapshot merging only after parity. Review clock/TUI/event producers individually. |
| 5. Cleanup and end-to-end verification | Pending. Audit reducer-cell TMS reachability before removal; verify headless views, desktop/mobile interaction, and full-environment reload. Report runtime, visualization, and regression results separately. |

This is not an automatic migration of existing events or distributed TMS. No
browser/reload verification or reducer-cell TMS removal is claimed. The original
implementation slices did not include commits or model mutation; the subsequently
authorized model supersession and isolated commit checks are recorded above.

The earlier whole-datum network-slot probe identified a transport audit item:
computation-unusable layered datums must not be discarded merely because their
bases/support are unusable. The approved forwarding correction is documented
below. The pre-existing tracer work remains untouched.

## Initial TTMS transport audit — September 29 (before the forwarding fix)

The results below describe the initial audit. The runnable diagnostic has since
been updated to assert the corrected behavior documented in the next section.

Run the diagnostic without modifying runtime policies:

```sh
clojure -M -m examples.lain.visualization-combinators.ttms-transport-audit
```

The command exited successfully. Its 12 assertions verify that identity activation
and subsequent message lifting preserve the exact payload/support in four cases.
The other outputs are observations of current behavior, not passing assertions
for the desired migration behavior.

| Input | Identity / lifted messages | Compiler mono-sync messages | Compound slot result |
|---|---|---|---|
| Active `10/{A@1}` | 1 / 1 | 1 | Same value and support |
| Retracted `nothing/{A@2 retracted}` | 1 / 1 | 0 | Bare nothing; no support |
| Contradiction with `{A@1}` | 1 / 1 | 0 | Bare nothing; no support |
| Mixed `30/{A@1,A@2}` | 1 / 1 | 0 | Bare nothing; no support |

Here A is an actual source-cell NodeId. These are isolated snapshot probes, not
proof of live compound withdrawal. A compiled lexical `x` expression also follows
10 → 20 → retraction → 7, retaining support. Its result is the source cell itself;
this does **not** demonstrate transport across compiler-generated forwarding cells.

Responsible boundaries:

- `layered/forward-transport-messages` accepts whole values and skips only nil.
- Compiler basis `sync-update`, used by `mono-sync-messages`, returns nil for
  unusable TTMS strongest values. Its existing event/TMS policies are separate.
- Compound `network-slot` source projection, peer forwarding, and projected
  accessor paths contain usability gates. The executed probe confirms loss at
  the slot boundary; it does not separately exercise every peer-routing branch.
- `stdlib/id` emits the whole datum. `message/lift-message collection/content`
  correctly packages that emitted datum but cannot restore a suppressed patch.

The reviewed grounding/evolution boundary requires renewed consent before
changing compound synchronization. No compiler, compound, runner, cell evaluator,
or distributed-TMS implementation was changed by this audit. A TTMS compiler
extension cannot yet be claimed to support all primitives and transport paths;
an arithmetic-only chain benchmark would not establish that claim.

Next decision: approve a separately reviewed, opt-in TTMS transport migration
covering forwarding and compound access while preserving persistent bidirectional
topology, or explicitly exclude compound transport from the first compiler
experiment. Tracer publication, consumer migration, and end-to-end verification
remain incomplete. No new full-suite, browser, or performance result is claimed
for this audit-only slice.

## Shared primitive publication — September 29

Following the user's approval to make existing forwarding primitives dependency
aware, publication is now shared through `stdlib.prop/forward-value`:

```clojure
(defn forward-value [content strongest]
  (cond
    (collection/content? content) content
    (datum/layer-present? strongest :support) (collection/content strongest)
    :else strongest))
```

This operation retains separate TTMS observations when available. It must not
replace a collection with a newly combined projection: doing so could fabricate
a jointly supported observation and change dominance. A supported datum without
collection content uses the existing two-layer publication contract. Extra
layers are rejected rather than silently discarded. Ordinary values keep their
previous strongest semantics.

`stdlib/id` and ordinary boundary links share one forwarding activation. Compiler
basis forwarding and compound-slot source/peer/avatar publication reuse the same
value operation. TTMS collections can travel even when their projected base or
support is unusable. No new source identity or timestamp is generated.

The compiled `def-net` test exposed an additional blocking forwarding primitive:
`application/concrete-boundary` guarded its copy with `concrete-propagator`.
That boundary now tests the publishable information, retaining supported
withdrawals. Ordinary nothing/contradiction still block; false still forwards;
ordinary retained content is still copied intact. The global concrete guard,
closure construction, application wiring, compiler syntax, scheduler, `Net`,
cell evaluator, merge, and existing distributed-TMS policy are unchanged.
The unrelated existing `closure-call` visibility edit was preserved.

This Lain composition is exercised directly by the new tests:

```clojure
(let-cell [middle out]
  (def-net copy [input] [output] (-> input output))
  (copy x middle)
  (copy middle out)
  out)
```

Here `x` is bound to a real injection cell. Explicit updates produce
`10/A@1 → 20/A@2 → nothing/A@3-retracted → 7/A@4` at the final output.
Neither the network body nor its application needs a TTMS-specific operator.

Verification:

- `propagators.support-transport-test`: **206 assertions passed** across six
  tests, including live identity/compiler/bidirectional/compound-slot chains,
  compiled single and double compound applications, stale replay, quiescent
  reruns, unchanged topology, conflicts retaining two separate observations,
  support-only withdrawal, recovery, and ordinary boundary behavior.
- The runnable audit passes **20 assertions** for active, retracted,
  contradictory, and mixed-version input. All four now produce forwarding
  messages and preserve exact support at the compound slot.
- Separate event and visualization regressions pass **22** and **33** assertions.
  Dataflow remains **36 passed / 2 known failures**; its diagnostic assertions
  were not changed. That combined command exits 1.
- Final `clojure -M:test`: **3,260 assertions passed, zero failures/errors**.
  This includes the 206 transport assertions; the separately reported dataflow
  namespace is not in the default suite.

This establishes the tested forwarding compositions, not universal dependency
awareness of every primitive. Arithmetic continues to use layered procedures
with the support layer and message lift. An all-primitive compiler environment,
comparative chain benchmark, tracer publication, consumer migration, and
desktop/mobile/reload verification remain separate pending work. No KIROSHI
facts were mutated, and no commit or push was performed in this slice.

## Primitive branching — September 29: implemented, integration blocked

The opt-in `propagators.experimental.ttms-branching/session-extension` declares
`if`, `switch`, and `branch` through the standard session extension API. Existing
Compiler 2 syntax and argument/output selectors are reused; `cond` already lowers
to these operators. Default compiler bindings are unchanged.

The primitive selects argument cell IDs. An ordinary layered procedure returns
the last selected base and applies `stdlib.support/procedure` to the selected
arguments. `message/lift-message` packages the result as TTMS evidence. No
branching code retracts premises, changes source IDs, or advances timestamps.

```clojure
;; Usable condition: only these two arguments enter layered application.
(layered/p:apply-layered procedure-id
                         [condition-id selected-input-id] output-id)

;; Publication is composed using the existing activation interface.
(prop/compose-activation activation
                         (message/lift-message collection/content))
```

Disabled `switch`/`branch` outputs select the condition and a constant `nothing`
cell. This emits absence with condition support, not a retracted premise.
An unusable condition selects only itself. Unselected value dependencies never
enter the emitted observation. Plain constants carry empty support.

Application activations are prepared once using the public layered installer
on a disposable scratch graph. That graph is not installed in the live network;
the live selector owns all input/output edges. Evaluation uses the unchanged
layered runtime and its existing activation-local materialization. There is no
private executor, mutable history, or persistent per-activation topology.

### Verified boundary and stopping condition

`clojure -M:test propagators.ttms-branching-test`:
**30 passed, 2 failed, 0 errors (4 tests, 32 assertions)**.

Passing checks cover primitive truthiness (`true`, `false`, `nil`, `0`), exact
selected support, both branch outputs, disabled output publication, layered
nothing/contradiction/retracted/mixed conditions, selected unusable values, plain
constants, compiled `if` and `cond`, unselected-source withdrawal, unchanged
topology, and quiescent reruns.

The required recovery test reproduces this failure:

```text
select A:                10      / {C@1 active, A@1 active}
A withdraws:             nothing / {C@1 active, A@2 retracted}
select B, primitive:     20      / {C@2 active, B@1 active}       CORRECT
select B, TTMS strongest:20      / {C@2 active, B@1 active,
                                   A@2 retracted}               UNUSABLE
```

Both failing assertions concern the strongest projection: exact support and
usability. The primitive emission assertion passes. The test remains registered
in the default suite; it is not skipped, inverted, or weakened.

Current `support-collection/strongest-value` combines the retained source frontier
with support from current observations. Correcting that is a separate revision
of clause 5 of `:constraint/ttms-compacts-by-support-dominance`, not a branching
responsibility. Implementation stopped at the approved boundary. The collection,
runner, compiler, compound synchronization, and KIROSHI facts were not changed
by this slice.

The earlier in-memory projection experiment (33 tests / 1,512 assertions passed)
is supporting evidence only. It is not an implemented or approved contract change.
Any proposal must preserve independent-source conjunctive invalidation and
mixed-version incompatibility, while separating obsolete evidence from current
result dependencies. It needs separate review before consumer migration.

Additional regression check: events **22 passed**, visualization composition
**33 passed**, and dataflow **36 passed / 2 known failures**. The dataflow command
exits 1; this slice does not fix its diagnostic failures. `git diff --check` passed.
The full `clojure -M:test` run completed with **3,290 passed / 2 failed / 0 errors**.
Only the two newly registered branch-recovery assertions fail; the existing
default-suite assertions remain green. This is a blocked implementation, not a
completed migration or a healthy full-suite result.

### Remaining slices and acceptance gates

1. **Branching integration:** resolve the TTMS projection contract separately.
   Then complete nested conditional support, compiled switch/branch, repeated
   branch flips, same-valued branches, condition withdrawal/recovery, and task
   order tests. Current evidence does not establish full reactive branching.
2. **Transport and core primitives:** complete scoped/nested coverage and the
   opt-in core primitive environment. No compound synchronization redesign.
3. **Tracer publication:** pure complete snapshots composed with stable-source,
   change-sensitive epoch stamping and message lift; unchanged activation must
   not manufacture observations.
4. **Consumer migration:** migrate selected dataflow/collection/view consumers;
   preserve reasons and source references. Retire graph snapshot merging only
   after replacement parity. XR clears stale data but keeps a status card.
5. **End-to-end verification:** headless composition, desktop/mobile interaction,
   external-process file reload, stale controls, and plain/TTMS chain benchmarks
   (10/100/1,000 stages; initial execution separate from update/withdrawal/recovery).

`when` stays an availability-triggered topology constructor, including concrete
false values; it is not a truthiness branch. Withdrawal of its already-created
topology is explicitly deferred. Reducer-cell TMS remains while maintained
callers exist. Clock/TUI/event migration, commits, pushes, and cross-repository
ports are not part of this slice.

## Projection correction and continued slices — September 30

The user approved correcting strongest projection while retaining cell content.
`strongest-value` still uses the global source frontier to identify stale
observations, but combines only current observations' full support into a current
result. When no observation is current, the base is `nothing` and the frontier
remains as invalidation information. No stale payload receives a fresh support.
Evidence merge and dominance are unchanged. Incompatible current support is
still incompatible; current independent sources still obey conjunctive retraction.

This fixes the previously recorded branch-recovery failure. The old B-support
expectation in `incompatible-computations-are-not-relabeled` was updated: B belonged
only to a stale observation and cannot become a dependency of the current
`nothing/A@2` observation. New tests cover both arrival orders, stale replay,
concrete/nothing/contradictory payloads, retained content, and the no-current case.
The canonical TTMS documentation is updated. KIROSHI clause 5 and its historical
declaration are not silently overwritten; separate model supersession is pending.

### Continued implementation

- **Branching:** 81 assertions pass, including nested `cond`, compiled `switch`
  and `branch`, repeated activation/deactivation, condition withdrawal/recovery,
  equal-valued branches with distinct supports, and all six arrival orders of
  condition/A/B updates. The existing diamond test separately explores eight
  task schedules. These finite tests do not prove unrestricted glitch freedom.
  Correct Lain syntax is `(cond [c a d b else z])`; the earlier multi-vector
  `cond` test was incorrect and did not establish the coverage previously claimed.
- **Scalar extension:** `propagators.experimental.ttms-primitives/session-extension`
  provides `+ - * / <= < > >= = not and or str` plus the branching bindings through
  the existing session extension API. Each call installs an ordinary base/support
  layered procedure and composes its activation with message lift. No compiler
  defaults, lowering, or application contracts changed. **62 assertions pass**,
  including a compiled arithmetic/conditional chain and `def-net` with both
  explicit input and lexical capture through update/withdrawal/recovery. Scalar
  `not` retains Compiler 2 truthiness, and comparisons retain variable arity.
- **Publication:** `propagators.experimental.ttms-publication` provides
  `next-source-datum`, `stamp-source`, and a composition of the existing pure
  observer with stamping and message lift. The source is a dedicated injection
  cell with positive integer epochs, not the observed cell. All prior state is
  read explicitly from that cell; no counter/cache is hidden in a closure.
  Identical base/status reuses the epoch. More than one message to the same
  source in one activation is rejected, avoiding ambiguous epoch assignment.
  **22 assertions pass** for pure complete sampling, unchanged reruns, no topology
  growth, one-source evidence compaction, withdrawal/recovery through forwarding,
  source ownership validation, and preservation of unrelated patches/metadata.

### New transport boundary: whole supported compound objects

The previously verified shape was a compound object whose slot contains a TTMS
datum. The additional migration gate tests the distinct shape:

```clojure
(collection/content
 {:base (obj/compound-object {:x 10})
  :support #{{:source owner :timestamp 1 :premises-status :active}}})

;; Access the wrapped object's x slot through the existing bidirectional API.
((obj/p:network-slot :x output owner) network)
```

`clojure -M:test propagators.ttms-compound-boundary-test` reports **1 passed,
0 failed, 1 error**. The plain compound control passes. The wrapped case throws
`Expected supported collection content`: the accessor declaration sends a raw
accessor-network fragment to the owner cell, and the normal merge dispatch sends
that fragment to TTMS evidence merge, which accepts only supported observations.
The intended wrapped-value assertions are not reached; no correctness is claimed.

This probe is a separately executable migration gate, **not included in the
default suite**. Its expected behavior is not inverted into an expected exception.
The current shape must not be advertised as supported merely because the default
suite passes. The failure is independent of the corrected strongest projection.

The grounding stop boundary applies: do not make TTMS evidence accept raw
topology declarations, reinterpret writes through source references, change
compound synchronization, or introduce a private execution path to bypass this.
The next review must choose an explicit compound-access composition that preserves
bidirectionality, or approve a narrowly scoped routing change for supported owners.
Scalar and headless sampler publication do not require that change and are verified
independently. Consumer migration is paused pending that review.

### Remaining work

- Resolve whole-supported compound access; finish scoped/nested transport gates.
- Migrate trace/dataflow/map/filter/view consumers and preserve diagnostic reasons.
  Keep old graph merging until all maintained callers have replacement parity.
- Integrate source-stamped observers into the live session/loader and XR status
  cards; verify desktop/mobile interactions and full-environment external reload.
- Run the plain/TTMS chain-family benchmark with separate timing and instrumentation.
- Retain reachable reducer-cell TMS. `when` topology withdrawal remains deferred.

Separate regression command: events **22 passed**, visualization **33 passed**,
dataflow **36 passed / 2 known failures**. The dataflow assertions were not changed.
No benchmark, browser, phone, reload, or all-primitive-environment completion is
claimed by this slice. No KIROSHI mutation, commit, or push was performed.

Final verification of this working tree: `clojure -M:test` passed **3,459
assertions, zero failures/errors**. This includes the corrected projection,
expanded branching, scalar extension, and source publication tests. It excludes
the explicitly reported compound migration gate and separate dataflow namespace.
`git diff --check` passed. Unrelated pre-existing work was preserved.

## Composed read-only compound access: simple and nested gates (2026-09-30)

This section supersedes the **current-status interpretation** of the preceding
compound probe and 3,459-pass report, not their historical results. The probe had
sent an accessor declaration to the outer TTMS evidence cell instead of accessing
the ordinary compound in its base. That failure does not prove that all supported
compound access requires changing compound synchronization.

The experiment now exposes `ttms-primitives/slot-operator` using the existing
`scalar-operator` installation: a base closure, `support/procedure`, ordinary
`layered/p:apply-layered`, and `message/lift-message`. The base closure explicitly
projects a retained named-network evidence set with `evidence/strongest`, then
reads the selected slot with `obj/slot-value`. A missing slot produces `nothing`.
There is no raw accessor declaration into the TTMS owner, source counter, private
executor, or change to the compiler, runner, cell evaluator, TTMS rules, or compound
synchronization in this slice. This is **read-only derived access**, not a new
implementation of the persistent bidirectional `p:network-slot` relation.

The compiled nested test supplies these bindings through the existing compiler
binding contract (compiler defaults are unchanged):

```clojure
['read-scope (primitives/slot-operator :scope)]
['read-x (primitives/slot-operator :x)]
['environment (env/cell-binding owner)]
```

It runs the Lain expression:

```clojure
(+ (read-x (read-scope environment)) 1)
```

An environment-shaped compound `{:scope <compound {:x 10}>}` produces `11`;
a fresh environment with `x=20` produces `21`; source withdrawal produces
`nothing` with retracted support; reactivation with `x=7` produces `8`. Each
output retains the exact source cell identity, timestamp, and status. This proves
withdrawal of **data derived from an environment-shaped value**, not deletion of
compiler lexical bindings, removal of installed topology, or rollback of effects.

### Tests and discovered limits

`propagators.ttms-compound-boundary-test` is now registered in `run_tests.clj`.
Its **11 tests report 47 passed assertions, 1 failure, and 2 errors**. Failing
assertions remain normal regression tests, not inverted expected-error checks.

Passing cases cover plain access parity, whole-supported slot reads, source
isolation, fresh updates, withdrawal/recovery, delayed stale replay, missing and
false-valued slots, two nested accessors followed by compiled arithmetic,
two-source support, independent equal-valued assertions under the existing
conjunctive rule, inspectable slot-local support, stable outer topology, and
quiescent reruns. The topology assertion covers the active outer network, not
allocation inside activation-local layered execution.

Two distinct limitations remain:

1. **Nested support is retained but not honored by readiness.** A selected slot
   can carry a retracted premise inside a nested layered datum. The output's base
   becomes retained named-network evidence. Explicit evidence projection exposes
   the child premise correctly, but `value/unusable?` reports the outer result as
   usable. Current layered readiness does not traverse that evidence container.
   Retention alone is therefore insufficient to claim correct downstream
   withdrawal for arbitrary nested supported values.
2. **Domain `:support` fields are mistaken for TTMS layers.** Both a compound
   containing `{:x 10 :support :domain-data}` and one also containing a domain
   `:base` field throw during shared forwarding. `stdlib.prop/forward-value`
   classifies strongest values by presence of `:support`, then calls
   `collection/content`, whose exact two-layer contract rejects the domain
   compound. A domain `:base` field alone passes. These failures occur inside
   layered application's ordinary compound transport before the new accessor
   can interpret its base.

Grounding used the current bidirectional-slot constraint (2026-09-24) and explicit
projection constraint (2026-09-09), with current source and tests checked against
their older evidence. The affected runtime path stops at the approved fixed
boundary. No shared readiness/forwarding patch or TTMS policy change is smuggled
into this experiment. A follow-up needs review of explicit layer identification
and evidence-aware readiness before this can support arbitrary environments.

Focused existing regressions remain green: compound network slots **71**,
TTMS primitive/compiler tests **62**, and TTMS branching **81** passing assertions.
No browser/reload, benchmark, or full compiler-environment retraction claim is
made. No KIROSHI fact mutation, commit, or push was performed.

Full registered suite: `clojure -M:test` reports **3,506 pass, 1 fail, 2 errors**.
All failures/errors are the new boundary cases above; other registered tests
pass. The two separately documented dataflow error-reporting failures are outside
this default registration and were not rerun or fixed in this slice.

## Bidirectional switch route experiment (2026-09-30): not promoted

The user authorized trying a support-aware bidirectional switch in the accessor,
continuing migration only if it works, otherwise documenting and committing the
experiment. `propagators.experimental.ttms-accessor-switch` therefore composes
the existing opt-in TTMS switches, without replacing ordinary compound bi-sync.

```clojure
(accessor/install-collection-switch network collection slot-port participant)
;; => {:network installed :tasks initial-tasks :enabled condition-cell}
```

The collection-presence condition is an ordinary base/support layered procedure.
The two directions are the existing switch operator installed as
`[slot-port enabled] -> participant` and `[participant enabled] -> slot-port`.
They use the normal constructed runner. No source identities or epochs are
invented; there is no private executor, mutable reader state, or support stripping.

This is a **route-level acceptance experiment** over explicitly supplied ports,
not a completed accessor that discovers nested slot addresses or writes a raw
accessor declaration into a TTMS owner. It tests a necessary condition before
promoting the route into actual compound-slot installation. Whole-value wrapping
and the shared projection/identification defects remain separate gates.

### Failure mechanism

With `C` the collection source and `B` the independent slot source:

```text
before:       slot contains 10 / {B@1 active}
forward:      participant receives 10 / {C@1 active, B@1 active}
feedback:     slot receives the same supported value
compaction:   {C@1, B@1} dominates {B@1}; original evidence is removed
withdraw C:   route outputs nothing / {C@2 retracted}
reactivate C: route reads its own withdrawn result, not the original 10/B@1
              support combines C@2-retracted and C@3-active; result is unusable
```

This uses the approved timestamp-aware support-set dominance law, not a scheduler
bug. It also demonstrates why checking a concrete base alone is insufficient:
the acceptance tests separately require usability and exact support on recovery.
The source collection cell itself remains unchanged by route execution, both
initial directions propagate, and the network reaches quiescence without growing
its outer topology. These passing properties do not repair evidence loss.

`propagators.ttms-accessor-switch-test` retains ordinary **failing acceptance
assertions** for original-evidence retention and recovery, in both directions.
The test is registered in the default runner. This checkpoint is deliberately
not represented as a passing migration or as a supported default accessor.

### Stop boundary and remaining slices

The direct two-switch proposal does **not** satisfy the migration gate. Migration
of trace/dataflow/map/filter/view consumers and live XR publication is paused.
Ordinary bidirectional slot topology, TTMS dominance, runner, and compiler
contracts were not altered to force this experiment to pass.

A follow-up could investigate explicit separation of independent source facts
from received route contributions, including how a collection update reconstructs
the current slot projection. That requires a reviewed ownership/transport design;
simply dropping the collection premise on reverse flow would manufacture an
unjustified dependency-free result. This failure does not prove that all
bidirectional supported accessors are impossible.

The earlier temporary JVM diagnosis of whole-compound access remains separate:
validating support entries before forwarding and explicitly projecting merged
base evidence passed 3,511 registered assertions under temporary substitutions.
Those substitutions were **not applied**. A further temporary supported-slot
arithmetic probe still produced contradiction and lost child premises, showing
that full selected-slot base/support composition needs its own gate as well.

Deferred work remains: whole-datum/accessor transport, complete tracer consumer
migration, XR desktop/mobile/reload checks, chain performance measurements, and
the reachability-gated reducer-cell TMS cleanup. `when` topology withdrawal stays
explicitly deferred. No KIROSHI mutation is part of this checkpoint.

### Checkpoint verification

- Bidirectional route gate: **3 tests, 18 passed assertions, 10 failures,
  0 errors**. The failures cover both initial directions: independent evidence
  loss, failure to recover on collection reactivation, and a fresh source value
  that restores the base `10` but remains unusable with incorrect support.
- Earlier whole-compound gate: **47 passed, 1 failure, 2 errors** (nested
  readiness and ordinary domain `:support` fields).
- `clojure -M:test` in the working checkout: **3,524 passed, 11 failures,
  2 errors**. Other registered suites pass. The experimental failures remain
  visible and are not converted into expected-error assertions.

The checkpoint includes earlier TTMS transport/branching/publication work needed
to reproduce the experiment. Unrelated tracer/dataflow changes, local skills,
system-model files, and other prototypes are excluded. The guarded
bidirectional route is not installed in any default environment.

The exact staged source tree was separately exported and tested without any
unstaged tracer changes. Its full suite reproduced **3,524 passed, 11 failures,
2 errors**. Staged whitespace validation passed. This is a reproducible failed
experiment checkpoint, not an assertion that the full test suite is healthy.
