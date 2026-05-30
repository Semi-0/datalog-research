# Compound Data: Current Linked-List Model

Source files:

- `propagators/datastructures/compound_data.clj`
- `propagators/datastructures/compound_subnet.clj`
- `propagators/datastructures/compound_subnet_state.clj`
- `propagators/datastructures/compound_update.clj`
- `propagators/cells/merge.clj`

## Status

This path is deprecated. It remains in the tree as the old linked-list
dispatcher spike and compatibility baseline, but new compound slot work should
use `propagators.datastructures.compound-object`.

The linked-list implementation is intentionally narrow. It centralizes effectful
compound dispatch in `c:linked-list`, which works for the current tests but is a
fragile shape for general compound data.

## Goal

Represent list structure as compound subnet state:

- `p:car` writes `{:head elem-id}` into a collection cell
- `p:cdr` writes `{:tail elem-id}` into a collection cell
- `p:cons` installs `p:car`, `p:cdr`, and `c:linked-list`
- `c:linked-list` is the only propagator that runs the internal subnet and
  dispatches results outward

The cell content remains structural until the linked-list constraint runs.

## Data Flow

```text
p:car / p:cdr
  -> compound-update message
  -> cell-merge builds or extends compound subnet state
  -> strongest-value returns structural state only
  -> downstream c:linked-list is enqueued
  -> c:linked-list runs subnet effectfully
  -> dispatch messages to updated outer ids
```

`strongest-value` for compound subnet state does not run the subnet. That
execution boundary is deliberate. Running in strongest selection caused nested
access to happen too early and made dispatch order hard to reason about.

## Deprecated API

`p:car` and `p:cdr` are deprecated structural writers:

```clojure
(p:car elem-id collection-id) ; writes {:head elem-id}
(p:cdr elem-id collection-id) ; writes {:tail elem-id}
```

`c:linked-list` is deprecated. It reads the collection cell content. If the content is compound
subnet state, it calls `run-subnet-effectful` and emits messages for ids that
were marked updated by avatar taps.

`p:cons` is deprecated and installs one layer:

```clojure
(p:cons head-id tail-id collection-id)
```

Internally it installs:

- `p:car`
- `p:cdr`
- `c:linked-list`

`p:cons-scheduled` is deprecated and returns all three prop ids for tests that
need explicit task control.

## Why Dispatch Is Centralized

Today, `c:linked-list` is the only place that knows how to:

1. run the internal subnet
2. inspect the continuation updated set
3. decide whether to send a scalar strongest value or a `compound-sync`
4. emit messages back to outer cells

That made nested linked-list tests easier to stabilize because one constraint
owns the boundary between structural subnet state and effectful execution.

## Fragility

This centralization is also the main weakness.

The dispatch policy is encoded in a linked-list-specific propagator even though
compound subnet synchronization is more general. That means the current design:

- couples compound execution to linked-list semantics
- makes other compound data structures copy or route through linked-list logic
- hides a general dispatch boundary inside one domain-specific constraint
- depends on mutable `updated*` tracking during an effectful subnet run

We should expect to replace this with a more general compound dispatch
abstraction.

## Nested Access Model

There are no read accessors like `p:read-car` or `p:read-cdr`.

Nested list access is modeled by chained dispatch:

```text
coll0 -> coll1 -> coll2 -> out
```

Each layer has its own collection cell and linked-list constraint. `(car (cdr
(cdr coll0)))` is not a one-shot projection out of `coll0`; it is a path through
multiple collection constraints.

## Scheduling Rule

Install-time enqueue is not enough. The structural writers must run after the
head or tail values they depend on have been seeded. This is one reason the
named-network slot model supersedes this path: `p:car` and `p:cdr` are local
bidirectional slot constraints, so collection-cell updates can wake neighboring
accessor props through the ordinary scheduler instead of relying on one
centralized linked-list dispatcher.

Tests use explicit queue helpers to model this:

- seed a cell
- enqueue neighboring propagators
- run from a collection boundary

This keeps failures honest: if the writer never runs after the value exists, the
nested access path should not magically work.

## Replacement Direction

The replacement shape is the named-network slot model in
`compound_object.clj`:

1. keep collection cell content as durable named-network partial information
2. let `p:car` and `p:cdr` describe bidirectional slots directly
3. store slots, avatars, slot indexes, and pure sync structure in the collection
4. keep effect taps and `updated*` activation-local

`compound_data.clj` should now be read as deprecated reference behavior, not as
the final compound-data framework.
