# Recursive tracer repair and quality assessment

October 1, 2026. Working-tree changes based on
`841c62255756967abed988d48b4e5393f6f47be8`; not committed or pushed.
This supersedes the open projection failures in `COMPOUND_REPORT.md`, while
retaining that report as the pre-repair evidence.

## Outcome

High-level tracing now excludes unrelated invocations connected only through a
shared callable definition. Compound-body projection keeps the intended child
applications, normalizes expression-return aliases, and resolves local names from
the existing runtime dictionary. Recursive body selection traverses structural
wrappers such as `when` but stops at the next application occurrence.

The bounded recursive example is `recursive.lain`:

```clojure
(def-net down [a] [out]
  (when (switch true (<= a 1)) (-> a out))
  (when (switch true (> a 1)) (down (- a 1) out)))
```

For input `4`, ordinary execution constructs distinct frames for `4`, `3`, `2`,
and `1`, and produces `1`. The outer semantic graph contains exactly the selected
call and its input/output, not the unrelated second `down` invocation. Each
non-base body has exactly 17 directed edges; the base body has 13. Exact node,
edge, propagator-count, and local-name assertions pass. Reinjection preserves
topology and the graph. TTMS publication of this ordinary recursive computation
also passes.

**Fully TTMS-extended recursive computation is not fixed.** It exceeds the
test-only 30,000-transition budget both with and without any tracer. Those two
acceptance tests remain errors, not skipped tests or claimed successes.

## Public composition and ownership

```clojure
;; Existing high-level entry point, retaining its signature:
(dataflow/dataflow-graph network operational-graph)

;; New pure, occurrence-specific body projection:
(dataflow/child-dataflow-graph network [[:outer] propagator-id])
```

An occurrence's propagator ID is available through the existing application
metadata or `occurrence-of` reference. The child API samples the supplied immutable
network. It does not execute a body, recursively evaluate it, or automatically
install a live Lain observer. Call it again with the current network for a fresh
body projection. No XR rendering change or new Lain binding is claimed here.

Responsibilities remain separate:

- `relationship-observer` samples raw topology and carries explicit observed seed
  identities. Its raw operational connected-component meaning is unchanged.
- `dataflow-projection` implements pure return aliases, cycle-checked alias
  resolution, edge renaming, and semantic connected-component selection.
- `relationship-dataflow` reads declared application ports and selects a body or
  seed-connected semantic graph. It does not infer parentage from names or values.
- Compiler `lazy-topology` declares that its `when` result is topology-only;
  `topology-effects` exports only that observation metadata through ordinary name
  binding patches. The record is in existing `Net.dict`, not hidden mutable state.
- `semantic-trace/graph-union` preserves optional seed sets so ordinary publication
  cannot lose the requested semantic selection. Graph merge policy is unchanged.

Net shape, runner, cell merge/equality, TTMS policy, compound synchronization,
application execution, and the `when` activation guard are unchanged. The compiler
does emit additional observation declarations; this is not a zero-compiler-diff
claim.

The `stage` oracle now expects its named `out` directly: improving local-name
resolution enables the same named-output contraction already used for outer
chains. Exact assertions remain; they were not replaced with subset-only checks.

## Quality review

The read-only `review-system-quality` skill was loaded from the local KIROSHI
repository, followed by implementation under the user's explicit fix request.
The grounding skill was applied before changes. Final component-scoped retrieval
at model revision 483 selected Compiler 2 and compound objects; it reported
`grounded`, six budget-omitted evidence facts, and no excluded facts. No model
mutation or verification receipt was submitted.

Model-grounded facts: current/active explicit-projection constraints and principles
(September 9) require readable projections over declared information; the
bidirectional-slot constraint (September 24) preserves compound synchronization.
The current non-materialization and pull-only constraints still have
`needs-verification` evidence status. This repair performs an explicit finite
observation and does not claim a system-wide materialization audit. Component
ownership and hierarchy remain unchanged.

| ID | Status | Evidence, impact, and confidence |
|---|---|---|
| Q1 | Strength | Exact tests now distinguish operational connectivity from semantic value flow. Shared definitions no longer bring unrelated calls into the selected view. High confidence. |
| Q2 | Strength | Return aliases and seed selection are pure, independently tested operations. Real self-loops, bidirectional edges, and multi-output identities are preserved. High confidence for tested cases. |
| Q3 | Strength | Body selection stops at application boundaries, so repeated and recursive occurrences remain distinct without eagerly unfolding the whole tree. High confidence for flat Compiler 2 occurrences. |
| Q4 | Concern | TTMS recursive `when` execution fails independently of tracing. Flat GUR checks bare nothing/contradiction, not layered usability. The guard mismatch is observed; identifying it as the sole cause of non-quiescence remains an inference. |
| Q5 | Strength | Local labels reuse runtime lexical declarations in `Net.dict`; no second dictionary or renderer-owned inference was introduced. High confidence. |
| Q6 | Unknown | No throughput benchmark, arbitrary recursive-depth proof, or physical XR/mobile validation was performed. Bounded regression success is not a general convergence or performance claim. |

