# Compound-chain XR example

Run from the repository root:

```sh
clojure -M -m examples.lain.visualization-combinators.compound-levels 45670 127.0.0.1
```

Open http://127.0.0.1:45670/relationships. The source is
`compound_levels.lain`; saving it rebuilds the environment through the existing
file watcher. Stop the process with Ctrl-C.

The juxtaposed cards, left to right:

1. Outer chain: literal `2` → `source` → `stage` → `prepared` (9) →
   `pipeline` → `result` (10201).
2. Selected pipeline body: `x` → `stage` → `middle` → `stage` → `out`.
3. Selected stage body: `x` → `shift` → intermediate → `square` → `out`.

The third card selects the **outer stage occurrence**, not either nested stage
inside the pipeline. It demonstrates the same stage definition's internal
function composition without conflating the identities of repeated calls.

`body-snapshot` is an example-only binding composed from the existing observation
operator and pure `child-dataflow-graph`. Its completion argument waits for the
calculation before sampling. It does not continuously observe all descendant
changes. File reload takes new snapshots in a fresh environment. This example
uses ordinary arithmetic and TTMS-aware trace publication, not the unresolved
fully TTMS recursive `when` path.

No runner, compiler, merge or renderer changes are required. `xr:io` only
publishes the declarative juxtaposition. Graph transformation remains headless.

Verification: `clojure -M:test propagators.experimental.compound-levels-demo-test`
passed 6 assertions: result, three graph cards, node/edge counts and exact outer
directed edges. The browser displayed all three cards; 2D mode was selectable.
The existing small-card layout may require zooming. The file watcher is reused;
an external-editor reload was not retested for this example.
