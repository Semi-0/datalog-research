# Interactive windows with ordinary TTMS network observations

## Recovery update — 2026-10-12

The website automatically requests `xr/context/subscribe`; full Net observations
arrive separately as `xr/context/update`. Lightweight declarative-view updates
remain available to existing clients without requiring full-context encoding.
The full Net is transported through the existing TTMS inlet, outside the observed
Net. Generation changes clear stale inspection context. Desktop windows form a
separate stacking context so they cannot cover the phone menu.

Earlier verification notes below describe the saved prototype; the current
recovery checks are recorded in `experiments/history/missing-slices-recovery.md`.

## Run

From the repository root:

```sh
clojure -M -m examples.lain.visualization-combinators.compound-levels 45671 127.0.0.1
```

Open http://127.0.0.1:45671/relationships. Saving `compound_levels.lain`
recompiles the complete environment using the existing file watcher. Stop with
Ctrl-C. This address is localhost only; binding a LAN address exposes the full
network inspection data and existing runtime command endpoint to that network.

2D is the initial mode. Each child of a declarative juxtaposition becomes a
window. Drag its title, resize with the lower-right grip, pan the graph by
dragging its background, and zoom with the wheel or +/− buttons. Close windows
with × and reopen them from the Views menu. Node clicks (or keyboard Enter)
open live inspectors. 3D/XR retain their previous renderer and interactions;
the new movable desktop windows are a 2D feature, not an XR-controller feature.

### Phone controls and LAN

Phone screens use one scrollable column and a compact Views menu. Window buttons
have 44px touch targets, and the resize grip is enlarged. Drag a window title to
move it; drag the graph to pan; pinch with two fingers to zoom while preserving
the graph coordinate under the moving midpoint. Tap a node to inspect it. Fit
and +/− remain available without a wheel.

To expose this example to a trusted LAN, replace the localhost host argument
with the computer's Wi-Fi address and use an unused port, for example:

```sh
clojure -M -m examples.lain.visualization-combinators.compound-levels 45672 192.168.178.96
```

The phone must be on the same network. This exposes full Net inspection data and
the existing runtime controls to devices that can reach that address; it is not
an authenticated read-only viewer. The address can change. No firewall or router
settings are changed by these instructions. After explicit approval of that
exposure, the LAN server was launched at the example address above and its
`/relationships` page returned HTTP 200. Access from a physical phone remains
to be verified.

Phone-control verification: 18 browser unit tests passed, including pinch anchor
preservation, zero-distance safety, single-column placement, existing mobile menu
behavior, and unchanged desktop interaction math. Real-device pinch input remains
to be verified.

LAN 2D rendering fix: SVG arrow markers originally used `crypto.randomUUID()`,
which is unavailable on ordinary insecure LAN HTTP origins even though localhost
allows it. Marker IDs now use a page-local counter; these are DOM identifiers,
not semantic IDs or security tokens. All 19 browser tests passed, including graph
initialization without the crypto API and uniqueness across two windows. The LAN
server was checked to serve the updated JavaScript. Physical-phone rendering after
reload remains to be confirmed; the in-app LAN browser diagnostic timed out.

Monochrome styling: desktop windows, graph wires, node shapes, and 3D view
textures now use only black/white/gray. Windows are square and desktop labels use
monospace text. Cell circles and propagator triangles remain distinct without
color. The 24-test browser suite passed, including a check of the renderer color
literals, and `git diff --check` passed. Physical-phone appearance after reload
has not yet been confirmed.

## Contracts and ownership

The publication boundary uses one ordinary TTMS source cell, outside the Net
being observed. Its observation is `{base: complete-Net, support: premises}`;
each premise still contains exactly source cell ID, timestamp and status.
There is no new stream, revision, frame or latest-wins data structure. The
existing session generation is transport metadata used to retire old UI
inspectors on full reload, not a TTMS timestamp.

`graph.xr-network-inlet/publish` retains evidence with the existing cell/TTMS
merge and projects it with `support-collection/strongest-value`. Its injected
base merge treats a completed Net atomically: nothing is identity, equal Nets
agree, unequal same-version observations contradict. This avoids interpreting
the inspected program's dictionary as compound refinement. All TTMS support
ordering, compaction and retraction rules are unchanged. `observe` advances the
source timestamp only for a different completed immutable Net. Reading the same
Net does not create another observation. `observe-context` caches only its
encoded transport, not hidden semantic history.

The XR server captures effects and the corresponding session state under the
session lock, then encodes outside that lock. Its 500ms publication poll samples
completed updates; it can coalesce intermediate completed states. This is not
an activation-history recorder or a guarantee that every intermediate update
will be delivered.

