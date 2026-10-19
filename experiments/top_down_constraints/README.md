# Incremental constraint-first stack development

This folder is the canonical record for the
`stack-spec-2026-10-20-r1` experiment. It tests whether a retained,
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

Timing uses `System/nanoTime` and separates construction, commit admission and
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
  graph.lain-editor.stack-design-test

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

See [REPORT.md](REPORT.md) for observed evidence and [DECISIONS.md](DECISIONS.md)
for revision amendments. The current run is a development checkpoint, not the
completed comparative benchmark.

## Limitations

Frozen-scope readers, traversal-state primitives, recursive validation, spatial
stack rendering, browser acceptance, and the alternating benchmark remain
unimplemented. The current checkpoint therefore cannot support a claim that the
overall method is faster or that stack semantics are accepted.
