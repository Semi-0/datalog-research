# Core Runtime Model

The runtime implements **networked semantics** (feature 1) and **fixpoint
evaluation** (feature 2) from [Four Core Features](four-core-features.md).
**Partial information** (feature 3) is domain-specific via `cell-merge` /
`strongest-value`. **Dependence tracking** (feature 4) is **not** part of
`eval-propagator` or `eval-cell` (MIT-shaped: a separate subsystem wired only at
**`cell-merge`** when messages are absorbed). It is not implemented yet.

Source files:

- `propagators/graph.clj`
- `propagators/network.clj`
- `propagators/io.clj`
- `propagators/runtime.clj`
- `propagators/propagator.clj`
- `propagators/core.clj`
- `propagators/compile.clj`
- `propagators/helpers/task_queue.clj`

## Representation

The central experiment is to separate network declaration from network
evaluation. Declaration builds a graph/env/dict value. Evaluation consumes that
value with an explicit task queue and returns a new network value.

That means a network can itself become data. It can be stored in a cell, merged
as partial information, inspected, and passed to compound propagators without
requiring evaluation to be hidden inside construction.

The runtime separates topology from state and evaluator IO:

| Piece | Shape | Role |
|-------|-------|------|
| graph | `id -> Node` | wiring: input and output node ids |
| env | `id -> Cell or Propagator` | runtime content and behavior |
| dict | `name -> id` | optional named interface |
| io | `{:queue ... :queued-props ... :inbox ... :outbox ...}` | evaluator-local deliveries and network boundary IO |

`Net` stores all four:

```clojure
(net graph env dict io)
```

This is different from a monolithic network object. A caller can construct a
network, inspect it, seed cells, enqueue tasks, and run the scheduler explicitly.

## Cells

A cell has:

```clojure
{:content ...
 :strongest ...}
```

`content` is the raw merged information. `strongest` is the readable value used
by propagator inputs. Most simple values have `content == strongest`, but richer
domains can keep more evidence in content than they expose as strongest.

Examples:

- named-network evidence stores an antichain in content and computes strongest
  lazily
- compound subnet state stores structural state; effectful execution is not part
  of strongest

## Propagators

A propagator stores an activation function:

```clojure
(fn [input-ids output-ids network] messages)
```

Primitive propagators are built with `primitive-propagator`. They read strongest
values from input cells, skip activation if any input is unusable, and return
messages for output cells.

`p:id` is the simplest primitive:

```clojure
(def p:id (primitive-propagator (fn [x] x)))
```

## Installer Shape

Installers mutate the immutable network value by returning a new network:

```clojure
installer = (fn [network] [installed-id network'])
```

`construct-cell` installs a blank graph node plus a cell env entry.
`construct-propagator` installs a graph node, wires edges, and stores the
propagator env entry.

## Scheduler

`run-tasks` accepts an explicit FIFO queue of propagator ids, converts it into
network-carried IO deliveries, and then drains the network's own `:io/:queue`:

```text
task ids -> io queue -> eval-propagator -> messages/io deliveries -> eval-cells -> maybe enqueue outputs
```

`eval-propagator`:

1. reads the propagator node from `graph`
2. reads the propagator function from `env`
3. clears activation-local queue state from the network view
4. binds the evaluator continuation as `runtime/*continue*`
5. calls the function with input ids, output ids, and the network
6. merges returned messages into cells or applies returned IO deliveries

`eval-cell`:

1. merges the update into cell content via **`merge/cell-merge`**
2. computes strongest
3. stores the new cell
4. if strongest changed, enqueues downstream propagators

Dependence tracking (when it exists) attaches at step 1 inside **`cell-merge`**, not in the scheduler loop above.

Task queue entries are propagator node ids. Message targets are cell node ids.
Propagator activations may also return IO deliveries such as "append this
record to the outbox" or "drain these inbox records." Those deliveries are data
inside the network value; the core scheduler does not know about reality ports,
compound slots, recursion, or domain-specific boundary policy.

## IO-Carrying Continuation Evaluation

2026-06-14 experiment: the primitive network now carries evaluator IO while the
core stays small.

`propagators.io` owns the queue/inbox/outbox shape:

```clojure
{:queue []
 :queued-props #{}
 :inbox []
 :outbox []}
```

`core/continue` repeatedly pops one delivery from `:io/:queue`, evaluates it,
and enqueues any downstream propagator ids as new IO deliveries. `run-tasks` is
now compatibility sugar over that continuation:

```clojure
(defn run-tasks [tasks n]
  (continue (io/enqueue-props n tasks)))
```

The important guardrail is that propagator activation receives
`(io/clear-queue n)`, not the parent network with its pending scheduler queue.
Without that, child or compound networks can accidentally inherit the parent's
pending tasks when network values are copied through messages. The recursive
compound benchmark exposed this as an exponential runtime blow-up; clearing the
activation queue restored the old bounded behavior.

`propagators.runtime/*continue*` exposes the evaluator continuation to
propagators without making the core domain-aware. A compound-like propagator can
push messages into a child network's inbox, call `(runtime/continue child-net)`,
drain the child outbox, and return ordinary parent messages.

Boundary IO is declared outside the core:

- `reality/p:reality-in` drains matching inbox records into a child cell
- `reality/p:reality-out` records selected child cell messages in the outbox
- `lexical/p:compound` wires parent cells to child reality ports and stores the
  updated child network back into its network-valued cell

This is a lexical child-network evaluation path, not hidden parent mutation.
The parent only changes when returned messages are merged through ordinary
cells.

## Compiler Surface

`compile-net` lowers a small quoted DSL into the same graph/env representation:

```clojure
'(let-cell [c0 c1]
   (p:id c0 c1))
```

Supported concepts are currently small:

- cell binding
- sequential `do`
- primitive propagator installation such as `p:id`

The compiler is closer to MIT-style install-time expansion for primitive
networks: it adds cells and propagators to one flat graph/env. Compound forms are
not yet part of this compiler surface.

`propagators.builder-policy/*builder-policy*` (`:lazy` default, `:queue` optional)
controls whether compile install/seed only wire topology or enqueue tasks drained
at `(do …)` and `eval-net` flush boundaries. Scheduler order should still be
immaterial at quiescence; flush **placement** is not — see
[Builder Policy, Run Order, and Correctness](builder-policy-run-order-and-correctness.md).

## Assumptions

- graph nodes must be installed before edges are wired
- propagator ports are cell ids
- propagator functions return messages for cells, not propagator nodes
- missing env entries are errors, not soft failures
- port order is not guaranteed when using graph node input/output sets
- callers decide what to enqueue first

## Current Limits

- contradiction handling is still a stub
- dependence tracking is not implemented (intended hook: `cell-merge` only)
- backtracking is not implemented (depends on merge-time dependence subsystem)
- richer domains need explicit `cell-merge` and `strongest-value` methods
- lexical child-network evaluation is experimental; existing runtime compounds
  and legacy slot paths still exist for compatibility

The design favors explicit data flow over hidden runtime mutation. That makes
tests slightly verbose, but it keeps each scheduler step reproducible.
