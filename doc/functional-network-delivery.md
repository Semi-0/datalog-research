# Functional-network migration delivery

The production compiler owns canonical `define`, `network`, and linked-list
`apply`. Runtime owns sessions, effects, loading, and pure inspection. Wired owns
UI assembly, web routing, graph rendering, and widget presentation. The research
repository mirrors these owner snapshots with its established namespace layout.

| Repository | Published main revision |
| --- | --- |
| [lain-compiler](https://github.com/Semi-0/lain-compiler/commit/b9806338dfeea418210733b748ebce38f23c561b) | `b9806338dfeea418210733b748ebce38f23c561b` |
| [lain-runtime-clojure](https://github.com/Semi-0/lain-runtime-clojure/commit/473876b1433d312a3f58b3d9ab13c1afe6d91edf) | `473876b1433d312a3f58b3d9ab13c1afe6d91edf` |
| [wired](https://github.com/Semi-0/wired/commit/e82ea1668eca61cf74b194293cf566cd643daa28) | `e82ea1668eca61cf74b194293cf566cd643daa28` |

Runtime pins the published compiler revision; wired pins both published owner
revisions. Infrastructure remains at `6b031cf71df7a27a71e52c14cf1227904f637a13`.
No infrastructure, GUR, compound-object, TMS, merge, scheduler, environment-kernel,
or immutable session-extension changes were made. No remote history was replaced
and repository default-branch settings were not changed.

## Verified checks

| Check | Tests | Assertions | Result |
| --- | ---: | ---: | --- |
| Compiler owner suite | 147 | 464 | Pass |
| Runtime suite on published compiler pin | 158 | 572 | Pass |
| Maintained web integration on published owner pins | 32 | 163 | Pass |
| Shared-session port regressions on published owner pins | 4 | 14 | Pass |
| Mirrored compiler/CPS/application/linked-list-GUR/TMS checks | 187 | 622 | Pass |
| Mirrored UI assembly/server/loading/replacement/watch checks | 13 | 53 | Pass |
| Browser-side Node tests | 14 | — | Pass |

Every Clojure test Var was timed independently and finished within three seconds.
Namespace startup was separate. Print limits were scoped only to test reporting;
globally bounded print runs were excluded because they change stable-ID encoding.
These rows overlap; they are not an additive aggregate or a claim that every
historical research/UI test passes.

The actual localhost browser compiled canonical source, reported 7 nodes / 6
edges, rendered named calls/cells/results, and acknowledged a slider event as
`gain/value: 1`. No browser errors were reported. The temporary browser and
server were stopped after verification.

The previous workspace slider gap was fixed by preserving the existing wired
assembly in `graph.compiler-2-assembly`, with the deprecated graph facade
delegating session creation to it. Remote wired additions and file-watch checks
were retained. Source examples were migrated offline; no runtime syntax adapter
was introduced.

Temporal behavior is deprecated and absent from active session roots. Historical
opt-in behavior code/tests remain. Terminal TUI repair, unrelated performance
work, physical mobile/XR testing, and physical deletion of historical behavior
are excluded. The original analyzer experiment and `.agent-memory` were untouched.

KIROSHI evidence is prepared in
[functional-network-kiroshi-evidence.edn](functional-network-kiroshi-evidence.edn).
It has not been persisted, approved, or used to supersede model facts.
