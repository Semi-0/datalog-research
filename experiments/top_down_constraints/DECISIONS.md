# Specification decisions and amendments

## stack-spec-2026-10-20-r1 — 2026-10-20

The supplied constraint-first plan is the governing protocol. The revision pins:

- source-only recoverable blocks with explicit replay;
- observation-qualified occurrence identity, sharing, cycles, and partial tails;
- explicit pending/discovery/output-connection traversal state;
- supported constraint outcomes and separate aggregate rates; and
- incremental-versus-clean comparison without normalization by incidental IDs.

The tail-recursive composition is in `stack/hierarchy.lain`; declared fixture
outcomes are in `stack/fixtures.lain`. Primitive-dependent results remain pending.

No constraint was weakened in this revision.

## stack-spec-2026-10-20-r2 — 2026-10-20

The first running traversal exposed that bare boolean conditions install both
conditional subnetworks in this runtime. For mutually exclusive recursive
branches that produced contradictory output topology and could expand an
inactive recursive path. The high-level traversal now gates each branch with
`(switch true predicate)`, matching the runtime's explicit conditional contract.

This is a control-flow amendment only: occurrence identity, frozen-source reads,
sharing, cycles, partial information, and all fixture expectations are unchanged.
Revision-matched evidence must use `stack-spec-2026-10-20-r2`; r1 evidence is
retained as historical baseline evidence and is stale for acceptance.

The same investigation found that independently returned cells do not preserve
an alias between a pending work output and the root value. The Lain traversal
therefore carries related cells inside one compound traversal state. This uses
the existing compound topology instead of adding an opaque traversal primitive;
recursion, discovery, reference selection, and scheduling remain in Lain.

## stack-spec-2026-10-20-r3 — 2026-10-20

Premise-supported compound results require a support-aware field projection.
The ordinary `p:slot` reader sees the publication wrapper rather than its base
compound value. The structure contract now declares one generic `stack-slot`
obligation, and the three public stack readers compose through it. The primitive
unwraps and republishes the selected field with the same support; it does not
special-case identity, content, or children.

The generic traversal-state operations were also moved behind supported
primitive bindings after a counterexample showed that a writable compound alias
is not retained through two nested Lain closure output boundaries. The state is
immutable data. `hierarchy.lain` still owns the recursive loop, the discovered
occurrence decision, reference selection, and frozen-reader orchestration. No
fixture outcome or semantic constraint changed.

## Failed candidate: eager clean reconstruction with live capture

A wrapper that installed every r3 implementation before declaring the example
capture exceeded the 60-second sample limit. A thread dump showed active work in
semantic root acquisition and relationship-label construction, not stack
recursion. Adding implementation topology enlarged the graph being frozen and
made the observation itself dominate the run. The wrapper was removed. This is
recorded as a timeout, not as correctness evidence and not as an omitted sample.

The integration path must freeze the intended source graph before admitting
implementation topology, and it must prevent that historical observation from
being reacquired merely because unrelated implementation blocks were added.

## stack-spec-2026-10-20-r4 — 2026-10-20

The versioned runtime admits one top-level form per block. The r3 experiment
incorrectly treated a multi-form implementation file as one versioned slot, so
only its first definition was admitted. R4 flattens canonical source into one
stable block per definition and gives every primitive and traversal obligation
a pending definition with the same public signature as its concrete replacement.
No semantic constraint or fixture expectation changed.

R4 also installs frozen observation and retention capabilities in the shared
editor assembly; without them, `observe-semantic-captured` was an unresolved
name in development sessions even though ordinary editor sessions installed it.

The corrected sequence exposes a remaining correctness mismatch: supported
compound and traversal values pass through a versioned closure boundary as a
nested supported value. Raw host variants remove one publication layer, but the
full recursive result is still pending. The focused host traversal, supported
direct-call traversal, frozen readers, and scene compiler pass independently.
The experiment therefore remains blocked at integrated versioned composition;
this counterexample is acceptance evidence, not a reason to weaken constraints.

## Failed candidate: recursive Lain `struct`

A candidate implemented `struct` by recursively reading `(key value)` pairs and
connecting them with `p:slot`. Loading the closure passed, but instantiating a
concrete stack fixture did not quiesce in two bounded attempts (each stopped
after more than 60 seconds). The candidate was removed. `struct` remains an
explicit obligation; compile-only success is not accepted as implementation
evidence. Any replacement must satisfy late refinement, local conflict,
idempotence, and withdrawal tests before the specification can use it.

## stack-spec-2026-10-20-r5 — 2026-10-20

The integrated r4 investigation separated two previously conflated states.
Raw stack primitives were receiving premise transport wrappers before checking
readiness, and repeated returned-member gates could publish a wrapper as the
next gate's semantic value. R5 recursively projects the semantic base at those
two boundaries while each versioned gate continues to own and combine support.

The Lain composition now invokes every explicit-output wrapper explicitly,
including branch predicates and root discovery. This preserves the declared
network signatures and avoids relying on an implicit return cell for a closure
whose last parameter is itself the output connection.

After those corrections, a root-only frozen capture remains pending as required.
Publishing the child scope from the retained original observation resumes the
same running traversal and yields the materialized stack. This is not a weakened
success condition: missing child information is still pending, and acquisition
remains outside transformation and rendering. Fixture expectations are unchanged.

## stack-spec-2026-10-20-r6 — 2026-10-20

