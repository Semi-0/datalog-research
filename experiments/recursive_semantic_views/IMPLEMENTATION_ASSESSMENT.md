# Recursive semantic views: implementation assessment

## Fullscreen topology presentation

Added a Full screen / Exit full screen button in a frontend-only topology shell.
The browser fullscreen API expands the existing graph, replay timeline and layout
controls without replacing their mounted children. Escape is browser-owned;
fullscreen change events synchronize the button. Standard and WebKit APIs are
supported, failures are displayed, and pane disposal releases owned listeners
and exits an owned fullscreen view. Container growth expands the canvas without
changing pan/zoom or invoking the layout provider.

Verification: frontend suite passed 65 tests / 629 assertions; production build
and whitespace checks passed. Tests cover enter/exit, simulated Escape, content
identity, cleanup, prefixed APIs, rejection reporting and camera-preserving
viewport growth. Safari was refreshed for manual verification, but the saved
workspace was still rebuilding. A subsequent browser observation confirmed the
fullscreen shell with the replay at observation 2/34 and 43 nodes / 55 edges.
The user subsequently confirmed playback was working; no further replay change
was made. Automated end-to-end stepping through fullscreen remains unverified.

## Adjustable node spacing

Added Normal (1x), Roomy (1.5x), Wide (2x) and Very wide (3x) controls next to
the layout budget. Selection explicitly relaxes the existing semantic graph;
card sizes, node IDs, edges and camera state are unchanged. Spacing and budget
settings are retained with the pane/generation/scope view state across remounts.
This is the simpler spacing experiment requested after the circular-layout
discussion; circular layout itself is not implemented.

The layout provider uses the numerical engine's public size adapter to scale
layout footprints, plus edge-length targets and minimum clearance. Scaling just
edge length would have little effect on very tall cards whose radii already
dominate the target. The rendered/public sizes are never scaled. Spacing changes
recreate the owned flow through its public constructor, seeded at retained
positions, without mutating opaque engine state. This rebuild recomputes distance
preparation; unchanged spacing/input still does zero solver work. Default spacing
preserves the 28-unit clearance; larger settings increase it. Invalid settings or
dimensions are rejected before replacing the prior flow.

Verification: layout suite passed 18 tests / 140 assertions; frontend suite
passed 61 tests / 610 assertions; production build and whitespace checks passed.
Coverage includes defaults, increased separation for 1,000-unit-tall cards,
unchanged public dimensions/topology, returning to normal spacing, zero work on
unchanged requests, transport settings and invalid-request isolation. The server
was restarted. Manual browser spacing interaction is not yet verified because
the active Safari window changed during the reload attempt.

## Convergence with a user-selected layout work budget

The host layout provider replaces its fixed 300-iteration solve ceiling with
Standard stress relaxation in batches of ten iterations. It stops when maximum
movement is at most 0.05 world units per step or its monotonic time budget is
spent. The default is 1,000 ms; validated requests may select 1–10,000 ms.
This is cooperative work budgeting, not preemption: a batch can overrun the
deadline, and transport/final clearance are not a wall-clock response guarantee.
Movement convergence is not a claim of a globally optimal stress minimum.

The interface offers 250 ms, 1 s, 3 s and 10 s budgets plus Refine layout.
Refinement is explicit, reuses the retained scope flow and distance cache, and
does not reset the camera. Automatic unchanged-input requests still do zero
solver work. Drag updates now synchronize retained solver positions without
relaxing neighbors or introducing pins, so refinement starts at the displayed
released position. Status distinguishes convergence, spent budget and exact
clearance adjustment. Animation milestone storage is bounded at 64 snapshots.

Verification: layout suite passed 15 tests / 119 assertions; frontend suite
passed 61 tests / 607 assertions; production build and whitespace check passed.
A 100-node chain converged after 460 iterations in approximately 408 ms under
a 1,000 ms budget, demonstrating work beyond the former ceiling. Native browser
accessibility inspection showed the budget selector and Refine layout button.
The server was restarted with the provider changes. Manual dropdown/refinement
interaction was not verified because the active Safari window changed.
No shared numerical engine, task policy, merge or semantic tracer change was
needed for this layout feature. No model sync, commit or push was performed.

