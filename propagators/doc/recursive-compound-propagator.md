# Recursive Compound Propagator

Source files:

- `propagators/recursive.clj`
- `propagators/compile.clj`
- `propagators/datastructures/compound_object/map.clj`
- `propagators/datastructures/compound_object/reduce.clj`
- `propagators/datastructures/reducer_subnet.clj`
- `propagators/gur.clj`
- `propagators/gur_routed.clj`
- `propagators/gur/subenv.clj`
- `propagators/gur/subenv/env.clj`
- `propagators/gur/subenv/dispatch.clj`
- `propagators/gur/subenv/queue.clj`
- `propagators/gur/subenv/output.clj`
- `propagators/gur/subenv/frame.clj`
- `propagators/gur/subenv/examples.clj`
- `test/propagators/recursive_compound_test.clj`
- `test/propagators/gur_routed_test.clj`
- `test/propagators/gur_subenv_test.clj`

## Current Conclusion

The current direction is **network accumulation through accessor topology**,
with lexical sub-env dispatch used as a general routing substrate. Recursive
work should declare or accumulate ordinary network topology, then let the
ordinary scheduler evaluate that topology. Direct recursive activation remains
as a compatibility path, but it is not the preferred model for general nested
compound objects.

The strongest previous ownership result is the routed GUR experiment:

```text
child frame discovers next recursive topology
-> child emits a topology declaration through an outbox route
-> parent-side route installs that declaration idempotently
-> ordinary propagation evaluates the installed topology
```

The newer lexical sub-env GUR experiment keeps that ownership split, but moves
the routing into a generalized parent dict model. The implementation is now
split into a thin `propagators.gur.subenv` facade plus focused namespaces for
env registration, dispatch, child queues, output projection, frame application,
and concrete probes:

```text
parent scoped message
-> parent dict resolves owner network cell
-> eval-cell* seeds a child-local message
-> only the owner cell is evaluated in the parent network
-> contextual apply/recur propagators advance recursion inside the child env
-> child-to-parent compound outputs return through diff-internal-output-cells
```

Dispatch is routing-only. It does not manufacture semantic contradictions; if
a registered owner route no longer points at a child network, that is an invalid
route/state error. Contradictions still arise from ordinary cell merges or from
domain propagators that explicitly publish contradiction values.

This is not yet a compile-2 target or a source DSL. It is a working experiment
that validates the proposal's dispatch shape, contextual apply/recur
accumulation, Fibonacci, flat compound recursion, and nested map composition
over cons-style objects.

## Core Concepts

- **Direct recursive activation**:
  `recursive/recursive-closure` plus `recursive/p:recursive-compound`. The
  activation builds and runs an inner network immediately.
- **Frame**:
  one recursive step with stable input/output cells, optional semantic facts,
  and optionally a child network value.
- **Accumulator network**:
  a named network that retains monotone facts discovered by recursive frames.
- **Network-valued declaration**:
  a network value emitted as data, usually by `closure/p:apply-network`, before
  the caller chooses when to evaluate it.
- **Accessor topology**:
  live `obj/p:slot`, `obj/p:car`, `obj/p:cdr`, and `obj/p:cons` routes. Nested
  compound data is represented by demanded accessor routes, not by rebuilding
  host Clojure maps or vectors.
- **Continuation tunnel**:
  the evaluator continuation exposed by `runtime/*continue*`, used to run a
  child network value through declared IO boundaries.
- **Topology ownership**:
  a child frame may report discovered topology, but the live parent network
  owns whether and how that topology is installed.
- **Lexical sub-env dispatch**:
  a parent network dict may resolve scoped names or local ids to the owner cell
  whose strongest value is a child named network. `eval-cell*` only routes the
  message to that owner; it does not own recursive expansion.
- **Contextual apply/recur**:
  recursive expansion is done by propagators watching the owner child network.
  The closure receives contextual `apply` and `recur` functions that accumulate
  applied frames into the inner network.

## Kernel Extension

The kernel extension is deliberately small. It does not teach the scheduler
about recursion, compound objects, or GUR.

The kernel substrate now includes:

- `d1cce22 Add IO continuation kernel experiment` added evaluator IO state,
  `runtime/*continue*`, `reality/p:reality-in`, `reality/p:reality-out`, and
  `lexical/p:compound`.
- `444cdc6 Add lexical pointer dispatch for recursive subenvs` added
  `io/cell-ref`, `io/name-ref`, and sparse lexical env dispatch.
- `cca0b94 Add routed GUR experiment` added `:apply-topology-installer`, an
  idempotent IO delivery that applies a parent-side topology installer and
  enqueues the installed propagators.
- The current lexical sub-env GUR experiment adds `core/eval-cell*` as the
  kernel routing hook. The hook delegates the experimental dispatch cases to
  `propagators.gur.subenv` / `propagators.gur.subenv.dispatch`, so the core
  scheduler still does not know recursion semantics.

The IO route shape is:

```text
parent value -> child inbox -> reality.in -> child cell
child cell -> reality.out -> child outbox -> parent message or declaration
```

The routed installer delivery keeps ownership explicit:

```clojure
(defn example-topology-delivery [id installer]
  (io/io-delivery
   :apply-topology-installer
   {:id id
    :installed-key gur-routed/installed-declarations-key
    :installer installer}))
```

## Experiment Matrix

| Experiment | Hypothesis | Mechanism | Result | Decision |
| --- | --- | --- | --- | --- |
| Direct recursive activation | Recursion can expand and run an inner network during activation. | `recursive/p:recursive-compound` with boundary avatars and output diff. | Works for Fibonacci and simpler reductions; fragile for mixed nested map/vector output. | Keep for compatibility/prototypes. |
| Retained frame accumulation | Recursive calls can retain semantic frame facts. | self-refining closure or explicit accumulator named network. | Works; explicit accumulator is cleaner. | Keep accumulator path. |
| Network-valued expansion | Recursion can declare topology first and evaluate later. | `closure/p:apply-network` emits expanded network values. | Works better for nested map/reduce because declaration and evaluation are separated. | Default direction. |
| Declared accessor recursive map | Nested compound traversal can be represented by accessor routes. | `obj/p:accessor-recursive-map` over visible `p:cons` topology. | Works when topology is visible up front. | Keep as accessor baseline. |
| Lexical-pointer GUR | Dynamic frames can be addressed through lexical env refs. | `io/name-ref` / `io/cell-ref` into stored child envs. | Works for dispatch, but topology ownership leaks into lexical scope. | Investigate only; not preferred. |
| Accessor GUR | Lazy terminal cdr expansion can run as child frame values. | `gur/p:run-frame` plus branch network values and accessor snapshots. | Works for current cases, but needs snapshots/subscribers/lexical env coupling. | Keep as comparison. |
| Routed GUR | Child frames can emit topology declarations instead of owning parent topology. | `gur-routed/p:routed-run-frame` and parent-side installers. | Passes route, late cdr, nested cons, and incrementality tests. | Best previous ownership baseline. |
| Lexical Sub-Env GUR | Routed owner/child split can be generalized as lexical sub-env dispatch. | `core/eval-cell*`, scoped vector keys, scoped slot accessor registration, `gur.subenv/p:run-subenv-frame`, contextual apply/recur. | Passes owner-only routing, sub-env registration, Fibonacci, flat map-list, composed nested map-list, bidirectional late nested cdr through parent-visible output, constructed-accessor lazy cdr extension, and nested constructed lazy cdr extension through transitive scoped slot fanout. | Current proposal validation; continue, but not compile target yet. |

## Experiments

The snippets in this section are mechanism pseudocode. They are written in
Clojure shape to expose the control/data movement, not to duplicate the exact
implementation helpers.

### Experiment A: Direct Recursive Activation

Hypothesis: a recursive propagator can expand and execute an activation-local
inner network, then publish boundary output messages.

Mechanism: the propagator activation creates an inner boundary, lets the
recursive closure install more topology into that activation-local network,
runs that inner network to quiescence immediately, then diffs only boundary
outputs back to the outer network.

```clojure
(defn direct-activation [outer closure arg out]
  (let [inner (make-boundary outer [arg] [out])
        self  (install-self-call closure inner)
        frame (closure self inner)
        done  (run-to-fixpoint frame)]
    (diff-boundary done outer [out])))
```

Evidence: Fibonacci tests, max-depth contradiction, lazy base branches, and
normal compound wrapper tests pass.

Limit: topology expansion and evaluation are interleaved inside activation, and
the mixed nested map/vector experiment can produce localized contradictions.

Decision: keep as compatibility and fast prototyping path.

### Experiment B: Retained Frame Accumulation

Hypothesis: recursive expansion can retain stable semantic facts about each
frame.

Mechanism: each recursive frame emits a named-network fragment. The accumulator
cell merges that fragment with earlier facts. Re-emitting the same frame fact
is idempotent because frame fact cell ids are deterministic.

```clojure
(defn retain-frame [acc frame facts]
  (let [fragment (named-network frame facts)
        merged   (named/join acc fragment)]
    (if (contradiction? merged)
      contradiction
      merged)))
```

Evidence: self-refining and explicit accumulator tests retain frames such as
`[:fib 5]`; named-network subsumption and idempotence tests pass.

Limit: self-refining closure couples closure identity to retained history.

Decision: keep explicit accumulator as the default retained-state shape.

### Experiment C: Network-Valued Expansion

Hypothesis: recursive topology can be emitted as a network value and evaluated
later by the ordinary scheduler.

Mechanism: expansion is only a network transformation. The closure receives a
template declaration network, adds more cells/propagators, and returns a larger
network value. A later caller chooses when to enqueue the declared propagators.

```clojure
(defn declare-then-run [template expander]
  (let [declared (expander template)
        props    (declared-propagators declared)]
    (run-propagators declared props)))
```

Evidence: declared nested map and nested reduce topology run correctly after
the expanded network is evaluated.

Limit: the caller must still decide when to run the emitted network and how to
install or compose it.

Decision: use as the default declaration/evaluation split.

### Experiment D: Declared Accessor Recursive Map

Hypothesis: nested compound traversal should use accessor topology instead of
materialized values.

Mechanism: a cons cell is recognized by visible `:car` and `:cdr` accessor
routes. The map declares a mapped `car`, recursively declares the mapped `cdr`,
and assembles output through `p:cons`. It does not read the whole list into a
host value.

```clojure
(defn declare-accessor-map [source out]
  (if (cons-topology? source)
    (let [car' (fresh-cell)
          cdr' (fresh-cell)]
      (map-leaf-or-nested (:car source) car')
      (declare-accessor-map (:cdr source) cdr')
      (install-cons car' cdr' out))
    (wait-for-later-topology source out)))
```

Evidence: live cons-list map, nested cons in car positions, and public accessor
update tests pass.

Limit: it can only walk topology that is already visible, unless paired with a
lazy frame strategy.

Decision: keep as the accessor baseline.

### Experiment E: Lexical-Pointer GUR

Hypothesis: dynamic recursive frames can be addressed by stable lexical refs.

Mechanism: the parent stores child networks in a lexical env table. A message
whose target is `io/cell-ref` or `io/name-ref` is dispatched into the child
network, the child continuation runs, and child outbox messages return to the
parent.

```clojure
(defn lexical-dispatch [parent scope msg]
  (let [child  (get-in parent [:lexical-envs scope])
        local  (resolve-ref child (:id msg))
        child' (continue (enqueue child (retarget msg local)))]
    (-> parent
        (assoc-in [:lexical-envs scope] (clear-io child'))
        (enqueue-all (:outbox child')))))
```

Evidence: kernel IO tests show nested lexical frames can be created and
messaged by scope.

Limit: lexical scope starts carrying topology ownership, which creates pressure
for snapshots, subscribers, and frame-env synchronization.

Decision: useful evidence, but not the preferred GUR ownership model.

### Experiment F: Accessor GUR In `propagators.gur`

Hypothesis: a lazy terminal cdr can emit a branch frame network and evaluate it
through a continuation boundary.

Mechanism: the outer graph stores a branch frame as a cell value. The runner
injects parent inputs through `reality.in`, runs the frame continuation, drains
`reality.out`, externalizes accessor outputs so detached shells remain readable,
and writes the updated frame back.

```clojure
(defn accessor-gur-run [parent frame-id]
  (let [frame   (strongest parent frame-id)
        child   (inject-route-inputs frame parent)
        child'  (continue child)
        records (drain-outbox child')]
    (concat (externalized-parent-messages records child')
            [(message frame-id (store-frame child'))])))
```

