# Merge removal result — 2026-10-22

The approved endpoint was complete removal of temporal behavior and legacy event
APIs, with supported consumers migrated before deleting the old implementations.
This endpoint is implemented locally. It has not been committed or pushed.
A fresh loopback editor server on port 4784 runs the current implementation for
the AMB gallery; the existing editor on port 4781 was left untouched.

## Implemented boundaries

The built-in merge registry went from twelve methods to eight. Semantic graph,
epoch, behavior, and event registrations are gone. The remaining methods are
basic/default, named-network, closure, reducer-cell, reducer-subnet, distributed
TMS, support collection, and membership collection. Dependency, scope-source,
and intensity remain three separate fallback handlers; this work does not prove
them unused. The duplicate direct routes and unused installation flag are gone.

Compiler call graphs now compose independently addressable contributions through
an explicit graph-union propagator. Replacement and withdrawal replace the
producer's contribution, not its siblings. Graph publication carries support and
current premise states; equal publications do not advance their revision. Clean
reconstruction is checked against refinement/withdrawal in the union fixture.

Clocks, widget inputs, bridges, display boundaries, list-text, and trace results
publish through TTMS. Source identities are stable NodeIds and independent ports
have distinct identities. Shared-source inputs at different revisions remain
pending rather than producing a falsely coherent result.

Both reducers remain. Compound-object reduction and dispatch use reducer-subnet;
it now folds ordinary public slots instead of recognizing tagged event facts.
History retention is an explicit fold over immutable snapshots. TTMS selects
currently supported information; it is not the history accumulator.

Supported TUI displays are read from their owning program network in both editor
modes. Legacy graph-snapshot effects and reactive graph views are explicitly
selected at the XR assembly boundary. The one-argument XR constructor preserves
its reactive-view behavior, including withdrawal and recovery. Widget display
projection handles supported content before formatting; its epoch comes from a
single supporting source, not unrelated transported state facts.

The deleted code remains recoverable from Git history. No compatibility loader,
process-global live-editor redefinition, or silent historical-syntax translator
was added. Ordinary unbound symbols can wait, so loading an old program is not
proof that its removed operators executed.

## Verification actually observed

| Check | Passed | Failures | Errors |
| --- | ---: | ---: | ---: |
| Propagators plus focused history/boundary/file-loader checks | 4770 | 0 | 0 |
| Graph suite | 1292 | 1 | 0 |
| Runtime server | 300 | 8 | 3 |
| XR runtime | 198 | 7 | 2 |
| Mirrored session versioned commits | 71 | 2 | 0 |
| Frontend main Node suite | 699 | 0 | 0 |
| Separate remote-files Node summary | 33 | 0 | 0 |
| Isolated merge audit fixtures | 672 | 0 | 0 |

The graph failure was reproduced on the clean baseline. The runtime-server
baseline also had 8 failures and 3 errors. All remaining XR failure/error test
names were present among the baseline's 41 failures and 2 errors. The mirrored
versioned-commit baseline had the same two failures. Retired behavior-only tests
and migrated fixture representations mean suite populations differ; these totals
are not an apples-to-apples percentage improvement claim.

The final widget normalization change and its four-assertion regression fixture
were checked by the final XR run after the preceding propagator run. Whitespace
verification passed. Graph-linked test discovery is incomplete for dynamic
compositions; Gortex has no configured guards and reports high change risk. These
are limitations, not additional passing verification.

[Audit counters](runs/2026-10-22-audit.edn) and
[verification metadata](runs/2026-10-22-verification.edn) record source fingerprints,
environment, commands, and limitations. Audit-stage and final-stage fingerprints
are distinct. Artifacts contain data only, no live JVM capabilities.

## Counterexamples and corrections

- Replacing graph union with ordinary transport merge passed final assertions but
  introduced contradictory intermediate values. Producer-aware auditing exposed
  the hidden composition performed by the old rule.
- An inbox containing only contribution IDs did not wake on replacement. Explicit
  revision signals now schedule composition while authoritative values remain in
  their contribution cells.
- Zero-input union normalization previously depended on a transport side effect.
  Explicit empty composition now produces consistent aliases.
- On-demand runtime refresh still seeded raw graphs, causing contradictions after
  migration. All runtime graph publication paths now publish supported revisions.
- Trace subscription creation was gated on a graph read that was not an activation
  dependency. Removing that unnecessary gate allows subscription before topology
  is available; later publication supplies the graph.
- Treating every supported graph as a snapshot broke the existing reactive-view
  withdrawal fixture. Explicit presentation at composition boundaries preserves
  both existing interfaces without choosing intent from transport alone.
- A supported display written into the program network was invisible to legacy
  pane inspection. Ownership-based selection fixes that without changing raw
  boundary-write fallback.

## Limits and conclusions

The evidence supports removal for the exercised compiler, graph, clock, history,
widget, TUI, XR, and replay paths. It is not proof that every historical example or
external caller is compatible. The AMB browser check below is narrower than full
browser acceptance or deployment. Remaining baseline failures are recorded, not
repaired by weakening assertions or treated as passing checks.

## AMB follow-up investigation

The editor's learning-replay fixture has a root semantic scope and observation
identity. A node-level `semanticScope` is only emitted for a nested compound;
requiring one in this primitive fixture was obsolete. The replacement assertions
check root scope, observation identity, an AMB propagator, closed edge endpoints,
`chosen = 2`, `valid = true`, idle repair, and the learned index-zero no-good.
Existing frozen-prefix and read-only inspection assertions remain.

The separate whole-compound `(switch (list a b c) valid)` characterization no
longer throws `Expected supported collection content`. It reaches `:completed`
with active choices 3, 4, 5, but its root is `:bool4/nothing`, its list snapshot
is not ready, and no solution is extracted. This is a genuine unresolved-output
gap, not solver acceptance or evidence that the compound-gating implementation
is correct. Its test now explicitly characterizes those observations rather than
expecting the obsolete exception. Per-element gating still has a separate solver
acceptance test. No production solver/tracer code was changed for this follow-up.

Built the frontend with `clojure -M:editor/cljs`, started
`clojure -M -m graph.lain-editor.server 4784`, and imported
`examples/lain/amb/editor/workspace.json` into a new Safari tab. Desktop 3 rendered
8 recorded observations, 20 nodes, and 21 edges, with `chosen = 2` and
`valid = true`. Previous/next observation navigation was checked. This confirms
the exercised gallery trace, not every browser interaction.

Focused verification after the assertion corrections passed **288 assertions,
zero failures, zero errors** across editor AMB, recursive semantic expansion,
semantic scenes, and AMB runtime/learning/search/Lain tests. The whole-compound
characterization is included in that total and remains an acknowledged gap, not
a successful solver case. `git diff --check` passed.

The full default Clojure runner was then repeated with bounded failure reporting:
**6316 assertions passed, zero failures, zero errors**, exit status 0. The runner
omits the separately exercised runtime-server, XR-runtime, and mirrored-session
suites whose baseline failures are listed above. Thus the default gate is green,
not every repository suite. No commit or push was made under the user's
all-tests-pass condition; publication needs an explicit decision about those
known baseline failures. The whole-compound gap likewise remains unresolved.

No speedup is claimed. Explicit composition adds nodes: the gallery accumulator
at n=8 retained 3592 nodes in the audit, compared with an earlier observed baseline
of 3427. These are diagnostic observations, not warmed benchmarks. Runtime latency,
instrumentation overhead, and development productivity remain unmeasured.
