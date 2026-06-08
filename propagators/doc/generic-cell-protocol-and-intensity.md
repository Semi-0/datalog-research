# Generic Procedures, Cell Protocols, And Intensity

Source files:

- `propagators/generic_procedure.clj`
- `propagators/cells/cell_protocol.clj`
- `propagators/cells/merge.clj`
- `propagators/datastructures/intensity.clj`
- `propagators/stdlib/arithmetic/intensity.clj`
- `test/propagators_generic_procedure_test.clj`
- `test/propagators_cell_protocol_test.clj`

## Status

Generic procedures are propagator-native dispatch values. A generic procedure
cell stores policy/default slots plus method branch data in one named-network
value. Method extension is ordinary slot propagation: adding a method installs
compound-object slot links into the generic cell.

The cell merge/strongest protocol uses two generic procedure cells installed
inside the same network:

```clojure
:cell/merge-generic
:cell/strongest-generic
```

`cell-merge` and `strongest-value` first try those network-local generics. If no
method matches, they fall back to the built-in merge/strongest behavior.

Intensity is the first protocol extension. It is not implemented as a special
merge multimethod case. It is an ordinary layered value with `:base` and
`:intensity` slots, and the protocol generics know how to merge and select that
data.

## Generic Procedure Shape

Initialize a generic procedure with a default cell:

```clojure
((generic/make-generic-propagator generic-id default-id) network)
```

The generic cell becomes a named-network value with:

```clojure
{:generic/default default-value
 :generic/policy  :select-one
 [:generic/method method-key] method-branch}
```

V1 policy is fixed to `:select-one`:

- zero usable branch results emits the default
- one usable branch result emits that result
- multiple usable branch results emits contradiction

The default slot is attached with `compound-object/p:slot`, so `the-nothing` is
a real no-match default value rather than an absent slot.

## Method Extension

The ergonomic handler API is:

```clojure
(generic/define-generic-propagator-handler
  generic-id
  (generic/match-cells-pred number? number?)
  (generic/handler-closure +))
```

`match-cells-pred` turns ordinary predicate functions into predicate closures.
The default matcher is `all-args-match-closure`: every predicate result must be
`true`.

Stored method branch values are compound-object slot values:

```clojure
{:method/predicates [predicate-closure-a predicate-closure-b]
 :method/matcher    matcher-closure
 :method/handler    handler-closure}
```

At application time `p:apply-generic` reads the merged method branches, installs
all branch topology in parallel, writes branch results to a compound-object
result bank, and reduces with `dispatch/select-one-policy`.

## Application Flow

```text
generic cell + args
-> predicate closures per argument
-> matcher closure
-> p:filter gates
-> handler closure
-> result-bank slot
-> select-one reducer
-> output cell
```

The generic procedure does not use a central imperative dispatch switch. Branches
are ordinary propagator topology, and dispatch is parallel execution plus a
reducer.

Known limitation: if an already-run generic application has emitted a concrete
default value into its output, attaching a later matching handler to the same
generic cell cannot yet replace that same output cleanly. The outer output cell
currently receives the reducer's strongest value, not durable reducer content,
so the concrete default and the later handler value merge as ordinary cell
values. Reactive late handler attachment is supported when no concrete default
has already been committed, for example with `the-nothing` as the default, or by
running a later application with a fresh output cell.

For pure protocol execution there is also:

```clojure
(generic/apply-generic-value generic-value [arg0 arg1])
```

That helper runs one generic application in a temporary subnet and returns the
selected value. The temporary network has no cell-protocol dict keys, so using a
generic to implement `cell-merge` or `strongest-value` does not recursively call
itself.

## Cell Merge And Strongest Protocol

`propagators.cells.merge` keeps the old built-in behavior as fallback helpers:

```clojure
built-in-cell-merge
built-in-strongest-value
```

The public operations are protocol-aware:

```clojure
(merge/cell-merge content update network)
(merge/strongest-value content network)
```

They consult the network dict:

```clojure
(net/network-dict-entry network :cell/merge-generic)
(net/network-dict-entry network :cell/strongest-generic)
```

If the generic application returns `the-nothing`, that means no protocol method
handled the input, so the public operation falls back to built-in behavior. A
handled protocol result is wrapped internally so even unusable values, vectors,
or named-network-like values can pass through the generic `select-one` reducer
without being mistaken for no match.

Install the protocol generics:

```clojure
(compile/install-and-run network
                         (protocol/install-cell-protocol))
```

Extend them like any other generic procedure:

```clojure
(protocol/define-merge-handler
  (generic/match-cells-pred #(= :left %) #(= :right %))
  (generic/handler-closure (fn [_content _update] :merged)))

(protocol/define-strongest-handler
  (generic/match-cells-pred vector?)
  (generic/handler-closure first))
```

## Intensity Values

An intensity value is a layered compound object:

