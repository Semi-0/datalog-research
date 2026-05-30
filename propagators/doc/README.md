# Propagators Documentation

This folder holds design notes for the propagator experiment. It intentionally
lives under `propagators/` so the propagator system can be separated from the
larger repo later.

## Map

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

The runtime compound model is still experimental, but the main fragility today
is in compound data: `compound_data.clj` centralizes dispatch in the
linked-list-specific `c:linked-list` constraint.
