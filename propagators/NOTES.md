# Propagator Notes

The design notes have been split into focused documents under
`propagators/doc/` so this directory can become an independent project later.

Start here:

- [Documentation index](doc/README.md)
- [Core runtime model](doc/core-runtime.md)
- [Named network evidence](doc/named-network-evidence.md)
- [Compound runtime](doc/compound-runtime.md)
- [Compound data linked-list model](doc/compound-data-linked-list.md)
- [Experiments and commands](doc/experiments-and-commands.md)

Current warning: `compound_data.clj` is a linked-list-centered spike. It works
for the present tests, but the dispatch logic is centralized in
`c:linked-list` and should be generalized before treating compound data as a
stable framework.