```clojure
(intensity/intensity-value 10 :payload)
;; slots:
;; :base      :payload
;; :intensity 10
```

The public helpers are:

```clojure
(intensity/intensity value)
(intensity/base-value value)
(intensity/intensity-value? value)
(intensity/intensity-content? content)
(intensity/merge-content content update)
(intensity/strongest-value content)
```

Cell content for intensity may be:

- one layered intensity value
- a vector of layered intensity values

It deliberately uses a vector for multiple candidates. A set of named networks
already means evidence-set in this system, and evidence sets are strongest-joined
by the named-network merge path. Intensity needs to keep candidates separate
until its own strongest protocol selects the highest intensity.

## Intensity Protocol

Install intensity methods after installing the cell protocol:

```clojure
(-> network
    (compile/install-and-run (protocol/install-cell-protocol))
    (compile/install-and-run (protocol/install-intensity-protocol)))
```

The merge method matches:

```clojure
content = the-nothing or existing intensity content
update  = layered value with numeric :intensity and usable :base
```

The merge result keeps all candidates unless the same intensity/base pair is
already present. Strongest selects the value with the highest numeric intensity:

```text
nothing + {:base :low,  :intensity 1}  -> strongest :low
content + {:base :high, :intensity 10} -> strongest :high
content + {:base :low2, :intensity 0}  -> strongest still :high
```

If two candidates at the highest intensity have different base values,
`strongest-value` returns contradiction.

Scheduler behavior follows strongest, not raw content:

- a higher-intensity update changes strongest and wakes downstream propagators
- a lower-intensity update is retained in content but does not wake downstream
  if strongest is unchanged

## Building Intensity Layers

`p:with-intensity` builds an intensity value from cells:

```clojure
((intensity/p:with-intensity intensity-id value-id out-id) network)
```

It writes:

```clojure
:base      <- value-id
:intensity <- intensity-id
```

This uses the ordinary layered/compound-object slot machinery.

## Layered Arithmetic With Intensity

`propagators.stdlib.arithmetic.intensity` defines an arithmetic layer closure
for `+`, `-`, `*`, and `/`. The current v1 rule is the same for each operator:
the output intensity is the sum of the argument intensities.

```clojure
(prov-arith/+ network {:provenance? false
                       :intensity? true})
```

Then applying the returned operator to:

```clojure
a = {:base 10, :intensity 2}
b = {:base 20, :intensity 3}
```

produces:

```clojure
out = {:base 30, :intensity 5}
```

The intensity arithmetic layer reads only argument intensity layers. It does not
feed the current output intensity back into the sum, avoiding runaway
accumulation on repeated propagation rounds.

## Relation To Layered Procedures

Layered procedures and generic procedures share the same dispatch shape:

```text
branches in parallel -> compound-object result bank -> reducer combinator
```

They differ in what chooses the branches:

- layered procedures branch by procedure layer slots such as `:base`,
  `:provenance`, and `:intensity`
- generic procedures branch by predicate/matcher/handler methods
- cell protocols are generic procedures used by `cell-merge` and
  `strongest-value`

Intensity connects all three:

1. it is represented as a layered value
2. it is merged/selected by network-local generic protocol methods
3. it can be propagated by layered arithmetic as an ordinary procedure layer

## Debugging

`propagators.debugger` is opt-in. Generic application reports:

- method key
- predicate results
- matcher result
- filtered args
- handler result
- result-bank value
- selected value

Layered application reports:

- active layer
- branch result
- selected layered output

Example:

```clojure
(debugger/with-debugger
  (compile/install-and-run network
                           (generic/p:apply-generic generic-id args out)))
```

The debugger observes dispatch/application state. It does not change merge,
strongest, or scheduling semantics.

## Dispatch Benchmark

Use the explicit benchmark alias for generic and layered procedure dispatch:

```bash
clj -M:dispatch-bench
clj -M:dispatch-bench 50 51
```

Recorded local baseline on 2026-06-08: generic dispatch with 50 handlers took
342.616 ms median for one application and 2516.185 ms median for 51 applications.
The benchmark is intentionally outside `clj -M:test`; regular tests keep only
correctness and scheduler-order regressions.

## Current Tests

`test/propagators_generic_procedure_test.clj` covers:

- select-one initialization
- compound-object method branch storage
- handler declaration before generic initialization
- one match, no match, and multiple-match contradiction
- late method extension affecting later applications
- installed application observing a late handler after later input evaluation
- nested generic procedure dispatch from an outer handler
- operator wrapper
- debugger events
- correctness pressure tests with 10 and 50 handlers

`test/propagators_cell_protocol_test.clj` covers:

- fallback when protocol generics are absent or unextended
- extending merge/strongest through generic handlers
- intensity merge and strongest selection
- `p:with-intensity`
- layered arithmetic intensity accumulation
- scheduler wake/no-wake behavior based on strongest changes
