# Compound Object Slot Sync

Source files:

- `propagators/datastructures/compound_object.clj`
- `propagators/datastructures/named_network.clj`
- `propagators/cells/merge.clj`
- `test/propagators_compound_object_test.clj`

## Status

This supersedes the deprecated linked-list dispatcher in `compound_data.clj` for
new compound slot work. The old implementation remains as a compatibility
baseline and scheduling comparison.

The current experiment treats the collection named network as durable partial
information. Pure sync structure such as `p:id` can be stored as declarative
subnet data, but activation-local effects must not persist in the collection
value. The test `collection-value-does-not-persist-effect-taps` captures that
invariant.

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

Deprecated `compound_data.clj`:

- `p:car` and `p:cdr` only write structural updates
- `c:linked-list` owns internal execution and parent dispatch
- dispatch is centralized in one linked-list-specific constraint

This experiment:

- `p:car*` and `p:cdr*` are the bidirectional constraints
- the collection cell is a named-network value
- parent avatars and slot indexes live in that named network
- each slot propagator runs only the slot-indexed subnet work it needs
- no `c:linked-list` dispatcher is installed

## Robustness Over The Deprecated Dispatcher

The named-network slot model is more robust for nested accessor scheduling.
Every slot relation is a real bidirectional propagator, so updates to collection
cells naturally wake neighboring `p:car*` / `p:cdr*` accessors through the normal
graph scheduler. The old linked-list path depends on the timing of structural
writers plus a centralized `c:linked-list` dispatcher; its schedule experiment
still documents a case where install-time enqueue leaves the nested `out` cell at
`:bool4/nothing`.

The new model also lets each slot propagator run only the slot-indexed subnet
work it needs, then emit direct messages to the real parent cells whose avatars
changed during that activation.

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
- an invariant test that collection content should not persist effect taps

The nested accessor helper currently uses `install-prop!` for the accessor
chain. That eagerly enqueues the newly installed `(p:cdr* coll1 coll0)`,
`(p:cdr* coll2 coll1)`, and `(p:car* out coll2)` propagators before any head is
seeded. This early activation pre-attaches their parent avatars and slot indexes
in the collection networks.

The eager activation is not required for the simple seeded nested case. If the
accessor propagators are installed but not initially enqueued, seeding `head2`
still wakes the neighboring `p:cons*` slot propagator for `coll2`; the resulting
collection-cell update then enqueues neighboring accessor props, and the chain
can still deliver `30` to `out`. Eager activation is therefore a scheduling
convenience in the test, not a semantic requirement of `p:car*`/`p:cdr*`.

## Runtime Boundary: Declarative Structure vs Effects

The current implementation makes nested access work by storing slot sync
structure in the collection named network:

```clojure
[:slot-sync :car parent-id :avatar->slot] -> prop-id
[:slot-sync :car parent-id :slot->avatar] -> prop-id
```

These entries are pure `p:id` constraints between parent avatars and slot cells.
They are acceptable if we treat the collection network itself as partial
information: the stored network says how the object subnet is related, and
evaluation still happens only when a propagator runs that network.

The lifetime boundary is instead between declarative subnet structure and
activation-local effects. A collection value may contain durable partial
information:

- slot cells
- parent avatar cells
- slot index metadata
- pure sync constraints such as `p:id`

Effectful execution machinery should be activation-local:

- tap propagators
- `updated*`
- task queue frontier

Persisting tap props is wrong because taps close over an `updated*` atom from one
activation. Stripping taps after the run keeps nested access working without
leaking activation-local state into the collection value.

## Boundary-Preserving Flow

Slot sync can still be described as three explicit transformations:

```clojure
(defn stable->execution-frame [collection-net slot-key parent-net]
  ;; start from durable cells/index/declarative sync structure
  ;; install fresh taps that close over this activation's updated*
  )

(defn run-execution-frame [frame]
  ;; run-tasks over the frame and collect updated*
  )

(defn execution-frame->stable [frame-result]
  ;; keep slot cells, avatar cells, :slot-index, and pure sync constraints
  ;; drop tap props, transient effect state, and updated*
  ;; return a named-network value for the collection cell
  )
```

With that split:

- `p:slot*` can still emit a named-network update to the collection cell
- fan-out still uses tap-recorded `updated*`
- the collection value stays durable partial information
- repeated activations cannot accidentally reuse stale closures
- the effect-tap test becomes the safety check for projection correctness

## Risks

- Effect taps and other activation-local state must not persist in collection
  content.
- Slot ids and parent avatar ids must remain stable for named-network merge to
  stay readable.
- Pure sync propagators such as `p:id` are currently stored as installed network
  structure. That is acceptable while we treat networks as partial information,
  but equivalent independently-created sync structure still depends on stable
  ids or named-network merge rules that recognize it.
- Scalar conflicts still use normal cell merge and can become contradictions.
- Fan-out cycles rely on `cell-updated?` suppressing no-op strongest updates.
- The test coverage is strongest for cons-like `:car` / `:cdr` slots; more slot
  shapes may need a clearer generalized API.
- The dict now contains metadata such as `:slot-index`; named-network preorder
  therefore supports metadata comparison in addition to node-id commitments.