Evidence: current `obj/p:accessor-recursive-map` tests cover late cdr frame
declaration and nested cons cells.

Limit: branch-local accessor outputs need source-slot externalization, and the
lexical env table becomes part of frame synchronization.

Decision: keep as the comparison path and evidence for boundary requirements.

### Experiment G: Routed GUR In `propagators.gur-routed`

Hypothesis: child frames can emit topology declarations, while the parent owns
installation.

Mechanism: the child frame still runs through reality IO, but topology is not
hidden in lexical env mutation. Records on the `:topology` route are interpreted
as declarations, and a parent-side installer applies each declaration once.

```clojure
(defn routed-gur-run [parent frame-id]
  (let [child   (inject-route-inputs (strongest parent frame-id) parent)
        child'  (continue child)
        records (drain-outbox child')]
    (-> parent
        (merge-messages (normal-records records))
        (install-once-each (topology-records records))
        (seed frame-id (clear-io child')))))
```

Evidence: `propagators.gur-routed-test` covers route IO, idempotent topology
installation, prebuilt cons-list map, Fibonacci-style leaf mapping, late cdr
expansion, nested cons-in-car traversal, and depth-stable public accessor
updates.

Limit: still experimental and not yet the compile-2 target API.

Decision: previous preferred direction and current comparison baseline for
ownership boundaries.

### Experiment H: Lexical Sub-Env GUR In `propagators.gur.subenv`

Hypothesis: routed GUR's owner/child split can be lifted into a generalized
lexical sub-env dispatch model. `eval-cell*` should route a parent message into
the named-network cell that owns the child env, but recursion should still be
advanced by contextual `apply` / `recur` propagators watching that owner cell.

Mechanism: when a normal parent cell merge makes a cell's strongest value a
named network with `[:env/scope]`, the parent dict registers the child scope
and its lexical bindings with plain vector keys:

```clojure
[:env/scope scope]             ;; parent dict -> owner cell id
[:env/ref scope name]          ;; parent dict -> [:dispatch/subenv owner local]
[:env/cell-ref scope local]    ;; parent dict -> [:dispatch/subenv owner local]
[:env/bind name]               ;; child dict -> local cell id
[:gur/applied frame-key]       ;; child dict -> idempotence guard
```

Nested child scopes are lifted as `[:dispatch/subenv-ref owner target]`, so a
parent-visible message can be routed through the owning child network and then
resolved again by the child dict. The important constraint is that the parent
still evaluates only the owner network cell.

Compound-object slot accessors use the same scoped address substrate. A child
frame accessor that participates in a parent slot is registered as
`[:env/cell-ref child-scope child-local]` on the parent collection's slot
topology. Local parent ids still seed from the parent env, child ids seed from
the child env after dispatch, and structural slot registration remains distinct
from slot value updates.

The code is split by responsibility:

- `subenv/env.clj`: scope keys, lexical bindings, dispatch directory
  registration, and route resolution;
- `subenv/dispatch.clj`: the `eval-cell*` routing hook only;
- `subenv/scoped_slot.clj`: scoped slot accessor registration and accessor
  parent-cell import across frame/dispatch boundaries;
- `subenv/queue.clj`: child propagator run tokens;
- `subenv/output.clj`: child queue execution plus diff-based output
  projection;
- `subenv/frame.clj`: frame boundary copying and contextual `apply` / `recur`;
- `subenv/examples.clj`: Fibonacci and cons-list probes.

```clojure
(defn eval-cell* [directory msg parent-net]
  (match (lookup directory (message-target msg))
    [:dispatch/local cell-id]
    (eval-cell cell-id (message cell-id (message-value msg)) parent-net)

    [:dispatch/subenv owner-id local-id]
    (let [child-net  (strongest parent-net owner-id)
          _          (assert-child-network child-net)
          child-msg  (message local-id (message-value msg))
          child-net' (eval-child-local child-net child-msg)
          tasks      (queued-child-props child-net')]
      (eval-cell owner-id
                 (message owner-id (queue-child-props child-net' tasks))
                 parent-net))

    [:dispatch/subenv-ref owner-id target-id]
    (let [child-net  (strongest parent-net owner-id)
          _          (assert-child-network child-net)
          child-msg  (message target-id (message-value msg))
          child-net' (eval-cell* (net-dict child-net) child-msg child-net)
          tasks      (queued-child-props child-net')]
      (eval-cell owner-id
                 (message owner-id (queue-child-props child-net' tasks))
                 parent-net))))
```

Dispatch is not a semantic contradiction source. If the owner route no longer
points at a child network, the experiment reports an invalid route/state error.
Contradiction remains a value-level result produced by ordinary propagators and
cell merge.

Child-to-parent compound output deliberately does not use lexical dispatch.
Child-local accessors write to child-local output cells. The owner-cell watcher
runs the queued child propagators and projects selected outputs with the
existing diff path:

```clojure
(defn p:run-subenv-frame [owner-id external-output-ids]
  (construct-propagator
   (fn [_inputs _outputs parent-net]
     (let [child0 (network-cell-strongest parent-net owner-id)
           child1 (run-child-queue child0)
           diff-view (externalize-output-cells child1 external-output-ids)
           child2 (clear-child-queue child1)
           output-msgs
           (diff/diff-internal-output-cells
            diff-view
            parent-net
            external-output-ids)]
       (cond-> output-msgs
         (not= child0 child2)
         (conj (message owner-id child2)))))
   [owner-id]
   (into [owner-id] external-output-ids)))
```

The `diff-view` is only a parent-facing projection. It is not stored back into
the child frame, because doing so would erase child-local accessor routes needed
for later output assembly.

Contextual recursion is represented as ordinary propagators. The implemented
`def-recursive` is a runtime constructor function, not a source-level macro. It
passes contextual `apply` and `recur` functions into the closure; applying a
closure extends and accumulates the child network with an applied-frame fact.

The concrete probes in `subenv/examples.clj` use the small `propagators.compile`
DSL with an experiment-local installer extension for contextual recursion:

```clojure
(compile/eval-net-with-bindings
 frame-net
 {'ctx/recur (contextual-recur-installer recur-fn)
  'p:fib-base? p:fib-base?
  'p:+ p:+
  'car p:car-out
  'cdr p:cdr-out}
 {'n n-id, 'out out-id}
 '(do
    (seed one 1)
    (-> (::fib-base? n) base?)
    (-> (::not base?) recur?)
    (p:id
     (cond
       base? n
       recur? (::+ (::recur (switch recur? (::- n one)))
                   (::recur (switch recur? (::- n two)))))
     out)))
```

The compile DSL now has expression-returning keyword calls: `(::foo a b)`
allocates an output cell, installs the matching output-last propagator, and
returns that cell. The `switch` form also returns a gated output cell, `cond`
installs propagated branch gates into one output cell, and `(-> expr name)`
binds an expression result to a readable cell name.

The probes do not branch by materializing host lists. `fib-definition` composes
local bottom-aware numeric primitives with `switch`, `cond`, `not`, contextual
`recur`, and `+`. `map-list-definition` is intentionally flat, like mapping
over one array/list level: it composes accessor `car`/`cdr`, contextual
`apply` over the `car`, contextual `recur` over the `cdr`, and `cons` to rebuild
the output, with the empty input list gated directly to the accumulator. It does
not inspect whether the `car` is itself a nested list. Nested mapping is tested
by composition: the outer `map-list` receives a mapper closure whose body invokes
an inner `map-list` with `fib`.

The example cons values are accessor networks; predicates observe accessor
source slots rather than raw `{:car ... :cdr ...}` maps.

```clojure
(defn apply-closure [closure args inner-net]
  (when (and (ready? args)
             (not (applied? inner-net closure args)))
    (let [frame-net ((:body closure)
                     {:apply contextual-apply
                      :recur  contextual-recur
                      :args   args})]
      (-> inner-net
          (merge-frame frame-net)
          (mark-applied closure args)))))
```

Evidence: `propagators.gur-subenv-test` covers:

- owner-only `eval-cell*` routing, proving the parent local/avatar cell is not
  evaluated directly;
- registration of `[:env/scope]`, `[:env/ref]`, and `[:env/cell-ref]` after a
  named child network is merged into an owner cell;
- Fibonacci values `0`, `1`, `5`, and `8`, with applied closure facts retained
  in the child network;
- scalar factorial values `0! = 1`, `1! = 1`, and `5! = 120`, using recursive
  multiplication through the same contextual `recur` path;
- scalar integer square root by binary search, with floor results for perfect
  and non-perfect squares from `0` through `81`. This is the current scalar
  branching stress case because it uses multi-argument recursion, arithmetic
  intermediates, comparison, and two recursive branches;
- `map-list` over `[0 1 2 3 4 5]`, mapping Fibonacci to `[0 1 1 2 3 5]`;
- nested `map-list` composition over `[[0 1] [2 3]]`, mapping inner lists to
  `[[0 1] [1 2]]`;
- bidirectional nested dispatch where a late nested `cdr` delivery is routed
  into the owning child env through a lifted lexical ref, and the only
  assertion is the parent-visible recursive output value;
- constructed accessor input built only with `compound-object` accessors:
  parent-side `obj/p:cons` plus a later `obj/p:cons` into the first cdr slot is
  observable as `[0 1]` through `obj/p:car` / `obj/p:cdr`, and recursive
  `map-list` output observes `[0 1]` after the lazy cdr extension. The source
  collection's direct cdr source slot remains `nothing`; the update travels
  through scoped slot accessor fanout, not list materialization;
- nested constructed accessor input, where an outer `map-list` maps
  `map-list-fib` over an inner list built only with `obj/p:cons`; a later inner
  cdr extension reaches the nested mapper frame and updates the observed inner
  output from a single mapped element to `[0 1]`;
- derived `reduce-list` over the same cons/accessor shape, with a `sum-step`
  closure applied at each node, returning `15` for `[0 1 2 3 4 5]`;
- derived `filter-list` over the same cons/accessor shape, with an
  `even-predicate` closure and branchy `cons` output, returning `[0 2 4]` for
  `[0 1 2 3 4 5]`;
- constructed-accessor lazy extension for `reduce-list`: the reducer output is
  `nothing` while the cdr is unknown, then becomes `3` after the terminal cdr is
  installed through `obj/p:cons`. This is the expected reduce behavior: unlike
  map, reduce has no prefix output until the list end is known;
- a prefix-style reduce experiment with a `sum-present-step` operator that
  ignores `nothing` shows why this cannot be the default scalar reduce
  semantics: it first emits provisional sum `1`, then the later correct sum `3`
  conflicts in the same output cell and the cell becomes contradiction. To make
  prefix reduce monotone, the output would need a monotone summary value rather
  than a plain scalar strongest value;
- constructed-accessor lazy extension for `filter-list`: filtering `[0 . ?]`
  exposes first kept value `0`, and after a later cdr extension to `[2]` the
  observed output updates to `[0 2]` through the same scoped slot fanout path.

Local microbenchmark on `2026-06-17`, measured with an ad-hoc
`clojure -M -e` command, `5` warmups and `20` timed end-to-end runs:

| Case | Avg ms/run | Min | Max | Parent cells |
| --- | ---: | ---: | ---: | ---: |
| `fib(6)` | `34.285` | `27.318` | `47.128` | `6` |
| `map-list-fib [0..5]` | `45.713` | `42.396` | `54.402` | `8` |
| `nested-map-list-fib [[0 1] [2 3]]` | `21.069` | `19.781` | `22.587` | `8` |
| `reduce-list-sum [0..19]` | `32.215` | `30.701` | `34.008` | `8` |
| `filter-list-even [0..19]` | `51.139` | `49.370` | `54.388` | `8` |

This is a snapshot, not a benchmark harness. It shows that reduce/filter do not
require new runtime machinery, but filter is visibly more expensive because it
combines predicate application, recursive tail production, branch selection,
and conditional `cons` output.

Limit: this is not a compiler target, not the exact source syntax sketched for
`def-recursive`, and not a general nested map/vector writer. It does not
redesign `p:slot` or the existing nested `p:car` / `p:cdr` accessor behavior.
The constructed-accessor probes now cover flat and nested scoped slot paths, but
the implementation is still experiment-specific runtime machinery rather than
compile-2 lowering. Transitive scoped-slot export now follows the accessor
chain itself: nested child frames are scanned, but a scoped target is registered
back to a parent collection only when it is reachable through a parent-owned
accessor parent id and still has a live child route. The kernel hook still
delegates to the experiment namespace rather than moving every dispatch case
into `propagators.core`. No AST compound-object transformation probe was added
in this pass; without a small arbitrary-record constructor, it would mostly be
map/filter boilerplate over another accessor shape. No unification probe was
added; plain value equality/merge is already covered elsewhere, while useful
unification needs its own monotone substitution value rather than another
scalar recursion body.

