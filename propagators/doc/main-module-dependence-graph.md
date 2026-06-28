# Main Module Dependence Graph

Status: analysis note, June 2026.

This note separates two graphs:

1. the current namespace/module dependency graph in the repo;
2. the target conceptual dependency graph we want the project to converge on.

The important conclusion is that the project should use **compound object as
partial data** as the spine, then define **propagator-agnostic unbounded
recursion** over that partial data. Layered procedures, generic procedures,
compiler-2 recursion, and hot-reloadable compiler work should become derived
uses of that spine rather than parallel dispatch systems.

## Reading The Graph

Edges point from a dependent module to the thing it needs.

```text
A -> B
```

means "A depends on B."

## Current Code Dependencies

The current module graph already has a strong partial-data center, but the
procedure systems still bypass the unbounded recursion substrate.

| Module | File anchors | Current direct dependencies | Meaning |
| --- | --- | --- | --- |
| Minimum runtime | `propagators/core.clj`, `network.clj`, `message.clj`, `propagator.clj`, `network_builder.clj`, `cells/*` | none above the core runtime | Scheduler, immutable networks, messages, cells, merge, strongest, task queue. |
| Partial information | `cells/merge.clj`, `datastructures/named_network.clj`, `datastructures/evidence_set.clj`, `datastructures/behavior.clj` | minimum runtime | Merge and strongest policies; named-network/evidence-set partial facts; behavior content/summary split. |
| Compound object partial data | `datastructures/compound_object.clj`, `compound_object/network_slot.clj`, `compound_object/reduce.clj`, `scoped_address.clj` | minimum runtime, partial information | The current shared representation for slotful partial data and accessor topology. |
| Lexical sub-env dispatch | `gur/subenv.clj`, `gur/subenv/env.clj`, `gur/subenv/dispatch.clj`, `core/eval-cell*` | minimum runtime, scoped addresses | Parent dict routes scoped messages into the owner network-valued cell. |
| Lexical-aware apply closure | `gur/subenv/frame.clj`, `gur/subenv/scoped_slot.clj`, `gur/subenv/output.clj`, `gur/subenv/queue.clj` | compound object, lexical sub-env dispatch, minimum runtime | Current contextual `apply` / `recur` experiment. It knows how to seed child frames, publish accessors, and project outputs back. |
| Unbounded general recursion | currently split across `recursive.clj`, `closure.clj`, `gur/subenv/frame.clj`, `compile.clj`, tests | compound object, lexical-aware apply closure, minimum runtime | Not yet one stable module. This is the missing common primitive. |
| Branch result banks and reducers | `application.clj`, `dispatch.clj` | compound object, closure application, minimum runtime | Shared application frame, result bank, reducer policy, output diff. Used by layered and generic procedures today. |
| Layered data/procedure | `layered.clj`, `stdlib/layered.clj`, `stdlib/provenance_arithmetic.clj` | compound object, branch reducers, minimum runtime | Current layered procedure application discovers layers through slot materialization, not through general recursion. |
| Generic procedure | `generic_procedure.clj`, `generic_procedure/*` | compound object, branch reducers, minimum runtime | Current generic application discovers methods through procedure-specific materialization, not through general recursion. |
| Compiler 2 | `compiler_2/core.clj`, `compiler_2/application.clj`, `compiler_common/core.clj` | compound object, activation-local runtime, minimum runtime | Current compiler retains IR and slot-backed closure/application data, but application lowering still owns its own activation logic. |
| Behavior compiler / hot reload precursor | `compiler_behavior/core.clj`, `compiler_behavior/application.clj`, `datastructures/behavior.clj` | compiler-2 AST/env helpers, behavior, compound object, minimum runtime | Current behavior compiler models time-varying values and closure versions, but not yet hot-reloadable procedure definitions over generic/layered extension. |
| Vijual tooling | `graph/vijual.clj`, `graph/vijual/layout/*` | graph/layout/render only | Analysis renderer for dependency graphs, not a runtime dependency of propagators. |

The current non-uniformity is visible in the imports:

- `propagators.layered` depends on `propagators.application`,
  `propagators.dispatch`, and `propagators.datastructures.compound-object`.
- `propagators.generic-procedure.application` depends on the same application,
  dispatch, and compound-object path.
- neither one depends on a stable unbounded-recursion module, because that
  module does not exist yet.
- `compiler_2.application` and `compiler_behavior.application` also duplicate
  activation-local body application patterns instead of lowering onto the same
  recursion/iteration primitive.

## Target Conceptual Graph

This graph was rendered with Vijual's stress-majorized directed layout:
`graph.vijual/draw-stress-directed-graph`.

Render options:

```clojure
{:seed 3
 :stress-node-spacing 3.0
 :stress-iterations 300
 :stress-refine-iterations 300
 :stress-aspect-ratio 1.4
 :routing :shortest-path}
```

