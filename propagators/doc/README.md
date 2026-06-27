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
not stored in the named network. The accumulating executor also keeps a
runner-local mailbox epoch as a scheduling token instead of hashing printed
network values. Current evidence: the stricter `obj/p:cons` source builder now
passes accumulating HOP mapper depths `5/10/15` and filter depths `5/10` without
materializing the source or output lists.

The accumulating executor also has a runner-local unchanged-prop guard. Broad
boundary/mailbox scheduling is still used for correctness, but a scheduled prop
is skipped when its declared input and output cells match the last state this
runner executed for that prop. This cut about `44-57%` of scheduled HOP prop
occurrences in the local counter run while keeping full-suite correctness green.
The request-expansion path also has a runner-local fast path: once every current
application request is known expanded or frame-declared, the runner skips the
request-map scan/sort until a new request fact appears.
The runner also selects only the next pending task instead of rebuilding the
whole pending task vector on each child loop, and `core/eval-cell` skips exact
content-duplicate messages before cell merge. Accessor-network merge records the
slot index it has already refined, so repeated merge reads do not reinstall the
same canonical/avatar/sync topology. The task queue now uses `PersistentQueue`
internally to avoid copying the remaining vector on every pop.

Current retained local HOP timing is below the current practical sub-`300 ms`
depth-15 bar, while still not claiming the older sub-`100 ms` target. On
`2026-06-27`, after accumulating-GUR and named-network merge fast paths,
`clojure -M:gur-accumulating-bench 5 21` measured mapper depth `15` at
`273.273 ms` median and `292.267 ms` max, and
`clojure -M:gur-accumulating-bench 10 31` measured mapper depth `15` at
`266.642 ms` median. Both benchmark runs kept every scenario `ok=true`.
Full regression after the shared named-network change:
`clojure -M:test` -> `1393 pass, 0 fail, 0 error`.

The intended coordination-language framing is now narrower than "everything is
GUR." Ordinary programs should mostly be primitive propagators, iterative
operators, explicit behavior reducers, slots, and retained application data.
GUR is the advanced layer for recursive declaration problems: macros, compiler
construction, recursive AST/list traversal, recursive lexical accessor
construction, and higher-order operators that need unbounded but idempotent
network expansion. That makes the current accumulating-GUR performance good
enough for prototype compiler work, while leaving long-term GC and behavior
version retention to behavior-aware propagators and behavior merge policies.
Compound objects are similarly scoped as structural carriers: they should
preserve behavior/TMS-like partial-information content through slots and
accessors, but behavior owns time/version policy and a future TMS owns support,
justification, and retraction policy.

The retained optimization keeps declaration and evaluation separate. Cell merge
still only refines declaration facts; runner-local cursors/cache state stay in
the executor primitive. The useful cuts were: idempotent accumulated-fragment
merge for GUR network facts, less allocation in named-network preorder/join,
request-only mailbox suppression, cached boundary/request scans, and cached
accessor export collection lookup. A direct "always join accumulated fragments"
cell-merge path was rejected because it returned fresh equal network values and
made the focused accumulating suite hang; idempotence must return the existing
network value when the update is already subsumed.

The runtime compound model is still experimental, but the main fragility today
is in compound data: `compound_data.clj` centralizes dispatch in the
linked-list-specific `c:linked-list` constraint.
