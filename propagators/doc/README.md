# Propagators Documentation

This folder holds design notes for the propagator experiment. It intentionally
lives under `propagators/` so the propagator system can be separated from the
larger repo later.

## Four core features

The propagator experiment is organized around four goals inherited from the
original `propagators/NOTES.md` design (restored in [Four Core Features](four-core-features.md)):

| # | Feature | Status (summary) |
|---|---------|------------------|
| 1 | **Networked semantics** | Done — immutable `graph` + `env`, installers, compile |
| 2 | **Fixpoint evaluation** | Done — `run-tasks` and inner subnet quiescence |
| 3 | **Partial information** | In progress — named-network merge, layered/compound objects; contradiction stub |
| 4 | **Dependence tracking** | Not started — merge-time subsystem (`cell-merge`); not `eval-cell` |

See [Four Core Features](four-core-features.md) for definitions, MIT comparison, and a milestone log since the doc refactor.

## Map

- [Propagators As A Coordination Language](coordination-language-kernel.md)
  frames the experiment as a self-reflective, multi-projectional coordination
  language and defines the minimal-kernel scope.
- [Four Core Features](four-core-features.md) — canonical checklist and progress
- [Core Runtime Model](core-runtime.md) describes the graph/env split,
  installer shape, scheduler, compiler surface, and current assumptions.
- [Named Network Evidence](named-network-evidence.md) describes named networks,
  the interface preorder, evidence-set merge, and why strongest views are
  computed lazily.
- [Compound Runtime](compound-runtime.md) describes runtime compound
  propagators, avatars, bi-sync boundary scheduling, and the planned runtime vs
  compile-time split.
- [Compound Data: Current Linked-List Model](compound-data-linked-list.md)
  describes the current `compound_data.clj` approach, including why it is
  intentionally fragile and likely to change.
- [Compound Object Slot Sync](compound-object-slot-sync.md) describes the
  replacement direction where `p:car` and `p:cdr` directly sync
  named-network slots without `c:linked-list`.
- [Layered Procedure Network](layered-procedure-network.md) describes layered
  data/procedure slots, slotful procedure extension, and stable layered
  operators such as `p:+`.
- [Generic Procedures, Cell Protocols, And Intensity](generic-cell-protocol-and-intensity.md)
  describes propagator-native generic dispatch, network-local merge/strongest
  generics, layered intensity values, and intensity arithmetic.
- [Behavior Reactivity](behavior-reactivity.md) describes sparse event-to-history
  behavior reducers, the content/strongest split, and why behavior strongest
  carries a summary without changing the scheduler kernel.
- [Compiler 2](compiler-2.md) describes AST-based compiler-2 network expansion,
  slot-backed closure data, activation-local application evaluation, contextual
  dependency arithmetic, and common algebra shared with compound/layered/generic
  systems.
- [Recursive Compound Propagator](recursive-compound-propagator.md) describes
  activation-local recursive network expansion, compile DSL wiring, and the
  Fibonacci proof.
- [Main Module Dependence Graph](main-module-dependence-graph.md) maps the
  current module dependencies against the target consolidation graph: compound
  object partial data, lexical-aware apply closure, unbounded recursion,
  layered/generic procedures, compiler-2, and hot reload.
- `propagators.stdlib.provenance-arithmetic` — `+`, `-`, `*`, `/` that bootstrap
  base and provenance on a fresh `proc` and return the `layered/*` installer.
- [Eager Install and Arithmetic Procedure](eager-install-and-arithmetic-procedure.md)
  is a two-stage plan: experiment eager installer/macro activation (stage 1),
  then define `install-arithmetic-procedure` as a bootstrap installer (stage 2).
- [Builder Policy, Run Order, and Correctness](builder-policy-run-order-and-correctness.md)
  explains why propagator results should not depend on task order, how `:lazy`
  vs `:queue` flush boundaries interact with that goal, and which test suites
  break if policy is applied globally without refactoring helpers.
- [Experiments And Commands](experiments-and-commands.md) keeps test commands,
  benchmark commands, file maps, and open-work notes.

## Current Direction

The stable core idea is additive propagation over immutable network values:

1. Cells hold partial information.
2. Propagators emit messages.
3. `cell-merge` combines content.
4. `strongest-value` exposes the best currently readable view.
5. `run-tasks` drains explicit propagator tasks until quiescence.

The main experiment is separating network declaration from network evaluation.
A declared network is data: graph, env, and dict. Evaluation is an explicit
function over that data plus a task queue. Because of that separation, a network
itself can be treated as partial information inside a cell, and compound
propagators can run subnets as ordinary evaluation of network values instead of
requiring hidden runtime mutation.

Accumulating GUR now records executor obligations as monotone task facts in the
accumulated network. A task fact is declaration information: task identity maps
to one or more indexes. Re-seeing the same task with a new index means the task
is stronger and must be run again; the executor primitive keeps only a local
`ran [task index]` cursor. That runtime cursor is not recursive semantics and is
not stored in the named network. Current evidence: index-batched execution
helped the older lazy-accessor HOP source, but the stricter `obj/p:cons` source
builder now fails accumulating HOP with invalid scoped-dispatch owner `nothing`
or unknown child-local node errors. The failing tests remain as design evidence.

The runtime compound model is still experimental, but the main fragility today
is in compound data: `compound_data.clj` centralizes dispatch in the
linked-list-specific `c:linked-list` constraint.
