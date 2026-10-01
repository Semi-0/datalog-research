# Run the live visualization example

## October 1 consumer update

The same launch command now enables the opt-in TTMS visualization extension.
`visualization/trace.clj` composes the existing observer installers with explicit
source stamping/message lift and supported dataflow projection. Shared compiler
defaults are unchanged. Selection has a **Clear selection** button and uses a
dedicated TTMS source; full-file reload clears it.

See [current consumer results and limitations](TTMS_CONSUMER_MIGRATION.md).
The September 29 topology diagnosis below is historical and predates the local
application-port projection work. It is not a current test report.

## Exact launch used on 2026-09-29

From the source repository, not a split-repository checkout:

```sh
cd /Users/linpandi/cloj-leapfrog
clojure -M -m examples.lain.visualization-combinators.demo
```

Open <http://127.0.0.1:45668/relationships>. Leave the terminal running; stop
with Ctrl-C. The launch was verified with HTTP 200 using:

```sh
curl --fail --silent --output /dev/null --write-out '%{http_code}\n' \
  http://127.0.0.1:45668/relationships
```

The initial Codex-sandbox launch failed with `SocketException: Operation not
permitted` while binding the socket. Retrying the **same command** with local
server permission succeeded. No code or port was changed for that retry.

The defaults are equivalent to:

```sh
clojure -M -m examples.lain.visualization-combinators.demo \
  examples/lain/visualization_combinators/chain.lain 45668 127.0.0.1
```

This launch is localhost-only. For an explicitly enabled trusted-LAN demo,
replace the last argument with `0.0.0.0` and browse to
`http://<computer-LAN-IP>:45668/relationships`. The experimental server exposes
interactive runtime controls without authentication: do not expose it to an
untrusted network.

## What runs and what reloads

[demo.clj](demo.clj) installs the layered-primitives and visualization session
extensions, loads [chain.lain](chain.lain), starts the XR HTTP server, and calls
`file-loader/watch-file!` on that file.

Saving different `.lain` contents, including from another editor or process,
compiles the entire file into a fresh environment. A successful compilation
replaces the live environment; an unsuccessful reload prints an error and
leaves the last working environment live. This is full replacement, not
incremental compilation. Only the selected file is watched, not its directory.
Restart the server after Clojure changes; reload the browser after renderer
changes. The watcher implementation is in
[file_loader.clj](../../../propagators/compiler_2/runtime/session/file_loader.clj).

The arithmetic inside `chain` is `input (5) -> add 1 -> add 1 -> output (7)`.
The eight panels and interactive selection behavior are described in
[REPORT.md](REPORT.md#reading-the-eight-panels).

## Where the trace is implemented

The example composes two separate operations:

```clojure
(relationship:roots input output relationships)
(relationship:dataflow relationships chain-graph)
```

1. [extension.clj](../../../propagators/experimental/visualization/extension.clj)
   binds the Lain names to `roots-operator` and `dataflow-operator`.
2. [runtime/operators/relationship_observer.clj](../../../propagators/compiler_2/runtime/operators/relationship_observer.clj)
   compiles operands to cell IDs and installs the native propagators.
3. [relationship_observer.clj](../../../propagators/relationship_observer.clj)
   defines `p:observe-roots`. It installs `p:observe-network` samplers, selects
   connected top-level nodes, and emits a graph snapshot as an ordinary cell
   message. It reads the activation's current `Net`.
4. [relationship_dataflow.clj](../../../propagators/relationship_dataflow.clj)
   defines `p:dataflow` and `dataflow-graph`. This transforms the snapshot into
   an application-level graph before XR sees it. XR renders the declaration;
   it does not determine these edges.

## Known first-panel topology defect (diagnosed 2026-09-29)

The first panel is **not an accurate expanded arithmetic chain**, and its
collapsed compound-output direction is currently wrong. Do not interpret
passing collection/composition tests as verification of this exact topology.

- `root-node-keys` excludes nodes with structural parents. Consequently,
  `relationship:roots` intentionally omits the additions inside `chain`.
  This is its top-level selection contract, not missing child ancestry.
- `connected-root-node-keys` traverses both input and output neighbors. The
  `(occurrence-of chain input output)` observation is connected to the same
  seed cells, so it is selected too. `observer-node?` excludes native observer
  propagators by name, not every observation-related application. This broad
  connected-component selection does not isolate the chosen computation.
- `selected-applications` passes all `:argument-ids` to `application-edges`.
  Its generic branch draws `argument -> application` for each argument, then
  `application -> result`. It does not interpret the closure's declared input
  and output ports. For `(chain input output)`, this incorrectly treats
  `output` as an input and displays a separate expression-result cell.
- Only `->` and `<->` receive special edge-direction handling. The projection
  excludes `xr:io`/`io:xr`, but does not exclude `occurrence-of`.

The relevant emitted edges are:

```text
input cell  -> chain application
output cell -> chain application       [incorrect semantic direction]
chain application -> anonymous result
chain definition cell -> occurrence-of application
input cell -> occurrence-of application
output cell -> occurrence-of application
```

The two nodes labeled `chain` are distinct: one holds the definition, the
other is the application propagator. Labels alone are not node identities.

A collapsed computation view should instead show `input -> chain -> output`
(and optionally the constant seed). Expanding that compound should reveal
its two additions, intermediate cell and constant inputs. That requires a
separate selection/expansion decision; it is not accomplished by changing
the renderer's layout.

The declaration information is already available:
`declared-interface` in
[observation.clj](../../../propagators/experimental/visualization/observation.clj)
uses `closure-inputs`, `closure-output` and application arguments to implement
`inputs-of`/`outputs-of`. The projection currently does not use that port
information. This diagnosis does not change runtime or tracing semantics.

Existing exact-edge tests in
[relationship_dataflow_test.clj](../../../test/propagators/relationship_dataflow_test.clj)
cover primitive expression chains, routing, fan-out and reload. They do not
assert the explicit-output compound edges or exclusion of `occurrence-of`
in this example.

Verification on 2026-09-29: loading the current `chain.lain` headlessly with
`demo/options` produced 8 nodes and 8 edges, including the incorrect
`output -> chain` edge above. The existing focused suites passed 16 tests /
50 assertions, with zero failures or errors:

```sh
clojure -M:test propagators.relationship-dataflow-test propagators.experimental.visualization-composition-test
```

This passing result demonstrates the coverage gap; it does not resolve the
diagnosed projection defect.
