# Named Network Evidence

Source files:

- `propagators/datastructures/named_network.clj`
- `propagators/datastructures/evidence_set.clj`
- `propagators/cells/merge.clj`
- `propagators/cells/bool4.clj`

## Problem

Network values can appear as cell content. We need to merge those values without
pretending to solve full graph equivalence.

This is possible because declaration is separated from evaluation. A network is
not only a running machine; it is also an immutable value that can represent
partial information. Named-network evidence is the current merge discipline for
that kind of cell content.

Full network comparison is too broad:

- anonymous node ids can differ even when behavior is equivalent
- graph topology may include implementation detail
- propagator function equality is not generally decidable

The current design therefore compares only the named interface of a network.

## Named Network

A named network is a `Net` with a non-empty `:dict`.

The dict is the observable interface:

```clojure
{:graph ...
 :env ...
 :dict {:x cell-id
        :sync prop-id}}
```

Only keys in `:dict` participate in the preorder. Unnamed graph and env entries
are treated as derivable implementation detail.

## Interface Preorder

`named-network->=` is a Bool4 preorder over named interfaces.

`a >= b` means:

1. every named key in `b` is present in `a`
2. every corresponding named entry in `a` subsumes the entry in `b`

Named cells compare by strongest value:

```clojure
(bool4/>= (cell/cell-strongest a-cell)
          (cell/cell-strongest b-cell))
```

Named propagators compare by id:

- same name and same internal id means `true`
- same name and different internal id means `contradiction`

This is deliberately strict. A propagator value is behavior, and two different
function closures should not be treated as equivalent just because they occupy
the same named slot.

## Bool4 Role

Bool4 is the small knowledge lattice used by named cell comparisons:

- `:bool4/nothing` is bottom
- `true` and `false` are incomparable concrete evidence
- `:bool4/contradiction` is top

`join` combines evidence. Incompatible `true` and `false` become
`contradiction`.

## Evidence Sets

Raw cell content for named-network merges is normalized into an evidence set:

```clojure
#{named-network-a named-network-b}
```

The evidence set is maintained as an antichain:

- stronger incoming evidence replaces weaker old evidence
- weaker incoming evidence is ignored
- incomparable incoming evidence is kept alongside old evidence

This keeps merge conservative. Content does not collapse to contradiction just
because two networks are currently incomparable.

## Merge Contract

`cell-merge` dispatches named networks and evidence sets to
`evidence/merge-evidence`.

| Case | Content result |
|------|----------------|
| `nothing` + named network | singleton evidence set |
| incoming network subsumes old evidence | replace weaker evidence |
| old evidence subsumes incoming network | keep old evidence |
| incomparable networks | keep both |
| explicit contradiction | contradiction |

The important separation is:

- content stores evidence
- `strongest-value` computes a readable view

## Strongest View

`evidence/strongest` returns:

- `nothing` for no evidence
- the only network for singleton evidence
- a joined network for multiple compatible networks
- `contradiction` if the join cannot produce a coherent named interface

`named/join` unions named commitments. When both networks define the same named
cell, it joins the Bool4 strongest values. When both define the same named
propagator, the ids must match; otherwise strongest becomes contradiction.

## Why Join Is Deferred

Forcing join during merge would erase information too early. The cell would lose
the distinction between:

- multiple incompatible candidates still present as evidence
- one actual contradiction value

By keeping content as an antichain and joining only in `strongest-value`, callers
can still inspect the original evidence while ordinary propagation sees the best
current view.

## Runtime Sync Behavior

Named networks are ordinary cell values. The tests in
`test/propagators_named_network_test.clj` verify that:

1. a cell can contain a named network
2. `p:id` can sync that value into another cell through `run-tasks`
3. `bi-sync` can sync that value through its boundary directions
4. a later named network that subsumes the old one replaces weaker target
   evidence correctly

This is the main behavioral contract: named-network evidence must work as cell
content under the normal scheduler, not only under direct `cell-merge` calls.

## Boundaries

This design does not attempt graph isomorphism. It is an interface lattice for
named commitments. If code needs a stronger notion of equivalence, it should
introduce a more specific named commitment rather than comparing anonymous
network internals.
