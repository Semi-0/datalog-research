# Semantic network replay

Load `workspace.json` through the editor command palette, or put `replay.lain` in a block and run it. The example is loaded only when requested; it is not a default desktop.

The reusable definitions live in `collect-history.lain`. Load it once in each block, then give `collect-semantic-history` a root cell and an inclusive elapsed-millisecond range:

```clojure
(load-lain
  "examples/lain/network_replay/collect-history.lain")

(display
  (collect-semantic-history input 0 60000))
```

The helper calls `observe-semantic` with `:both`, adds `relationship:with-values`, records changes, and reduces the selected frames. `collect-history` remains available for an already constructed graph. Each call owns its recording. Paths are relative to the runtime working directory; run this example from the repository root. The web editor supplies the history operators used by the library.

Load `gallery.json` to view the earlier value-update and topology-extension examples on desktops 1 and 2, and `clock.lain` on desktop 3. The clock example loads the existing clock primitive and publishes the current wall-clock milliseconds once a second. Its replay nodes show the event epoch and value, retaining the source event evidence. Running the block schedules the clock; closing or replacing it stops that session's clock. As with other recordings, the selected range does not bound retention.

The observation query is defined in Lain and executes in a disposable one-time network through the traversal API. Its frozen subject excludes the observation-producing calls by actual ownership evidence. The collector therefore omits its own growing replay value, and its callback topology is never installed into the parent. Nested user-defined wrappers are regression-tested against the direct trace. The history library needs no explicit `relationship:caller-view` composition. Ordinary tracing retains its existing scope contract. See `propagators/doc/semantic-traversal.md`.

Inside the library, `history:record` watches the semantic graph. `history:between` selects observations in the requested range. `reduce` invokes the compiled Lain network `collect-frame` for each selected observation, in order. `display` recognizes the replay value and shows previous/play/next controls and a timeline slider below the code.

To create two observable changes, use **Extend this running block** twice:

```clojure
(-> 7 input)
```

```clojure
(def-cells doubled)

(-> (* input 2) doubled)
```

Do this within the selected interval, or increase its upper bound before running. The first extension records `input = 7` and `result = 8`. The second adds a multiplication and `doubled = 14`. Scrub back to inspect the earlier network: the added nodes disappear and earlier values remain intact. Repeated changes to the same external source should use versioned TTMS input, as demonstrated in `network_replay_test.clj`, rather than conflicting constant assertions.

## Value and provenance contract

A recording contains immutable semantic graph observations with a sequence number, wall-clock capture time, elapsed milliseconds, and original source support from the graph and its captured cell values. Full recorded values also retain their membership and premise-state layers. Its current TTMS publication depends on the recorder cell's actual source identity and latest revision. Historical source support is data inside each frame; an old sample is never relabeled as a new observation from its original source.

A reducer callback receives an accumulator and the ordinary update envelope `{:slot index :value frame}`. It has two inputs and one explicit or implicit output. This prototype accepts a concrete finite vector and pure Lain network callbacks. Callback networks run in isolated copies of captured cells; they do not install callback topology into the displayed live network. Output support, membership guards, and source state are carried through the fold.

Playback renders captured data. It does not rewind the runtime, rerun the program, or repeat external effects. Node inspection during playback reads the selected frame, even if the live runtime has advanced. The same cell/propagator shapes, layouts, midpoint arrows, and update highlights are used as for live graphs.

Capture occurs when the semantic graph publishes a changed observation. It is not a trace of every internal scheduler activation. Equal graphs are deduplicated; distinct graphs at the same millisecond retain distinct sequence numbers. Topology additions are integration-tested; removing nodes/edges is represented by the next complete graph observation. The time range bounds the selected output, not recorder retention: retained evidence and recordings currently grow for the session. Workspace JSON saves source/layout and reconstructs execution, not a serialized runtime recording.

## Checks

```sh
clojure -M:test propagators.membership-collection-test network-replay graph.lain-editor-test
clojure -M:editor/cljs-test
node target/lain-editor/tests.js
```

## Editing replay steps

Applied extensions are editable sections of the same pane's code layer, below
the main source. Run the block with Cmd+Enter / Ctrl+Enter after editing a saved
extension: the editor creates a fresh session and replays the main source and
each extension in order, preserving the initial and added-topology observations.
A failed replay leaves the previous live session in place. Use the footer's
extension draft to append a new incremental step.

Workspace JSON retains executed extensions in `extensions` and unsaved edits in
`extensionEdits`. Loading reconstructs the last successful execution and restores
the edits for the next run.

Frame rendering uses `inspection/render-value`, the same renderer as direct value
display. Node labels, tooltips and historical node inspection consume that one
formatted result. The renderer receives captured values from each frame; it never
substitutes the current live cell value. Formatting does not alter saved values
or their source provenance.
