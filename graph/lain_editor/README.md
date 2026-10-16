# Tiled Lain environment

Start from the repository root:

```sh
clojure -M:editor/cljs
clojure -M -m graph.lain-editor.server 4780
```

Open http://127.0.0.1:4780. The server uses the existing JVM Lain runtime and
binds only to loopback. It needs no npm installation or external browser assets.
Use Open JSON with `graph/lain_editor/example-workspace.json` for a three-pane demo.

- Click or focus a block to select it. Its border highlights the selection;
  blocks have no title bars or visible Run, Split, Close, or Add buttons.
- **Cmd/Ctrl+K** or right-click opens the command palette for the selected block.
  Search commands and press Enter, use the arrow keys, or click a command.
  Escape or clicking outside dismisses it without changing the block.
- **Cmd/Ctrl+Enter** runs the selected block. Run prepares a complete replacement;
  a failed compilation keeps the previous running program. **Load Lain file…**
  in the palette reads a local file into that editor before execution.
- **Split side by side / Split stacked** subdivide the selected tile with a new
  independent empty block. **Spawn new block** adds an empty block beside the
  entire layout. **Close block** closes the selection; the final block is kept.
  Drag dividers to resize tiles or the textarea handle for more editing space.
- The workspace header is hidden until the pointer approaches the top edge.
  Keyboard focus also reveals it. Its Commands button opens the same palette.
- **Extend this running block** adds source to that tile's existing bindings and
  topology. Successful extensions are retained in order. It is an append
  operation, not hot replacement of an individual definition or file.
- The **Layers** panel controls Code, Result, Topology, and Cell details.
  Toggle visibility, use the up/down buttons for top-to-bottom order, and choose
  Overlay or Stack. The output binding setting is inside this panel.
  Palette commands reveal Value, Cell details, Topology, and select trace direction.
  Values and node inspection share the same SVG structural renderer as graph nodes.
  Records and layers use centered mini grids; linked lists use boxes and connecting dots.
  Clicking a cell opens formatted Strongest and Content sections with a Close button.
  New semantic snapshots retain cell content for replay; older frames without it show
  “not recorded”. Identity, connections, and ownership remain in Runtime details.
  Topology uses the existing relationship/dataflow semantic projection, followed
  by upstream, downstream, or both-direction tracing.
  The shared Leapfrog projection presents transformations and returned-member
  connections. Private expression results flow directly into their named binding,
  with definition aliases retained as metadata; the editor adds no separate tracer augmentation. Root views keep
  calls opaque. Opening a call enters exactly one application level.
- The topology is an SVG graph laid out by Vijual Balanced Stress. Drag nodes, pan the background,
  scroll to zoom. **Compact topology** is available in the command palette. Select a node for actual runtime metadata. Select
  a call and **Open this call body** to inspect that occurrence. **Return to root topology** in the palette returns to
  the display cell's trace. Display panels refresh every two seconds while visible.
- **Save JSON / Open JSON**, inside the workspace **…** menu, round-trip all desktop split trees and ratios, pane titles,
  file names, drafts, display targets/modes, last executed source, and ordered
  successful extensions. Import validates and prepares all programs before
  replacing the current sessions. Drafts are separate from executed source.
  Browser local storage uses the same document for recovery after page reload.

JSON is a **source replay document**, not a serialized JVM or heap snapshot.
Clock timestamps, external effects, and transient runtime inputs are not frozen.
Loading a file stores its source and name; it does not create a file watcher.
Use one browser workspace per server process: tabs connected to the same process
share its editor-session registry. Closing a tile stops its clocks and traces;
closing the server releases all owned sessions. Closing only the tab leaves the
server alive so the saved workspace can be reloaded.

The server executes local Lain with the normal runtime capabilities. Transactional
preparation protects editor/runtime state from compilation failure; it does not
undo external effects invoked by program code. The editor uses the JVM compiler, scheduler, and semantic-tracing operators described
below. File and networked environment prototypes remain opt-in.

