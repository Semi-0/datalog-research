# Constraint-first plan: recoverable blocks and executable semantic stacks

Status: measured acceptance checkpoint, 2026-10-21. Gates A and B are pinned as
`stack-spec-2026-10-20-r9`; recoverable blocks, read-only checkpoint aggregation,
a clean revision-pinned semantic baseline, the opt-in premise-versioned editor
path, frozen readers, explicit traversal, recursive validation, semantic clean
comparison, spatial rendering, complete fixture execution, anchored browser
acceptance, and comparative measurements are implemented. See
`experiments/top_down_constraints/` for evidence and limitations.

## Goal and governing order

Deliver recoverable closed editor blocks, a complete high-level Lain
compound-occurrence-to-stack transformation, its primitive dependencies, and an
in-editor spatial stack view.

Follow [the top-down methodology](lain-top-down-agent-orchestration.md):

1. Finish the observable constraints, failure cases, and acceptance fixtures.
2. Express the complete high-level transformations and orchestration in Lain.
3. Review and pin that specification revision.
4. Fill only the lower-level obligations required by the accepted composition.
5. Project the validated result into editor scenes and verify it in the browser.

Recoverable blocks are implemented after their own constraint gate; they do not
need to wait for stack primitives. No stack primitive or renderer implementation
starts before the stack constraint and high-level specification gates pass.

## Current evidence and gaps

- Closed-block recovery and the opt-in premise-versioned editor path are
  implemented behind the existing acknowledged command channel.
- `stack-design.lain` imports the structure, constraints, traversal, hierarchy,
  fixtures, and example domains. The example evaluates to 15 and produces a real
  frozen semantic capture.
- The high-level loop is tail-recursive and keeps pending work, discovery, and
  output connections explicit. Host primitives implement generic compound and
  frozen-reader obligations rather than an opaque finished traversal.
- A root-only capture remains pending. Publishing the child scope from the
  retained original observation resumes the same network and materializes a
  stack. An independently reconstructed network agrees semantically after
  generated identities are normalized.
- The renderer anchors each inter-plane edge to its source compound node. A
  Chromium acceptance run verified two semantic planes, preserved node kinds,
  a source-anchor distance below 0.19 px, and view-state-only spacing changes.
- All nine declared fixture expectations agree. Five alternating retained/clean
  pairs per measured edit scenario agree semantically; unrelated edits execute
  no stack-constraint primitives under exact-name classification.
- Unrelated dirty worktree changes remain outside this task. A locally available
  uncommitted helper is not automatically an accepted dependency.

## Gate A: complete and review the constraints

For each constraint below, name its public inputs/outputs, positive fixture,
negative or partial fixture, owner, and expected diagnostic. Encode the stack
checks as high-level Lain contracts in the constraints domain before adding the
lower-level implementations. Unresolved checks remain unresolved; this gate
does not demand or permit dummy predicates returning success.

Constraint completion means the expected behavior and fixtures are reviewable,
not that the implementation has passed them. Record every expectation as
declared, tested-pass, tested-fail, or not-yet-tested against a specific revision.

### A1. Recoverable editor blocks

- Close archives the complete editable source descriptor: base source, extension
  sources and edits, pending extension, title, filename, and display preferences.
  Preserve the distinction between edited and last-executed source; do not flatten
  extensions or silently replace drafts with disk contents.
- Archive insertion and removal from the active layout are one document
  transition, applied only after runtime close succeeds. A close failure leaves
  both the draft and active layout intact.
- Closing still releases runtime capabilities, subscriptions, and layout state.
  Archived entries contain no session object, generation, clock, or capability.
- Reopening restores the editable document into a fresh pane identity in the
  selected desktop. It is explicitly Not run and does not evaluate source.
  Last-run history must not be mistaken for a live session or automatic replay.
  Run explicitly rebuilds the restored source and extension sequence.
- Reopen consumes the archive entry only after insertion succeeds. Repeated
  deliveries of the same API command must not create duplicate panes.
