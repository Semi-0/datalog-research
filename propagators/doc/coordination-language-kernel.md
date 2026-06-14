# Propagators As A Coordination Language

Status: analysis note, June 2026.

This experiment should be framed as a minimal kernel for a coordination
language, not as a process language with propagators as an implementation
technique.

The language coordinates partial information, declared networks, and multiple
views over the same evolving facts. A program does not primarily say "run this
process next." It says which cells, propagators, slots, closures, applications,
and projections should exist, then lets monotone message flow and fixpoint
evaluation settle the declared structure.

## Thesis

The kernel is small because the main abstraction is coordination:

- cells hold partial information
- propagators emit messages
- merge absorbs contributions
- strongest/projection views expose readable facts
- network values can be declared, stored, inspected, merged, and evaluated

That makes the propagator language self-reflective: network structure is not
only runtime machinery. It can also be ordinary data in cells, named networks,
closure values, application IR, recursive frame fragments, and expanded
declaration networks.

It also makes the language multi-projectional: the same underlying information
can support several declared views, such as base values, provenance layers,
compound slots, behavior summaries, accessor topology, dependency facts, graph
visualizations, or compiler-retained IR. A projection should be an explicit
relation over shared partial information, not a hidden copy maintained by
process-local state.

## Minimal Kernel Boundary

The minimal kernel should remain close to the current runtime model:

- immutable `Net` values made of `graph`, `env`, and `dict`
- cell entries with `content` and `strongest`
- propagator activation functions that return messages
- merge and strongest policy at cell absorption time
- explicit task queues and fixpoint evaluation
- installers as pure network-value transformations
- activation-local subnet execution through boundaries, avatars, diffs, and
  ordinary outbound messages

The kernel should not grow into a process runtime. It should not own domain
control flow, dependency semantics, projection policy, or recursive expansion
policy. Those belong in declared topology, merge policies, named-network facts,
compiler layers, or explicit higher-order propagators.

The important boundary is:

```text
declaration: build or accumulate network data
evaluation: run declared topology to quiescence
projection: expose selected information through messages or strongest views
```

Crossing that boundary should be explicit. For example, a recursive declaration
can accumulate network facts into a cell. A recursive evaluation can run an
activation-local network and diff declared output cells. A dynamic subnet can
use activation-local taps to discover changed cells, but the taps themselves are
not durable declaration data.

## Scope

In scope:

- treating networks, closures, applications, procedures, recursive frames, and
  compound objects as inspectable declaration data
- keeping declaration separate from evaluation
- using named-network fragments and stable ids for idempotent accumulation
- adding projections as explicit topology, reducer policies, slot accessors,
  strongest policies, or compiler-retained IR
- using activation-local effects only to bridge an executed subnet back into
  ordinary messages
- testing that results depend on declared facts and merge policy, not incidental
  construction order or scheduler order

Out of scope for the minimal kernel:

- a general process language with program counters, threads, coroutines, or
  scheduler-visible domain steps
- propagators that mutate the live outer graph during activation
- global mutable registries for procedures, methods, layers, projections, or
  recursive frames
- durable effectful taps, mutable frontier atoms, or task queues stored inside
  declaration values
- dependence tracking inside `eval-propagator` or `eval-cell` rather than at
  merge time
- projection logic hidden in ad hoc readers that bypass declared topology
- unbounded recursive expansion as a scheduler feature

## What To Do

Prefer declaration over procedural control. If a feature needs more structure,
first ask what network facts, slots, named ids, or projection topology should be
declared.

Keep output movement message-shaped. Inner networks may run, but their effects
leave through declared output cells, explicit diffs, accumulator messages, or an
activation-local changed-cell frontier that is converted back into messages.

Make reflection ordinary. Compiler IR, closure data, application objects,
recursive frame facts, and expanded networks should remain inspectable cell
content or named-network data. Avoid opaque evaluator state when a declared fact
would do.

Make projections explicit. A new view should normally be represented by a slot,
layer, reducer policy, strongest policy, accessor topology, or retained IR
relation. The projection can be lazy or summarized, but its existence should be
visible in the network model.

Use stable identities for accumulated declarations. Recursive and iterative
network accumulation only remains coordination-friendly when repeated expansion
redeclares the same semantic topology instead of generating fresh unrelated
topology.

## What Not To Do

Do not make the scheduler the semantic center. It should drain tasks and wake
neighbors; it should not know about layers, recursion, behavior windows,
projection invalidation, or dependency truth maintenance.

Do not turn recursive declaration into direct outer-graph mutation. If recursion
discovers topology, emit network data or named-network fragments. If recursion
computes values, run an activation-local network and project selected results
out as messages.

Do not persist activation-local effects. Taps, changed-cell atoms, and task
frontiers are execution machinery. Persisting them inside compound or recursive
declaration values leaks one activation into future activations.

Do not collapse multi-projectional data into one native value too early. Native
maps, vectors, and scalars are useful strongest views, but the durable
coordination object should remain slot-backed or network-backed when later
projection or update is expected.

Do not treat provenance as the whole dependence story. Provenance layers are
domain projections. Generic dependence tracking belongs at merge time as a
future subsystem, not in propagator activation or scheduler logic.

## Open Design Work

- stable-id discipline for recursive and iterative network accumulation
- a common projection algebra for slots, layers, reducer policies, behavior
  summaries, and compiler-retained IR
- merge-time dependence tracking that can explain and retract cell content
- bounded iteration as declared fixed topology rather than recursive runtime
  expansion
- clearer APIs for converting activation-local changed-cell frontiers into
  outbound messages

These are kernel-adjacent design problems. They should be solved by tightening
the declaration, merge, projection, and boundary model, not by turning the
propagator language into a process language.

