# Specification decisions and amendments

## stack-spec-2026-10-20-r1 — 2026-10-20

The supplied constraint-first plan is the governing protocol. The revision pins:

- source-only recoverable blocks with explicit replay;
- observation-qualified occurrence identity, sharing, cycles, and partial tails;
- explicit pending/discovery/output-connection traversal state;
- supported constraint outcomes and separate aggregate rates; and
- incremental-versus-clean comparison without normalization by incidental IDs.

The tail-recursive composition is in `stack/hierarchy.lain`; declared fixture
outcomes are in `stack/fixtures.lain`. Primitive-dependent results remain pending.

No constraint was weakened in this revision.

## Failed candidate: recursive Lain `struct`

A candidate implemented `struct` by recursively reading `(key value)` pairs and
connecting them with `p:slot`. Loading the closure passed, but instantiating a
concrete stack fixture did not quiesce in two bounded attempts (each stopped
after more than 60 seconds). The candidate was removed. `struct` remains an
explicit obligation; compile-only success is not accepted as implementation
evidence. Any replacement must satisfy late refinement, local conflict,
idempotence, and withdrawal tests before the specification can use it.
