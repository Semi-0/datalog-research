# Compiler 2 environment API audit

Audit date: 2026-09-15. `model/env.clj` is an active Compiler 2 dependency.
Deprecation preserves public Vars and behavior; it does not remove their code.

## Active responsibilities

| Responsibility | Entry points | Consumers |
| --- | --- | --- |
| Compound environment construction | `bind`, `bind-at`, `set-depth`, `lookup` | compiler basis, behavior compiler, runtime environment setup |
| Symbol compilation | `lexical-binding-status`, `p:lexical-access-local-first`, `p:binding-value` | compiler handlers |
| Closure frames and local declarations | `p:scope-frame`, `p:declare-canonical-local`, reservation APIs | application runtime, compiler declarations |
| Import and extension | `import-environment-topology`, `declare-bindings` | CPS compiler, session program, session extension |
| Runtime resolution and inspection | `resolve-binding`, `resolve-binding-id`, `binding-names`, `lexical-topology-effects` | sessions, call graph, topology effects |
| Scoped compatibility | `p:lexical-access`, `p:structural-lexical-access`, `externalize-env` | runtime resolution, application output, compatibility tests |

The first structural accessor previously defined `p:lexical-access`, captured
that function in an alias, then redefined `p:lexical-access`. It now declares
`p:structural-lexical-access` directly. Both public call shapes are preserved.

## Deprecated APIs with no repository consumers

Repository source, tests, and textual references were searched. Internal calls
within the following unused family do not make that family reachable from an
active compiler or runtime entry point:

```clojure
#{bind-locals
  p:reducer-lexical-access
  p:bound-value-layer
  p:scope-dependent-read
  p:direct-lexical-value
  p:reducer-lexical-value
  p:structural-lexical-value
  p:legacy-lexical-value
  p:local-first-lexical-value
  p:lexical-value
  boundary-ids
  rebind-boundary}
```

These Vars carry `:deprecated true`. New compiler symbol reads should compose
`p:lexical-access-local-first` and `p:binding-value`; that composition returns
ordinary values, so it is not a drop-in replacement for callers requiring
scope-source provenance envelopes. Such external callers can retain the old
APIs while migrating explicitly.

The reducer representation itself is still used by environment construction,
declarations, import, and eager inheritance. Structural scoped lookup is still
used by `resolve-binding`. Neither is removable merely because the compiler's
ordinary symbol path uses flat GUR. Repository searches cannot rule out external
consumers in separately delivered modules.

This audit changes only the environment namespace and its documentation. It
does not change frame semantics, the scheduler, GUR, compound objects, or TMS.