The r5 `stack(identity, content, children)` representation captured occurrence
hierarchy but lost the graph node that caused each expansion. That is
insufficient for the intended spatial meaning: a stack expands a graph through
the third dimension, and the inter-plane connection must begin at the compound
node in the parent plane.

R6 replaces the public shape with `stack(identity, graph, expansions)`, where
each `stack-expansion(source, target)` retains the source node and targets either
another canonical stack plane or `stack-ref(identity)`. Primitive nodes remain
in their graph plane. Sharing and cycles still create references rather than
duplicate planes. The direct-child constraint is correspondingly renamed and
strengthened to direct-expansion membership, including the source anchor.

The primary high-level operation is now `compound->stack`. It implicitly returns
one stack; explicit output cells are confined to the lower-level tail-recursive
interpreter and versioned primitive boundary. Frozen observations remain a
required source dependency and are not renderer state.

This is a semantic amendment, so r5 acceptance evidence is stale for the stack
shape and scene. Fixture statuses are unchanged, but revision-matched evidence
must additionally preserve expansion anchors. Incremental and clean results are
compared after removing generated runtime identities while retaining node
kinds, values, directed topology, plane depth, reference/expansion kinds, and
canonicalized source anchors.

## stack-spec-2026-10-20-r7 — 2026-10-20

R6 still exposed the propagator installer's explicit output-cell convention in
ordinary single-result constructors, readers, accessors, and `scope->stack`.
Those cells were transport wiring, not stack-domain inputs, and made the
declarative composition read like a manually assembled runtime graph.

R7 uses Lain's implicit single-result relation for those operations. Explicit
applicants remain only where the domain operation genuinely has multiple
results and in the private tail-recursive `expand-pending` helper, whose two
conditional branches must converge on one result cell. `compound->stack` and
`scope->stack` now both read as value transformations; callers do not allocate
their results.

No stack semantics or fixture expectation changed. Because public versioned
signatures changed, r6 runtime evidence is stale. Revision-matched focused
evidence is the primitive and incremental reconstruction suites, which pass 44
assertions after replaying the r7 ledger.

## stack-spec-2026-10-20-r8 — 2026-10-21

The first complete r7 fixture run exposed two representation mismatches. A
captured scope combined its one-plane semantic graph with nested-scope
membership, while the semantic graph information merge correctly retained only
graph fields. Separately, the sharing branch constructed a live `stack-ref`
struct and passed it across a primitive boundary that could not inspect the
struct's owning-network field cells. Distinct trees passed, but sharing and
cycles consequently failed shape, reference-integrity, and direct-expansion
constraints.

R8 makes the boundary explicit. `content-of-scope` returns the canonical
one-scope semantic graph; `direct-compound-expansions` reads nested membership
as its separate frozen fact. Semantic-graph normalization retains distinct
capture diagnostics. The Lain sharing branch passes its already-known,
observation-qualified identity into the traversal transition, which produces a
capability-free `stack-ref` at materialization. The decision to reference rather
than expand remains in the Lain recursion.

Fixture expectations did not change. R7 result evidence is stale because the
public frozen reader now has a precise plane-only contract. Revision-matched
evidence is 77 focused primitive/semantic assertions and the isolated executable
fixture gate: 11 assertions, all nine declared fixture statuses in agreement,
including sharing, cycles, partial information, localized contradiction,
withdrawal, and late refinement.

## stack-spec-2026-10-20-r9 — 2026-10-21

The r8 withdrawal check exposed two hidden output paths. `take-pending` and
`register-occurrence` wrote caller-supplied cells, so those values escaped the
single versioned return boundary. The private recursive helper likewise accepted
an explicit caller output. As a result, replacing a reader implementation could
leave a previously materialized stack supported by only downstream candidates.

R9 makes each traversal operation single-result. `take-pending` returns an
immutable step read through `pending-work` and `pending-remaining`;
`register-occurrence` returns the next traversal state directly. The recursive
helper now has an implicit result, with local branch convergence and its
recursive call in tail position. The stack extension's raw primitive adapter
computes from semantic bases while transporting distributed support and premise
state, so an upstream retraction invalidates retained downstream results. This
is extension-local and does not redefine process-global compiler behavior.

Recursive reuse also exposed an overly coarse generic candidate-input rule: a
conjunctive claim containing the current definition premise was discarded in
full. The runtime now removes only that self-support conjunct and retains the
claim when independent upstream support remains; claims supported only by
themselves are still excluded. A focused runtime test covers both invariants.

Fixture expectations did not change. R8 measurements are stale because the
implementation signatures and support behavior changed. Revision-matched
evidence includes the 12-assertion incremental integration check: reader
withdrawal makes the existing result pending and restoration resumes it. The
isolated fixture gate passes all 11 assertions and all nine declared fixture
statuses, including deep tail expansion, sharing, cycles, partial information,
localized contradiction, and late refinement.

## Browser measurement lifecycle correction — 2026-10-21

This is an evidence-method correction, not a stack-specification amendment.
The first Playwright runner disposed each browser context but did not send the
editor's `close` command. Backend sessions therefore accumulated across runs;
after several complete 3+5 repetitions, the editor server exhausted its Java
heap. Those earlier latency samples remain recorded observations, but they are
not a clean-lifecycle baseline.

The runner now sends the ordinary close command for the context's pane in a
`finally` block and treats close failure as a failed acceptance run. A fresh
server completed the depth-presentation 3+5 run and two further complete runs
without exhaustion. The correction changes neither stack semantics nor fixture
expectations.
