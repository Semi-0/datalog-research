# Missing-slice recovery — 2026-10-12

## Scope and source

Base: `23eccd87e21e96e3bb57b702caa41b940503d110` on research main.
Saved source: `stash@{0}`, immutable snapshot
`615b5d5a9ab4d38d039512542e215aac69500b8f`. The stash remains intact.
This is selective recovery, not application of the complete old workspace.

Recovered 44 previously untracked source/test/report files and reconciled their
XR integration edits with current main. Main's newer compiler and renderer work
was retained. The recorded benchmark result files are historical evidence, not
fresh performance claims.

## Ownership and exclusions

| Module | Responsibility |
| --- | --- |
| XR browser | Window movement/resize, graph zoom, mobile controls, monochrome rendering, inspectors and timeline presentation |
| XR transport | Typed read-only Net encoding, explicit TTMS observation inlet, independently subscribed context delivery |
| Layered datum readiness | Recognize unusable supported values inside named-network evidence containers; ordinary sets stay ordinary |
| Synth extension | Declarative bounded notes, clock lifecycle, explicit input epochs, runtime-owned audio effects and activation recording |
| Relational experiment | Opt-in relation union/strongest and differential operators composed with the existing runner |
| Profiler | Synchronous activation/patch timing outside Net; no search or scheduling policy |

No changes to Net shape, runner constructor, TTMS retention/strongest policy,
cell evaluator, compound synchronization, or production compiler. No KIROSHI
facts were mutated. The old functional compiler, whole-compound TTMS merge
substitutions, withdrawn structural-premise prototypes, pure child-routing
experiment, LVars, local skill/model proposals and generated caches were not
restored. Historical real-accessor diagnostics are not replaced or weakened.

## Reconciliation

The synth imports the maintained production file loader and session-extension
API. Its child view uses `child-dataflow-graph`; `xr:io` interprets declarative
views. Public stage cells are explicit graph-observation anchors. Pulse-only
coverage across identity-return aliases is not an acceptance claim here.

Datalog source uses canonical `define`/`network`. Relation policy and callback
application mechanism stay isolated from production merge. Canonical callbacks
may register return ports when becoming concrete. The pending-callback test now
checks preservation of existing nodes and exact callback publisher identities,
rather than incorrectly forbidding that supported compiler interface growth.
The naive harness runs only at its explicit entry point, not on import.

Nested slot retraction formerly survived in data but was hidden from readiness
by its evidence-set container. Readiness now visits recognized evidence
containers and checks nested support compatibility; plain sets retain their old
semantics. The existing failing assertion is unchanged and now passes. This is
not a TTMS merge change or new structural accessor.

## XR protocol and lifecycle

Lightweight `xr/effects/update` notifications remain independent of full-Net
encoding. The website automatically sends `xr/context/subscribe` on connection;
the separate context worker sends `type: "xr/context/update"`. Repeated
subscription is idempotent. Existing clients need not subscribe.

The observation inlet is an ordinary TTMS cell outside the inspected Net.
Unchanged Net reads retain their epoch and cached encoding. Full environment
replacement changes generation; lightweight view packets carry generation so
stale inspection context can be cleared without discarding declarative views.
Context failures clear inspection, not the displayed graph. Connection cleanup
stops all owned subscriptions on normal close and abrupt disconnect.

The first monolithic recovery delayed selection updates while encoding complete
Nets. That integration failed the existing live-WebSocket test and was rejected.
The separate context channel fixes this without changing selection semantics or
raising timeouts. A live browser check caught the server's `type` notification
field versus command `op`; the reducer and test now use the actual wire shape.
Desktop windows have their own stacking context, below the phone menu.

## Verification

Commands are run from the repository root:

```sh
clojure -M:test
clojure -M:test relational-experiments
clojure -M:test graph.ttms-view-live-test graph.xr-context-stream-test graph.xr-inspection-test experiments.synth-test propagators.experimental.runner.profiling-test
clojure -M -m examples.lain.naive-datalog.experiment
node --test test/graph/*.mjs
git diff --check
```

| Check | Result |
| --- | --- |
| Unmodified full main baseline | 4568 pass, 4 failures, 16 errors |
| Relational, semi-naive, engine-comparison, maintained clause/proof tests | 722 assertions, all passing |
| Focused live WebSocket/context/reload, synth, inspection and profiler | 116 assertions, all passing |
| Naive non-stress smoke suite | 7 tests, 10 assertions, all passing |
| Full suite before final lifecycle assertion | 4685 pass, 3 failures, 16 errors; no new failures/errors |
| Final publication gate | 4688 pass, 3 failures, 16 errors; exact baseline failure comparison has no new identities |
| Final browser-side suite | 33 tests, all passing |

The removed full-suite failure is nested TTMS slot readiness. The remaining
failures/errors match baseline TUI/block/environment fixtures; this is not a
claim that the full repository is green. The historical `graph.xr-runtime-test`
was separately compared against an untouched temporary export of main: both
reported 165 pass, 41 failures, 2 errors. Those legacy behavior/TUI fixtures
were not migrated or weakened by this recovery.

The final live localhost browser displayed all six compound-chain windows and
a node inspector with content, strongest and actual TTMS observation premises.
A 390×844 preview verified the phone menu remains above open inspectors. No
browser console errors were reported. Pure browser tests cover drag/resize/zoom,
pinch geometry, stale socket callbacks, typed inspection and context updates.

## Limits and follow-ups

Physical phone/XR hardware and SuperCollider audio were not exercised. The
synth uses mock/silent audio in tests. Historical benchmark values were not
rerun as performance comparisons. Full-Net encoding still costs a full walk on
changed observations and can be large; the separate channel does not promise
lossless high-frequency context delivery or constant-time encoding. Child body
snapshots retain their completion-gated contract. Naive Datalog stress cases
remain explicitly unverified, not promoted as passing inference scenarios.

Experimental relation merge is insertion-only and is not a default TTMS policy.
The proof-relation example keeps alternative proofs separate without changing
TTMS's conjunctive support semantics. Pure nested routing, whole-compound TTMS
wrapping, source-file/line IR inspection, and a generic pulse-only identity-alias
tracer repair are not included in this recovery.
