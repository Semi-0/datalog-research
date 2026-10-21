# Stack design domains

Next work follows the [constraint-first implementation plan](../../../../doc/stack-editor-constraint-first-plan.md).
Complete and review its constraints, then the high-level tail-recursive Lain
composition, before filling primitive obligations or implementing rendering.

Load `examples/lain/top_down/stack-design.lain` as the composition entry point.
It defines `(define import load-lain)` and imports these files in order:

- `structure.lain`: named-field stack and reference constructors/accessors.
- `constraints.lain`: constraint composition and unresolved check contracts.
- `traversal-state.lain`: explicit pending work, discovery, ownership, and output
  connection contracts.
- `hierarchy.lain`: frozen hierarchy readers and tail-recursive expansion.
- `fixtures.lain`: revision-pinned outcomes and the controlled edit sequence.
- `example.lain`: a nested compound network producing `15`.

Imports use project-root-relative paths and share the program environment;
they do not introduce namespaces or an idempotent module cache. Load the entry
once in a fresh editor block. Opening domain files in neighboring blocks is
for source review, not sharing runtime bindings across blocks.

This is the executable, pinned `stack-spec-2026-10-20-r9` specification.
`compound->stack` is the primary declarative entry point and delegates to an
explicit-work, tail-recursive `scope->stack` traversal. Its primitive bindings,
constraint outcomes, incremental checkpoint path, clean reconstruction
comparison, and spatial scene interpreter are implemented. Missing capture
remains unknown rather than becoming an empty hierarchy. Occurrence identity
includes the original observation, so sharing and cycles produce explicit
references instead of duplicate planes. Camera state remains outside the stack
domain value.

For a live editor walkthrough of actual compound children, load
`examples/lain/top_down/stack-live-trace.lain`. It starts by displaying the
`captured` binding. Open the pipeline call body and its two nested stage call
bodies, then use **Toggle display layers** in the command palette and set its
**Output binding** to `stacks`. Those actions
refine the same frozen observation; the existing traversal resumes and renders
the pipeline plane above two distinct stage planes. The example does not use the
controlled renderer fixture or acquire from a newer runtime.

Verification:

```sh
clojure -M:test \
  graph.lain-editor.stack-design-test \
  graph.lain-editor.stack-primitives-test \
  graph.lain-editor.stack-incremental-integration-test \
  graph.lain-editor.stack-experiment-test \
  graph.lain-editor.stack-scene-test
```