- Offer a visible Reopen closed block menu and searchable palette entries with
  title/filename and close order. Untitled blocks remain recoverable.
- Keep the existing one-active-pane-per-desktop restriction. Opening/reopening
  respects workspace pane-count and split-depth limits without partial mutation.
- Archive entries survive local persistence and workspace JSON round trips.
  Old documents without an archive remain valid and default to an empty archive.
  Malformed archive entries are rejected before replacing the current workspace.
- Proposed archive capacity: 64 entries. At capacity, reject another close with
  an explanation; never silently evict an unsaved draft. Permanent archive
  deletion is an explicit user action, not an implementation convenience.
- A filename from a browser file picker is not a reliable filesystem path.
  Reopen uses the archived source; reloading a changed disk file is a separate,
  explicit operation. New workspace replacement follows existing explicit
  replacement semantics; archives are workspace-scoped, not a global history.
- Extend the existing tab-scoped acknowledged API, not a second control channel.
  List closed-entry metadata without exposing draft text by default. Target an
  archive entry by its identity, validate the desktop, and report application or
  failure. Disconnected tabs and expired requests remain inspectable errors.

### A2. Semantic stack data

- One stack denotes one compound execution occurrence, not its function name,
  arbitrary nested collection, or raw compiler implementation graph.
- Identity is the pair of original observation identity and occurrence identity.
  Two calls of the same definition remain distinct. One occurrence reached by
  multiple paths retains one identity. Do not conflate runtime recursion through
  distinct occurrences with a genuine reference cycle to the same occurrence.
- Root selection accepts an explicit occurrence. Convenience root discovery may
  succeed only when uniqueness is supported by complete root-membership facts.
  Missing information stays unknown; multiple supported roots are an explicit
  ambiguity diagnostic, not an arbitrary first match.
- Named-field stack constructors and public accessors define the abstraction.
  Keep `stack(identity, graph, expansions)`, `stack-expansion(source, target)`,
  and `stack-ref(identity)` as the public variants. Internal traversal state is
  not part of their rendering contract.
- Content contains the captured scope's actual semantic cells, propagators,
  ports/boundaries, and dataflow edges. Do not infer topology from collection shape
  or replace all semantic nodes with cells.
- Expansions denote immediate compound occurrences and retain the compound node
  in the parent graph as their source. Primitive propagators remain nodes within
  their containing graph plane, not additional stack planes.
- Sharing and cycles use explicit references to stable identities. Every reachable
  occurrence is expanded at most once per derivation; every known child position
  is connected to its canonical stack or a reference. References do not create
  additional semantic dataflow edges.
- Missing expansions or an unfinished list tail are not a known empty list. Known
  prefixes and fields may be exposed while unresolved positions remain partial.
  Unrelated siblings must not all wait for one unavailable descendant.
- Contradictory facts remain localized. Validation distinguishes supported true,
  supported false, unresolved information, and contradictory evidence. A complete
  success cannot be claimed while any required reachable constraint is unresolved.
- Validation covers the reachable structure and reference targets, not just the
  root marker. Nonexistence and complete child membership require explicit
  completeness evidence; absence in a partial capture is not evidence of absence.
- Refinement reads only the same original frozen observation. No fallback to a
  newer live runtime. Earlier captures, stack results, and recorded frames remain
  unchanged. Unsupported originals yield an explanation and partial information.
- Public field reads and derived results preserve their actual supports. Refined
  source facts update their consumers; withdrawal invalidates dependent results.
  Do not turn a missing field into a dependency-free default value.

### A3. Tail-recursive expansion in Lain

- Keep `compound->stack` as the readable public entry point and `scope->stack`
  as its lower-level occurrence interpreter. Express expansion orchestration in
  Lain, not a hidden host-side recursive traversal.
- Pass pending work, discovered occurrence bindings, and output connections
  explicitly. No mutable global visited set or state hidden inside propagators.
- Connect each source-node expansion to its stack/reference, register an
  occurrence before expanding its descendants, and schedule its unresolved work.
  Known work can proceed without treating unknown child membership as complete.