Binary search exposed one DSL rule worth keeping: `cond` is declarative, so it
builds all branch topology. Recursive branch expressions must gate their
arguments with an effective branch predicate such as `(and search? fits?)`, or
the inactive recursive branch can still install a self-recursive frame.

Decision: continue this as the current generalized GUR proposal validation. It
does not replace the routed ownership lesson; it lifts that lesson into the
lexical sub-env dispatch model.

## Current APIs

```clojure
(recursive/recursive-closure step-f)
(recursive/p:recursive-compound closure-id arg-id out-id)
(recursive/p:accumulating-recursive-compound closure-id arg-id acc-id out-id)
(closure/p:apply-network closure-id network-id out-id)
(obj/p:accessor-recursive-map closure-id acc-id source-id out-id)
(gur/p:run-frame frame-net-id input-routes output-routes)
(gur-routed/p:routed-run-frame frame-net-id input-routes output-routes :topology)
(gur-routed/p:routed-accessor-recursive-map closure-id acc-id source-id out-id)
(core/eval-cell* directory msg network)
(gur-subenv/def-recursive name closure)
(gur-subenv/p:apply-closure closure-id arg-ids out-id)
(gur-subenv/p:run-subenv-frame owner-id external-output-ids)
```

## Test Evidence

Current behavior is covered by:

- `test/propagators/recursive_compound_test.clj` for Fibonacci, retained frame
  accumulation, nested map/reduce, accessor-recursive map, late cdr expansion,
  and compile DSL wiring.
- `test/propagators/kernel_io_test.clj` for evaluator IO, reality routes, and
  lexical ref dispatch.
- `test/propagators/compound_object_network_slot_test.clj` for accessor route
  semantics, detached shell snapshots, and subscriber dispatch.
- `test/propagators/gur_routed_test.clj` for routed GUR and route-owned
  topology installation.
- `test/propagators/gur_subenv_test.clj` for lexical sub-env GUR dispatch,
  contextual apply/recur, Fibonacci, flat and composed nested map-list
  recursion, reduce-list, filter-list, and bidirectional late nested cdr
  delivery asserted through the parent-visible output.

## Open Problems

- decide how routed GUR and lexical sub-env GUR should relate in the final
  compile-2 target;
- promote `def-recursive` from a runtime constructor experiment into source
  syntax or compile output, if the model holds;
- define bidirectional nested writer semantics over arbitrary compound shapes,
  beyond the current cons-style compound/list tests;
- add a dedicated GUR benchmark harness;
- derive compact route declarations so compile-2 does not emit verbose frame
  boilerplate.

## Design Details

### Accessor GUR Topology Snapshot

The following drawing was regenerated on `2026-06-14` with the
`graph.vijual` stress-majorized directed layout from the real late-cdr accessor
topology used by the regression test. The layout used a wider spacing target
and longer solve:

```clojure
{:stress-node-spacing 3.2
 :stress-iterations 420
 :stress-refine-iterations 420
 :routing :shortest-path}
```

This is the focused continuation slice of the real `net-graph`: unrelated
first-car and scalar fib internals are omitted, but every shown dependency is
drawn from the installed topology.

```text
+------------+        +---------------+        +------------+
| late car=3 | -----> |  terminal cdr | -----> |  late end  |
+------------+        +---------------+        +------------+
                              |
                              v
                       +---------------+
                       | ready watcher |
                       +---------------+
                         |           |
                         v           v
                    +--------+   +----------+
                    | ready? |   | expander |
                    +--------+   +----------+
                         |           |
                         v           v
                       +----------------+
                       |   when-apply   |
                       +----------------+
                         |            ^
                         v            |
                   +-------------+    |
                   | branch frame | <--+
                   +-------------+
                         |
                         v
                    +------------+
                    | GUR runner |
                    +------------+
                      |        |
                      v        v
             +-------------+  +----------+
             | mapped root |  | out :cdr |
             +-------------+  +----------+
                |       |           ^
                v       v           |
        +------------+ +------------+
        | reader :car0 | reader :car1 |
        +------------+ +------------+
```

### Accessor GUR Solved And Unsolved

Solved in the current experiment:

- general frame execution through `reality.in` / `reality.out`;
- late `cdr` expansion after new `:car` / `:cdr` slots are installed;
- nested cons cells in `car` positions using recursive accessor topology;
- synchronization back through the continuation tunnel without changing the
  kernel or splicing branch declarations into the parent graph.

Still open:

- GUR is proven for the current accessor list/map cases, not yet packaged as
  the final compile-2 iteration primitive;
- there is no dedicated GUR benchmark harness yet, so current timings are
  subsystem-level evidence;
- arbitrary bidirectional writer semantics over all nested compound shapes need
  more design;
- route-list and frame-boilerplate ergonomics still need a derived API before
  compile-2 should target this directly;
- the current benchmark is an ad-hoc local snapshot; keep using it for direction
  only until there is a dedicated GUR benchmark harness.

### Runtime Invariants

The experiment keeps these constraints explicit:

- Network declaration is separate from network evaluation. Recursive steps build
  ordinary propagator topology and return a network value. Evaluation happens by
  running that network to quiescence at the activation boundary.
- The outer scheduler still sees only propagator messages. Recursive activations
  do not imperatively edit the outer network.
- Retained recursive state is named-network declaration data. Frame facts are
  stored under deterministic dict keys like `[:recursive/frame frame slot]`.
- Accumulation is monotone. New frame facts are joined with old frame facts by
  `named/join`; incompatible facts become contradiction instead of replacing
  older facts.
- Accumulation is idempotent at the named interface. Frame fact cell ids are
  deterministic from `[frame slot]`, so re-declaring the same fact does not keep
  creating semantically new named commitments.
- Successful recursive computation is composed out of propagators. The
  Fibonacci experiment uses `prop/switch` for the base case and `prop/+` for the
  recursive combine. Direct writes are reserved for evidence seeding and failure
  boundaries such as contradiction or max-depth overflow.

### Runtime Fit

Normal propagators return messages, not graph edits. Recursive propagation
therefore cannot mutate the outer graph during `eval-propagator` without
changing the core runtime contract.

Instead, recursion happens inside the inner network value:

```text
outer cells
-> avatar frame
-> closure applies to activation-local network
-> closure may install recursive compounds into that network
-> inner run-tasks reaches quiescence
-> changed avatar output becomes an outer message
```

This keeps recursive work isolated from the outer scheduler. The recursive
network can expand and resume because a network is ordinary immutable data.

### Recursive Closure Step

`recursive-closure` wraps a step function. The step receives:

```clojure
{:closure-net closure-net
 :self-id self-id
 :acc-id acc-id
 :input-ids input-ids
 :output-ids output-ids
 :network network
 :depth depth}
```

The step returns either an updated activation-local network or:

```clojure
{:network updated-network
 :frame-fragment named-network-fragment}
```

Recursive calls are ordinary topology installs against `self-id`:

```clojure
(recursive/p:recursive-compound self-id n-minus-1 fib-1)
(recursive/p:recursive-compound self-id n-minus-2 fib-2)
```

Each recursive activation increments `:recursive/depth` in the closure net. If
the configured `:max-depth` is reached, the recursive closure writes
`contradiction` to its output avatars.

### Named Frame Declarations

A recursive frame is named by semantic data, for example:

```clojure
[:fib 5]
```

The frame can declare monotone facts:

```clojure
(recursive/frame-fragment
 [:fib 5]
 {:input 5
  [:child 0] [:fib 4]
  [:child 1] [:fib 3]
  :combine :+
  :output 5
  [:status :expanded] true
  [:status :done] true})
```

Status uses separate monotone facts such as `[:status :expanded]` and
`[:status :done]`. It intentionally does not update one `:status` slot from
`:expanded` to `:done`, because that would be non-monotone.

These fragments are normal named networks. Subsumption is checked with
`named/named-network->=`, and merging is `named/join`.

### Two Accumulation Designs

#### Self-Refining Closure

`p:self-refining-recursive-compound` treats the closure cell as both input and
output. Recursive calls update the retained closure net, and closure cell merge
uses `named/join` on compatible closure nets.

This is concise at the call site:

```clojure
(recursive/p:self-refining-recursive-compound self n-1 fib-1)
```

It is expressive when the closure itself should remember its semantic expansion.
The cost is that closure values now need a structural merge rule, and the
closure cell changes as semantic frames accumulate.

#### Explicit Accumulator

`p:accumulating-recursive-compound` keeps the closure stable and sends retained
frame declarations through a separate named-network accumulator cell.

The recursive call has one extra port:

```clojure
(recursive/p:accumulating-recursive-compound self n-1 acc fib-1)
```

This makes dataflow more explicit and keeps closure identity simpler. It is the
better default for higher-order compound-object operations because map/reduce
style operators can thread one accumulator through many recursive applications.

#### Comparison

| Design | Retained state | Merge path | Expressiveness | Main cost |
| --- | --- | --- | --- | --- |
| `p:recursive-compound` | None | Output diff only | Computes recursive values | No semantic frame history |
| Self-refining closure | Closure net | Closure merge + `named/join` | Closure carries its own expansion | More coupling in closure cell merge |
| Explicit accumulator | Separate named-network cell | Cell merge + `named/join` | Best fit for higher-order map/reduce | One extra accumulator port |

The chosen direction for further higher-order work is the explicit accumulator.
The self-refining closure remains useful as a compact comparison and for cases
where a closure should intentionally retain its own declaration history.

### Compile DSL Support

The default compile installer vocabulary includes:

```clojure
prop/id
prop/+
prop/-
prop/<=
prop/not
prop/switch
closure/p:apply-closure
closure/p:apply-network
closure/p:when-network
recursive/p:recursive-compound
recursive/p:self-refining-recursive-compound
recursive/p:accumulating-recursive-compound
obj/p:nested-recursive-map
obj/p:accessor-recursive-map
```

That lets recursive branch topology be written with the same DSL as other
network construction:

```clojure
(let-cell [n-1 n-2 fib-1 fib-2]
  (seed n-1 n-minus-1)
  (seed n-2 n-minus-2)
  (recursive/p:recursive-compound self n-1 fib-1)
  (recursive/p:recursive-compound self n-2 fib-2))
```

The frame-template vocabulary can also express arithmetic and guards:

```clojure
(let-cell [one n-1 base? recur?]
  (seed one 1)
  (prop/- n one n-1)
  (prop/<= n one base?)
  (prop/not base? recur?))
```

`let-cell` now creates fresh lexical cells for declared locals. That matters for
recursion: each recursive frame must get fresh `n-1`, `fib-1`, and similar
scratch cells instead of reusing prior frame bindings from the network dict.
Plain symbol references outside `let-cell` still resolve through the dict.

### Network-Valued Expansion

`closure/p:apply-network` applies a closure to a network-valued cell and emits
the expanded network as a value:

```clojure
(closure/p:apply-network expander template expanded)
```

It is declaration-only:

- It does not run the expanded network.
- It does not install the expanded network into the outer graph.

`propagators.gur/p:run-frame` is the corresponding evaluation boundary for
these frame values. A frame records its own propagator ids plus declared
`reality.in` / `reality.out` ports. The runner injects parent input values into
the child inbox, invokes the existing evaluator continuation on the child
network, drains the child outbox, and translates matching outbox records back
to parent messages. The child frame value can be written back to its frame
cell, but no branch declarations are spliced into the live outer graph during
activation.

For accessor-network outputs, GUR also exports source-slot snapshots for slot
route values that live only inside the child frame. This is what lets a parent
`p:car` / `p:cdr` reader observe a branch-local mapped car after a late cdr
frame runs, without installing the branch's internal graph in the parent.

#### GUR Frame Runner Logic

`propagators.gur` is the lexical-frame implementation. It treats a branch frame
as a network value with declared `reality.in` / `reality.out` ports, then stores
the updated child frame as both the frame-cell value and a lexical env entry.
The live parent graph is still not rewritten by the child activation.

The boundary declaration is small: install reality ports inside the frame and
remember their propagator ids so the runner can wake them.

