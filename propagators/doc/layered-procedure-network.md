# Layered Procedure Network

Source files:

- `propagators/layered.clj`
- `propagators/datastructures/compound_object.clj`
- `propagators/dispatch.clj`
- `propagators/propagator.clj`
- `test/propagators_layered_procedure_test.clj`
- `test/propagators_dispatch_test.clj`

## Status

Layered procedures are modeled as pure propagator values.

Bootstrap and compile policy: see [Builder Policy, Run Order, and Correctness](builder-policy-run-order-and-correctness.md) and [Eager Install and Arithmetic Procedure](eager-install-and-arithmetic-procedure.md). Test helpers that split `eval-layered` (install apply, then seed, then manual `run-propagators`) assume **`:lazy`**; under **`:queue`** they run apply before seeds unless refactored into a single `(do …)` flush boundary. A layered procedure
cell stores a named-network procedure object. Extending a procedure means
attaching a closure cell as a compound-object slot on that procedure cell.

There is no global layer registry, atom, or mutation-based default table in
`propagators.layered`. Defaults are represented as ordinary procedure slots.

## Core Shape

Layered data and layered procedures both use compound-object slots:

```clojure
(layered/p:base base-cell layered-object)
(layered/p:layer :provenance provenance-cell layered-object)
```

A procedure layer is a closure cell attached to the procedure object under its
layer name. For example, the `:base` slot contains the base closure for `+`, and
the `:provenance` slot contains the provenance closure.

`p:layered-procedure` is a normal propagator:

```clojure
(layered/p:layered-procedure :base base-closure plus-proc)
```

It delegates to `compound-object/p:slot`, so the closure cell and procedure
slot stay synchronized through normal propagation.

## Applying A Procedure

`p:layered-operator` is stable:

```clojure
(def p:+ (layered/p:layered-operator plus-proc))
```

It closes only over the procedure cell id. Future applications read the current
strongest procedure value in `plus-proc`, so `p:+` can be defined before all
layers are known.

`p:apply-layered` builds an activation-local application network from the
current procedure value. The procedure object chooses which layer branches exist.
For each active procedure branch:

- `:base` extracts `:base` from every argument, runs the base closure, and writes
  the branch result to an internal result-bank `:base` slot.
- Non-base branches receive the current output layer plus the full layered
  argument cells. The branch closure may inspect any layers it needs using
  `p:slot`, then writes its layer result to the matching result-bank slot.

The result bank is a compound object. After branches are installed, reduction
uses `propagators.datastructures.compound-object/p:reduce`: it gathers named
result-bank slots through `p:slot`/`p:layer`, then runs a supplied reducer
network over the gathered slot cells. `propagators.dispatch/reduce-results`
wraps that lower-level primitive for dispatch applications. For layered
procedures the reducer combinator is `layered-object-policy`, which copies each
active result-bank slot to the same slot on the final output object. There is no
runtime policy switch; the caller supplies the reducer topology directly.

The procedure network itself owns layer access and result reduction. Callers only
supply the full argument cells and output cell.

## Dispatch Combinators

`propagators.dispatch` contains reusable topology helpers:

```clojure
(dispatch/p:filter predicate value filtered-value)

((obj/p:reduce
  [:base :provenance]
  reducer-install
  result-bank
  out)
 network)

((dispatch/reduce-results
  (dispatch/layered-object-policy [:base :provenance])
  result-bank
  out)
 network)

((dispatch/reduce-results
  (dispatch/select-one-policy [:handler/number :handler/string] default)
  result-bank
  out)
 network)
```

`compound-object/p:reduce` is the generic compound-object reducer boundary. It
turns a compound net into ordinary slot-value cells by using `p:slot`, then lets
the supplied reducer network produce one output value.

`layered-object-policy` is the reducer used by layered procedures. It is
implemented through `compound-object/p:reduce` and writes gathered result slots
to matching output slots.

