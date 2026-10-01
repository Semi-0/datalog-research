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
4. The same stage's cells transposed into a list of source references.
5. A list of their current strongest values: 2, 3 and 9 (stable identity order,
   not execution order).
6. `call-graph stage`: potential calls from its retained body AST, alongside
   realized calls from all three stage invocations.

The third card selects the **outer stage occurrence**, not either nested stage
inside the pipeline. It demonstrates the same stage definition's internal
function composition without conflating the identities of repeated calls.

## Compose cells into a list

```clojure
(def child-cells (filter (:: [item] (cell-ref? item)) stage-body))
(def child-list (transpose child-cells :list))
(def child-values (map (:: [item] (strongest-of item)) child-list))
```

`cell-ref?` is a small example-only observation operator checking the actual
referenced network entry, not its label or value. The callback is an ordinary
Lain closure. Existing collection operations preserve source identity through
filter, transpose and map. This means all cells **visible at the selected body
level**, including its ports and intermediate cells, not recursively flattened
descendants or every compiler-internal cell. Substitute `pipeline-body` to view
its three cells instead. Filtering out propagators leaves no cell-to-propagator
edges; the original graph remains reachable through the collection's graph source.

## Retained compilation information

```clojure
(def compiled-calls (call-graph stage))
(xr:io (juxtapose stage-body child-list child-values compiled-calls))
```

This reuses the existing `call-graph` primitive, not a new compiler or tracer.
`closure-value/closure-body` retains the slot-backed AST. `call-graph/call-sites`
reads its syntax paths; `potential-call-graph` projects declared calls;
`p:application-call` records realized calls. `potential shift` and `potential
square` describe syntax; `call shift` and `call square` describe instantiations.
The test checks three realized calls of each because this program invokes stage
three times. Definition-level call relations are not occurrence-level dataflow.

This is a retained-syntax **call graph**, not the entire lowering graph, an AST
tree, or a source-file/line map. Retained application IR also exists in
`compiler_2/model/application_value.clj`, but this example does not add a complete
application-IR inspector. The legacy call graph uses its own node IDs; generic
cell-reference collection transforms are demonstrated on `stage-body`, not
claimed for those syntax-call nodes.

`body-snapshot` is an example-only binding composed from the existing observation
operator and pure `child-dataflow-graph`. Its completion argument waits for the
calculation before sampling. It does not continuously observe all descendant
changes. File reload takes new snapshots in a fresh environment. This example
uses ordinary arithmetic and TTMS-aware trace publication, not the unresolved
fully TTMS recursive `when` path.

No runner, compiler, merge or renderer changes are required. `xr:io` only
publishes the declarative juxtaposition. Graph transformation remains headless.

Verification commands:

```sh
clojure -M:test propagators.experimental.compound-levels-demo-test propagators.compiler-2-call-graph-test propagators.experimental.visualization-composition-test
```

Tests check result, six published cards, exact outer directed edges, selected
cell identities, ordering, values, source correspondence, retained AST call paths,
and repeated realized invocations. October 1 result: 80 assertions passed across
these three namespaces, with no failures or errors. This is not a new full-suite
pass; the checkpoint report's TTMS recursion and other nonpasses remain open.
The existing small-card layout may require
zooming. The file watcher is reused; an external-editor reload was not retested
for this example.