```clojure
(defn example-gur-boundary [frame source out]
  (gur/install-boundary
   frame
   {:inputs [[:source source]]
    :outputs [[:out out] [:source source]]}))
```

The runner injects parent values, runs the child continuation, translates
outbox records, and writes the updated frame back through the frame cell.

```clojure
(defn example-gur-runner [frame-id source out]
  (gur/p:run-frame
   frame-id
   [[:source source source]]
   [[:out out] [:source source]]))
```

The accessor-specific part is output externalization. When a child frame emits
an accessor shell, GUR snapshots live slot values into `source-slots` at the
boundary so parent readers can inspect a detached branch output.

```clojure
(defn example-gur-detached-output [parent coll]
  (obj/externalize-accessor-cell parent coll))
```

The current `obj/p:accessor-recursive-map` path uses this runner for lazy
terminal cdr frames: an initially empty tail waits; a later slot update creates
a branch network; `gur/p:run-frame` evaluates that branch and publishes mapped
values through declared outputs.

#### Routed GUR Experiment

`propagators.gur-routed` is the parallel 2026-06-15 experiment. It keeps the
old `d1cce22` route-boundary style: child frames emit ordinary outbox records,
including a dedicated topology route such as `:topology`. The parent runner
interprets topology records as declarations and applies parent-side installers
idempotently. This tests whether GUR can avoid lexical env mutation while still
accumulating new recursive topology.

The route declaration is just data. The child can emit it through
`reality/p:reality-out`; the parent decides how to install it.

```clojure
(defn example-routed-declaration [source out]
  (gur-routed/topology-declaration
   [:map source out]
   :install-accessor-map
   {:source-id source :out-id out}))
```

The routed frame runner has the same value IO shape as `gur/p:run-frame`, plus
one explicit topology channel.

```clojure
(defn example-routed-runner [frame-id source out]
  (gur-routed/p:routed-run-frame
   frame-id
   [[:source source source]]
   [[:out out]]
   :topology))
```

A topology declaration becomes an idempotent parent-side install. The installed
declaration id is recorded under `gur-routed/installed-declarations-key`, and
newly installed propagators are enqueued.

```clojure
(defn example-routed-frame-install [net frame-id frame]
  (gur-routed/install-routed-frame
   net
   {:frame-id frame-id
    :frame-net frame
    :input-routes []
    :output-routes []}))
```

The routed accessor map is the comparable user-facing prototype. It walks live
`p:cons` accessor topology, leaves waiting frames at terminal cdrs, and expands
the next recursive frame when a later public accessor update installs `:car`
and `:cdr`.

```clojure
(defn example-routed-accessor-map [f acc source out]
  (gur-routed/p:routed-accessor-recursive-map
   f acc source out))
```

The important contrast is ownership:

| Path | Child can emit | Parent graph changes during child activation? | State written back |
| --- | --- | --- | --- |
| `propagators.gur` | normal outbox values | no branch splicing | frame cell + lexical env table |
| `propagators.gur-routed` | normal values + topology declarations | only via parent-side installer delivery | frame cell + installed declaration set |

The routed experiment currently passes the focused route tests, prebuilt cons
map, Fibonacci-style leaf map, late cdr expansion, nested cons-in-car traversal,
and depth-stable public accessor update probe in
`propagators.gur-routed-test`.

#### Lexical Sub-Env GUR Experiment

`propagators.gur.subenv` is the newer generalized-subenv experiment. It keeps
the owner-cell rule from routed GUR: a parent-scoped message never evaluates a
child avatar as if it were a parent cell. Instead, the parent dict resolves the
message target to the owner cell, the child network receives the local message,
and the parent evaluates only that owner cell with the updated child network.
The implementation is split under `propagators.gur.subenv.*`, with the top-level
namespace kept as a facade.

The registration boundary is a normal cell merge. When the owner cell's
strongest value is a named network with `[:env/scope]`, the parent dict is
extended with the child scope, child name refs, child local refs, and lifted
nested refs. This is why the child env is registered at the merge boundary
rather than when a local accessor is read.

The output boundary is intentionally not symmetric. Parent-to-child messages use
lexical sub-env dispatch. Child-to-parent compound outputs use
`diff/diff-internal-output-cells`, so inner accessors do not route "up" through
lexical dispatch. This preserves the existing nested `p:car` / `p:cdr` behavior
and confines the new bidirectional recursion logic to the frame boundary. The
stored child frame keeps internal accessor routes; only the diff view is
externalized for parent output messages.
Dispatch itself does not emit contradiction messages; invalid owner routes are
reported as routing errors, while contradictions still arise from value
propagators and cell merge.

The example definitions are built with the small `propagators.compile` DSL plus
experiment-local installers for contextual `apply` / `recur`, not host-side
branch functions. `fib-definition` uses local bottom-aware numeric primitives
plus `switch`, `cond`, and recursive applications. `map-list` uses
accessor-network cons cells, output-last `::car` / `::cdr` aliases, gates,
contextual `apply` over the `car`, contextual `recur` over the `cdr`, and
`::cons`. It is intentionally flat; nested mapping is represented by composing
`map-list` with a mapper closure that runs an inner `map-list`.

The current tests validate both scalar and compound recursion:

- Fibonacci uses composed propagators for `n - 1`, `n - 2`, recursive calls, and
  the final sum.
- `map-list` maps Fibonacci over flat cons-style compound data.
- Nested mapping composes an outer `map-list` with a mapper closure that applies
  an inner `map-list` to each nested cons list.
- A late nested `cdr` update is routed through a parent-visible lifted sub-env
  ref and asserted only through the final parent-visible recursive output.
- A constructed-accessor probe uses only `obj/p:cons`, `obj/p:car`, and
  `obj/p:cdr` for the input. The parent compound object lazily extends from
  `[0 nothing]` to `[0 1]` for new accessor observers. The collection cell is
  rewritten only for accessor route topology, while the direct cdr source slot
  remains `nothing`. Recursive `map-list` sees the lazy extension through scoped
  slot dispatch and maps the output to `[0 1]`.
- A nested constructed-accessor probe maps `map-list-fib` over an inner list
  built with `obj/p:cons`; after a lazy inner cdr extension, accessor observation
  of the nested mapped output yields `[0 1]`.

The experiment is deliberately still below the final language surface. It has
the dispatch substrate, frame watcher, contextual apply/recur, and idempotent
applied-frame accumulation. It does not yet have the exact `def-recursive`
source syntax, arbitrary nested map/vector writers, or compile-2 lowering.

For all network-valued expansion paths:

- It is intended for declaration closures that add deterministic named-network
  facts or deterministic topology.
- If the closure creates fresh random ids on every activation, repeated
  expansion will not be idempotent. Frame declarations therefore use stable ids
  derived from `[frame slot]`.

This is the direction for frame topology as data:

```text
frame request + frame-template closure + prior declaration network
-> closure/p:apply-network
-> larger declaration network
-> outer scheduler decides when to evaluate declared propagators
```

That keeps "what topology should exist" separate from "run this topology now."

#### Conditional Network Expansion

`closure/p:when-network` is the declaration-level conditional counterpart to
`closure/p:apply-network`:

```clojure
(closure/p:when-network condition expander accumulator out)
```

Semantics:

- `true` applies the closure-valued expander to the accumulator network and
  emits the expanded network.
- `false` emits the accumulator network unchanged.
- `nothing` waits and emits no message.
- contradiction, non-network accumulator values, and non-network expansion
  results emit contradiction.

This is different from `prop/switch`: `prop/switch` gates a value after topology
may already exist, while `p:when-network` gates declaration expansion itself.
It still does not mutate the live outer graph. The result is a network value,
and evaluation remains a later explicit scheduler run.

The old compile DSL can thread it like other installers:

```clojure
(let-cell [ready expander template expanded]
  (seed ready true)
  (seed expander expander-value)
  (seed template template-value)
  (closure/p:when-network ready expander template expanded))
```

#### Primitive Basis For Derived Reducers

2026-06-11 update: the recursive compound expansion sketch should not use a
host-side `compound-shape?` branch, an external `enabled?` cell, or a reducer
primitive. The first reducer sketch used a finite cursor experiment built from
smaller primitives:

```clojure
prop/nothing?
prop/when
cursor/p:car        ;; now deprecated
cursor/p:cdr        ;; now deprecated
obj/p:slot-cursor   ;; now deprecated
closure/p:bind-network
closure/p:apply-network
closure/p:when-apply-network
```

That cursor path has now been moved under `propagators.deprecated.cursor`.
It remains useful as a record of the finite-cursor reduction experiment, but it
is not the linked-list reducer path and should not be used as evidence for
recursive nested compound traversal.

The control shape is two one-way exits:

```clojure
(prop/nothing? cursor done?)
(prop/not done? more?)

;; completion branch
(prop/when result done? out)

;; expansion branch
(closure/p:when-apply-network more? next-step result expanded-network)
```

`p:when-network` remains useful when false should pass the accumulator through.
Derived reducers should use `p:when-apply-network`, because the inactive branch
must emit no network value.

Concise nested compound reducer sketch:

```clojure
;; acc holds declaration-network data, not a live graph mutation.
(let-cell [source slots acc step out]
  (seed acc net/empty-net)
  (seed source source-object)

  ;; Project the compound object to a pure cursor. Empty means done.
  (obj/p:slot-cursor source slots)

  ;; The step expander reads the current cursor item through
  ;; closure/current-item, declares one slot's topology, and returns a larger
  ;; declaration network.
  (seed step
        (decl/closure
         (decl/compose
          (decl/slot-accessor
           (fn [item] (stable-cell-id [:source-slot (:path item)]))
           :declared/source-slot-props)
          (decl/record :declared/slot-step :declared))))

  ;; Derived, not primitive: internally this declares the two-exit reducer frame
  ;; from cursor access, nothing?, when, bind-network, and when-apply-network.
  (decl/reduce-cursor slots step acc out))
```

`stable-cell-id` above stands for the existing semantic-id discipline: repeated
expansion for the same slot path should name the same declaration cells.

Read it as a loop over declaration data:

```text
slot cursor + step expander + acc network value
-> if cursor is nothing, send acc to out
-> otherwise bind car(cursor) as current item
-> apply the bound step to acc
-> recurse on cdr(cursor)
-> caller later evaluates declared prop ids from the emitted network
```

#### Linked-list reducer first

Later on 2026-06-11, the reducer plan was narrowed again: do not use
`obj/p:slot-cursor` as the first reducer target. Looping through compound slots
and reducing a linked list are two different experiments. The finite cursor
namespace and cursor-derived forms are therefore deprecated. The next reducer
experiment should reduce the compound-object linked-list shape first, and only
then generalize the same control basis over other data structures.

The linked-list reducer should use the current compound-object sequence
accessors:

```clojure
obj/p:car
obj/p:cdr
obj/p:cons
```

The terminator is `value/nothing`. The current frame tests the current list
cell, and only the non-empty branch declares `obj/p:car` / `obj/p:cdr`
topology:

```clojure
(prop/nothing? list done?)
(prop/not done? more?)

;; completion branch
(prop/when acc done? out)

;; expansion branch
(closure/p:when-apply-network more? next-frame acc branch-network)
```

The `next-frame` expander declares exactly one list step:

```clojure
(obj/p:car item list)
(obj/p:cdr rest list)
(closure/p:bind-network step item bound-step)
(closure/p:apply-network bound-step acc next-acc)
(decl/reduce-list rest step next-acc out)
```

This keeps nil-list termination from creating accessor topology. It also keeps
the reducer derived: there is no reducer primitive, no map primitive, and no
external `enabled?` cell. Branch choice remains ordinary propagator dataflow.

For trampoline-style expansion, each frame may emit a tiny continuation record
as compound-object data:

```clojure
{:state :continue
 :next-network-id branch-network}

{:state :done
 :out-id out}
```

Those records are selected through `prop/when`; host code should only read the
selected continuation to know whether to tail-call the next emitted network or
return the finished network. Any frame handle used by the trampoline is an
execution entry point, not semantic branch control.

## Appendix: Detailed Experiment Notes

### Fibonacci Proof

`test/propagators/recursive_compound_test.clj` implements Fibonacci as a
test-local recursive closure.

The closure step:

1. reads concrete numeric `n`
2. writes contradiction for negative or non-integer input
3. installs and runs a switch-guarded base branch first
4. stops if the base branch wrote the output
5. otherwise lazily installs recursive branches for `n - 1` and `n - 2`
6. runs both recursive branches
7. installs `prop/+` only after both child outputs are usable

