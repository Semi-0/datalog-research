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
