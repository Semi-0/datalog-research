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

This is the executable, pinned `stack-spec-2026-10-20-r1` partial specification.
The example and semantic capture
are real; `struct`, hierarchy readers and validation predicates remain explicit
implementation obligations. No spatial renderer is implemented. Missing capture
must remain unknown rather than becoming an empty hierarchy. Occurrence identity
must include the original observation; path-based cycle detection alone does
not deduplicate sharing across sibling branches. Resolve these contracts before
implementing lower-level readers or rendering.

Verification:

```sh
clojure -M:test graph.lain-editor.stack-design-test
```