The base branch is intentionally lazy:

```text
n -- prop/switch(base?) --> out
```

If `out` is no longer `the-nothing` after that branch runs, no recursive
topology is built for that activation.

### Wrapped In A Normal Compound

The tests also wrap the recursive propagator inside a normal compound closure:

```clojure
(closure/p:apply-closure wrapper n out)
```

The wrapper installs `recursive/p:recursive-compound` in its own closure body.
This proves the recursive helper can be used as a one-time recursive compound
inside the existing runtime compound mechanism.

### Compound Object Experiment

`propagators/datastructures/compound_object/map.clj` adds experimental
higher-order map installers over compound-object public slots:

```clojure
(obj/p:map-slots-with-recursive-closure closure source out)
(obj/p:map-slots-with-recursive-accumulator closure acc source out)
```

The important rule from the nested experiment is: do not recurse by turning a
nested compound value back into a native Clojure map/vector and rebuilding it at
the end. Nested traversal is now modeled as accessor topology:

```clojure
source --(p:slot k)--> child-source
child-source --recursive map/reduce--> child-out
child-out --(p:slot k)--> out
```

That is the same mechanism used by nested `p:car` / `p:cdr`. It means a later
write to a leaf slot, for example `top/:second/:value`, propagates through the
middle object back to `top` by normal slot synchronization.

There are now two map implementations:

- Dynamic map propagator: `obj/p:map-slots-with-recursive-*` reads the source
  value during activation and runs each leaf recursive application internally.
  This was useful for proving semantics, but it couples declaration and
  evaluation.
- Declared topology builder:
  `obj/install-declared-nested-recursive-map-with-*` traverses the source shape
  during construction, builds the source object and the output object with
  recursive `p:slot` accessors, installs one recursive propagator per leaf,
  returns `:prop-ids`, and does not run the network. The caller runs the
  returned propagators from the outside. This is the stricter
  declaration/evaluation split.
- Accessor-recursive list map:
  `obj/p:accessor-recursive-map` walks live `p:cons` topology. Scalar cars
  become recursive leaves, cars that already expose `p:car` / `p:cdr` topology
  become nested accessor maps, cdrs continue the list traversal, and terminal
  cdrs install a lazy GUR frame runner for later slot installation.

The test input:

```clojure
{:left [0 1 2]
 :right {:a 3
         :b [4 5]}}
```

maps through Fibonacci to:

```clojure
{:left [0 1 1]
 :right {:a 2
         :b [3 5]}}
```

Both accumulation designs retain frames including `[:fib 5]`, and both are
idempotent under repeated named-network joins.

Nested reduce has two test paths:

```clojure
;; direct recursive activation over accessor-built slots
{:left [0 1 2]
 :right {:a 3
         :b [4 5]
         :empty []}}
;; => 15

;; network-valued expansion emits accessor topology, then the caller runs it
;; => 15
```

The result is mixed:

- Direct recursive activation can reduce nested numeric leaves through slot
  accessors.
- Direct recursive activation can map scalar leaves and first-level vector
  slots, but it is not robust for a nested map that contains vector children
  under a sibling; those nested vector output slots currently collapse to a
  localized contradiction.
- Network-valued expansion handles both nested map and nested reduce for the
  same input because it declares the whole accessor topology first and evaluates
  it afterward.

A public recursive reducer installer is still future work; it should reuse the
same accessor/accumulator pattern rather than introduce a separate recursion
data structure.

### Existing Reducer Cell

`obj/p:reduce` is already a useful compound-object reducer:

```clojure
(obj/p:reduce source merge-net init out)
```

It emits a `reducer-subnet` value whose strongest value folds usable public
source slots through a merge network with dict keys `:acc`, `:update`, and
`:out`.

The reducer is shallow today. It normalizes the source value and folds immediate
public slots only:

```clojure
{:a 1
 :nested {:b 2
          :c [3 4]}}
;; reducer output:
#{[:a 1]
  [:nested {:b 2 :c [3 4]}]}
```

It does not recursively enter `:nested`, `:b`, `:c`, or vector indexes by
itself. The new test-only helper composes repeated shallow reductions over
nested values and gets:

```clojure
#{[[:a] 1]
  [[:nested :b] 2]
  [[:nested :c 0] 3]
  [[:nested :c 1] 4]}
```

That proves the reducer can be reused as a building block for nested reduction,
but a public propagator-native nested reducer still needs explicit traversal
topology or bounded iteration.

### Benchmark Snapshot

Local benchmark on 2026-06-09, source:

```clojure
{:left [0 1 2 3 4]
 :right {:a 5
         :b [6 7]}}
```

Each row was warmed up twice, then run 8 times. All variants produced 8 retained
Fibonacci frames and a mapped leaf sum of 33. The declared variants mapped 8
leaf values and ran only through the outer scheduler.

| Implementation | Accumulation | Total ms | Avg ms/run |
| --- | --- | ---: | ---: |
| Dynamic map propagator | Self-refining closure | 734.57 | 91.82 |
| Dynamic map propagator | Explicit accumulator | 421.96 | 52.75 |
| Declared topology | Self-refining closure | 373.33 | 46.67 |
| Declared topology | Explicit accumulator | 376.69 | 47.09 |

This is a small, local measurement, not a statistically rigorous benchmark. It
does show the expected shape: the declared-topology implementation avoids
per-leaf internal runs and lets the scheduler settle the whole graph in one
network evaluation. The two declared accumulation styles are close on this small
input because both share the same leaf topology and deterministic frame
fragments.

### Four Approach Assessment

| Approach | Performance | Expressiveness | Conciseness | Practicality | Robustness |
| --- | --- | --- | --- | --- | --- |
| Direct recursive activation | Medium. Runs activation-local inner networks and re-enters the scheduler during expansion. | Good for tree recursion and immediate computation. | Good; `p:recursive-compound` is a small call. | Works for Fibonacci and nested reduce. | Not robust for mixed nested map output yet; nested vector children under a map sibling can contradict. |
| Network-valued declaration / `apply-network` | Medium to good. It avoids running during expansion, but still needs a later evaluator. | Best for semantic topology and named declarations. | Medium; needs closure plus network value. | Experimental but clean. | Stronger for nested map/reduce because complete accessor topology is declared before evaluation. |
| Existing reducer cell | Good for shallow folds. It folds immediate public slots through one reducer-subnet value. | Good for top-level compound slots, not nested traversal by itself. | Good; one `obj/p:reduce` call plus merge-net. | Works now. | Strong for shallow data; nested behavior is explicit and tested. |
| Bounded iteration | Expected best for linear/tail recursion because topology is fixed once. | Good for loops, scans, and reductions; weaker for tree recursion unless reformulated. | Medium; needs state/step/done/project closures. | Design only for now. | Strong if per-step ids are stable and max steps are explicit. |

Default choice: use network-valued declaration / `closure/p:apply-network` for
new recursive compound-object work. Keep direct recursive activation for small
immediate recursive computations and backward compatibility.

Evidence:

- Direct recursive activation calls `boundary/run-internal-network` during
  recursive activation. The nested reduce accessor experiment passes; the
  direct nested map experiment records a localized contradiction for nested
  vector outputs under `:right`.
- Dynamic nested map uses per-leaf temporary network runs; declared nested map
  now returns `:prop-ids` for source accessors, recursive leaf propagators, and
  output accessors, then lets the caller run the graph.
- `closure/p:apply-network` emits expanded network data and does not run it.
  The experiment uses it to emit both nested map and nested sum topology.
- `obj/p:reduce` folds immediate `public-slot-keys`; nested data remains an
  ordinary slot value unless another reducer/traversal step is explicitly
  composed.
- Bounded iteration is not implemented yet, so its performance entry is a design
  expectation, not a measured result.

### Bounded Iteration Direction

Bounded iteration is the TCO-inspired path for cheap linear recursion:

```clojure
(iteration/p:bounded-iterate state-closure
                             step-closure
                             done-closure
                             project-closure
                             init-state
                             max-steps
                             out)
```

The state closure declares deterministic cells for step `i`, the step closure
wires `state_i -> state_(i+1)`, the done closure produces `done_i`, and the
project closure exposes a candidate output guarded by `prop/switch`. The whole
loop is a fixed graph of `max-steps` copies, so it avoids recursive runtime
expansion and keeps intermediate execution cells collectable when the loop
network is not retained.

### Current Limits

- V1 is concrete-input only; it does not implement relational Fibonacci or
  backward propagation from output to input.
- Recursive expansion is activation-local and returns boundary messages only;
  it does not edit the outer graph during `eval-propagator`.
- There is no memoization. Fibonacci intentionally builds a recursive tree as a
  proof of recursive network expansion, not as an efficient numeric algorithm.
- Recursive steps should avoid installing primitive arithmetic over unusable or
  contradiction child outputs. The Fibonacci test checks child outputs before
  installing `prop/+`.
- The dynamic compound-object map helper is experimental and host-recursive over
  nested maps/vectors. It demonstrates reuse of named-network accumulation, but
  the declared-topology builder is the better fit for the runtime invariant that
  declaration and evaluation stay separate.
- Direct nested map inside one recursive activation is not robust enough for the
  mixed map/vector case. The current robust path is network-valued declaration
  first, then evaluation.
- Nested reduce is tested both as direct accessor recursion and as
  network-valued accessor expansion. A public recursive reducer should be built
  as another accumulator-based higher-order propagator.
- `obj/p:reduce` is shallow; nested reduction currently requires explicit
  composition of repeated shallow reducers.

### Nested Object Recursion: Why Network Accumulation Became Default

Experiment date: 2026-06-10

Goal:
- verify whether recursion over nested objects should be implemented as
  immediate recursive activation or as declaration network accumulation via
  `closure/p:apply-network`.

Setup:
- same nested source used across approaches:
  `{:left [0 1 2] :right {:a 3 :b [4 5] :empty []}}`
- two mapped operators: recursive Fibonacci and a recursive sum.
- two recursion styles:
  - direct recursive activation (`recursive-closure` running an inner network in
    activation path)
  - network-valued declaration (`closure/p:apply-network` producing a network
    that is run by the caller later)

Findings:
- direct activation maps scalar slots and first-level vectors, and reduces nested
  numeric leaves correctly;
- direct activation also sets a localized contradiction on `[:right :b]` and
  `[:right :empty]` in this mixed nested map/vector case,
  showing incomplete robustness when nested vectors appear under nested map
  branches.
- network-valued declaration produces full nested map and nested reduce topology
  first, then evaluation of that topology succeeds end-to-end with consistent
  leaf counts and values for both map and reduce.

Conclusion used for direction:
- recursive network accumulation is the default for nested object mapping and
  reduce patterns because declaration and evaluation stay separated and the
  complete accessor topology is established before propagation runs.
- direct recursive activation remains supported as an immediate path for
  simpler recursive shapes and compatibility tests, but is not the default for
  nested object semantics.
- Bounded iteration is a design direction and has no public implementation yet.

### Historical Test Inventory

`test/propagators/recursive_compound_test.clj` covers:

- `fib(0)`, `fib(1)`, `fib(2)`, `fib(5)`, and `fib(10)`
- DSL installation of `recursive/p:recursive-compound`
- lazy base branch behavior
- negative input contradiction
- unusable input leaving output empty
- max-depth contradiction
- recursive propagation wrapped inside a normal compound closure
- compatible closure merge over retained named networks
- self-refining closure accumulation
- explicit named-network accumulator accumulation
- monotone subsumption and idempotence of retained frame declarations
- recursive map over compound vectors
- recursive map over nested map/vector compound objects
- declared recursive map topology over nested map/vector compound objects using
  source and output slot accessors
- direct recursive nested reduce over accessor-built compound objects
- network-valued expansion for nested map and nested reduce
- conditional network expansion with `closure/p:when-network`, including old
  compile DSL wiring, lazy recursive frame expansion, and guarded nested map
  topology
- the current direct nested-map limitation for vector children inside a nested
  map

`test/propagators/compound_object_test.clj` covers:

- shallow `obj/p:reduce` over maps and vectors
- nested compound values remaining immediate reducer slot values
- test-only composition of repeated shallow reducers to fold nested leaves
- accessor-built nested slot updates propagating from `:second/:value` back to
  the top object

## Appendix: Chronological Experiment Log

Total executed recursion experiments described here: `9`

Separate from that total:

- `1` auxiliary non-recursive comparison reused repeated shallow `obj/p:reduce`
  composition for nested folding.