Legend:

| Key | Module |
| --- | --- |
| MR | minimum runtime |
| PI | partial information / merge |
| CO | compound object partial data |
| SD | lexical sub-env dispatch |
| LAC | lexical-aware apply closure |
| UGR | propagator-agnostic unbounded recursion |
| BR | branch result banks and reducers |
| LD | layered datum |
| LP | layered procedure |
| GP | generic procedure |
| C2 | compiler 2 |
| HRC2 | hot reloadable compiler 2 |

```text
         +----+
         | SD |················>····+
         +----+                     |
            |                       |
            |                       |
            |                       |
            |                       |                        +----+
            |                       |                  +····+| PI |
            |                       |                  |    |+----+
            |                       |                  |    |
            |                       |                  |    |
            |                       |                  |    |
            |                       |                  |    |
            ^                       +··············<···+····+
            |                    +----+                |
            |   +··>·············| MR |·········+      |
            |   |                +----+         |      |
            |   |                   +···········+······+·····<··········+
            |   |                   |           |      |                |
            |   |                   |           |      ^                |
            |   |                   |           ^      |                |
            |   |                   |           |      |                |
         +-----+|                   |           |      |                |
         | LAC ||                   |           |      |                |
         +-----+|                   |           |      |                |
                |                   |           |      |                |
                |                   |           |      |                |+----+
                |                   |           |      |           +····+| BR |
                |                   |           |      |           |    |+----+
                |                   |           |      |           |    |   |
                |                   ^           |      |           |    |   |
                |                   |           |   ···+········<··+····+   |
                |                   |           |+----+|           |        |
                |                   |  +········+| CO |+····+      |        |
                |                   |  |        |+----+     |      |        |
                |                   |  |        |   +·······+······^····+   |
                |                   |  ^        |   |       |      |    ^   |
                |                   |  |        |   |       ^      |    |   |
                |          ·········+··+·>······+   |       |      |    |   |
                |       +----+      |  |            |       |      |    |   ^
                |       | LD |··+   |  |            |       |      |    |   |
                |       +----+  |   |  |            |       |      |    |   |
                ^               |   |  |            |       |      |    |   |
                |               |+----+|            |       |+----+|    |   |
                |               v| C2 |+············+<·····+|| GP ||    |   |
                |               |+----+             |      | +----+     |   |
                |               |   |   +········<··+······+····+       |   |
                |               |   v   |           |      |    ^       |   |
                |               |+-----+|           |      |+------+    |+----+
                +···············+| UGR |+···········+      || HRC2 |··>·+| LP |
                                 +-----+                    +------+     +----+
                                    ····················<····················
```

The edge set behind the graph is:

```clojure
[[:HRC2 :C2]
 [:HRC2 :LP]
 [:HRC2 :GP]
 [:C2 :UGR]
 [:C2 :CO]
 [:C2 :MR]
 [:GP :UGR]
 [:GP :CO]
 [:GP :BR]
 [:LP :UGR]
 [:LP :CO]
 [:LP :BR]
 [:LD :UGR]
 [:LD :CO]
 [:UGR :LAC]
 [:UGR :CO]
 [:LAC :SD]
 [:LAC :MR]
 [:SD :MR]
 [:CO :PI]
 [:CO :MR]
 [:BR :CO]
 [:BR :MR]
 [:PI :MR]]
```

## Target Interpretation

The target graph says:

```text
minimum runtime
-> partial information
-> compound object as partial data
-> lexical-aware apply closure
-> propagator-agnostic unbounded recursion
-> layered datum / layered procedure / generic procedure / compiler-2 lowering
-> hot reloadable compiler-2
```

More precisely:

- **Minimum runtime** remains small. It owns message merge, strongest, tasks,
  and network evaluation. It should not know layered procedure, generic
  procedure, compiler-2, or recursion semantics.
- **Compound object partial data** is the central substrate. Slot topology,
  accessor networks, and scoped addresses are the durable partial-data layer.
- **Lexical-aware apply closure** is a boundary primitive. It should know how to
  apply a closure in a child/subenv frame, import selected outer data, publish
  selected output/accessor state, and remain replayable.
- **Unbounded general recursion** should be propagator-agnostic. It should not
  know layered or generic procedure semantics. It should only know how to walk
  slotful partial data, create stable frames, accumulate declarations/results,
  and continue when new structure appears.
- **Branch result banks and reducers** stay library-level. They reduce results
  after branch expansion; they are not the recursion engine.
- **Layered procedure** becomes recursion over procedure layer slots plus a
  layered-object output policy.
- **Generic procedure** becomes recursion over method slots plus matcher,
  handler, default, and select-one policies.
