# Compound Runtime

Source files:

- `propagators/closure.clj`
- `propagators/lexical_compound.clj`
- `propagators/reality.clj`
- `propagators/io.clj`
- `propagators/stdlib.clj`
- `propagators/propagator.clj`
- `propagators/core.clj`

## Context

The classic MIT propagator model treats compound propagators as install-time
expansion: the compound body creates cells and propagators directly in the
parent network. Boundary cells are the same cells as the caller sees.

This experiment separates declaration from evaluation. A compound can therefore
hold a declared subnet as data and run that subnet by applying the evaluator to a
network value and a task queue. The subnet run is not a hidden mutation of the
constructor; it is an explicit evaluation step that returns messages for the
outer network.

This repo also experiments with runtime compounds. A runtime compound stores a
closure as data and runs an inner network when the compound propagator activates.
That gives better inspection and hot reload. It is more indirect than flat
expansion, so long stable paths should eventually have a compiled lowering.

## Runtime Compound Model

Current runtime compound activation uses an avatar frame:

1. parent boundary cells exist in the outer graph
2. activation creates or uses avatar cells for inner work
3. topology-only links connect real cells and avatars
4. the inner closure installs or runs inner propagators on avatars
5. inner `run-tasks` starts from avatar inputs
6. changed avatar outputs are diffed back into messages for real boundary cells

The key point is scheduling isolation. Inner work must not enqueue the compound
propagator itself.

## Why Avatars Exist

If inner `run-tasks` starts from real boundary cells, those cells have outgoing
edges to the compound propagator. The compound can schedule itself again while
already running, causing re-entry loops or stack overflow.

Avatars avoid that:

| Seed for inner run | Schedules compound? | Use? |
|--------------------|---------------------|------|
| real boundary cells | yes | no |
| avatar boundary cells | no | yes |

This is a runtime frame, not lexical identity. A real parent cell and its avatar
are different cells connected by explicit synchronization logic.

## Bi-sync Boundary Shape

`stdlib/bi-sync` installs two `p:id` propagators in opposite directions. For a
compound constraint, the same boundary cells should usually appear in both
input and output sets:

```clojure
(install-compound n closure-cell [a b] [a b])
```

Directional wiring such as `[a] -> [b]` is not enough for a symmetric
constraint. If `b` changes and is only an output, the compound is not woken by
that change.

## Current Two-Stage Direction

The intended long-term model is two lowerings for the same propagator language:

| Stage | Purpose | Shape |
|-------|---------|-------|
| runtime compound | inspection, experiments, hot reload | closure-as-data + avatars + inner run |
| compiled compound | stable fast path | expand into one parent graph/env |

Runtime compound is useful while editing or reflecting on the closure. Compiled
compound should be used for stable hot paths once promotion/demotion exists.

Promotion will need:

- semantic equivalence tests between runtime and expanded forms
- generation/version tags on expanded nodes
- demotion or garbage collection when the closure changes
- protection against duplicate wiring in hybrid runtime/compiled graphs

## Lexical IO Compound Experiment

2026-06-14 experiment: `lexical/p:compound` adds a second runtime compound path
that does not use avatar diffs for its boundary.

The shape is:

1. the parent stores a child `Net` in a normal cell
2. parent input cells are copied into the child network as inbox records
3. child `reality/p:reality-in` propagators publish inbox records to child cells
4. the child network runs by calling the evaluator continuation
5. child `reality/p:reality-out` propagators append selected cell messages to
   the child outbox
6. the compound activation translates outbox records into ordinary parent
   messages and stores the updated child network back into its cell

The core evaluator remains generic. It sees only messages, propagator ids, and
IO deliveries; it does not know about compound objects, slots, recursion, or
reality ports.

This path is lexical because the child network keeps its own graph/env/dict/io
identity across activations. It is not a mutation of the parent env. Parent
state changes only through messages returned by the compound propagator and
merged by the parent scheduler.

Current proof tests cover:

- parent-to-child-to-parent value propagation without `diff-cells`
- bidirectional child topology through two reality ports
- nested compound object read through accessors
- nested bidirectional slot write through the legacy slot compatibility path
- recursive nested compound map running inside the child continuation

This does not remove the old runtime compound or `diff-cells` path yet. It
proves that a declared IO boundary can replace diffing for child networks that
explicitly expose their inputs and outputs through reality propagators. General
unbounded recursion over nested network-slot accessors still needs the later
subenv/named-cell dispatch work, or a derived recursion primitive that declares
the necessary child boundaries.

## Historical Performance Notes

The experiment moved through several eras:

1. snapshot inner nets were fast and isolated but not unified with the parent
   graph
2. parent-network execution without avatars exposed re-entry scheduling bugs
3. avatars fixed scheduling but added overhead
4. boundary caching helped some short runs
5. removing the old `closure-out` bookkeeping message made current runtime
   compounds much faster for prototype workloads

The current runtime model is good enough for experiments, but it should not be
treated as the final representation for long stable chains.

## Open Risks

- port order currently comes from sets in graph nodes, so pairing by position is
  order-sensitive and should be made explicit
- runtime compounds are still more expensive and indirect than flat expansion
- scheduling correctness depends on seeding inner work from avatars, not real
  boundary ids
- the API does not yet make promotion to compiled compound explicit
