# Propagator Notes

The design notes have been split into focused documents under
`propagators/doc/` so this directory can become an independent project later.

## Four core features (checklist)

Restored from this file before commit `484781b`; full definitions and progress:
[doc/four-core-features.md](doc/four-core-features.md).

| # | Feature | Status |
|---|---------|--------|
| 1 | Networked semantics | Done |
| 2 | Fixpoint evaluation | Done |
| 3 | Partial information | In progress (named-network, layered, compound) |
| 4 | Dependence tracking | Not yet |

Start here:

- [Documentation index](doc/README.md)
- [Four core features](doc/four-core-features.md)
- [Core runtime model](doc/core-runtime.md)
- [Named network evidence](doc/named-network-evidence.md)
- [Compound runtime](doc/compound-runtime.md)
- [Compound data linked-list model](doc/compound-data-linked-list.md)
- [Experiments and commands](doc/experiments-and-commands.md)

Current warning: `compound_data.clj` is a linked-list-centered spike. It works
for the present tests, but the dispatch logic is centralized in
`c:linked-list` and should be generalized before treating compound data as a
stable framework.


clojure -M:wired/server
clojure -M:wired/client -name A
clojure -M:wired/xr