## 2026-06-14 Lexical Continuation Boundary

The current kernel refactor keeps the coordination-language framing but changes
where evaluator state lives. A primitive network now carries an IO record:

```clojure
{:queue []
 :queued-props #{}
 :inbox []
 :outbox []}
```

`core/continue` drains the queue stored in the network value. Propagator
activation receives the evaluator continuation through
`runtime/*continue*`. This lets a compound-like propagator run a child network
without asking the kernel to understand compound objects, recursive frames, or
named slots.

The lexical compound experiment uses that continuation as a small subenv
boundary:

```text
parent cell -> child inbox -> reality.in -> child cell
child cell -> reality.out -> child outbox -> parent message
```

The parent does not mutate the child env directly. It stores the updated child
network value back in the child-network cell, and any visible parent result
leaves through ordinary messages. This keeps declaration and evaluation
separate:

- declaration stores the child topology, reality ports, and durable cells
- evaluation pushes activation-local inbox records and drains queue work
- projection back to the parent is explicit outbox data translated to messages
- no activation-local queue, frontier, or tap is persisted as declaration facts

This makes `diff-cells` less central. For a child network with explicit
`reality.out` ports, the outbox is the boundary contract and no diff is needed.
But `diff-cells` is not deleted: old runtime compounds and some legacy
compound-object compatibility paths still use it.

The experiment also clarifies the remaining nested-recursion issue. A lexical
child network can run existing recursive nested compound code when the needed
boundary ports are already declared. It does not by itself solve general
unbounded recursion over nested network-slot accessors, because newly discovered
inner accessor topology still needs either:

- explicit child reality boundaries declared by the recursion primitive, or
- a later kernel-level subenv/named-cell dispatch mechanism.

So this refactor is a small evaluator substrate, not the final recursion
primitive.

## 2026-06-14 Lexical Pointer Dispatch

The next increment adds the missing indirection layer: a sparse evaluator
lexical environment table plus cell/name references into that table.

The important shape is:

```clojure
{:lexical-envs
 {[:right :b] child-network}}

(io/name-ref [:right :b] :source)
(io/cell-ref [:right :b] some-cell-id)
```

This lets recursive expansion create a new child frame, store it under a stable
scope id, and send the next message to that frame without mutating the parent
graph. The evaluator fetches the child net, merges the message into the child
cell, runs the child continuation, and writes the updated child net back to the
same scope id.

A focused regression now walks a nested compound value dynamically:

```clojure
{:left [0 1 2]
 :right {:a 3
         :b [4 5]
         :empty []}}
```

The walk creates lexical frames for paths such as `[:left 2]`,
`[:right :b 1]`, and `[:right :empty]` during evaluation. That proves the
kernel can address recursive nested compound frames through pointers rather
than through a fixed list of parent-declared IO ports.

This solves the core dispatch problem we identified: later recursive frames can
receive messages through stable lexical pointers. It does not yet define the
whole high-level recursive map/reducer library. In particular, automatic output
assembly for arbitrary nested compound maps still needs derived declaration
forms that create the parent-frame aggregation cells and propagators. The kernel
now has the pointer substrate those derived forms need.

## TODO: Unbounded Procedure Definitions

2026-06-12 note: layered procedures and generic procedures currently work by a
non-uniform bridge. Their live declarations use accessor-first compound-object
topology, but their application paths materialize temporary legacy slot objects
inside activation-local networks before dispatch. That bridge preserves current
behavior, but it is not the final procedure model.

The better framing is that layered procedures and generic procedures should be
library definitions written with the same unbounded recursion/iteration
primitive needed for nested compound data. Both procedures inspect slotful
procedure data, discover layer or method branches, build branch application
topology, collect branch results, reduce/select an output, and keep reacting to
later slot changes. Their current APIs are awkward because they hand-code this
pattern without the substrate.

The next design sequence should be:

1. Define the unbounded recursion/iteration primitive as the procedure
   substrate. It must express "walk slotful data, expand one frame, accumulate
   declaration/result state, and continue when new structure appears" without a
   procedure-specific dispatcher.
2. Surgically extend the kernel only enough to support that primitive with an
   explicit subenv dispatch boundary. A propagator running an inner/simulated
   network should be able to import selected outer cell content into inner
   avatars and export selected changed inner cells back as ordinary outer
   messages. This boundary must be explicit, bidirectional, and message-shaped;
   it must not mutate the live outer graph or persist activation-local
   taps/frontiers as durable data.
3. Table general unbounded recursion and iteration implementation work until
   the primitive and boundary contract are specified. Without that contract,
   recursive inner networks cannot consistently dispatch newly discovered nested
   compound/accessor state, and pure declaration-first expansion only remains
   incremental over already-declared topology.
4. Rebuild layered procedures and generic procedures as derived definitions over
   that primitive. Layered procedures become recursion/iteration over layer
   slots; generic procedures become recursion/iteration over method slots plus
   policy/default slots. Their method/layer dispatch should not depend on ad hoc
   materialization into legacy slot objects.
5. Remove or quarantine the current legacy materialization bridges once the
   derived definitions are available. Until then, treat them as compatibility
   boundaries, not the intended procedure model.
6. Ban the kernel from extending cell merge/strongest by defining generic
   propagator handlers at live runtime. Merge/strongest extension may still be
   modeled inside a simulated or explicitly extendable network propagator, where
   the extended generic environment is part of that network value. The live
   kernel merge path should not be mutated by ordinary procedure-definition
   effects.

This keeps the minimal kernel coordination-oriented while making the missing
procedure recursion substrate explicit. Procedure extension, recursion,
iteration, and cell protocol experiments can then share the same subenv/message
protocol instead of each building a separate legacy materialization bridge.