- The recursive loop call is in tail position; no `cons` or stack construction
  remains to execute after that call. Tail position does not by itself prove a
  bounded host call stack or bounded retained network size: verify both separately.
- Terminate on a finite, fully acquired reachable occurrence graph. A partial or
  infinite source is not reported as a complete finite stack. A bounded diagnostic
  experiment may stop with pending work; exhaustion must not imply emptiness.
- Stable expansion enumeration makes canonical ownership deterministic. If the source
  provides semantic ordering, preserve it. Otherwise define a stable identity
  ordering, without treating that presentation order as a semantic dependency.

### A4. Primitive and host boundaries

- Define generic `struct` semantics before implementing it: partial fields remain
  readable; identical field evidence is idempotent; incompatible values conflict
  locally; subsequent refinements reach existing `p:slot` readers; retraction
  preserves ordinary support semantics. No materialized snapshot masquerades as
  a live struct.
- Semantic reader primitives use capture-owned public interfaces. Prefer Lain
  compositions for identity selection, traversal, reference resolution, and
  validation wherever the available primitives suffice. Do not implement one
  opaque host primitive that returns a finished stack hierarchy.
- Install the needed bindings through existing environment composition. Use
  ordinary propagators and TTMS. No global merge registration, custom scheduler,
  new parser syntax, or session merge override.
- Acquisition stays with the tracer/frozen-source boundary. Transformation and
  scene compilation are pure with respect to acquisition and runtime mutation.
- Any required shared-kernel change beyond these interfaces requires a separate
  boundary review before proceeding. Report the failed contract, do not conceal
  it behind fallback behavior.

### A5. Spatial interpretation and compatibility

- Recognize explicitly marked stack values through their public abstraction.
  Ordinary unmarked values, recordings, and graphs keep their existing behavior.
- Render one canonical plane per expanded occurrence, root above owned expansions.
  Siblings occupy the same abstraction depth. Shared/cyclic references navigate
  to existing planes; reference links need not point downward and do not imply
  additional copies or infinitely descending planes.
- Keep semantic dataflow within scopes distinct from inter-scope containment and
  reference links. Preserve exact semantic node kinds and boundary edges.
- Layout stays behind the shared provider contract, with Standard as default and
  existing measured-card clearance. The stack transform does not choose an
  algorithm or store camera coordinates.
- Camera, layer spacing, expansion/collapse, selection, and fullscreen are view
  state. Camera-only changes and unchanged polling do not recompute layout.
  Scope geometry is keyed by pane generation and semantic identity; topology and
  measured-size changes invalidate it. Temporary drag anchors end on release.
- Missing/conflicting scopes are visibly partial/conflicting, not empty planes.
  An acquisition action requests the original source and publishes a new capture;
  renderer code never reads runtime internals to repair the view.
- Closing/reopening or replacing a session releases all old capabilities and
  view subscriptions. Reopening restores source, not historical live capabilities.

## Gate B: complete the high-level Lain program

After Gate A review, revise the public composition and its contracts together:

- `stack/structure.lain`: constructors/accessors and explicit identity/reference
  contracts. No inline representation assembly by unrelated algorithms.
- `stack/constraints.lain`: complete reachable-shape, scope ownership,
  direct-expansion membership (including source anchors), reference validity,
  completeness, and support-sensitive checks.
- `stack/hierarchy.lain`: direct compound entry and tail-recursive expansion,
  with discovery and pending-work transitions expressed in Lain.
- Add a focused traversal-state domain file only if the expansion composition
  needs a genuine abstraction with its own constructors/accessors.
- Add separate fixture/checkpoint files for repeated calls, sibling sharing,
  cycles, deep nesting, partial children, conflicts, and source refinement.
- `stack-design.lain`: imports the domains in explicit order, captures the example,
  selects the root, transforms, checks, and publishes a reviewable result.

