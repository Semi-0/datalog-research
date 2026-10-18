# Recursive semantic views: boundary review

Prepared 2026-10-18. Planning and assessment only. Production implementation is
paused for review of this revised composition. Existing working-tree edits have
been preserved. The attempted `strongest-value-with` change was reverted;
`propagators/cells/merge.clj` has no working-tree diff.

## Problem and observed evidence

The captured fragment belongs in the cell's semantic payload. Its observation
identity, scopes, captured slots, dependencies and refinement provenance belong
to that value. The recorder should retain the publication unchanged. There is
no additional `captured` argument to a cell or to the visualizer.

The current fragment also advertises the legacy `:semantic-trace/graph` marker.
The observer puts it inside a normal support collection. The evaluator invokes
the existing strongest projection, which reduces evidence bases using global
`cell-merge`. Even `nothing + fragment` reaches legacy `graph-union`, which
reconstructs a flat graph and drops the fragment's capture fields. Injecting a
merge at direct cell absorption does not change that nested reduction. A focused
probe reproduces this loss. Adding a merge callback to global strongest would
expand the approved shared evaluation seam and is excluded from this proposal.

Other problems found during source assessment:

- Sampling currently imports capture policy into the generic compiler sampling
  operator. Initial/refresh observer declarations also need a single consistent
  publication path; decorating only installed activations can leave a previously
  emitted declaration with the legacy sampler. This risk needs an integration
  test, not another global merge change.
- A new random observation identity on every sampling attempt can make unchanged
  evidence appear new. Observation identity must be allocated only for a new
  publication; unchanged evidence must produce no publication or retention effect.
- Source storage can be dropped when a load rebuilds a session state map.
  Extension currently shallow-copies session resources and closes the predecessor;
  independently owned retention storage and runner capabilities need a lifecycle
  test before historical expansion is reliable.
- `scope-preview` in `frontend/graph.cljs` draws a fixed line and two circles. It
  is an affordance, not the actual captured semantic graph or shared geometry.
- Replay expansion currently replaces the visible graph without a scope trail,
  per-scope camera state, or an implemented refinement-publication contract.

Browser assessment at `http://127.0.0.1:4780/`, Safari, desktop 3, `replay.lain`:
the saved source was the AMB replay example with extension `(-> (list 1 2)
domain)`. Initially the topology layer said `Pane changed; refresh before
retrying`. After page refresh, enabling Topology and running the saved pane with
Command-Enter, the result showed `THE NOTHING`; the topology contained a single
`Inspect cell` with `the nothing`. No recursive preview/detail traversal or
recording timeline was visible. This is a failed user-visible assessment. The
long-running server's loaded classes were not fingerprinted, so this observation
does not prove that every current working-tree edit was loaded.

## Grounding and hierarchy

Kiroshi selected the intended repository and HEAD
`2419e6de84e204ba695f47aba74f92e8376c51d1`. The combined Question Model at revision
180 was provisional and omitted 22 facts. Follow-up component queries at revisions
183, 185, 186, 187 and 188 remained provisional: information, host and structure
omitted zero facts; compiler and kernel each omitted four explanatory facts.
Selection reasons included direct component applicability and supported-by,
motivated-by and preserves explanation paths. Missing-evidence lists include the
candidate components and constraints. No derived obligations were returned.
No derived relationship is used as architecture knowledge.

All relevant Principles, Constraints, components and boundary assignments are
active **candidates**, with `needs-verification`; they are context, not current
normative authority. The superseded flat-GUR candidate is historical; the active
flat-GUR-v2 and AMB decisions remain candidates. The user's explicit module,
immutability, session isolation and composition requirements govern this review.

The six inspected assignments each give one distinct child the same parent:
`propagator-infrastructure` owns kernel, information, structure, recursion,
compiler and host. Seven component identities and six assignments form one
acyclic star with no orphan or multiply parented child. Preserve all identities,
assignments and AMB facts. Classification: implementation within the existing
boundaries, with new candidate evidence/decisions; no responsibility transfer or
hierarchy mutation is proposed.