The high-level path contains five substantive stages: application-port reading
(boundary adapter), return normalization (semantic transformation), named-output
contraction (semantic transformation), seed-component selection (policy), and
current-value graph sampling (observation). Child projection adds occurrence-body
selection before the same path. No compatibility facade or delegation-only
execution layer was added.

Cybernetic assessment: repeated definitions, late values, recursive wrapper
installation, aliases, cycles, and duplicate activation are the tested
disturbances. Stable occurrence identities, explicit declaration roles, finite
visited sets, exact-edge tests, and idempotent dictionary facts regulate them.
TTMS recursive execution remains uncontrolled in the tested case; a test budget
detects it but is not a runtime solution. Unavailable ordinary inputs are not
hidden simply because their current values are nothing.

Legacy assessment: raw relationship graphs and ordinary graph merging still have
active consumers and unmatched contracts. They are necessary, not removal-ready.
The existing legacy diagnostic-loss tests remain separate failures.

## Rejected implementation and regression lesson

An initial attempt exported every new name binding from a compiled fragment.
The full suite then exposed four subnet/TMS failures because execution markers
also crossed that boundary. That implementation was removed. The final export
is restricted to the new topology-result observation scope; a direct test proves
runtime markers are not exported. The affected Compiler 2 suite returned to
337 passes with no failures/errors. Observation metadata and execution markers
must not be treated as interchangeable dictionary contents.

## Verification receipts

Checks ran on October 1, approximately 09:36–09:58 UTC. The tested HEAD is above;
modified/new files remain uncommitted, so these are working-tree evidence hashes,
not a claimed committed verification receipt.

| Required check | Outcome |
|---|---|
| Earlier eight-program matrix and environment replacement | Passed: 226 assertions. |
| Composed/nested/recursive exact projection, labels, values, occurrence identity and rerun stability | 210 assertions passed; two separately bounded full-TTMS execution controls error. |
| Pure projection and observation-only declaration export | Passed: 15 assertions. |
| Compiler 2 regression suite | Passed: 337 assertions. |
| Compiler closure frames and delayed metadata activation | Passed: 14 assertions. |
| Separate observer/dataflow regression batch | 57 passes, the same two legacy diagnostic-loss failures, no errors. |
| Full suite with localhost access | 4,164 passes, 1 failure, 4 errors. |
| `git diff --check` and evidence hashes | Passed. |
| Desktop/mobile/physical XR and performance benchmark | Not run for this repair. |

The full-suite nonpasses are the prior `ttms-compound-boundary-test` failure and
two errors, plus the new TTMS recursion-with/without-tracer budget errors. The
full suite is **not healthy**. Both bounded recursive runs report 30,002 observed
transitions, with matching leading activation counts; this is evidence that the
failure is not introduced by visualization. Replacing TTMS computation with
ordinary primitives while retaining TTMS graph publication passes.

Reproduce:

```sh
clojure -M:test propagators.experimental.compound-tracer-test propagators.experimental.tracer-topology-test propagators.dataflow-projection-test
clojure -M:test propagators.relationship-observer-test propagators.relationship-dataflow-test
clojure -M:test
git diff --check
```

Representative working-tree blobs:

| Path | Blob |
|---|---|
| `propagators/dataflow_projection.clj` | `8799da4af45311cf0f5aa9cefdc1537707c0dbe2` |
| `propagators/relationship_dataflow.clj` | `244aa245fd3a60a297dd07f389cf60c88d27bd7b` |
| `propagators/relationship_observer.clj` | `ee99172e13987f39c8f749b68d58e7e841663a60` |
| `propagators/compiler_2/runtime/topology_effects.clj` | `e8aaf1d1db25597b1c7fa3366c3111afe1b9f145` |
| `test/propagators/experimental/compound_tracer_test.clj` | `c4b2b25a8aa016d12c719efe340e0912e34c41b5` |

## Remaining work

Investigate the opt-in TTMS interaction with the existing flat-GUR `when` guard
as a runtime task. This repair deliberately does not change that execution
contract. Premise retraction of already installed `when` topology remains deferred.
Cross-owner nested Net paths and automatic live child-view publication need their
own tests; no support is inferred from these flat recursive examples.