Verification:

```sh
clojure -M:test graph.lain-editor-test graph.lain-editor.graph-layout-test graph.lain-editor.desktop-test
clojure -M:editor/cljs-test
node target/lain-editor/tests.js
```

## Program-driven display

`def-cells` declares one or more empty named cells, equivalent to consecutive
`define` forms. `relationship:dataflow` returns its result cell and can be nested,
passed as an operator, or called with an explicit output for compatibility.

```clojure
(def-cells input raw)
(-> 5 input)
(relationship:roots input raw)
(display (relationship:dataflow raw))
```

`display` is an editor extension operator with one input. It forwards live content
using the same layered transport as `->` and returns the pane's display cell.
Graph values reveal Topology; other values reveal Result. Each pane has one sink;
multiple incompatible writes obey ordinary cell merge/contradiction semantics.
Run, Extend, and workspace replay select this sink automatically. The saved output
binding `@display` denotes it; selecting another binding in Layers still allows
explicit inspection. Panes without a display call retain their configured target.
A pending source stays pending, and later propagation updates the display.
No host callback or extra global display registry is involved.

## Frontend dataflow

The browser application is ClojureScript with Javelin 3.9.3. The compiler is
pinned to ClojureScript 1.12.145 in the editor-only dependency aliases. Rebuild
with `clojure -M:editor/cljs` after editing frontend source; generated JavaScript,
source maps, and compiler caches are ignored by Git. There are no handwritten
JavaScript frontend modules or npm dependencies.

- `frontend/model.cljc` owns pure document transitions and command policy.
  `workspace.cljc` shares the existing JSON validation contract with the JVM.
- `frontend/state.cljs` declares input cells for the workspace, selection,
  generations, inspection results, and palette. Formulas derive the selected
  pane, layout structure, available commands, filtered commands, and inspection
  requests. Javelin transactions publish related state changes together.
- `frontend/effects.cljs` owns HTTP and file effects. Successful runtime replies
  commit executed source and generations; failed preparation retains the last
  working program. `frontend/inspection.cljs` cancels superseded requests and
  rejects stale replies. A polling tick never invalidates a slow request.
- `frontend/reactive.cljs` owns formula/watch lifetimes. Layout mounts own their
  pane subscriptions; graph mounts own coordinate and camera cells, animation
  frames, observers, and inspection requests. Disposal removes those resources.
- `frontend/layout.cljs`, `pane.cljs`, and `palette.cljs` bind derived cells to
  native DOM elements. Source edits and divider ratios do not rebuild the tree
  or replace the focused textarea. Structural changes rebuild the layout.
- `graph_layout.clj` runs the existing Vijual persistent stress-layout flow independently of the tracer.
  `frontend/graph.cljs` renders its coordinates through reactive SVG bindings;
  actual semantic identities and relationships still come from the JVM.

The frontend tests exercise pure transitions, JSON validation, command policy,
transaction consistency, disposal, stable layout projections, graph physics,
and delayed/stale inspection responses. Backend tests cover isolated sessions,
failed replacement, extension identity, JSON replay rollback, and real tracing.

## Semantic tracing examples

Open `examples/lain/semantic_tracing/workspace.json` for eight runnable panels:
unary, binary, three-input application, true/false branching, accumulator recursion,
two-output application, and a returned closure with a captured binding.
Each example declares output cells with `def-cells` and directly calls
`(display (relationship:roots-semantic seed))`. Declarations and expressions
are written on separate lines.
`relationship:roots` also returns a cell as an ordinary callable expression;
`(relationship:dataflow (relationship:roots seed))` is the explicit composition.
Both observers preserve their explicit-output forms. See
`examples/lain/semantic_tracing/README.md` for results and call-body navigation.

## Desktops and Vijual Balanced Stress layout