| Component | Responsibility and proposed local work | Exclusions / fixed dependencies |
| --- | --- | --- |
| Information | Capture join and capture-publication evidence/projection adapter | Global `cell-merge`, global strongest, support-collection implementation and freshness policy stay fixed |
| Tracer (uses the existing information/compiler boundaries) | Frozen acquisition, relevant-scope selection, stable observations, capture publication and refinement | No live fallback for historical slots; no renderer/layout policy |
| Kernel | Consume the already approved explicit merge through evaluator/patch constructors | No further kernel/evaluator/dispatcher/scheduling change |
| Structure | Public compound constructors and slot/accessor transport | No capture freshness or support policy added to compound merging |
| Compiler | Existing CPS and callable/patch contracts; local adapters only | Lain dispatcher, recursive declaration policy and generic sampling stay fixed |
| Host | Pane capability selection, source retention/lifecycle, request delivery, pure scenes and rendering | Recorder/reducer and solver policy stay fixed; host does not invent semantic topology |

Dependencies remain host/session composition -> public tracer and information
operations -> existing compound, runner and compiler APIs. No reverse dependency
from shared merge/strongest or generic sampling into capture is introduced.

## Proposed code and public contracts

`review_probe.clj` contains complete executable Clojure for the proposed
information adapter and tracer publication composition. It is a planning probe,
not a loaded runtime extension. Its new names are proposed contracts. Calls to
`collection/content`, `collection/merge-content`, `collection/strongest-value`,
`object/compound-object`, slot access, evaluator construction and CPS APIs are
inspected existing contracts.

The proposed `publication` constructor builds a named compound with public base,
support and optional premise-state layers, plus adapter-owned kind/evidence
slots. The retained support collection remains authoritative and opaque; the
base/support layers are explicitly derived from that evidence. The adapter calls
the existing support collection projection with `capture/join` as its base join.
It never discards evidence in order to manufacture merge laws. Existing global
strongest returns the compound unchanged; existing readers obtain the capture
from its ordinary base slot. The outer publication carries complete dependencies.
This is ordinary cell content, not an extra captured parameter or a second
side-channel representation of semantic facts.

```clojure
(def session-merge
  (combinator/branch
    publication-pair? join-publications
    capture/capture-pair? capture/join
    merge/cell-merge))

(defn session-iterator [evaluate-patches]
  (constructor/network-iterator-constructor
    runner/fifo-task-policy
    runner/evaluate-propagator
    evaluate-patches))

(session-iterator (runner/make-patch-evaluator session-merge))
```

The probe defines all adapter helpers. `publication-pair?` claims only a pair
containing this explicit publication type. Its counterpart may be nothing,
contradiction, another publication, or supported state/evidence routed to that
dedicated publication cell. Unsupported mixtures are inspected failures. Ordinary
pairs delegate to the existing merge. Freshness between distinct publications
uses the existing support order; ACI fragment refinement joins only the same
original observation. Incomparable cross-observation bases must diagnose conflict.

The proposed `make-root-capture-activation` demonstrates an immutable tracer
publisher: read the prior publication, allocate a prospective source epoch,
capture from an explicitly identified frozen source, compare relevant evidence,
and return either no effects or an ordinary cell message plus a source-retention
request. It captures only root/output identities. It does not capture a session
atom. Watch installation and refresh are still to be implemented and tested.

## Implementation order and permitted files

1. Move the tested publication adapter into the capture information module and
   compose it in the pane's session runner. Retain the original approved merge
   constructor seam; remove the abandoned strongest extension completely.
2. Put capture sampling/publication in a tracer-owned observer adapter. Compose
   existing observer, propagator, declaration and relationship APIs so initial,
   control and dynamically refreshed watches all emit the same publication.
   Apply the final activation before declaration recording. Restore generic
   `network_sampling.clj` to its capture-independent behavior. Install the capture
   observer only through the owning pane's extension. Lain supplies root and
   direction; capture remains the resulting cell value.
3. Preserve capture publications through tracer-owned graph modifiers and their
   operator adapter. Publish supported modifiers with the capture constructor;
   do not send fragments back through legacy graph/compound unions. Audit the
   relevant paths rather than broadening global dispatch.
4. Retain frozen originals in the owning pane generation. Clone retention state
   and recompose the candidate runner when extending; promotion must not clear
   the successor's sources. Close/failed load removes only its own capabilities.
   Explicitly select that runner for synchronous, resumed and asynchronous work.