- `1` bounded-iteration follow-up exists only as a design direction and was not
  executed or benchmarked.

Benchmark method for the benchmarked entries below:

- entries `1` through `6` were measured locally on `2026-06-10`
- warmed up twice, then timed for `8` end-to-end runs
- Fibonacci timings use `fib(10)`
- map benchmarks use `{:left [0 1 2 3 4] :right {:a 5 :b [6 7]}}`
- nested stress benchmarks use
  `{:left [0 1 2] :right {:a 3 :b [4 5] :empty []}}`
- later experiments record test evidence but do not yet have benchmark numbers

1. `2026-06-04` Plain direct recursive activation.
   Assumption: recursion can expand and run an activation-local inner network,
   with concrete input, no retained semantic frame history, and no mutation of
   the outer graph during `eval-propagator`.
   Outcome: `recursive/p:recursive-compound` computes Fibonacci, supports lazy
   base branching, depth guards, compile DSL installation, and wrapping inside a
   normal compound closure.
   Benchmark: `fib(10)` averaged `19.76 ms/run` (`14.40` min, `26.32` max).

2. `2026-06-09` Retained semantic frame accumulation.
   Assumption: recursive progress can be represented as monotone named-network
   frame facts with deterministic frame ids, merged by `named/join`.
   Outcome: both self-refining closure and explicit accumulator preserve
   semantic frames such as `[:fib 5]` and are idempotent by subsumption; the
   explicit accumulator is materially cheaper on the same Fibonacci workload.
   Benchmark: self-refining `fib(10)` averaged `212.99 ms/run`; explicit
   accumulator `fib(10)` averaged `137.71 ms/run`.

3. `2026-06-09` Dynamic higher-order recursive map over compound objects.
   Assumption: a higher-order recursive map can inspect compound slots during
   activation and run one recursive leaf application immediately for each slot.
   Outcome: recursive map works over compound-object structure and retains frame
   data, but declaration and evaluation are coupled and the implementation pays
   for activation-local per-leaf runs.
   Benchmark: dynamic self-refining map averaged `66.06 ms/run`; dynamic
   accumulator map averaged `47.21 ms/run`; both produced mapped leaf sum `33`.

4. `2026-06-10` Declared accessor topology for nested recursive map.
   Assumption: nested traversal should be declared up front with `p:slot`
   accessors, with source and output shape both represented as graph topology
   instead of rebuilding native Clojure maps/vectors after recursion.
   Outcome: the declared path keeps declaration separate from evaluation and
   preserves accessor semantics for later slot updates; it is cleaner than the
   dynamic path and stays aligned with the scheduler contract.
   Benchmark: declared self-refining map averaged `55.94 ms/run`; declared
   accumulator map averaged `56.61 ms/run`; both mapped `8` leaves with total
   mapped leaf sum `33`.

5. `2026-06-10` Direct nested recursion stress test.
   Assumption: one direct recursive activation should be able to recurse through
   nested mixed map/vector structure and also assemble correct nested output in
   the same activation style.
   Outcome: nested reduce succeeds and returns `15`, but nested map is not
   robust for mixed nested map/vector branches and localizes contradiction at
   `[:right :b]` and `[:right :empty]`.
   Benchmark: direct nested map averaged `20.74 ms/run`; direct nested sum
   averaged `10.37 ms/run`.

6. `2026-06-10` Declaration-first network accumulation via
   `closure/p:apply-network`.
   Assumption: recursion over nested objects should accumulate declaration
   topology first, then run the expanded network after the full accessor graph
   exists.
   Outcome: nested map and nested reduce both succeed on the same mixed nested
   source because the complete accessor topology is declared before evaluation;
   current structural idempotence is still incomplete because repeated expansion
   creates fresh topology ids.
   Benchmark: network-valued map averaged `23.80 ms/run`; network-valued sum
   averaged `8.61 ms/run`; both operated on `6` nested leaves.

7. `2026-06-11` Conditional declaration expansion via
   `closure/p:when-network`.
   Assumption: recursive and nested-object declaration should be guardable
   before expansion, rather than using `prop/switch` after branch topology has
   already been declared.
   Outcome: `p:when-network` emits network values only. `true` expands,
   `false` passes the accumulator network through, `nothing` waits, and invalid
   network shapes contradict. The old compile DSL can install the combinator,
   and tests cover lazy child-frame declaration plus guarded nested compound map
   expansion.
   Boundary: this remains declaration lazy expansion, not lazy evaluation of
   already-declared topology.

8. `2026-06-11` Primitive basis for linked-list reducers.
   Assumption: reducer control should first be proven on compound-object linked
   lists, not on `obj/p:slot-cursor` traversal of arbitrary compound slots.
   Outcome: the planned reducer shape is `nothing?`/`not` plus two one-way
   exits: `prop/when` sends the accumulator to `out` when the list is done, and
   `closure/p:when-apply-network` expands one `obj/p:car` / `obj/p:cdr` frame
   when the list is non-empty. The reducer remains derived from propagator
   combinators; `map`, `reduce`, and generic slot traversal are explicitly left
   for later generalization.
   Boundary: trampoline continuation records may be compound-object data, but
   they are execution entry handles only. Branch semantics stay in propagator
   gates.

9. `2026-06-17` Lexical sub-env GUR with contextual apply/recur.
   Assumption: routed GUR's owner/child split can be generalized as parent dict
   dispatch into named child networks. `eval-cell*` should seed child-local
   messages and evaluate only the owner cell, while recursive expansion is
   owned by contextual `apply` / `recur` propagators inside the child network.
   Outcome: the experiment adds kernel-level `eval-cell*` routing, sub-env
   registration at owner-cell merge time, owner-cell frame watchers,
   idempotent applied-frame accumulation, a split implementation namespace,
   compile-DSL Fibonacci topology, accessor-only flat `map-list`, nested
   `map-list` composition, a bidirectional late nested `cdr` test where
   parent-to-child dispatch uses lifted lexical refs and child-to-parent output
   is asserted only through the recursive propagator's parent-visible output,
   a constructed-accessor lazy-extension probe that routes parent slot updates
   through scoped child accessor addresses without materializing the source cdr
   slot, and a nested constructed-accessor probe that forwards nested child
   slot interests transitively to the original inner collection through the
   bidirectional accessor chain. A follow-up robustness pass adds derived
   `reduce-list` and `filter-list` closures built from the same primitive
   propagators. `reduce-list` sums `[0..5]` to `15`,
   `filter-list` keeps `[0 2 4]`, and a constructed lazy-cdr reducer probe
   updates from `nothing` to `3` only after the terminal cdr becomes known.
   A lazy-cdr filter probe updates from `[0 ?]` to `[0 2]`. A prefix reduce
   variant with a `sum-present-step` operator first emits provisional `1`, then
   contradicts when the later tail requires `3`, exposing the plain-scalar
   monotonicity boundary. A scalar robustness pass adds factorial and integer
   square root by binary search; the sqrt probe passes floor results from `0`
   through `81` and documents that recursive `cond` branches must be explicitly
   gated because declaration builds all branch topology.
   Benchmark snapshot: `fib(6)` `34.285 ms`, flat map `45.713 ms`, nested map
   `21.069 ms`, reduce `[0..19]` `32.215 ms`, filter `[0..19]` `51.139 ms`
   over `20` timed runs after `5` warmups.
   Boundary: this is not compile-2 lowering, not the final `def-recursive`
   source syntax, not arbitrary nested map/vector writer semantics, and not a
   redesign of existing nested `p:car` / `p:cdr` accessor behavior.

Auxiliary comparison: repeated shallow reducer composition.
Assumption: nested reduction can be approximated by explicitly composing several
shallow `obj/p:reduce` passes rather than adding recursive reducer topology.
Outcome: this works as a comparison point and produces the nested leaf set, but
it is not itself the recursion mechanism and does not replace accessor-based
recursive traversal.
Benchmark: averaged `3.15 ms/run` on
`{:a 1 :nested {:b 2 :c [3 4]}}`.

## Appendix: Four Strategy Propagation Graphs

These diagrams are generated from the real outer propagation networks installed
by each strategy. They show the runtime contract each strategy exposes to the
ordinary scheduler.

Plain direct recursive activation:

```text
          +---------+        
          | closure |·+      
          +---------+ |      
                      |      
                      |      
                      |      
                      | +---+
                      | | n |
                      v +---+
                      |   |  
               +·····<+···+  
               |      |      
+-----+    +-------+  |      
| out |    | recur |··+      
+-----+    +-------+         
   |           |             
   +·····<·····+             
```

Assumption represented: the closure and argument cells are inputs; only the
output cell receives the activation result.

Self-refining direct recursion:

```text
                           +---+
                           | n |
                           +---+
                             |  
                   +····<····+  
                   |            
+---------+   +--------+        
| closure |·>·| refine |        
+---------+   +--------+        
     |             |            
     +······<······v            
                   |            
               +-----+          
               | out |          
               +-----+          
```

Assumption represented: the closure cell is both input and output, so retained
frame declarations are merged back into the closure value.

Explicit-accumulator direct recursion:

```text
      +---+          +-----+ 
      | n |          | out | 
      +---+          +-----+ 
        |               |    
        +·······>·······^    
                        |    
                    +-------+
                    | accum |
                    +-------+
                        |    
        +·······>·······^    
        |               |    
   +---------+       +-----+ 
   | closure |       | acc | 
   +---------+       +-----+ 
```

Assumption represented: the closure remains stable; accumulated frame
declarations flow through a separate `acc` cell.

Declaration-first network accumulation:

```text
               +----------+               
               | expander |·+             
               +----------+ |             
                            |             
                            |             
                            |             
                            | +----------+
                            | | template |
                            v +----------+
                            |       |     
                     +······<·······+     
                     |      |             
+----------+    +-------+   |             
| expanded |    | apply |···+             
+----------+    +-------+                 
      |              |                    
      +······<·······+                    
```

Assumption represented: a closure-valued expander and a template network are
inputs; the output is a larger network value that is evaluated separately.

## Appendix: Network Graph Snapshots

These ASCII graphs were generated with `graph.vijual` from real network values,
not hand-simulated sketches.

Fibonacci uses the retained accumulator frame network from
`run-accumulating-fib 5`. The visualized nodes are the frame facts accumulated
in that named network; edges are extracted from `[:child ...]` frame facts.

```text
-----------+   +-----------+   +-----------+  
| fib 0 = 0 |   | fib 1 = 1 |   | fib 5 = 5 |·+
+-----------+   +-----------+   +-----------+ |
      |               |               |       |
      |               +········<······+       |
      |               ^               v       |
      +······<········+               |       |
                      |               |       |
                +-----------+   +-----------+ |
                | fib 2 = 1 |   | fib 3 = 2 | v
                +-----------+   +-----------+ |
                      |               |       |
                      +········<······+       |
                      |               ^       |
                      +·······<·······+       |
                                      |       |
                                +-----------+ |
                                | fib 4 = 3 |·+
                                +-----------+  
```

The cons-cell snapshot uses an installed topology network built from
`obj/p:cons`, `obj/p:car`, and `obj/p:cdr`. It is intentionally a raw topology
view: `p:slot`-style accessors synchronize both directions, so the visual graph
contains paired-looking wiring rather than a single semantic arrow per slot.

```text
                                +------+
                                | tail |
                                +------+
                                    |   
                                    ^   
                                    |   
+----------+                    +-----+ 
| read car |·+·+                | cdr | 
+----------+ | |                +-----+ 
      |      | |                    |   
      +······+·+····<···············+   
      ^      | |                    |   
      +······+·+······>·············+   
      |      | |                        
  +------+   | | +----------+   +-----+ 
  | cons |···>·+·| read cdr |   | car | 
  +------+   v ^ +----------+   +-----+ 
      |      | |       |            |   
      +······+<+·······+            |   
      |      | |       |            |   
      +······+·+·····<·^············^   
      |      | |       |            |   
      +······+·+·····>·+············+   
             | |       |            |   
+---------+  | | +---------+    +------+
| car out |··+·+ | cdr out |    | head |
+---------+      +---------+    +------+
```

## Appendix: Complete Raw Runtime Networks

The diagrams below are complete `net-graph` renderings from actual runtime
network values. They are intentionally noisier than the semantic diagrams above.

For Fibonacci, the recursive inner activation networks are not retained after
activation. What is retained is the closure/accumulator frame network. That
network is a named network of fact cells, so the complete raw graph has cells
but no propagator edges. The self-refining closure and explicit accumulator
produce the same retained fact graph; they differ in where this network is
stored (`closure` cell versus `acc` cell).

