# Lain editor backend

The browser frontend is maintained in [Semi-0/lain-editor](https://github.com/Semi-0/lain-editor). That repository owns pure Replicant views, Missionary view-state composition, frontend domain/UI models, assets, portable contracts and frontend tests.

This module owns JVM pane sessions, execution and extension, inspection/history capture, Standard Stress layout with exact node clearance, development runtime, and project-file/workspace effects. Runtime/compiler/merge semantics are unchanged.

## Run

From this repository:

```sh
clojure -M:editor/cljs
clojure -M -m graph.lain-editor.server 4780
```

The stable build entrypoint delegates to the pinned frontend Git build provider and writes assets to `target/lain-editor/public/lain-editor`. The server serves those generated assets and the existing `/api` protocol. It remains loopback-only and rejects cross-origin API requests.

For separate frontend development, run the frontend dev server against this backend. See the frontend [running guide](https://github.com/Semi-0/lain-editor/blob/main/docs/running.md). The complete [user guide](https://github.com/Semi-0/lain-editor/blob/main/docs/user-guide.md) and example-workspace resource live there. Application programs and examples under `examples/lain` remain backend-owned.

## Dependency boundaries

Runtime depends only on the frontend repository's `contracts` subproject: workspace validation, layer descriptions, the development protocol and node-clearance geometry. Their production source blobs are unchanged by extraction. Optional build aliases consume its `build` subproject; frontend test builds consume its explicit `test` subproject. The combined test alias pins the frontend root directly so both subprojects resolve to one release instead of competing local roots. Backend integration tests depend on its pure model helpers. No sibling checkout is required, and there are no copied frontend source trees or browser framework dependencies in the backend runtime coordinate.

The frontend sends the existing run/extend/inspect/layout/restore/close/save commands. JVM execution networks and history are authoritative here; frontend observations cannot mutate them during rendering. No new accumulator, solver, compiler or global merge policy was introduced.

## Verify

```sh
clojure -M:editor/cljs-test
node target/lain-editor/tests.js
clojure -M:test graph.lain-editor.development.runtime-test graph.lain-editor.layout.graph-layout-test graph.lain-editor.workspace.source-files-test graph.lain-editor.runtime.slot-spawn-test
```

JVM HTTP integration tests need loopback access. Before cutover, an isolated backend candidate with copied frontend sources removed passed its application and frontend-test builds and 37 focused JVM tests/252 assertions. Standalone verification passed 32 JVM/shared/architecture tests/232 assertions. Final frontend verification passed 107 tests/850 assertions and 21 real-browser checks, including recursive AMB learning and frozen replay without live-node reads. The runtime-only classpath also served all generated assets and evaluated a real Lain program independently of the frontend build aliases.

The pre-existing `real-semantic-topology-and-child-inspection` expectation still omits the `copy` and `->` propagators; this extraction preserves that known backend failure. Native Escape in headless Chromium was separately blocked by a minimal-page driver probe; fullscreen entry/API exit and mounted graph/camera preservation passed.

Historical Kiroshi facts and evidence snapshots remain intact. Knowledge synchronization/approval is a separate pending operation, and no database backup was rewritten as a path-move side effect.