5. Refinement reads the retained original through `SemanticCaptureSource`. A
   request names an existing observation and scope. Join with that observation's
   accumulated fragment, then publish a new immutable graph with the same
   observation identity and refinement provenance through ordinary cell messages.
   Give each observation an accumulation cell and use the existing source-version
   publication stream to record each new accumulated result. The stream's
   publication version is distinct from original observation identity. A historical
   refinement can therefore become a new frame without rewriting its earlier
   frame or merging different original observations. Missing originals produce
   missing diagnostics. Verify the accumulation-cell -> publication-stream ->
   unchanged-recorder composition before adding the UI trigger.
6. Keep the existing recorder/reducer unchanged. Verify capture identity,
   support, historical domain/list slots, retraction and frame immutability at
   that boundary. Keep legacy recordings readable and explicitly partial.
7. Feed capture scopes to the existing pure CPS scene compiler. Resolve previews
   from semantic scope references, not collection shape. Use the same scene and
   layout-provider request for preview/detail, and scale canonical geometry into
   the initial 200 x 120 preview. Replace the fixed two-circle affordance with
   real scene nodes/edges. Acquire a missing scope at the tracer/session request
   boundary, then compile its publication; compilation itself has no effects.
8. Add recursive navigation/backtracking and per-generation/scope layout/camera
   state. Retain Standard, 28-world-unit measured rectangle clearance and anchors
   only while held. Recompute for topology or measured-size changes. Camera
   changes and unchanged polling must not trigger layout. Rebuild and fingerprint
   the loaded server/client before browser acceptance.

Expected production changes are limited to capture/tracer modules, their local
observer/modifier adapters, the session runner composition, pane runtime and
inspection, semantic scene compiler, recursive graph/replay rendering, and
focused tests. `cells/merge.clj`, `support_collection.clj`, compound merge,
`network_history.clj`, the reducer, Lain CPS dispatcher, task policy and AMB solver
are fixed. Any additional shared-kernel change returns to boundary review.

## Required checks and assessment

The probe declares its test checks before execution. The final probe run and
outcomes are recorded in `ASSESSMENT.edn`. Tests run against a dirty working tree
at the HEAD above. They are not committed-evidence receipts. Representative
committed dependency blobs are recorded there for provenance only; they do not
prove the uncommitted proposal is implemented.

Required implementation acceptance checks:

- Default parity: results, failures, supports, tasks, scoped routing and ordinary
  merges; no global method registration or mutation.
- Publication algebra: ACI by declared semantic equality, both operand orders,
  localized conflicts, cross-observation rejection, support/retraction, freshness
  and complete retained evidence. Inspect raw content separately from strongest.
- Tracer/history: unchanged sampling reaches quiescence; every watch uses the
  capture path; historical slots use the original; refinements append frames;
  previous frames and identities survive deep traversal, sharing and cycles.
- Lifecycle: ordinary/extended sessions concurrently; explicit runner selection
  after resume and asynchronous work; extend/promote/close/failed load isolation.
- Browser: fresh AMB run plus domain extension produces a scrub-able timeline;
  actual semantic preview nodes/edges; open two nested scopes and navigate back;
  inspect historical domain/tail after a later live change; cycle/shared-reference
  identity; preview/detail geometry agreement; pan/zoom preservation; no layout
  work on unchanged polling/camera input; 28-unit clearance and no released pins.
- Compatibility: legacy partial recordings and unextended sessions.

Only the focused proposal compatibility checks and the negative browser
assessment have run in this planning pass. Full runtime, lifecycle, asynchronous,
recursive browser and geometry acceptance remain not-run. If a proposed adapter
cannot satisfy these using the listed public APIs, report the specific failure
and revise the plan before altering a fixed dependency.

## Model delta and consent

`candidates.edn` displays the complete proposed three-fact batch. It retains the
original proposed identities and updates their planning evidence to the observed
failure and tested adapter. All three remain active candidates with
needs-verification; none is an approval or supersession. Existing component,
assignment and AMB identities remain untouched.

Each candidate is validated individually using non-persisting `kiroshi propose`
(the installed CLI accepts one map, not a vector batch). No remember, approve,
supersede, verify receipt, stale, export, commit or push is authorized by this
planning request. After implementation, reconcile evidence with actual checks
and committed blobs. Ask separately for model sync and named-human approval.
An authorized durable model change must export `.kiroshi/model.edn` and include
it in the related commit when committing is authorized.

The evolve-system-architecture skill step 7 requires explicit consensus on this
concrete boundary composition before implementation. Approval of this code plan
does not authorize model sync.