## Three-alternative triangle semantic-history check

The triangle example now displays `collect-semantic-history triangle 0 600000`
using the existing collector. Its documented seed is `(list 3 4 5)`: at most 27
assignments, with learning allowed to prune. The local diagnostic
`step_triangle.clj` drives the owning session's selected iterator directly;
it adds no shared runner API, policy or mutable Net state.

Observed stepped execution completed in 1,675 outer transitions, 13 recorded
frames and 13,773 ms, ending at `(3 4 5)`. Intermediate sides included `(3 3 3)`,
`(3 3 4)`, `(3 3 5)` and withdrawals. This demonstrates completion and progress
for the smaller domain, not a general performance fix for larger recordings.
The earlier five-alternative recording stalled in reduction and was stopped.
The 600,000 ms selection window is not a recorder retention cap or frame count.

Follow-up profiling used the existing `debug/call-with-activation-profile`
wrapper, including nested runner invocations, around the session-selected
iterator. With `triangle` as root it confirmed 13 frames but only the list
container as topology, and roughly 80% of measured exclusive activation time
in capture. The earlier reducer thread sample was not a complete attribution.
The example now explicitly roots at the `valid` constraint, preserving the
actual dataflow rather than inventing list-container edges. That profiled run
completed in 2,697 outer transitions, 34 frames and 32,679 ms, with `(3 4 5)`
and all three AMB calls in the recorded graph. Capture accounted for about 53%
of exclusive activation time; reduce's inclusive work was about 13.9 seconds.
These measurements describe different roots and are not comparable topology
contracts. No shared capture, reducer, scheduler or merge change was made.

## AMB declared-port correction and triangle example

`application-ports` now reads primitive input/output roles through the public
operator call interface, while preserving closure argument behavior, ordered
returned members and connection validation. Incomplete declarations without an
operator identity remain readable. This is a read-only topology correction, not
a runner, merge or AMB policy change. The regression verifies the JSON graph has
`amb -> decision` and not `decision -> amb`, and rejects a disconnected declared
secondary output.

Added `examples/lain/amb/editor/pythagorean.lain`: three finite AMB choices,
learning/backtracking, and a normal list result with no presentation wrapper.
The editor test starts its domain and verifies `(3 4 5)`, validity, idle repair,
learned knowledge and the actual result list.

Focused AMB, graph outputs, functional syntax, recursive capture, relationship
dataflow, semantic application/traversal, dataflow projection and logic suites
passed 301 assertions total. The broader semantic gallery had eight failures
comparing legacy live graph metadata against captured graph metadata; the same
eight failures were reproduced with legacy port behavior restored through a
non-persisting test override (333 assertions passed in that control test).
No gallery expectations were weakened. The restarted server answered HTTP 200;
manual browser edge inspection and a full-suite rerun were not performed.

## Subsequent clarification: generic structure and view-only folding

The generic value renderer no longer recognizes the AMB learning-view wrapper.
Raw no-good sets render directly through ordinary set, map, vector and public-slot
traversal. The optional producer wrapper remains readable through its public slots,
but has no special host formatter. Removed the unused `nogood_view.clj` formatter;
labels such as "forbidden combination" are not inferred or stored by the view.
Empty sets retain their `#{}` representation. Solver storage is unchanged.

The nearby Value view menu selects a cell and independently folds public layers
and nested fields. Folding is local presentation state scoped by pane generation,
cell, section and structural path. It projects immutable rows outside the general
graph renderer, does not mutate observations and reuses cached inspections.
Inline disclosure controls now use that same state through presentation events;
click, Enter or Space folds/unfolds branches directly inside cell value trees.
The general label renderer emits opaque control events, not folding policy.
Inline-control production build passed; frontend checks passed 59 tests / 596
assertions, including disclosure metadata, emitted accessible controls and the
view-only event route. Inline controls have not yet been manually browser-tested.