`select-one-policy` is a generic-procedure prototype reducer: exactly one usable
result is emitted, zero results fall back to a default cell, and multiple results
emit contradiction. It is also implemented through `compound-object/p:reduce`.
Reducers should not peek into the result-bank named network directly;
compound-object slots are partial information, and new slot data may arrive
asynchronously through slot sync. Because default fallback is a reduction
decision, prototype generic applications install the reducer after
predicate/handler branches have quiesced.

## Example Flow

Define the operator once:

```clojure
(def plus-proc (new-node-id))
(def p:+ (layered/p:layered-operator plus-proc))
```

Default stdlib bootstrap (base + provenance on a fresh `proc`):

```clojure
(require '[propagators.stdlib.provenance-arithmetic :as prov-arith])

(def {:keys [net proc operator]} (prov-arith/+ network))
;; `operator` is the `layered/+` installer for `proc`
```

Declaration-time extension (library boundary):

```clojure
(def plus-units-closure-id (new-node-id))

(let [n0 (nb/install-cell network
                          plus-units-closure-id
                          plus-units-closure
                          plus-units-closure)
      {:keys [net prop closure]}
      (layered/install-layered-procedure!
        n0
        plus-proc
        :units
        plus-units-closure-id)]
  net)
```

`install-layered-procedure!` declares the topology only. It records that
`plus-units-closure-id` is the `:units` closure slot for `plus-proc`; it does
not seed values, enqueue tasks, or run propagation. The returned `prop` is the
ordinary slot prop id, useful when a caller explicitly wants to materialize the
procedure cell value, but procedure application does not require declaration-time
eager activation.

Manual wiring by hand (same declaration semantics):

```clojure
(def plus-base-closure-id (new-node-id))

(let [n0 (nb/install-cell network
                          plus-base-closure-id
                          plus-base-closure
                          plus-base-closure)
      [_prop n1] ((layered/p:layered-procedure
                   :base
                   plus-base-closure-id
                   plus-proc)
                  n0)]
  n1)
```

Later, merge provenance behavior without redefining `p:+`:

```clojure
(def plus-provenance-closure-id (new-node-id))

(let [n0 (nb/install-cell network
                          plus-provenance-closure-id
                          plus-provenance-closure
                          plus-provenance-closure)
      [_prop n1] ((layered/p:layered-procedure
                   :provenance
                   plus-provenance-closure-id
                   plus-proc)
                  n0)]
  n1)
```

Future `(p:+ a b out)` installations use the declared slot topology even if the
procedure cell has not been eagerly materialized. Installed applications can also
observe a later declared layer after later input evaluation wakes the
application.

## Runtime Notes

`p:slot` filters stale indexed parent ids to the current execution frame. This
matters because compound-object values can be reused inside nested application
frames while carrying slot indexes from earlier frames.

`construct-propagator` preserves the ordered input/output vectors supplied at
installation time when invoking the activation function. The graph still stores
sets for adjacency, but compound closures need stable port order.

## Dispatch Benchmark

Use the explicit benchmark alias for layered and generic procedure dispatch:

```bash
clj -M:dispatch-bench
clj -M:dispatch-bench 50 51
```

Recorded local baseline on 2026-06-08: layered base+provenance dispatch took
2.203 ms median for one application and 81.630 ms median for 51 applications.
This benchmark is intentionally outside `clj -M:test`.

## Planned Bootstrap API

The current tests use closure cells + slot propagators for reactive procedure
extension.

See [Eager Install and Arithmetic Procedure](eager-install-and-arithmetic-procedure.md)
for the two-stage plan to (1) experiment with eager installer/compile
activation, then (2) add `install-arithmetic-procedure` as a one-shot bootstrap
over `proc`, with `p:layered-procedure` kept for late or external layers.

## Current Tests

`test/propagators_layered_procedure_test.clj` covers:

- **Default:** `propagators.stdlib.provenance-arithmetic` (`+`, `-`, `*`, `/` on a
  network), then apply via returned `:operator` or `p:apply-layered` on `:proc`
- skipping absent non-base layers when arguments lack them
- **Special case (reactive):** `install-plus-procedure-base-only`,
  `extend-procedure-layer`, late provenance on `layered/+`, extra `:units` layer
