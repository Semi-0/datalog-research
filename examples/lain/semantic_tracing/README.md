# Explicit semantic tracing gallery

Every example loads the shared Lain observation library and ends with one display
expression:

```clojure
(load-lain
  "propagators/compiler_2/runtime/lain/semantic_traversal.lain")

(display
  (observe-dependents-retained input))
```

`observe-dependents` is defined in Lain. It follows downstream transformations
through the shared Leapfrog semantic projection and retains every reached call's
input and output cells for context. Its callback runs in a disposable one-time
network; callback topology is not installed into the parent. It returns an ordinary
graph cell to `display` and composes through arbitrarily named Lain wrappers.
`observe-semantic` accepts `:upstream`, `:downstream`, or `:both`.
The existing `relationship:roots-semantic`, `relationship:roots`, and
`relationship:dataflow` APIs remain available.

Each program declares its result cells with `def-cells`. Declarations and
expressions occupy separate lines, with blank lines between logical sections.
The observed input binding varies by program. `display` sends the returned graph
to the current pane and reveals Topology automatically. The editor displays
that graph verbatim.
The separate Result layer is hidden when choosing Topology; inspect nodes for
values. An explicitly enabled Result layer uses plain text rather than a box.

| File | Meaning | Result binding |
| --- | --- | --- |
| 01-unary.lain | One input to `not` | true |
| 02-binary.lain | Two inputs to `+` | 7 |
| 03-three-inputs.lain | Three inputs to `combine` | 10 |
| 04-branch-true.lain | Lazy `if` builds addition | 8 |
| 05-branch-false.lain | Lazy `if` builds multiplication | 14 |
| 06-recursion.lain | Lazy `cond` accumulates 4 + 3 + 2 + 1 | 10 |
| 07-two-outputs.lain | `stats` writes sum 7 and product 12 | 19 |
| 08-captured-closure.lain | Returned closure retains bias 10 | 15 |

`workspace.json` is an optional four-desktop import. Fresh startup stays blank;
use the command palette to create desktops and load programs individually.
`single-desktop-v1.json` is the equivalent legacy import fixture.

## Tracer implementation and limits

`propagators/compiler_2/runtime/lain/semantic_traversal.lain` owns the composed
observation query. Its primitive traversal API is in
`propagators/compiler_2/runtime/operators/network_traversal.clj`; generic sampling
is in `network_sampling.clj`. Semantic snapshots use the same transformation
projection `semantic-network-graph` in
`propagators/relationship_dataflow.clj`. Root and explicit child views share the
same Leapfrog ownership rules. Cells, calls, routing operators, and relevant
copy propagators remain distinct: every edge joins a cell and a propagator.
The compact `relationship:dataflow` API is retained for existing callers.
No node captions or `name = value` labels are generated. Values remain metadata
and can be inspected by selecting their actual runtime cell.
Reachability traversal is in
`propagators/semantic_trace.clj`.

The projection retains a computed callable as a data input to the call that
uses it. Lazy `if` and `cond` select a suspended branch and invoke it automatically.
The branch examples observe `flag`, so the root shows the selection and dynamic
application. Open the selected application to see `+` for the true example or `*`
for the false example. The other arithmetic body is never built. Anonymous closure
cells display `network []`; generated selection and branch calls currently display
`:propagator/anonymous`.

The recursion example uses `cond` directly, without manual branch networks:

```clojure
(define sum-to
  (network [n acc]
    (cond [(<= n 0) acc
           else (sum-to (- n 1) (+ acc n))])))
```

Actual return-copy edges connect branch results to caller cells, retaining their
separate identities. Static operator declarations remain implicit.

`sum-to(n, acc)` recurses with `(n - 1, acc + n)` and returns the recursive
result without further arithmetic. Its source call is in tail position, but
there is no demonstrated tail-call elimination: runtime nodes grow with depth.
The identity base branch has no arithmetic application. Its graph retains the
actual return-copy propagators and their separate input/output cells instead of an empty
canvas. Select `sum-to`, then explicitly choose **Chase child calls** to see its actual
nested occurrences:

| n | acc | if branch | returned |
| --- | --- | --- | --- |
| 4 | 0 | else | 10 |
| 3 | 4 | else | 10 |
| 2 | 7 | else | 10 |
| 1 | 9 | else | 10 |
| 0 | 10 | then | 10 |

Every call returns 10 because the final accumulator is passed back unchanged.
These are runtime call instances, not an execution timeline. The inspector groups
by callable declaration identity, reports branch conditions and bounds traversal
to 64 calls / 32 nesting levels. Each row can open that occurrence's body. Raw
metadata remains under Runtime details. Cell nodes show their names; values are read in the inspector.

## Verification

```sh
clojure -M:test graph.lain-editor.inspection.semantic-gallery-test propagators.relationship-dataflow-test propagators.experimental.compound-tracer-test propagators.experimental.tracer-topology-test
```

Gallery checks require every `.lain` file in both workspace variants, require the
final expression to display `observe-dependents-retained`, and require every pane
to target `@display`. Runtime checks require a published Leapfrog root graph,
compare its JSON with the display verbatim, and disable re-projection during root
inspection so an editor fallback cannot satisfy the check. They also verify
operation ports, actual node identities, and that repeated inspection leaves
the program Net identical. The accumulator test follows
`(4,0) -> (3,4) -> (2,7) -> (1,9) -> (0,10)` and checks the chosen operator feeds
the dynamic call. Branch tests also spy on application declarations to require
exactly the chosen arithmetic operation and no declaration of the other body.
These fixture checks do not prove arbitrary Lain programs.

The default graph stays at the top-level chain. The captured-closure example
shows the application of `add-ten` downstream from `input`, including the callable
cell as context, without leaking its internal `+`. Use `:both` to also show the
upstream `make-adder` factory. Open the application to see `+`. See
`propagators/doc/semantic-tracing-datalog.md` for the executable Leapfrog rules.

The semantic view omits definition-location plumbing. A compiler-owned private
expression result used only to initialize a binding is represented by that
binding's actual cell. The producer connects directly to it, and alias metadata
retains the original cell ID. Independently named cells remain separate; their
copy operation is shown as `->`. Calls and cells are never merged with each other.
Cells have rounded frames and propagators have square frames. No legend or
extra caption is inserted into the graph.

See `propagators/doc/semantic-traversal.md` for the snapshot, isolation, and
publication contracts.

## Retained public tracer examples

Open `retained/workspace.json` for three numbered desktops showing
`relationship:semantic`, `relationship:roots` composed with `relationship:dataflow`,
and `relationship:children`. All three now run through retained observation; the
previous operator definitions remain available in their original namespaces.
The root views keep `combine` opaque, while the child view opens its multiplication
and addition network. Each example computes 10 and displays cell values.