Verification: focused backend AMB/value presentation checks passed 207 assertions;
frontend suite passed 58 tests / 587 assertions; diff whitespace check passed.
Native Safari accessibility inspection confirmed the running menu and per-field
controls for base, support, timestamp and nested values alongside the AMB graph.
This browser observation does not verify the newly changed backend formatter in
the already-running server process. The earlier no-folding statement below is
superseded. No model mutation, commit or push was performed.

## Subsequent user clarification: recursive values, not propagator subnets

The user clarified that recursion should reveal nested compound values inside
cells (for example growing no-good sets), not propagator bodies. This supersedes
the subnet-preview presentation described in the historical assessment below.

Propagator mini-graphs, automatic subnet acquisition/layout requests, enlarged
preview cards and the subnet navigator are now removed from the host renderer.
Hidden propagator scope metadata no longer affects the rendered topology shape.
Plain propagator rectangles and rounded value-bearing cells remain distinct.
Nested public value rows now render as an always-expanded indented tree with
parent/child branch lines, including recursively rendered collection contents.
The existing slot/entry traversal supplies this hierarchy; no raw runtime
network shape is interpreted as a value tree. No tracer, recorder, kernel or
layout-algorithm changes were made for this clarification.

Verification for this delta: production ClojureScript build passed; frontend
suite passed 52 tests / 562 assertions, zero failures or errors; diff whitespace
check passed. Tests cover parent relationships, nested indentation/measurement,
actual emitted branch paths, retained value entries and propagator sizing
independent of subnet metadata. Browser accessibility inspection confirmed the
subnet navigator/counts are absent and AMB value contents remain present.
Final screenshot verification was unavailable because native browser capture
timed out. No collapse/expand interaction is implemented for the value tree.
No model mutation, commit or push is authorized or performed by this delta.

Assessment updated 2026-10-18T19:17:31Z. Tested HEAD:
`2419e6de84e204ba695f47aba74f92e8376c51d1`, with a dirty working tree.
Implementation and tests are uncommitted. This is not a Kiroshi verification
receipt, approval, model sync, or claim of committed implementation evidence.
The earlier `ASSESSMENT.edn` remains the historical planning assessment.

## Implemented ownership

- Information: `capture.clj` owns partial capture join and evidence-preserving
  publications. Private publication metadata is not exposed as structural slots.
- Tracer: `observation.clj` owns acquisition, declaration watches, stable
  observation epochs, unchanged-input suppression and supported publication.
  The frozen observation adapter implements `SemanticCaptureSource` and captures
  public slot dependencies without adding those slots to visible topology.
- Session: the pane installs its own composed runner and retains immutable
  originals. Extension clones storage and replaces the owning runner; closing
  the predecessor cannot erase the successor's originals. Refinement uses only
  the original, accumulates in a cell and appends immutable publications.
- Host: pure CPS scene compilation, scope inspection and rendering consume the
  captured value. Historical scope requests avoid live topology/cell inspection.
  Graphs and recordings embedded in value content are summarized, not unfolded
  into invented data topology. List contents no longer draw semantic-node frames.
- Presentation: actual circle-and-edge previews, ordinary detailed node cards,
  accessible scope navigation and enclosing-scope navigation. Camera ownership
  includes pane, generation and semantic scope. The shared layout provider still
  selects Standard with 28-world-unit rectangle clearance.

The existing component identities, parent assignments and AMB facts are unchanged.
The permitted shared evaluator extraction threads the explicit merge through
direct evaluation, scoped routing and accessor patch application. No additional
shared evaluation policy was introduced. Global cell merge, support collection,
generic sampling/traversal, Lain dispatcher and recorder/reducer have no diff.

## Checks actually observed

