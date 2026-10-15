# Premise-state transport progress — 2026-10-01

Later checkpoint: [consumer migration and verification](TTMS_CONSUMER_MIGRATION.md).
The slice 1/2 evidence below remains valid; its no-consumer-migration status
describes that earlier checkpoint, not the latest working tree.

Status: **slice 1 recovery and slice 2 structural publication gates pass through
a local propagator extension**. No consumer/XR migration or overall completion
is claimed.

## Corrected TTMS acceptance

At the user's direction, removed the extra test requiring immediate downstream
withdrawal when `10/A@1` and `20/A@1` conflict without a source-state change.
Local contradiction is correct; it is not implicit source retraction. The
replacement test verifies local contradiction, idle concrete callbacks, repair
by `30/A@2` (two increments produce `32/A@2`), explicit withdrawal at A@2,
recovery by `40/A@3` (output `42/A@3`), and rejection of delayed A@1 replay.
No automatic epoch or observation-invalidation mechanism was added.

## Implemented

- TTMS content keeps `:support/observations` and `:support/states` separate.
  Source-state updates are explicit layered data, not hidden counters.
- State-enabled strongest checks source versions/statuses without assigning
  unrelated source knowledge to a computation's support. Active state alone
  cannot relabel an old payload.
- `stdlib.premise-state/procedure` joins state independently of base computation.
- `experimental.premise-publication/content` and message lift publish usable
  results or state-only updates. No computed nothing/contradiction payload is
  forwarded in this path.
- Opt-in scalar/branch applications compose state transport. Compiler lowering,
  runner, cell evaluator/equality, generic application, and accessors are unchanged.
- Structural-field publication now supports explicit premise-state inputs,
  including state from nested retained field content. Existing two-layer inputs
  retain their original publication semantics.
- `p:structural-data-field` specializes the same publication installation for
  ordinary maps in an explicit TTMS envelope. It checks only outer support and
  top-level nothing/contradiction; nested domain keys are opaque. Its publication
  policy likewise preserves nested maps instead of recursively classifying their
  keys as layers. Existing `p:structural-field` semantics remain unchanged.
- Registered `premise-transport-test` in the standard propagator suite.

## Verification

```sh
clojure -M:test propagators.structural-field-test propagators.premise-transport-test propagators.structural-publication-test propagators.compound-object-network-slot-test
```

- Premise transport: **9 tests, 80 assertions, all pass**.
- Structural fields: **16 tests, 551 assertions, all pass**. This includes the
  formerly failing domain-key examples at all three peers, nested maps whose
  domain fields contain nothing/contradiction, both legacy/state-enabled inputs,
  and blocking of unusable outer envelopes without blocking premise transport.
- Structural publication: **38 assertions pass**; existing compound slots:
  **71 assertions pass**. Together with transport: **740 assertions pass**.
- Existing regression namespaces: **1,837 unique assertions, all pass** across
  support collection, layered support, support, branching/primitives, structural
  publication, source publication, transport/glitch, event, and distributed TMS.
  The command accidentally repeated the 22-assertion source-publication namespace;
  the printed 1,859 total includes that duplicate and is not the unique count.

Passing new structural cases cover chained and cyclic bidirectional slots,
state-only withdrawal/bring-in, field disappearance/reappearance, nested parent
and field premises, both orders of simultaneous conflicting writes, fresh-source
repair, data before accessor installation, a late participant after withdrawal,
unchanged topology, stale replay, and quiescent rerun.

Visualization regression run: composition **33 pass**; relationship-dataflow
**36 pass, 2 fail**, preserving the two known error/reason-reporting failures.
Those failures are not claimed fixed by premise transport. `git diff --check`
passed. No browser or external-file reload check ran in this slice.

Earlier full `clojure -M:test`, before adding the local structural-data extension:
**3,586 pass, 1 failure, 2 errors**. All three remaining
outcomes are in `ttms-compound-boundary-test`: nested slot retraction readiness
and two domain-slot/metadata errors. The preexisting working-tree removal of the
abandoned switch test registration was preserved, not counted as a new fix.
The structural-field experimental suite is checked explicitly above and is not
included in the default full-suite total. The two dataflow failures are likewise
from the separate explicit regression run.

## Resolved locally: data keys mistaken for layers

The supported struct bases are ordinary maps:

```clojure
{:x 10 :support :domain-support}
{:x 10 :base :domain-base :support :domain-support}
```

Expected: field extraction publishes x=10 with the outer struct premise through
each real slot participant. The original generic-guard constructor completed
without extracting the field; the local extension now passes those assertions.

`datastructures.layered-value/layer-present?`, `support-of`, `support-layers`, and
the map `value/unusable?` implementation infer metadata from keys and recursively
inspect the base. The concrete guard therefore classifies ordinary domain data
as unusable and never invokes field extraction. This reproduces the semantic
issue previously seen in the old compound-boundary tests on the chosen new path;
it is not a request for another accessor mechanism.

The earlier conclusion that shared classification had to change was too broad.
The user directed a propagator extension instead. This constructor has an explicit
input contract (TTMS content with an opaque map base), so it can distinguish the
outer envelope locally without inventing a global classification or new wrapper.
It reuses the existing extraction, support combination, state transport, and
slot topology through a specialized readiness/publication policy:

```clojure
;; data-cell holds supported map DATA. slot-cell is a real participant in the
;; persistent topology; do not put TTMS around the topology owner itself.
((field/p:structural-data-field data-cell :x slot-cell) network)
```

No shared layered readiness, generic application, merge, accessor, runner, or
compiler change was needed for this extension. Ordinary operators do not gain
opaque-map semantics automatically; use the extension at the structural-data
boundary. The old compiler slot-operator failures are not claimed fixed.

Grounding checked the September 24 bidirectional-slot, September 9 explicit
projection, and September 29 TTMS dominance constraints at model revision 474.
The TTMS fact remains needs-verification; no model facts were changed. The
grounding review's fixed boundaries are preserved by the local extension.

## Remaining slices

1. Premise transport recovery gates pass; broader projection claims still rely
   on their explicit tests, not a universal convergence proof.
2. Structural premises gates pass through the explicit structural-data extension.
3. Migrate trace/dataflow/map/filter/views and preserve diagnostic reasons.
4. Replace only visualization selection's reducer with a TTMS source.
5. Verify desktop/mobile clearing, interaction, and external-file full reload.
6. Cleanup after parity; rerun final regressions after fixes. Compiled arithmetic/
   branch benchmarks at 10/100/1,000 stages remain necessary.

No commit or push was requested for this turn. Unrelated dirty work is preserved.