- **Compiler 2** lowers source forms onto compound objects and unbounded
  recursion instead of owning a separate activation-local procedure model.
- **Hot reloadable compiler 2** should wait until layered data and generic
  procedures are derived from the same recursion substrate. Otherwise it will
  multiply the current fragmentation.

## Current Gaps

### Gap 1: no single unbounded-recursion module

Current recursive behavior is split:

- `recursive.clj` has direct recursive activation and retained-frame
  accumulation.
- `closure.clj` has declaration-oriented network application.
- `gur/subenv/frame.clj` has contextual `apply` / `recur` and scoped accessor
  publication.
- `compile.clj` has source-level conveniences around current experiments.

The project needs one substrate that can be named and tested as:

```text
walk slotful partial data
-> apply frame closure
-> accumulate stable declaration/result facts
-> publish accessor/output changes
-> resume when new slots appear
```

### Gap 2: procedures still own branch discovery

`layered.clj` and `generic_procedure/application.clj` both use
`application/build-branch-application` and `dispatch/*` reducers, but each owns
its own branch discovery/materialization path.

Target:

```text
procedure object is compound data
-> unbounded recursion discovers branch slots
-> library branch builder applies branch closures
-> reducer policy projects output
```

### Gap 3: compiler application duplicates activation logic

`compiler_2.application` and `compiler_behavior.application` still materialize
closure slots, build activation-local networks, run bodies, and project outputs
directly.

Target:

```text
compiler closure/application IR
-> compound object partial data
-> lexical-aware apply closure
-> unbounded recursion / iteration where needed
```

### Gap 4: hot reload needs procedure/data consolidation first

Hot reloadable compiler-2 needs definitions, closures, methods, and layers to
be data that can change over time. The behavior compiler proves time-varying
values, but hot reload should not be attacked until generic and layered
procedures share the same partial-data recursion substrate.

## Attack Ranking

### 1. Define compound-object traversal as the recursion input

This is the most important point to attack.

The first concrete target should not be "generic procedure" or "compiler-2".
It should be a small primitive over compound object/accessor topology:

```text
given a collection/procedure cell
discover visible slots
install one stable frame per slot
publish frame results into an output compound/result bank
continue when late slots appear
```

This directly serves unbounded list/map traversal, layered data, layered
procedure layers, generic method slots, and compiler traversal.

### 2. Make lexical-aware apply closure replayable

`propagators.gur/p:apply-closure` now has the right location in the graph: it is
the accumulating GUR facade used for new compiler/macro work. The deprecated
`gur.subenv/p:apply-closure` remains as comparison evidence, but its frame key
is operational. The target primitive records stable application facts:

```text
closure id + argument identity + output identity -> applied frame fact
```

Repeated application should rediscover the same frame declaration, not merely
skip because an operational guard says the frame already ran.

### 3. Rebuild layered procedure first

Layered procedure is the simpler consolidation target:

```text
layer slots
-> apply every active branch
-> reduce with layered-object-policy
```

It has less selection logic than generic procedure. If layered procedure cannot
be derived from compound-object traversal plus branch reducers, the recursion
substrate is not general enough.

### 4. Rebuild generic procedure second

Generic procedure adds matcher/predicate/handler structure and select-one
semantics:

```text
method slots
-> apply predicate/matcher branches
-> apply handler for matches
-> reduce with select-one-policy
```

This is a stronger test after layered procedure proves the branch-discovery
loop.

### 5. Lower compiler-2 onto the consolidated substrate

Compiler-2 should not be the first consolidation target. It has more surface
area: retained IR, lexical envs, closure values, application values,
dependency/behavior variants, and escaped closure outputs.

After layered/generic are derived, compiler-2 can lower its slotful
closure/application objects onto the same apply/recursion substrate.

### 6. Hot reloadable compiler-2 comes last

Hot reloadable compiler-2 depends on:

- compiler-2 source/IR lowering;
- layered data as time-varying partial data;
- generic procedure definitions as data;
- behavior/history semantics;
- unbounded recursion over late structure.

Starting here would maximize fragmentation. It should be a final integration
test for the consolidated spine.

## Practical Next Milestone

The next milestone should be:

```text
compound-object unbounded traversal v1
```

Acceptance criteria:

- works over accessor-network compound objects, not only host Clojure maps or
  vectors;
- uses stable frame ids or stable frame facts;
- supports late slot discovery;
- emits result slots into a result bank or output compound object;
- has no layered/generic/compiler-specific policy inside the traversal core;
- can be used to rewrite one layered-procedure test without changing the
  layered output policy.

That milestone is the best leverage point because it directly attacks the edge
with the largest downstream fan-out:

```text
UGR -> CO + LAC + MR
```

Once that edge is real, the project can consolidate instead of adding more
parallel prototypes.