| Check | Result and scope |
| --- | --- |
| Full configured suite, socket-enabled | Final run: 5,617 assertions passed, zero failures/errors (`/tmp/recursive-semantic-final-tests.log`). Earlier run: 5,607 passed. The earlier sandboxed run had six socket-permission errors. |
| Focused capture, history, value presentation and layout | 348 assertions passed, zero failures/errors. Includes ACI samples, both operand orders, withdrawal, compiler rewrapping, concurrent ordinary/extended runners, resumed work, frozen cycles, historical slots, immutable prior frames, extension isolation, Standard selection, measured-size clearance, zero-work unchanged inputs, released anchors and scope flow cleanup. |
| Final incremental backend verification | Capture, historical recursion and layout: 146 assertions passed after the final capture recognition and historical-request refinements. |
| Asynchronous runtime entry | Final capture suite: 11 tests, 50 assertions passed, including concurrent worker-thread clock callbacks in capture-enabled and ordinary runtime sessions and explicit capability removal. No ambient runner binding is used. |
| Frontend suite | 50 tests, 552 assertions, zero failures/errors; includes data contents without node rectangles and preserved cell/propagator frame distinction. |
| Production ClojureScript build | Passed. Generated bundle is not hand-edited. |
| Boundary/diff checks | `git diff --check` passed. No global merge registration or root alteration was introduced. |
| Safari AMB | Timeline and seven nested scope references visible. Screenshot showed actual circle/edge previews inside propagator cards, rounded cell cards and square propagator cards. AMB scope opened and returned to its enclosing graph. |
| Safari deep recursion | Temporary isolated Lain block: root → outer → inner displayed depth 2, then returned through depth 1 to root. Refinement grew the timeline from two to seven publications. The temporary block was closed; the AMB block and its source/extension were retained. |
| Safari camera round trip | After wheel input, root → AMB child → root preserved the visible root scale and card/edge positions in before/after screenshots. This is visual evidence, not a numeric browser geometry measurement. |

Backend tests are executable in `test/propagators/semantic_trace/capture_test.clj`,
`test/graph/lain_editor/recursive_semantic_test.clj`, `amb_test.clj`,
`semantic_scene_test.clj`, `value_labels_test.clj` and `graph_layout_test.clj`.
Frontend tests are in `test/graph/lain_editor/frontend_reactive_test.cljs`.

## Verification limits and remaining acceptance

These checks are declared coverage, not exhaustive proof. Explicit live-browser
pan comparison, browser drag/release geometry measurement and browser
cycle/shared-reference traversal have not been completed. Pure geometry and
frozen-cycle tests do not substitute for those manual checks. Concurrent/resumed
runner selection and actual clock callback entry are tested on worker futures;
timer-resource scheduling/retirement was not separately exercised with capture
enabled. The existing configured suite covers ordinary clock contracts.

The more complex enclosing graph now contains seven 200×120 previews and thus
has larger measured cards than the old flat display. Standard selection and
unchanged-input stability are verified; improved aesthetic readability relative
to the old layout has not been established. No alternative algorithm was chosen.

## Evidence and approval boundary

Representative working-tree Git object hashes (not committed blobs):

- `propagators/semantic_trace/capture.clj`: `c3bd440bc02fbf025d3e44532884d4407f66fcb5`
- `graph/lain_editor/semantic_scene.clj`: `eb3b0ad8d304cf0dd81af56aaf0d10156ad65f16`
- `graph/lain_editor/inspection.clj`: `2e54b4e9c7ad7baa1bd4d6f98a6bbc3b0627dc0f`
- `graph/lain_editor/frontend/graph.cljs`: `0af07cab93468827b69a9347f1953c75e8115e88`

Production client SHA-256:
`4cc2b7480c7e1d0343dcd45313e005a1059a736095e38ebfb1e3c378485ed922`.

The three proposed Kiroshi facts remain provisional candidates. The planning
evidence text is historical and must be reconciled with this assessment and
actual committed blobs before any verified-result approval is requested. There
has been no durable model mutation, receipt submission, supersession, commit or
push. Model sync and any ensuing portable backup remain separately authorized
actions under the evolution workflow.
