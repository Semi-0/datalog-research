# High-level tracing

`trace:high-level` projects a semantic graph onto explicitly selected component boundaries. It preserves the selected nodes' identities and current values; intermediate routing, accessor, and result nodes are hidden. Execution is unchanged.

```lain
(load-lain "propagators/compiler_2/runtime/lain/tracers.lain")

(display
  (trace:high-level
    (trace:semantic user)
    "user" "runtime" "evaluation-framework" "pi" "harbor" "socket"))
```

Use this expression after defining the named components, for example after loading `examples/lain/subagents/composition.lain`. Import the tracer library once per session. This is an additive primitive binding, available with the updated visualization or relationship-XR extension; older deployed runtimes require the extension update before this expression can run.

A connection means a directed path in the source graph through hidden nodes, stopping at the next selected boundary. The projection does not add a transitive shortcut through another selected component. Fan-out, reciprocal connections, feedback loops, and isolated selected nodes are preserved. Names must match exactly and identify one node; absent or ambiguous names fail explicitly. The Clojure projection also accepts exact graph node IDs when labels are ambiguous.

Values and their support/state are retained, including pending, contradictory, and withdrawn information. The operator recomputes from incoming graph updates; it does not create a clock or subscribe to an independent process. Select an existing live observation as the source when continuous updates are required. Existing capture observation IDs, support, provenance, and child scopes are preserved.

Tracing, accumulation, and visualization have separate responsibilities:

- The tracer reads one immutable network observation and captures public field references with their values, aliases, and support/state. High-level projection filters the visible topology while preserving these frozen inspection facts. Hidden field cells stay out of `:nodes` and the drawn connections.
- `trace:history-latest` retains the latest requested number of immutable graph observations. It does not acquire fields or consult a live network.
- The visualizer formats the selected snapshot through its captured-value reader. Supported fields retain the existing base/support presentation. A historical frame never substitutes a current live value for a missing captured field.

The field/history regression covers actual `composition.lain` rendering, aliased references, pending values, contradictions, support, ten-frame eviction, and unchanged earlier frames. Missing capture data in an older recording remains missing; new captures use the corrected tracer path.

An `ambiguous` field means its captured accessor references disagree. Compare those references with the settled live network before changing capture or presentation. Accessor transport must forward supported `nothing` and contradictions: a new withdrawal revision must reach every alias even though its base is not a usable application value. The harness lifecycle regression checks initial availability, running assignment, completion, and recovery through both live aliases and frozen history. Old recorded disagreements remain historical evidence.

The original immutable root graph is retained under `:high-level/source` for inspection. This does not add an automatic UI drill-down interaction. Component selection is explicit, rather than inferred from node labels or the current usability of a value.

In the editor, `display` selects the graph through the default `@display` output binding. If assigning the graph to a variable instead, select that variable in the Layers panel's Output binding field. Keep the visualizer and Topology layer enabled.

`main.lain` is a small executable example. The two visible boundaries should be `input` and `output`, carrying 3 and 4, with one directed connection between them.

The focused suites are registered in the standard propagator and graph test lists:

```sh
clojure -M:test propagators.semantic-trace.high-level-test graph.lain-editor.inspection.high-level-trace-test
```

They cover path contraction, fan-out, feedback and hidden cycles, exact identities, invalid selections, current boundary values, captured facts and child scopes, pending graph input, the public Lain alias, and the six-component harness overview through the editor session API. Loading that overview checks observation and rendering data; it does not launch a Pi child or establish end-to-end harness execution.
