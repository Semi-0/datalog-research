# Semantic observation through the traversal API

Load the Lain library once:

```clojure
(load-lain
  "propagators/compiler_2/runtime/lain/semantic_traversal.lain")

(display
  (observe-dependents input))
```

`observe-dependents` follows downstream semantic edges from the caller's cell.
Every reached propagator retains its complete input/output interface. Other
inputs are boundary context: traversal does not continue along their unrelated
branches. Cells and propagators remain separate nodes, and reciprocal edges
retain their bidirectional annotation.

For upstream dependencies or the entire connected semantic component:

```clojure
(display
  (observe-semantic result :upstream))

(display
  (observe-semantic input :both))
```

The editor's display uses the returned graph verbatim. No traversal or projection
runs during rendering. The eight semantic gallery examples use downstream
observation. The history gallery uses `:both` to preserve its connected-network
recording contract.

## Composition boundary

The library defines the query as an ordinary Lain callback:

```clojure
(define semantic-neighborhood
  (network (snapshot root direction)
    (traverse
      (semantic-snapshot snapshot root)
      root
      direction)))

(define observe-semantic
  (network (root direction)
    (observe-network root semantic-neighborhood direction)))
```

`observe-network` receives a live root cell, a callback, and optional callback
arguments, and returns its result cell. The callback receives a frozen snapshot,
the root's identity as data, and the optional argument values. It executes through
`run-flat-gur-once` in a private immutable branch. Only its projected result is
published back; the callback's cells, propagators, and lexical frames stay in the
temporary branch.

The edge-free `Observer` registration is transported by a generic
`:network/declare-observer` declaration. Ordinary root-watching propagators
request samples on cell changes. The existing successful-load refresh boundary
adds watches for later declarations. This is reactive sampling, not autonomous
polling, and does not record every scheduler activation.

The observer output is an explicit source publication: changed samples advance
that output cell's local epoch; equal samples keep the epoch. Sampled graph values
retain their original layered values and provenance. History remains a separate
consumer of this result cell.

## Semantic snapshot

`semantic-snapshot` applies the existing Leapfrog semantic projection to the
frozen parent network. Its adapter implements the existing `NetworkSnapshot`
protocol, so `neighbors` and `node-info` work on semantic cells and propagators.
The standalone Lain breadth-first walker can consume the same adapter.

The observation subject excludes the producing observation call and its
enclosing calls by actual ownership evidence. This keeps observation helpers out
of their own subject without recognizing their names. Local parameter identities
resolve to caller roots. Ordinary root tracing outside this observation boundary
keeps its existing behavior.

The semantic ownership/projection rules still live in the existing Datalog
implementation. This change exposes that projection through the snapshot API;
it does not translate the rules into Lain. Default traversal stays at the root
abstraction level. Explicit child inspection continues to use the same child
projection as before.

`traverse` accepts `:upstream`, `:downstream`, or `:both` and returns a semantic
graph with its direction metadata, labels, values, aliases, and cell/propagator
kinds. Missing identities, invalid snapshots, and unsupported directions report
errors. A root with no usable value can still have observable topology.

## Checks

```sh
clojure -M:test propagators.compiler-2.runtime.semantic-traversal-test propagators.observer-test propagators.compiler-2.runtime.network-observation-test propagators.compiler-2.runtime.trace-cycle-test graph.lain-editor.semantic-gallery-test graph.lain-editor.semantic-scope-test network-replay
```

Tests cover normal Lain loading, arbitrary wrapper names, frozen snapshots,
complete dependent interfaces, unrelated branches, recursive scope, current
TTMS evidence, co-input updates, late declarations, and stable parent topology
across repeated samples and input updates.

## Retained observation (opt-in)

```clojure
(display
  (observe-dependents-retained input))

(display
  (observe-semantic-retained input :both))
```