Publication preparation is serialized through the existing inlet lock so
concurrent clients cannot stamp an older captured state after a newer one.
The existing WebSocket carries full context. `/api/relationships` remains the
small graph/view endpoint; enlarging that legacy polling response with full Net
context caused a measured reload timing regression, so that change was removed.

`graph.xr-inspection/encode` retains all Net fields, typed dictionary keys,
records, sets, vector/list structure, exact numeric text, NodeIds, support and
shared references. Executable/unsupported host objects are explicitly opaque;
they are never invoked, dereferenced or reconstructed as browser code. The
object table is a serialization mechanism, not a new semantic dictionary.
Equal immutable values share a transport object only when their type tags agree;
list/vector distinctions and explicit occurrence identities remain intact.

Browser responsibilities are split into:

- `inspection.js`: read the typed data and locate path-qualified entries.
- `desktop-state.js`: immutable local geometry, zoom and position retention.
- `desktop.js`: windows and reconciliation of published view identities.
- `desktop-graph.js`: SVG layout, arrows, shapes, picking and navigation.
- `inspector.js`: lazy entry/evidence/topology inspection and source links.

Inspectors expose strongest and retained content, graph inputs/outputs,
available parent/child relationships, names directly present in that Net's
dictionary, and TTMS premises. Publication premises link to the actual inlet
source cell. Nested references remain path-qualified. Synthetic syntax nodes or
absent entries display metadata and an explicit unavailable notice rather than
inventing a runtime entry. Domain values can be unnamed in the outer Net's
dictionary; the graph's semantic label remains the inspector title.

Geometry, node positions and zoom are browser-local and do not write through
provenance. A generation change closes old inspectors. Missing/withdrawn,
pending or contradictory contexts are marked unavailable for live lookup.
Reconnection ignores callbacks from an abandoned socket. This does not implement
TTMS projection in JavaScript. Net, runner, compiler, TTMS, concrete activation,
compound synchronization and headless view transformations are unchanged.

## Verification

Focused commands:

```sh
clojure -M:test graph.xr-inspection-test
node --test test/graph/xr_desktop_test.mjs test/graph/xr_static_update_test.mjs test/graph/xr_mobile_controls_test.mjs
git diff --check
```

Final focused results: 38 Clojure assertions and 17 browser unit tests passed.
Tests cover atomic named/full Nets, duplicate observation/read identity, stale
replay, same-version contradiction and repair, withdrawal and recovery, exact
encoding and sharing, session-lock capture, coherent generation, nested lookup,
unavailable contexts, source identity, pointer-centred zoom, bounds and stable
node positions. The original HTTP external-file reload test passed all four
assertions with its five-second deadline unchanged. Existing compound-level,
retained-call-graph and visualization-composition tests passed all 80 assertions.

The broader `graph.xr-runtime-test` is not healthy: the committed baseline
produced 43 failures and 1 error across 55 tests/208 assertions; the updated run
produced 41 failures and 1 error across the same counts. The difference is a
timing-sensitive large-TUI test, not a claimed fix. Shared nonpasses concern
legacy tracing, behavior/slider/TUI paths and a non-empty-past-block exception.
An initial combined runtime/server run was stopped after the runtime result
when it remained waiting in server tests; bounded individual checks were used
instead. No full-suite pass is claimed and these assertions were not weakened.

Manual browser verification: six windows render; cell/source inspection resolves
the actual runtime value; drag moved an inspector from (24,24) to (74,124);
resize changed it to 532×550; independent graph zoom reached 1.25. The publication
premise opens its actual source cell. Changing the example input 2→4 rebuilt the
environment, retired both old inspectors, updated the child list to 25/4/5, and
the new result inspector showed 458329 with publication timestamp 2. The source
file was restored to 2 afterward. Narrow-screen initial placement uses one
scrollable column; wider viewports use two columns.
The final browser check at 1280×900 confirmed two columns and a live indexed
pipeline relationship (63 children, including internal topology). At 390×844,
windows use one column and the compact menu opens correctly. Mode selection is
at the menu top and windows reserve space below the floating menu button. Close
and reopen, plus switching 3D→2D without losing windows, were also checked.

## Limits and evidence proposal

Full Net context can be large (the initial identity-only encoder produced over
100,000 objects for this example). Equal typed values now share transport
objects. Unchanged encoding is cached, but each changed completed network still
requires a full encoding. No performance or lossless-history guarantee is made.
Physical XR, 3D window manipulation and long-running high-frequency workloads
have not been verified. Example `body-snapshot` remains a completion-gated
snapshot, not a recursively live observer; this UI does not change that contract.

Candidate KIROSHI evidence, for separate review: interactive geometry and
inspection remain visualizer-owned; a runtime-owned ordinary TTMS input publishes
completed immutable Nets; typed transport preserves retained evidence and
explicit projection. No model facts were changed, and no commit/push is included.
