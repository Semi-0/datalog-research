# Compiler 2 One-Time Network Tracing

Compiler 2 can load a generic topology tracer without extending the session API
or adding trace policy to the scheduler. The implementation has three parts:

```clojure
propagators.compiler-2.runtime.operators.network-observation
propagators.compiler-2.runtime.one-time-network
propagators/compiler_2/runtime/lain/network_trace.lain
```

`neighbors` and `node-info` are policy-free primitive operators. They read a
`FrozenNetworkSnapshot` and return live compound values. Neighbor identities are
sorted by their printed semantic identity, so equivalent snapshots produce the
same traversal order.

```clojure
(neighbors snapshot node)
;; => {:node/id node
;;     :node/inputs [...]
;;     :node/outputs [...]}

(node-info snapshot node)
;; => {:node/id node
;;     :node/kind :cell | :propagator
;;     :node/name name}
```

`run-flat-gur-once` creates a private flat-GUR network from the current active
`Net`, seeds one immutable snapshot value, applies a supplied Lain closure, runs
that temporary topology to quiescence, projects its result, and discards the
temporary network. Only the projected value crosses back to the caller. The
active network receives no trace cells or propagators.

The Lain file owns traversal policy. `trace-walk` is an explicit-output,
tail-recursive network with breadth-first `ready` and `queued` collections and a
visited scan. `trace-network-once` uses a constant trigger. `trace-network-every`
is deliberately absent: changing one trigger while retaining one result cell
would merge distinct snapshots and can produce a contradiction. A periodic
effect boundary instead creates a fresh one-time invocation and result cell for
each sample.
Directions are `:upstream`, `:downstream`, and `:both`; another usable direction
leaves the traversal result unavailable.

Load the primitives and Lain code through the existing environment boundary:

```clojure
(load-primitive-environment
 "propagators/compiler_2/runtime/one_time_network.clj"
 :propagators.compiler-2.runtime.one-time-network/trace-environment
 0)

(load-lain
 "propagators/compiler_2/runtime/lain/network_trace.lain"
 0)
```

Inside lazily compiled closure bodies, use the canonical
`:compiler-2/list-empty` value for an empty list. The zero-argument `(list)`
compiler path currently seeds its value outside the additive topology diff, so
it can remain unavailable in a flat-GUR body.

## Current performance gap

The observation primitives and raw disposable runner are small. A complete
Compiler 2 traversal closure still declares a lexical frame for every recursive
step. Even an isolated-node end-to-end trace has been observed taking about
eight seconds on the development machine. It is therefore excluded from the
per-test three-second regression set. The focused tests cover deterministic
neighbor projection, node metadata, invalid nodes, temporary-network disposal,
and loading the callable Lain topology. Reducing recursive Compiler 2 frame cost
is a separate compiler-runtime concern and is outside this tracer boundary.
