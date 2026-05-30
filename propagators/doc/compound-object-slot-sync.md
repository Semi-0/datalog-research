# Compound Object Slot Sync

Source files:

- `propagators/datastructures/compound_object.clj`
- `propagators/datastructures/named_network.clj`
- `propagators/cells/merge.clj`
- `test/propagators_compound_object_test.clj`

## Status

This is an additive experiment beside the current linked-list implementation.
It does not replace `compound_data.clj` yet.

There is a known design error in the current experiment: the collection
named-network value persists execution-frame wiring. The red test
`collection-value-does-not-persist-activation-frame` captures the desired
invariant and is expected to fail until the execution frame is separated from the
stable collection value.

## Idea

The collection cell holds a named network. That network is both:

- a partial-information value merged by named-network evidence rules
- an index from object slots to parent cells connected to those slots

For a cons-like object, the collection network has stable slot cells:

```clojure
{:dict {:car car-id
        :cdr cdr-id
        parent-cell-id parent-avatar-id
        :slot-index {:car #{parent-cell-id}
                     :cdr #{other-parent-id}}}}
```

`p:car*` and `p:cdr*` are bidirectional slot constraints. They do not write a
structural update for a later `c:linked-list` dispatcher. Instead, each slot
propagator runs the collection network directly and emits:

- a named-network update back to the collection cell
- direct messages to real parent cells whose avatars changed during this run

## Runtime Flow

```text
parent cell / collection cell updates
  -> p:car* or p:cdr*
  -> ensure slot cell exists
  -> ensure parent avatar exists
  -> derive execution frame with avatar <-> slot p:id wiring
  -> hook activation-local taps on indexed parent avatars
  -> run-tasks inside the execution frame
  -> read updated* from taps
  -> emit messages to changed real parent cells
  -> project stable named network back to collection cell
```

The tap frontier is important. The propagator does not broadcast to every
indexed parent on every activation. It uses `updated*` to learn which avatar
cells changed in this round, then maps those real parent ids through the dict to
fetch avatar strongest values.

## Difference From Current Linked List

Current `compound_data.clj`:

- `p:car` and `p:cdr` only write structural updates
- `c:linked-list` owns internal execution and parent dispatch
- dispatch is centralized in one linked-list-specific constraint

This experiment:

- `p:car*` and `p:cdr*` are the bidirectional constraints
- the collection cell is a named-network value
- parent avatars and slot indexes live in that named network
- each slot propagator runs only the slot-indexed subnet work it needs
- no `c:linked-list` dispatcher is installed

## Named-Network Merge

The collection update is just another named network. There is no wrapper around
it. `cell-merge` normalizes named-network values into evidence sets and
`strongest-value` exposes the current readable collection network.

`cell-updated?` is named-network aware so equivalent strongest collection
networks do not re-wake slot sync just because raw evidence shape changed.

## Current Tests

`test/propagators_compound_object_test.clj` covers:

- parent value syncing into the `:car` slot
- collection slot value syncing out to a parent
- fan-out from one slot to multiple indexed parent cells
- avatar and sync-prop reuse across repeated activations
- missing slot creation before attach
- subsuming named-network values replacing weaker slot evidence
- named-network `cell-updated?` no-op suppression
- `p:cons*` installing only slot sync props
- one-layer old-vs-new local behavior comparison
- nested `p:cons*` local slot sync
- accessor-style `(car (cdr (cdr coll0)))` propagation using
  `(p:cdr* coll1 coll0)`, `(p:cdr* coll2 coll1)`, and `(p:car* out coll2)`
- a red invariant test that collection content should not persist activation
  frame propagators

## Design Error: Persisted Execution Frame

The current implementation makes nested access work by storing slot sync
propagators in the collection named network:

```clojure
[:slot-sync :car parent-id :avatar->slot] -> prop-id
[:slot-sync :car parent-id :slot->avatar] -> prop-id
```

That is the wrong lifetime boundary. A collection value should be durable
partial information:

- slot cells
- parent avatar cells
- slot index metadata

Execution machinery should be activation-local:

- p:id sync wiring used to run this round
- tap propagators
- `updated*`
- task queue frontier

Persisting tap props was already wrong because taps close over an `updated*`
atom from one activation. Stripping taps after the run avoids that immediate bug,
but it does not solve the deeper issue: the collection value is still partly an
execution frame.

## Potential Fix

Split slot sync into three explicit transformations:

```clojure
(defn stable->execution-frame [collection-net slot-key parent-net]
  ;; copy stable cells/index into an executable net
  ;; install p:id avatar <-> slot wiring for this frame
  ;; install fresh taps that close over this activation's updated*
  )

(defn run-execution-frame [frame]
  ;; run-tasks over the frame and collect updated*
  )

(defn execution-frame->stable [frame-result]
  ;; keep slot cells, avatar cells, and :slot-index
  ;; drop p:id sync props, tap props, transient graph edges, and updated*
  ;; return a named-network value for the collection cell
  )
```

With that split:

- `p:slot*` can still emit a named-network update to the collection cell
- fan-out still uses tap-recorded `updated*`
- the collection value stays pure data/index
- repeated activations cannot accidentally reuse stale closures
- the red test becomes the safety check for projection correctness

## Risks

- Duplicate or activation-local sync props must not persist in collection
  content.
- Slot ids and parent avatar ids must remain stable for named-network merge to
  stay readable.
- Scalar conflicts still use normal cell merge and can become contradictions.
- Fan-out cycles rely on `cell-updated?` suppressing no-op strongest updates.
- The dict now contains metadata such as `:slot-index`; named-network preorder
  therefore supports metadata comparison in addition to node-id commitments.