Fresh startup contains one blank block on desktop **1**, with no example programs.
The **New workspace** palette command returns to this blank state.
The always-visible top strip shows only desktop numbers and selects desktops. **Cmd/Ctrl+K → New desktop**
creates an independent desktop with one empty block. **Switch to desktop: …**
selects a desktop from the palette. Selection and split ratios are retained per
desktop. Switching unmounts only browser views; programs and clocks on hidden
desktops continue. Each pane belongs to exactly one desktop, with at least one
pane retained per desktop. Save/Open JSON includes all desktops and their programs.
Version 1 files import as Desktop 1; new saves use version 2 with a global `panes`
array, `desktops` containing names/layouts/selection, and `activeDesktop`.

The server prepares `graph.vijual.layout.nodes/create-balanced-layout` and uses
`graph.vijual.layout.nodes/layout-flow`, the documented core.async
adapter over the existing incremental scan/emit state machine. There is one
owned flow per pane's current generation and inspected call occurrence. Complete
node upserts retain the last public positions. Edge additions/removals are sent
as transactions; dragged nodes submit position upserts plus pins. Each command
requests at most 300 relaxation iterations and returns Vijual's public revision,
nodes, signed deltas, and metrics. The HTTP client uses the full public snapshot
so canceling an older reply cannot lose a delta. Distances and numeric caches are
retained by Vijual; the browser does not implement a second solver. Balanced
Stress uses the existing soft equal-edge-length, axis-alignment, inferred
twin-node mirror, and clearance preferences. The editor uses a 28-pixel node gap,
110-pixel edge target, 16-pixel edge clearance, and clearance weight 96. Other
weights retain their defaults. New nodes start from Vijual’s layered ordering
before stress relaxation; existing nodes retain their positions and pins. The
300-iteration budget is preserved. These are soft
preferences, so conflicting topologies or pinned positions may retain crossings
or tilted edges. See [the solver contract](../vijual/layout/README.md#balanced-stress).

On graph mount, the server captures actual solver milestones in batches of up to
10 iterations, within the same 300-iteration limit. The browser interpolates those
samples over 1.1 seconds in a fixed camera, then settles into compact framing.
Node payload or relationship changes briefly fill affected nodes and fade out
over 900 milliseconds. Unchanged polling preserves the mounted graph, camera,
and positions without replaying hints. Dragging, zooming, or resizing ends an
active layout animation; unmounting cancels animation resources. Reduced-motion
preferences skip layout motion and use a short static update hint.

Layout streams survive desktop switching. Successful Run/Extend, changing the
inspected call occurrence, closing a pane, workspace replacement, and server
shutdown retire the relevant streams. Failed preparation leaves them usable.
There is at most one current layout stream per runtime pane. Layout computation
never changes the program Net, semantic identities, or relationships.

`frontend/viewport.cljs` sizes each node frame to its full label with
28 pixels of horizontal padding, a minimum width of 44 pixels, and a shared
32-pixel height. Cells use rounded frames; propagators use square frames.
Code and graph labels retain the same 12-pixel font size.
Framing translates Vijual's coordinates without compressing distances or
repacking nodes onto a grid, preserving the solver's clearances and crossings.
It never stretches a sparse graph to fill a pane or changes node/text scale.
When a pane cannot accommodate the geometry, the canvas grows and scrolls.
ResizeObserver adapts the canvas to tile/code/detail resizing without rerunning
the solver. The palette's **Compact topology** restores this framing after
pan/zoom; deliberate zoom still scales the view. No frame-time guarantee is claimed.

## Display layers

Each pane owns an ordered `layers` array with `{id, visible}` entries for `code`,
`result`, `topology`, and `cell`, plus `layerMode` (`overlay` or `stack`). The first
entry is foremost in Overlay mode and first vertically in Stack mode. The small
Layers panel edits this configuration and the shared output binding. Overlay is
the default. Foreground code/result reserve their space above a topology below
them; moving topology to the front lets it cover the layers beneath it. The
panel itself remains accessible when every content layer is hidden.

The optional Result layer uses plain text beneath the source without a binding caption,
Run status, or graph toolbar. Fit and return-to-root actions remain in the
command palette. **Edit display layers** opens the same compact panel. Visibility
and order persist in saved JSON and local recovery, including across desktops.
Older files infer their output layers from their saved display mode.

`layers.cljc` owns validation and pure transitions. `composition.cljs` changes
visibility, order, and insets without recreating source or graph views.
`outputs.cljs` shares one inspection response between result/cell/topology layers.
Only a visible topology requires a graph projection; hiding all output layers
cancels sampling. Layer changes never run or replace the program. The existing
Vijual layout ownership and resize fitting remain intact.

The semantic-tracing gallery contains explicit `relationship:roots` and
`relationship:dataflow` expressions and publishes the `semantic` graph-valued cell
with `display`.
This displays the published Lain tracer output without editor augmentation.
For ordinary non-graph display cells, the editor uses the same shared projection
of the current runtime graph and its actual boundary copies.
Choosing **Show topology** hides the separate scalar Result layer; Layers can
enable it again explicitly. The gallery accumulator is source-level tail-position
recursion, not a claim of tail-call elimination or bounded topology growth.

## Call inspection

Topology labels use node names only. The tracer emits no generated captions.
The compiler names literals from their source text and application returns from
their source expression, for example `0` and `(+ left right)`. Returned member
ports retain their local binding names. Names survive seeding and declaration
replay without changing cell IDs or values. Anonymous network values may remain
unnamed. An unused application fallback ID is reserved without creating a cell.
Cells and propagators remain distinct. A private expression result with exactly
one definition consumer is represented by its actual named binding cell; this
removes the definition hop without merging a cell with a propagator. Alias
metadata retains the original cell for inspection. Independently named cells
keep their separate state and copy flow, shown as `->` rather than `define`.
Explicit routing and returned-member copy propagators remain visible. Values
and closure details are available on selection.
Selecting a call shows its real boundary ports and return. **Open this call body**
enters one level; **Chase child calls** explicitly requests the bounded occurrence
table. Branch entries then come from actual `if` condition cells. Rows can open
their own body; raw metadata is folded below. The table describes structural
nesting, not scheduler history. Limits of 64 calls / 32 levels are reported.
The common scope and producer rules are documented in
`propagators/doc/semantic-tracing-datalog.md`.

An otherwise empty identity body retains its actual return copy chain. An
unchanged-input annotation requires an identity path through known `->` and
that call's outbound boundaries, ending at exactly one local input cell. Equal
values alone never establish this relationship. Inspection never mutates the Net.

## Optional value rows

Compose `(relationship:with-values (relationship:roots-semantic input))` inside
`display` to show each cell's current value beneath its name. Ordinary operators
still return their result cells. This modifier adds only graph presentation
metadata; names, kinds, values, edges and abstraction scope are preserved.
Opening a child body inherits the modifier. Scalars occupy one extra line;
layered values show every public layer with the base first, bounded previews of
large nested payloads, and line wrapping. Cells remain rounded and propagators
square; only cell frames grow to accommodate their rows. Graph labels retain
the same 12-pixel size as code and feed their dimensions to Vijual.

See `examples/lain/value_tracing/workspace.json` for scalar, layered-object and
waiting-input panels. The existing fill-and-fade hint responds to observed value
and relationship changes, including value-row changes. It is sampled at the
editor's two-second polling interval and is not a scheduler activation timeline.

Compiler-generated literals are named `CONSTANT` and application outputs `RESULT`;
explicit bindings retain their own names. Rendering removes keyword-prefix colons
and replaces interior colons with spaces (`p:slot` becomes `p slot`), while quoted
strings and cell identities remain unchanged. Wires, arrowheads, and list dots use
one shared color. Node sizes follow rendered content; graph layout does not shrink
text to force the topology into a pane.
