# Session API grounding entry point

Status: source-grounded reference, 2026-11-02; current uncommitted working tree. This is documentation for KIROSHI and other coding agents, not an approval or durable architecture-database mutation.

Read the canonical [Lain session API reference](../propagators/doc/session-api.md) before creating, extending, observing, attaching or stopping a session. It inventories the compiler protocols, constructors, operator hooks, dependencies, file/native-module preparation, live host boundary, receipts, editor registry and HTTP commands. The [implementation guide](../propagators/doc/immutable-compiler-sessions.md) records actual local acceptance results.

Use source as authority. In particular:

- Compiler snapshots are immutable; the application owns one live admission/delivery boundary.
- Effect handlers may emit zero/one/many times, including while running; normalized input enters later committed rounds.
- Canonical live references preserve support, availability, retraction and caller ownership. Do not materialize compound graphs in host adapters.
- Session ID, run ID and editor generation are distinct. A fresh run fences old callbacks.
- Native preparation can execute arbitrary Clojure and have side effects before catalog publication. Atomic extension does not undo those effects.
- New primitive revisions do not replace existing instantiated propagators. Use explicit namespace/binding revisions.
- Existing editor registries own/close their entries. `register-compiler-session!` is not a non-owning lease, and `restore!` recompiles declarations.
- The sandbox service must own its runtime; view attach/reopen/refresh must not replay declarations or transfer that ownership.
- Compiler/session APIs and SSH session IDs provide no OS isolation. Remote mutation receipts, scoped transport credentials and containment are separate integration requirements.

Verified local compiler/editor gates must not be described as live GX10 sandbox acceptance. Revalidate live host, transport, limits, browser behavior and cleanup separately. Do not mutate durable KIROSHI architecture facts merely because this reference exists.
