# Incremental constraint-first stack development

This folder is the canonical record for the
`stack-spec-2026-10-20-r9` experiment. It tests whether a retained,
premise-versioned Lain program can support correct incremental refinement with
less repeated work than clean reconstruction. No speedup is assumed.

## Protocol

1. Load `examples/lain/top_down/stack-design.lain` and record the pinned
   specification revision and declared fixture expectations.
2. Register an isolated `:versioned-premise` client. Admit implementation blocks
   with unique commit IDs; replacement retracts the superseded premise.
3. Maintain outcomes by stable constraint ID. Capture checkpoints from that
   maintained index with `graph.lain-editor.constraint-checkpoint/checkpoint`.
   Inspection itself must not acquire source, propagate, or scan all constraints.
4. Replay the identical source ledger and edit sequence into a fresh session.
   Compare normalized semantic values, observation-qualified occurrence
   identities, diagnostics, and current support—not generated IDs or timestamps.
5. Use three warmups and five measured repetitions, alternating incremental and
   clean order. Each sample has a 60-second limit. Preserve timeouts and errors.

Required-constraint satisfaction is `satisfied / currently required`; pending,
violated, and contradictory outcomes stay in the denominator. With zero required
constraints the rate is not applicable. Fixture agreement is a separate measure
of observed status versus the fixture's declared expected status. No success is
reported while membership is incomplete.

JVM timing uses `System/nanoTime`; browser timing uses Node's monotonic
`performance.now`. The records distinguish construction, commit admission and
compilation, propagation, outcome maintenance, checkpoint capture/inspection,
browser request-to-visible update, and clean reconstruction. Runtime timing is
not a proxy for human or agent development time. Synchronous profiler values are
not presented as browser end-to-end latency.

## Reproduction

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:test \
  graph.lain-editor.frontend-model-test \
  graph.lain-editor.workspace-commands-test \
  graph.lain-editor.constraint-checkpoint-test \
  graph.lain-editor.constraint-index-test \
  graph.lain-editor.development-runtime-test \
  graph.lain-editor.experiment-comparison-test \
  graph.lain-editor.stack-design-test \
  graph.lain-editor.stack-primitives-test \
  graph.lain-editor.stack-incremental-integration-test \
  graph.lain-editor.stack-experiment-test \
  graph.lain-editor.stack-benchmark-test \
  graph.lain-editor.stack-scene-test

JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:test \
  propagators.compiler-2-versioned-output-projection-test

# About nine minutes on the recorded machine. Emits portable EDN.
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -e \
  '(require (quote [graph.lain-editor.stack-benchmark :as b]))
   (prn (b/compact-artifact (b/run)))'

# Browser acceptance uses an isolated temporary Playwright installation.
# Keep this server running in a first terminal.
clojure -M -m graph.lain-editor.server 4783

# Run these commands in a second terminal.
npm install --prefix /tmp/lain-stack-browser playwright@1.64.1
PLAYWRIGHT_BROWSERS_PATH=/tmp/lain-stack-browser-browsers \
  /tmp/lain-stack-browser/node_modules/.bin/playwright install chromium
NODE_PATH=/tmp/lain-stack-browser/node_modules \
PLAYWRIGHT_BROWSERS_PATH=/tmp/lain-stack-browser-browsers \
LAIN_EDITOR_URL=http://127.0.0.1:4783 \
  node experiments/top_down_constraints/browser_acceptance.js

clojure -M:editor/cljs-test
node target/lain-editor/tests.js
```

Loopback HTTP tests require an environment that permits binding localhost.

## Run artifacts

`runs/` contains machine-readable EDN records. Every record includes the run ID,
repository and specification revisions, dirty-source hashes, active premise
versions, fixtures/edit sequence, environment, budgets, instrumentation, raw
samples, and outcome. Portable records never contain sessions, live networks,
capabilities, or credentials.

`2026-10-21-stack-r9-measured-2.edn` is the current revision-matched compact raw
runtime record. `2026-10-21-stack-r9-browser.edn` records the separate browser
request-to-visible and visual acceptance run. `2026-10-20-pinned-baseline-r1.edn`
is the controlled semantic baseline at the
clean `f808b7a` revision. It is intentionally not a performance comparison: the
runtime boundary did not yet separate compilation from propagation and no warmed
samples or instrumentation-overhead sample had been taken.

See [REPORT.md](REPORT.md) for observed evidence and [DECISIONS.md](DECISIONS.md)
for revision amendments. The runtime and browser records together form the
completed r9 comparative and presentation checkpoint.

## Limitations

Frozen readers, traversal, clean comparison, scene interpretation, and frontend
model rendering pass their revision-matched automated boundaries. All declared
fixture expectations agree, and the alternating benchmark shows semantic
agreement and localized unrelated work for the measured fixture. Chromium
browser acceptance preserves semantic node kinds, source-anchored expansion,
and view-state separation. Safari-specific automation remains unavailable
without a user-authenticated global setting change. Exact cold compilation and
propagation components are also not separately available.
