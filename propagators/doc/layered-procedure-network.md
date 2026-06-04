# Layered Procedure Network

Source files:

- `propagators/layered.clj`
- `propagators/datastructures/compound_object.clj`
- `propagators/propagator.clj`
- `test/propagators_layered_procedure_test.clj`

## Status

Layered procedures are modeled as pure propagator values. A layered procedure
cell stores a named-network procedure object. Extending a procedure means
propagating another named-network fragment into that cell; normal cell merge
accumulates the branches.

There is no global layer registry, atom, or mutation-based default table in
`propagators.layered`. Defaults are represented as ordinary procedure extension
fragments.

## Core Shape

Layered data and layered procedures both use compound-object slots:

```clojure
(layered/p:base base-cell layered-object)
(layered/p:layer :provenance provenance-cell layered-object)
```

A procedure extension is a named-network value. For example, a base extension
for `+` is a named network whose `:base` slot contains a closure value. A
provenance extension is another named network whose `:provenance` slot contains
the provenance closure.

`p:layered-procedure` is a normal propagator:

```clojure
extension-cell -> procedure-cell
```

When the extension cell has a usable named-network value, the propagator emits
that value to the procedure cell. The merge layer then joins it with any earlier
procedure fragments.

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
For each procedure branch:

- `:base` extracts `:base` from every argument, runs the base closure, and writes
  the result to output `:base`.
- Non-base branches receive the current output layer plus the full layered
  argument cells. The branch closure may inspect any layers it needs using
  `p:slot`, then writes its layer result back to the output object.

The procedure network itself owns layer access. Callers only supply the full
argument cells and output cell.

## Example Flow

Define the operator once:

```clojure
(def plus-proc (new-node-id))
(def p:+ (layered/p:layered-operator plus-proc))
```

Merge base behavior:

```clojure
(def plus-base-extension (new-node-id))

((layered/p:layered-procedure plus-proc plus-base-extension) network)
(nb/seed-cell network plus-base-extension
              (nb/named-cell-net [[:base plus-base-closure]]))
```

Later, merge provenance behavior without redefining `p:+`:

```clojure
(def plus-provenance-extension (new-node-id))

((layered/p:layered-procedure plus-proc plus-provenance-extension) network)
(nb/seed-cell network plus-provenance-extension
              (nb/named-cell-net [[:provenance plus-provenance-closure]]))
```

Future `(p:+ a b out)` installations use the expanded procedure cell.

## Runtime Notes

`p:slot` filters stale indexed parent ids to the current execution frame. This
matters because compound-object values can be reused inside nested application
frames while carrying slot indexes from earlier frames.

`construct-propagator` preserves the ordered input/output vectors supplied at
installation time when invoking the activation function. The graph still stores
sets for adjacency, but compound closures need stable port order.

## Planned Bootstrap API

The current tests use extension cells + `seed` + manual `run-propagators` for
every arithmetic procedure. That is the reactive extension path, not the only
path.

See [Eager Install and Arithmetic Procedure](eager-install-and-arithmetic-procedure.md)
for the two-stage plan to (1) experiment with eager installer/compile
activation, then (2) add `install-arithmetic-procedure` as a one-shot bootstrap
over `proc`, with `p:layered-procedure` kept for late or external layers.

## Current Tests

`test/propagators_layered_procedure_test.clj` covers:

- merging pure procedure extension fragments
- applying base and provenance branches
- defining `p:+` before provenance exists
- extending the procedure later and using the same operator installer
- modeling defaults as ordinary extension fragments
- skipping absent non-base layers

