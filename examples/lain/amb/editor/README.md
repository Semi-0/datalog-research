# AMB learning in the block editor

## Pythagorean triangle

Load `examples/lain/amb/editor/pythagorean.lain` in an editor block, then use
open **Toggle extension editor** from the command palette, then apply:

```clojure
(-> (list 3 4 5) domain)
```

Three AMB choices search for positive integer sides satisfying `a <= b <= c`
and `a*a + b*b = c*c`, with at most 27 combinations. The result is `(3 4 5)`. The `choice-a`, `choice-b`
and `choice-c` cells contain the corresponding decision records; `knowledge`
contains the actual learned no-good set. No presentation wrapper is required.
The display uses `collect-semantic-history valid 0 600000`, so its topology
and captured values can be scrubbed through the existing replay timeline.
The root is the constraint's dataflow: the compound `triangle` container alone
does not expose its members' producing computations as topology edges.
The final number is a selection-window endpoint in milliseconds, not a frame
limit or a delay. It does not bound recorder retention.

For headless progress checks with the same three alternatives per side:

```sh
clojure -M -m experiments.recursive-semantic-views.step-triangle
```

Append `profile` to compose the existing activation profiler around the selected
session iterator, including nested runner work, without changing merge policy.

This diagnostic drives the session-selected iterator one transition at a time
and prints the sides and recorded-frame count every 100 steps. Its outer-step
and elapsed-time bounds are cooperative; a nested activation is not preempted.
The topology must show each AMB call producing both its side and its choice
record. Change the domain only by rerunning a fresh block, not by extending an
already-started search with a different domain.

Current value presentation is structural: public slots and collection entries
are annotated automatically, with menu and inline tree folding as view state.
The old custom no-good renderer described in the historical notes below has
been removed; it is not required to visualize learning.

Start the existing editor from the repository root:

```sh
clojure -M:editor/cljs
clojure -M -m graph.lain-editor.server 4780
```

Open <http://127.0.0.1:4780> and use **Cmd/Ctrl+K → Open workspace JSON…**
with `examples/lain/amb/editor/workspace.json`. The saved workspace replays
three independent panes on desktops 1, 2 and 3:

1. **Learned no-goods:** try `1`, learn that alternative index `0` fails,
   withdraw it and accept `2`. The display shows the forbidden combination,
   the exact choice-source marker and its logical context.
2. **Exhausted search:** neither `1` nor `2` satisfies `chosen = 3`. Resolution
   produces the empty no-good, displayed as **unsatisfiable under context**.
   The `repairs` cell contains the existing `:unsatisfiable` action.
3. **Published learning replay:** reuse `collect-semantic-history`,
   `history:record`, `history:between`, `reduce` and `history:append`. Scrub the
   existing timeline to see captured choices, failures, learned knowledge,
   withdrawal and the accepted choice. Select `learning` and press Enter to
   inspect its captured Strongest/Content cards. Playback does not run search
   or repeat effects.

Each source can also be loaded on its own. It starts with an empty `domain`;
use **Toggle extension editor** from the command palette, then apply:

```clojure
(-> (list 1 2) domain)
```

The workspace already contains that extension. Changing it and rerunning the
block reconstructs a fresh search; changing the domain of a running AMB choice
is outside the existing solver contract. A pending input creates no false
no-good. The current editor can report a layered `nothing` value as `ready`;
the new adapter still waits for usable knowledge before constructing a view.

## Session and display contracts

`web_editor.clj/primitive-bindings` supplies the existing opt-in TTMS scalar
and AMB bindings plus `amb:learning-view`. The existing
`load-primitive-environment` effect installs that declaration into the pane's
child environment. Other panes and compiler defaults are unchanged.

```clojure
(load-primitive-environment
  "examples/lain/amb/web_editor.clj"
  :examples.lain.amb.web-editor/primitive-bindings)
```