The semantic gallery now uses the retained helper. `observe-network` and the
one-time runner remain available. The new `observe-network-retained` takes the
same root, callback, and optional arguments and returns its result cell.

Root watchers publish to an internal request cell. An ordinary propagator reads
that request and a TTMS state cell and publishes the next state and result.
State is an inspectable layered datum: `:base` is the completed private Net,
`:compilation/hash` and `:compilation/key` identify its declaration, and
`:observation/request` prevents state writes from processing a request twice.
Equal hashes require exact declaration-key equality before reuse. Input values
and timestamps do not belong to the compilation key. Referenced captured
callable declarations participate in invalidation.

The private environment captures cells and lexical metadata without installing
parent propagators. Snapshots clear all registered request/state values and
remove observer registrations, preventing retained observers from holding a
chain of previous observer states through parent snapshots. Only ordinary
messages cross back into the parent. There is no stateful observer registry,
new kernel patch, scheduler change, or process-local mutable cache.

`semantic-snapshot` and `traverse` use `supported-procedure/install`: base,
support, and premise-state are ordinary procedure layers. Finite result
projection occurs at the observation boundary. Graph cell values retain their
original layers. An unusable callback result withdraws the published sample;
reactivation publishes a fresh source version.

Retention does not eliminate semantic projection or coalesce every watcher
activation. Recursive callbacks may still accumulate private topology under
existing GUR semantics; this is not a claim of bounded memory for arbitrary
recursive programs. The private network has no independently scheduled clock
or external-effect driver; parent clock observations remain ordinary inputs.

### Version-aware callback control flow

A retained callback receives changing evidence. For callback arithmetic and
branching over versioned arguments, explicitly load the existing layered TTMS
procedures before declaring the callback:

```clojure
(load-primitive-environment
  "propagators/compiler_2/runtime/operators/retained_observation.clj"
  :propagators.compiler-2.runtime.operators.retained-observation/layered-environment
  0)

(define sample
  (network (snapshot root n)
    (if (> n 0)
      (+ n 1)
      (- n 1))))
```

This explicitly loads the existing opt-in TTMS primitive extension into the
program environment. Shared compiler defaults remain unchanged. Ordinary `if` is not a substitute for the version-aware
branching procedure on TTMS conditions. The semantic-neighborhood callback uses
layered traversal directly and requires no extra arithmetic environment.

### Current verification limits

Mixed support/membership merging is now symmetric at the public cell merge
boundary, and the direct retained-callback branching checks pass for both input
evidence formats. The former mixed-evidence transport exception is resolved.

A retained callback that selects a network with layered `if` and applies it for
tail recursion still returns contradiction in the current regression. Arbitrary
recursive observation callbacks are therefore not verified. The semantic gallery
uses a direct projection callback; recursive subject programs remain covered by
the gallery tests. Shared application semantics are unchanged.

## Public relationship bindings

The public `relationship:roots`, `relationship:dataflow`, `relationship:semantic`,
and `relationship:roots-semantic` environment bindings now link to retained
adapters in `runtime/operators/retained_relationship.clj`. Their prior native and
experimental definitions remain intact in their original files. The ordinary
result-cell and explicit-output forms remain available. Retained outputs carry
TTMS evidence; host consumers read the graph using `visualization.data/payload`.

`relationship:children` opens one semantic call body from a result cell or an
explicit `occurrence-of` reference. Selection uses the existing graph and copy
aliases; ambiguous producers report error evidence. The projection itself is the
existing `child-semantic-network-graph`.

The editor's semantic child view uses the same callback in pane-owned retained
state. Repeated inspection reuses its result; a changed program snapshot advances
the private network. This state never enters the observed program Net, and pane
closure or replacement releases it. The older dataflow-only child projection is
preserved for that representation.

Three runnable comparison panels live in
`examples/lain/semantic_tracing/retained/workspace.json`: semantic roots, raw roots
composed with dataflow, and a child body. Each displays cell values and retains the
original graph-valued results.