Keep import as the ordinary `load-lain` alias. Do not introduce module syntax or
assume that neighboring editor blocks share bindings. Do not retain the old
path-only traversal as a competing implementation after the replacement passes.

Gate B passes when the Lain composition is well formed, all lower-level holes
are named with precise contracts, known portions execute, and the expected
fixtures are pinned for review. Primitive-dependent checks may still be pending,
but their expected outcomes cannot be weakened when implementations arrive.

## Dependency-based execution plan

```text
A: completed constraints + fixtures + review
├─ R1: archive document transitions and compatible persistence
│   └─ R2: reopen menu/palette/API + lifecycle verification
└─ B: complete and accepted high-level Lain composition
    ├─ P1: generic live-field struct contract and implementation
    └─ P2: public frozen-scope facts and observation-qualified identities
        (P1 and P2 feed the actual dependencies selected by B)
             ↓
       T: run the Lain transformation and recursive constraints
             ↓
       S: pure stack-to-scene interpretation
             ↓
       V: spatial editor rendering and view-state controls

R2 + V → E: browser end-to-end acceptance
```

Implementation order is R1/R2 first, then P1/P2/T, then S/V/E. B and the stack
constraints are completed before P1/P2, regardless of which operation seems easy.
Dependencies, not a count of unresolved cells, determine the next eligible work.

At each checkpoint record the pinned specification revision, the required binding
or constraint, its actual dependencies/support, permitted implementation boundary,
observed status, and applicable test evidence. Distinguish missing implementation,
missing source information, contradiction, inactive branch, and unverified value.
Only selected and warranted obligations are eligible. A changed premise withdraws
dependent acceptance evidence; it does not silently reuse an earlier green check.

Reuse existing observation, support, premise-state, and membership facilities.
Checkpoint reporting is separate from source mutation. This task does not require
a new agent executor, automatic dispatch, task policy, or hidden workflow runner.

## Verification and delivery gates

1. Recovery: exact draft/extension restoration, fresh pane identities, failed-close
   atomicity, archive capacity, pane limits, old JSON compatibility, malformed
   archives, refresh/replay behavior, API duplicate delivery, and session cleanup.
2. Structure: public accessor tests for pending fields, late refinement, equal and
   conflicting evidence, unrelated-field independence, and support withdrawal.
3. Semantics: explicit/ambiguous roots; two calls of one definition; shared child;
   self-reference and multi-node cycle; primitive leaves; missing child tails;
   localized conflict; immutable historical observations; no newer-live fallback.
4. Traversal: compare shallow fixtures to explicit expected identities/edges;
   exercise deep and wide finite cases; measure task count, retained structure,
   host-stack behavior, and elapsed time. Agree workload/time/resource thresholds
   at Gate A from a bounded baseline before advertising a scalability guarantee.
5. Integration: run the accepted Lain files with real primitive bindings. Complete
   fixtures must have supported passing constraints, not merely a nonempty result.
   Partial/conflicting fixtures must retain their specified diagnostics.
6. UI: verify actual stack output, repeated-call identity, deeper expansion,
   references, partial state, exact node kinds/edges, measured clearance, camera
   preservation, unchanged-input layout reuse, drag release, and hide/fill/fullscreen.
   Close and reopen the design source and explicitly run it again.
7. Regression: focused JVM and frontend suites, existing recording/workspace and
   ordinary-session compatibility checks, production frontend build, whitespace
   checks, and real browser assessment. Broaden tests when a concrete risk remains.

At handoff report tested revisions, actual checks/results, remaining partial
states, browser observations, and whether changes are local, committed, or pushed.
Do not declare completion from quiescence, a visible plane, or a reduced hole count.

## Next authorized checkpoint

Review Gate A and finish its Lain constraint declarations and fixture expectations.
In particular, confirm source-only reopen semantics, explicit occurrence identity,
canonical reference handling, and partial-child behavior. Then review the complete
tail-recursive high-level composition at Gate B. Only after those checkpoints do
we fill the implementation details. This planning pass changes documentation only.