The public `propagators.experimental.amb.learning-view` abstraction provides
`learning-view`, `learning-view?` and `knowledge`. It is an immutable compound
value with public presentation slots `:amb.view/type` and
`:amb.view/knowledge`. The constructor validates the existing AMB knowledge
spec and reuses immutable template slot identities. It contains no callbacks,
runtime state or private recorder. Repeated construction of equal knowledge
produces an equal view.

The ordinary layered `amb:learning-view` operator packages the solver's
knowledge without changing it. `display` uses its existing layered transport.
The shared value-grid delegates this explicit type to `nogood-view`, a read-only
renderer. No-good literals retain `[source-cell alternative-index]`; indexes
are zero-based and are independent of source epochs. Context rows retain
external source IDs, timestamps and active/retracted statuses. Source markers
carry exact source tokens and select their cells when present in the graph.

An empty knowledge set means **no learned conflicts**. A no-good with no
literals means **unsatisfiable under its context**, not necessarily under all
possible external contexts. Logical resolution/minimization remains AMB-owned;
TTMS dominance is not used to explain it.

The live solver remains the authority for knowledge. The display adapter and
recording do not feed back into choice or repair. Slot reads in the history
reducer use the existing `p:slot` propagator. Presentation reads operate on the
explicit view contract and captured graph values, without materializing a
compound as a replacement semantic evaluator.

## Recording scope

The existing recorder accepts semantic graphs, so replay observes the graph
rooted at `learning` with value capture. It does not pass knowledge sets directly
to `history:record`. Equal graphs are deduplicated and published graph changes
are accumulated with their source support. The source declares a ten-minute
selection window; this does not bound recording retention.

This is a record of published observations, not a guarantee of one frame per
solver activation or every intermediate no-good before minimization. The small
browser example produced 14 observations, including a failed first choice,
its no-good, withdrawal and final accepted choice. Timing and observation counts
can vary. Workspace JSON stores source and reconstructs a recording; it does
not persist an old runtime history across reloads.

## Verification

The new editor suite passed **4 tests / 28 assertions**:

```sh
clojure -M:test graph.lain-editor.amb-test
```

Coverage includes contextual no-good rows, empty knowledge versus empty
no-good, constructor validation, pending knowledge, stable slot identities,
session isolation, satisfiable/exhausted searches, quiescent repeated execution,
and preservation/rendering of recorded frames without live-state mutation.

Existing editor, replay, value-label and AMB suites reported **409 passing
assertions and three failures**, all in the old
`original-whole-compound-gating-is-a-reproduced-runtime-gap` characterization.
It expects a historical runtime error, but this working tree now completes that
case. The same three failures reproduce when running only
`propagators.experimental.amb-lain-test`, without loading the new editor code.
The assertion was retained and shared runtime/solver behavior was not changed.

The ClojureScript editor build succeeded. Linting the five changed/new Clojure
modules and tests reported zero errors/warnings. Browser checks loaded the JSON
through the editor, displayed the learned no-good, and scrubbed the 14-frame
replay from the pending source to the final `chosen = 2`, `valid = true`, and
idle repair. Recorded learning Strongest/Content inspection showed the same
custom no-good rows.

The full repository suite was not run. Tests ran on the working tree based on
`703616c5423c2cb5fbf6081dc707e08c3146237f`, alongside unrelated pre-existing
edits. These results are not a submitted Kiroshi verification receipt.

## Architecture evolution

The invoked grounding/evolution workflow selected host, compiler and compound
boundaries. Kiroshi grounding was provisional: the relevant model facts remain
candidates, with no current governing Principles. User-directed constraints
still guided the implementation.

No ownership, hierarchy, scheduler, merge, compound transport, compiler or
solver-policy change was needed. The implementation adds only session-local
example bindings, an immutable presentation value, and a renderer under the
existing host visualization boundary. The prepared candidate Evidence/Decision
delta is `system-model/amb-editor-evolution.edn`. After explicit user consent,
both facts were persisted as unapproved candidates and exported to
`.kiroshi/model.edn`. Their recorded text describes the verification-time
working tree; no formal committed-evidence receipt has been submitted.
Unrelated working-tree edits are preserved.