Outer runtime topology for self-refining `fib(5)`:

```text
                          +---+
                          | 5 |
                          +---+
                            |  
                  +····<····+  
                  |            
+---------+   +-------+        
| closure |·>·| recur |        
+---------+   +-------+        
     |            |            
     +······<·····v            
                  |            
              +-------+        
              | out=5 |        
              +-------+        
```

Outer runtime topology for explicit-accumulator `fib(5)`:

```text
              +-----+                 +---+  
              | acc |···+             | 5 |·+
              +-----+   |             +---+ |
                 |      |                   |
                 +······+·····<·····+<······+
                        v     |     |        
                        |     |     |        
                        |     |     |        
                        |     |     |        
+-------+   +---------+ | +-------+ |        
| out=5 |   | closure |·>·| recur |·+        
+-------+   +---------+   +-------+          
    |                         |              
    +············<············+              
```

Internal activation network for self-refining `fib(5)`:

```text
+-----+                +------+                  +---+   
| out |··+             | prop |                  | 3 |   
+-----+  |             +------+                  +---+   
         |                 |                       |     
         |                 +······>····+           |     
         |                 ^           |           |     
         |                 +···········<···········+     
         |                 |           |                 
         | +------+    +------+     +---+                
         ^ | prop |·>··| self |     | 2 |···+            
         | +------+    +------+     +---+   |            
         |     |           |                |            
         |     +·····<·····+                |            
         |     ^                            |            
         |     +····>······+                |            
         |     |           |                v            
+------+ |  +---+       +---+               |            
| prop |·+  | 4 |       | 3 |               |            
+------+    +---+       +---+               |            
    |                      |                |            
    ^                      +·····>·····+    |            
    |                                  |    |            
+------+   +-----+     +------+    +------+ | +---------+
| out* |   | n=5 |··>··| prop |    | prop |·+ | closure |
+------+   +-----+     +------+    +------+   +---------+
    |                      |           |                 
    +················<·····v···········+                 
                           |                             
                      +--------+                         
                      | n-in=5 |                         
                      +--------+                         
```

Internal activation network for explicit-accumulator `fib(5)`:

```text
 +-----+     +-----+                            +------+   +---------+
 | n=5 |···+ | out |                            | prop |   | closure |
 +-----+   | +-----+                            +------+   +---------+
           |     |                                  |                 
           |     +···············>··················+                 
           |     |                                  |                 
           |     +················<·················+                 
           |     |                                                    
+--------+ | +------+     +------+                                    
| n-in=5 | | | out* |     | prop |                                    
+--------+ | +------+     +------+                                    
     |     |     |            |                                       
     |     |     |            +·····················<····+            
     |     |     ^            v                          |            
     +·····v·····+····<·+     |                          |            
           |     |      |     |                          |            
           | +------+   | +-----+    +------+    +---+   |            
           | | prop |·+ | | acc |    | prop |·>··| 3 |   |            
           | +------+ | | +-----+    +------+ |  +---+   |            
           |     |    | |                |    |     |    |            
           |     +····+·+·········<······+····+·····+    |            
           |          | |                |    |          |            
           |          | |     +···>······+    |          |            
           |          | |     |          |    |          |            
           |          | |     |          +···<+·····+    |            
           |          | |     |          |    |     |    |            
           |          | |     |          +·>··+·····+    |            
           |          | |     |               |     |    |            
           | +------+ | | +------+   +------+ | +------+ |            
           +·| prop |·^·+ | self |·>·| prop |·^·| acc* |·+            
             +------+ |   +------+   +------+ | +------+              
                      |                  |    |     |                 
                      |                  +····+·····+·<·········+     
                      |                  |    |     |           |     
                      |                  +····<·····+           |     
                      |                  |    |                 |     
                 +····+········<·········+    |                 |     
                 |    |                       |                 |     
              +---+   |               +---+   |               +---+   
              | 2 |···+               | 4 |···+               | 3 |   
              +---+                   +---+                   +---+   
```

These internal activation graphs are complete network renderings captured from
the real `run-recursive-frame` path. They are not retained by normal execution;
normal execution keeps only the outer messages and the retained frame network.
The boxes labeled `prop` are real propagator nodes, but the runtime propagator
record currently does not retain a human-readable operator name.

Complete retained frame network for `fib(5)`:

```text
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+   +------------+   +------------+                                                   
                 |     f2     |   |     f3     |   |     f4     |   +------------+   +------------+                 
                 |  [:child   |   |  [:status  |   |  :combine  |   |     f5     |   |     f0     |                 
                 |  0] [:fib  |   | :expanded] |   |     :+     |   | :output 5  |   | :output 0  |                 
                 |     1]     |   |    true    |   +------------+   +------------+   +------------+                 
                 +------------+   +------------+                                                                    
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+                    +------------+   +------------+   +------------+   +------------+
                 |     f3     |   +------------+   |     f3     |   |     f5     |   |     f1     |   |     f2     |
                 |  [:child   |   | f5 :input  |   |  [:child   |   |  [:child   |   |  [:status  |   |  [:status  |
                 |  0] [:fib  |   |     5      |   |  1] [:fib  |   |  0] [:fib  |   |   :done]   |   | :expanded] |
                 |     2]     |   +------------+   |     1]     |   |     4]     |   |    true    |   |    true    |
                 +------------+                    +------------+   +------------+   +------------+   +------------+
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+                    +------------+   +------------+   +------------+   +------------+
+------------+   |     f2     |   +------------+   |     f5     |   |     f4     |   |     f5     |   |     f4     |
| f0 :input  |   |  [:child   |   | f3 :input  |   |  [:child   |   |  [:status  |   |  [:status  |   |  [:child   |
|     0      |   |  1] [:fib  |   |     3      |   |  1] [:fib  |   |   :done]   |   | :expanded] |   |  1] [:fib  |
+------------+   |     0]     |   +------------+   |     3]     |   |    true    |   |    true    |   |     2]     |
                 +------------+                    +------------+   +------------+   +------------+   +------------+
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+                                                                       +------------+
                 |     f0     |   +------------+   +------------+   +------------+   +------------+   |     f4     |
                 |  [:status  |   |     f4     |   | f1 :input  |   |     f1     |   | f2 :input  |   |  [:child   |
                 |   :done]   |   | :output 3  |   |     1      |   | :output 1  |   |     2      |   |  0] [:fib  |
                 |    true    |   +------------+   +------------+   +------------+   +------------+   |     3]     |
                 +------------+                                                                       +------------+
                                                                                                                    
                                                                                                                    
                                                                                                                    
                                  +------------+   +------------+   +------------+   +------------+   +------------+
                 +------------+   |     f3     |   |     f2     |   |     f5     |   |     f3     |   |     f5     |
                 |     f2     |   |  :combine  |   |  [:status  |   |  [:status  |   |  [:status  |   |  :combine  |
                 | :output 1  |   |     :+     |   |   :done]   |   |   :done]   |   |   :done]   |   |     :+     |
                 +------------+   +------------+   |    true    |   |    true    |   |    true    |   +------------+
                                                   +------------+   +------------+   +------------+                 
                                                                                                                    
                                                                                                                    
                                                                                                                    
                                  +------------+   +------------+                                                   
                                  |     f2     |   |     f4     |   +------------+   +------------+                 
                                  |  :combine  |   |  [:status  |   |     f3     |   | f4 :input  |                 
                                  |     :+     |   | :expanded] |   | :output 2  |   |     4      |                 
                                  +------------+   |    true    |   +------------+   +------------+                 
                                                   +------------+                                                   
```

Complete runtime topology for self-refining nested HOP over `{:x [2]}`:

```text
               +-----+                         +---+               +-----+                     
           +···| fib |···+·+                   | 2 |··········+    | out |                     
           |   +-----+   | |                   +---+          |    +-----+                     
           |      |      | |                      |           |        |                       
           |      +······+·>······+               |           |        v                       
           |             | |      |               |           |        +··········<···········+
           |             | |      |               |           |        |                      |
           |             ^ v    +---+          +---+          |  +---------+                  |
           |             | |    | 1 |          | 1 |······+·+ |  | slot :x |                  |
           ^             | |    +---+          +---+      | | |  +---------+                  |
           |             | |      |                       | | |        |                      |
           |             | |      ^               +·······+<+·+········+                      |
           |             | |      |               |       | | |        |                      |
           |             | |      |               +·······+·+>+········+                      |
           |             | |      |               |       | | |                               |
  +---+    | +---------+ | | +--------+       +-----+     | | | +------------+                |
  | 2 |    | | closure | | | | slot 0 |       | net |     ^ v | |    slot    |                |
  +---+····+ +---------+·+·+ +--------+···>···+-----+·····>·+·+·|   :count   |·+·+            |
     |                            |               |       | | | +------------+ | |            |
     |                            |               |       | | |        |       | |            |
     |                            |               |       | | +>·······+·······+·+······+     |
     |                            |               |       | |          |       | |      |     |
     ^                            |               +·······+·<··········+       | |      |     |
     |                            |               |       | |                  | |      |     |
     |                            +·······<·······+       | |                  | |      |     |
     |                                                    | |                  | |      |     |
+--------+                     +-----+     +------------+ | |                  | | +--------+ |
| slot 0 |                     | net |     |    slot    | | |                  | | | slot 0 | |
+--------+                     +-----+     |   :count   |·+·+                  | | +--------+·+
     |                            |        +------------+                      | |      |      
     |                            |               |                            | |      |      
     |                            |               +·····················>······v·^······+      
     |                            |               |                            | |      |      
     |                            v               +···················<········+·+······v      
     |                            |                                            | |      |      
     +·············<··············+                                            | |      |      
     |                            |                                            | |      |      
     +·············>··············+                                            | |      |      
                                  |                                            | |      |      
               +-----+       +---------+    +---------+             +---+      | |  +-----+    
               | net |···>···| slot :x |    | slot :x |             | 1 |······+·+  | net |    
               +-----+       +---------+    +---------+             +---+           +-----+    
                  |               |               |                                     |      
                  |               |               +··················>··················+      
                  |               |               |                                     |      
                  |               |               +··················<··················+      
                  |               |               |                                            
                  +·······<·······+               |                                            
                  |                               |                                            
                  +···············<···············+                                            
                  |                               |                                            
                  +···············>···············+                                            
```

Complete runtime topology for explicit-accumulator nested HOP over `{:x [2]}`:

```text
                             +-----+       +---------+        +-----+                  
                             | net |···>···| slot :x |·+·+    | acc |                  
                             +-----+       +---------+ | |    +-----+                  
                                 |              |      | |        |                    
                                 |              +······<·+········+                    
                                 |              |      | |        |                    
                                 v              +······+·>········+                    
                                 |              |      | |                             
                                 +······<·······+      | |                             
                                 |              |      | |                             
             +---+          +--------+       +-----+   | |     +---+        +---------+
             | 2 |····>·····| slot 0 |       | fib |···+·>·····| 1 |        | closure |
             +---+          +--------+       +-----+   | |     +---+        +---------+
               |                 |              |      | |        |              |     
               |                 |              +······v·^·······<+··············+     
               |                 |              |      | |        |                    
               +········<········+              |      | |        |                    
               |                                |      | |        |                    
               +················>···············+      | |        |                    
                                                       | |        |                    
                                 +·················>···+·+········+                    
                                 |                     | |        |                    
                                 +···················<·+·+········+                    
                                 |                     | |                             
          +---------+       +--------+       +-----+   | |     +---+                   
          | slot :x |       | slot 0 |       | net |···+·+     | 1 |                   
          +---------+       +--------+       +-----+           +---+                   
               |                 |              |                 |                    
               +················>+··············+                 |                    
               |                 |              |                 |                    
               +················<+··············+                 |                    
               |                 ^                                ^                    
               +···············<·+··············+                 |                    
               |                 |              |                 |                    
               +·············>···+··············+                 |                    
                                 |              |                 |                    
+-----+                      +-----+         +-----+       +------------+              
| out |                      | net |         | net |       |    slot    |              
+-----+·+             +······+-----+·····+   +-----+···>···|   :count   |              
   |    |             |          |       |      |          +------------+              
   |    |             |          |       |      |                 |                    
   |    |             |          v       |      +········<········+                    
   |    |             |          |       |      v                                      
   +····+·········<···^·+        |       |      |                                      
        |             | |        |       |      |                                      
        v             | | +------------+ | +--------+                                  
        |             | | |    slot    | | | slot 0 |                                  
        |             | | |   :count   | | +--------+                                  
        |             | | +------------+ |      |                                      
        |             | |        |       |      ^                                      
        |      +······+·+········^··<····+      |                                      
        |      |      | |        |              |                                      
        | +---------+ | |     +---+           +---+                                    
        +·| slot :x |·+·+     | 1 |           | 2 |                                    
          +---------+         +---+           +---+                                    
```

