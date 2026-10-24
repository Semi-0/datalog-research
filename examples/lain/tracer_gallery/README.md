# Lain tracer gallery

Open `workspace.json` with **Open JSON** in the Lain editor. The six desktops
demonstrate the public tracing layers against independent, current pane sessions:

| Desktop | Examples | Capability |
| --- | --- | --- |
| Live session | `01-current-session.lain`, `11-clock-history.lain` | `trace:session` and a clock-updated `trace:history` |
| Relationships | `02-semantic.lain`, `03-roots-dataflow.lain` | Retained semantic projection and explicit roots-to-dataflow composition |
| Scope | `04-roots-semantic.lain`, `05-children.lain` | Root shorthand and one explicit child-call body |
| Observers | `06-downstream.lain`, `07-both.lain`, `10-live-observer.lain` | Lain-defined retained traversal and the non-retained live sampler |
| Presentation | `08-caller-view.lain`, `09-replay.lain` | Caller projection, values, recording, range selection, reduction, and playback |
| Call graph | `12-call-graph.lain` | Datalog-derived calls/called-by relations, potential and realized calls, and their lexical environment and call frames |

The gallery is one editor workspace session: importing `workspace.json` restores
its desktops, panes, sources, and displays, then runs each pane in its own live
compiler-2 runtime session. The built-in `trace` operator receives its pane's
current graph from that runtime; no graph snapshot is passed in source. Saving
the workspace preserves the replayable editor document rather than JVM objects.

The gallery is organized by tracer capability rather than duplicating every
topology fixture. The existing semantic, value, retained, and replay galleries
remain focused regression and teaching examples.

The call-graph pane runs `pipeline` before displaying `(trace:calls pipeline)`.
Its `callgraph` semantic representation therefore distinguishes what the
closure declares from the applications that were actually connected, while
remaining compatible with the editor's ordinary graph renderer. The call
occurrence values name their `:call/from`, `:call/to`, and inverse
`:call/called-by` relationships. `captures` and `extends` nodes connect the
closures and realized call frames to the central lexical environment without
breaking the runtime graph's cell/propagator alternation.

All gallery panes import the same public module:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/tracers.lain")
```

| Public tracer | Meaning |
| --- | --- |
| `trace:session root direction output` | Current session trace with an explicit output cell |
| `trace:semantic root` | Retained semantic graph with values |
| `trace:roots root` | Root relationships for explicit composition |
| `trace:dataflow roots` | Dataflow of a selected relationship graph |
| `trace:with-values graph` | Attach values to a relationship graph |
| `trace:root-semantic root` | Root semantic graph with values |
| `trace:children root` | Child-call body with values |
| `trace:caller root` | Caller projection of the semantic graph |
| `trace:observe-live root direction` | Live, non-retained semantic observation |
| `trace:downstream-live root` | Live downstream observation |
| `trace:observe-retained root direction` | Retained semantic observation |
| `trace:downstream-retained root` | Retained downstream observation |
| `trace:history root start end` | Recorded semantic snapshots in a time range |
| `trace:calls closure` | Potential and realized call graph |

`semantic-snapshot` and `traverse` remain lower-level primitives. The shared
module composes them once. Roots-to-dataflow remains the explicit expression
`(trace:with-values (trace:dataflow (trace:roots root)))`: wrapping that expression
in another Lain network would include the tracer's own application in its graph.
The older `semantic_traversal.lain` and
`collect-history.lain` files retain compatibility aliases for existing programs;
new gallery code uses the `trace:` names. No recorder or renderer semantics change.

The clock pane publishes a new `time` event every 500 ms. Its
`trace:history` call records each changed semantic graph and displays
the result with the replay timeline. Closing the pane or replacing its program
stops the pane-owned clock resource.

Run the gallery check with:

```sh
clojure -M:test graph.lain-editor.inspection.tracer-gallery-test
```