## Appendix: Historical Conclusion Before Routed GUR

The executed evidence supports three decisions.

- Keep plain direct recursive activation for immediate tree-style recursive
  computation and compatibility.
- Use explicit-accumulator, declaration-first network accumulation as the
  default direction for recursive work over nested compound objects.
- Keep accessor topology as the representation of nested structure; do not
  rebuild nested recursive results by collapsing into native Clojure maps or
  vectors at the end.

The deciding experiment was the `2026-06-10` mixed nested map/vector stress
case: direct nested recursion remained fast enough but failed semantically on
`[:right :b]` and `[:right :empty]`, while declaration-first network
accumulation stayed correct with only modest end-to-end cost. That is the main
reason recursive network accumulation is now the preferred default.

## Appendix: Nested Accessor Dispatch Boundary Correction

2026-06-11 clarification, updated 2026-06-17: the older direct-activation
evidence should not be read as proof that general unbounded recursion over
nested compound data works merely by letting a recursive activation build
accessor topology inside an inner network. The newer lexical sub-env GUR path
does prove more: scoped slot accessor registration can route lazy parent slot
updates into child recursive frames for cons-list map, nested map, and reduce.

What has been shown:

- direct recursive activation works for Fibonacci and for a nested numeric
  reduce;
- direct recursive nested map is not robust for mixed nested map/vector output;
- declared network-valued expansion can produce nested map/reduce topology and
  run that topology later;
- lexical sub-env GUR can express Fibonacci, factorial, integer sqrt by binary
  search, map, nested map, reduce, and filter as contextual apply/recur
  topology;
- scoped slot registration can carry a constructed lazy cdr update from a
  parent accessor into the child frame and back to the parent-visible output;
- older declared nested-map tests still use materialized/legacy slot accessors
  in parts of the builder;
- the newer public `obj/p:slot`, `obj/p:car`, and `obj/p:cdr` path is
  demand-driven `p:network-slot` accessor topology.

What remains unproven:

- arbitrary nested map/vector writer semantics;
- AST-shaped compound-object transformation over arbitrary records;
- compile-2 lowering into the sub-env GUR forms;
- benchmark-grade performance. The current GUR timing is only a local
  microbenchmark snapshot.

The important dispatch boundary is that a nested accessor only wakes when its
outer cell receives an ordinary message. If recursion creates accessor topology
inside an activation-local inner network, that topology does not by itself wake
outer nested accessor propagators. The inner network must either project a
changed boundary cell back to the outer graph as a message, or the whole nested
accessor chain must be declared in the outer network before evaluation.

For the current `p:network-slot` path, nested dispatch is therefore:

```text
outer collection cell
  -> outer slot accessor propagator emits child collection/accessor value
  -> child cell wakes its own outer slot accessor propagators
  -> child accessor activation uses the child's own structural inner network
```

It is not:

```text
recursive activation creates inner accessor topology
  -> inner topology directly dispatches arbitrary outer child cells
```

This suggests a kernel-level design question for truly general unbounded
recursion over nested compound data. We may need an explicit mechanism that
allows a propagator running an inner network to dispatch selected inner cell
changes across the boundary as ordinary outer messages, without mutating the
outer graph and without persisting activation-local taps/frontiers as durable
data. In other words, the kernel may need a first-class inner-cell dispatch
boundary, rather than expecting recursive inner networks to implicitly wake
nested outer accessor topology.

Until that exists, the safe framing is narrower:

- use declared network accumulation when the full nested accessor topology is
  known or can be emitted before evaluation;
- use direct recursive activation for immediate tree-shaped computations and
  tested reductions;
- prove linked-list recursion next with public `obj/p:car` / `obj/p:cdr`, where
  each recursive step advances only after `cdr` is visible as an outer cell
  value;
- do not claim general nested compound recursion through inner accessor
  dispatch yet.

## Appendix: Demand-Driven Accessor Topology

The next compound-object experiment separates structure from slot values more
strictly. The current `p:slot` representation keeps durable slot cells inside
the collection's named network. That is convenient for `obj/slot-value`, but it
means a normal slot value update also changes the collection value and wakes
collection dependents.

The experimental `obj/p:network-slot` keeps the collection cell as a structural
inner network. As of 2026-06-12 this inner value is a plain named network; the
old accessor marker is compatibility metadata, not a required wrapper. The inner
network records demanded accessor topology, activation-local avatars, and
bi-sync wiring. Durable slot values usually remain in the outer accessor cells,
while preexisting public slot cells in a preserved named network remain readable
as source values.

```text
collection cell
  strongest = structural/accessor network

outer accessor cells
  strongest = actual slot values

slot activation
  fetch collection inner net
  copy relevant outer cells into avatars
  run inner net
  project changed avatars back to outer cells
```

This is the shape we want for future TMS/switch work: structural conditions can
rewrite the collection's inner network, while ordinary value updates propagate
between accessor cells without rewriting the collection.

This refactor preserves arbitrary internal named-network content, but it does
not make general unbounded recursion over nested compound data work by itself.
That still needs the planned kernel boundary work for dispatching messages into
inner recursive subenvs.

Focused evidence from `propagators.compound-object-network-slot-test`:

- raw map/vector source values can be projected to accessors without durable
  internal slot cells;
- repeated accessor declarations are idempotent;
- two accessors for the same slot sync through the inner network;
- after topology exists, a later accessor value update changes peer accessors
  but leaves the collection value unchanged.

Microbenchmark on `2026-06-10`, averaged over 8 timed runs after 2 warmup runs
and measuring setup separately from the later value update:

| accessors | strategy | setup ns | update ns | collection changed on update |
|---:|---|---:|---:|---|
| 2 | `p:legacy-slot` | 646,994 | 1,000,687 | yes |
| 2 | `p:network-slot` / `p:slot` | 757,734 | 344,067 | no |
| 10 | `p:legacy-slot` | 2,515,619 | 2,279,473 | yes |
| 10 | `p:network-slot` / `p:slot` | 4,294,234 | 1,299,890 | no |
| 100 | `p:legacy-slot` | 64,787,208 | 38,845,703 | yes |
| 100 | `p:network-slot` / `p:slot` | 77,206,369 | 33,355,682 | no |

Second microbenchmark on `2026-06-10`, also averaged over 8 timed runs after 2
warmup runs, using `200` accessors spread across `200` different slots and then
updating one accessor:

| shape | strategy | setup ns | update ns | collection changed on update |
|---|---|---:|---:|---|
| 200 accessors / 200 slots | `p:legacy-slot` | 334,783,843 | 170,275,536 | yes |
| 200 accessors / 200 slots | `p:network-slot` / `p:slot` | 155,213,739 | 366,062 | no |

Rerun after plain named-network preservation on `2026-06-12`, with the same
shape and timing method:

| shape | strategy | setup ns | update ns | collection changed on update |
|---|---|---:|---:|---|
| 200 accessors / 200 slots | `p:legacy-slot` | 298,543,583 | 150,788,917 | yes |
| 200 accessors / 200 slots | `p:network-slot` / `p:slot` | 143,394,791 | 316,500 | no |

Against the previous network-slot row, the current code is about `1.08x` faster
on setup and about `1.16x` faster on the single accessor update. The important
semantic result is unchanged: independent-slot updates do not rewrite the
collection value.

After this benchmark, the public compound-object facade was moved so
`obj/p:slot`, `obj/p:car`, `obj/p:cdr`, and `obj/p:cons` use the network-slot
strategy by default. The old durable slot-cell implementation remains available
as `obj/p:legacy-slot`, `obj/p:legacy-car`, `obj/p:legacy-cdr`, and
`obj/p:legacy-cons` for compatibility tests and APIs that intentionally inspect
materialized slot cells inside the collection value.

Conclusion: the experimental model proves the semantic separation we wanted,
but the performance result depends on shape. For many accessors synced to the
same slot, `p:network-slot` still does real fan-out work through the inner
network and loses at `100` accessors. For many independent slots, it wins
strongly because a value update stays on the one accessor route and does not
rewrite the collection cell. The optimized version seeds only the activated
accessor route and projects changed avatars by comparison instead of installing
activation-local taps.

## Appendix: Accessor-First Subsystem Migration

On `2026-06-10`, the live compound subsystems were migrated to treat
`obj/p:slot` as the default network-slot accessor declaration:

- dispatch result banks now write handler results through accessor routes;
- `obj/p:reduce` can fold either materialized public slots or network-slot
  accessor routes;
- generic procedures store policy/default/method branches through accessors and
  keep local materialization only for inspecting method declarations;
- behavior events use accessor routes, and event source enumeration reads
  accessor slot keys instead of only materialized `public-slot-keys`;
- layered procedure layers use network-slot topology for live declarations, then
  materialize only inside transient application frames and debug/reporting
  boundaries;
- compiler-2 closure env attachment uses accessors, while closure application
  materializes clean transient values from accessor source slots plus declared
  accessor routes before evaluation;
- constructors that intentionally return inspectable record values, such as
  `intensity/p:with-intensity`, still use the legacy materialized slot bridge.

The important split is now explicit:

```text
live declaration path
  obj/p:slot -> network-slot accessor topology

evaluation / inspection boundary
  local materialization bridge -> legacy slot cells in a temporary frame
```

This keeps normal updates monotone and mostly value-local, while preserving the
older inspectable record APIs where callers intentionally read `obj/slot-value`.
The full propagator test suite passed after the migration:

```text
clojure -M:test propagators
TOTAL: 802 pass, 0 fail, 0 error
```

Subsystem benchmark against the recorded `2026-06-08` dispatch baseline:

| Command | Case | Baseline median | Accessor-first median | Result |
| --- | --- | ---: | ---: | ---: |
| `clj -M:dispatch-bench` | generic, 50 handlers / 1 dispatch | 342.616 ms | 401.269 ms | 1.17x slower |
| `clj -M:dispatch-bench` | layered, base+provenance / 1 dispatch | 2.203 ms | 1.998 ms | 1.10x faster |
| `clj -M:dispatch-bench 50 51` | generic, 50 handlers / 51 dispatches | 2516.185 ms | 1167.847 ms | 2.15x faster |
| `clj -M:dispatch-bench 50 51` | layered, base+provenance / 51 dispatches | 81.630 ms | 72.831 ms | 1.12x faster |

The dispatch subsystem result matches the slot microbenchmark shape. One-shot
generic declaration has a little more overhead, but repeated dispatch benefits
from reusing accessor topology instead of rewriting the compound collection
cell. Layered dispatch improves slightly in both measured cases.

Compiler-2 was migrated too, but only on the declaration side:

- closure environment attachment now declares `closure-env-slot` with
  `obj/p:slot`, so the live closure record uses network-slot accessor topology;
- closure and application argument extraction still materialize a clean
  transient compound value at the evaluation boundary;
- the transient materialization bridge intentionally uses `obj/p:legacy-slot`,
  because compiler-2 application still evaluates a concrete closure body against
  concrete argument cells.

There was no earlier recorded compiler-2 benchmark, so the first compiler
comparison was taken on `2026-06-11` against a clean temporary worktree at
pre-migration commit `c7c07c3`. Each entry compiles the source and runs the
resulting network, averaged over 8 timed runs after 3 warmups:

| Source | Pre-migration median | Accessor-first median | Result |
| --- | ---: | ---: | ---: |
| `(+ 1 (- 4 2))` | 2.879 ms | 2.940 ms | 1.02x slower |
| `((:: [x] (+ x 1)) 4)` | 3.972 ms | 5.277 ms | 1.33x slower |
| `((:: [x] ((:: [y] (+ y x)) 4)) 5)` | 5.026 ms | 6.432 ms | 1.28x slower |

The compiler result is expected from the current boundary design: the migrated
compiler stores closure environment slots through accessors, but every closure
application still materializes a transient inspectable closure/argument object
before evaluating the body. That preserves correctness and keeps declaration
separate from evaluation, but it does not yet get the reuse win seen in repeated
generic dispatch.
