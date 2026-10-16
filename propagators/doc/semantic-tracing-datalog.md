# Semantic tracing by call scope and Leapfrog rules

The editor, `relationship:dataflow`, and `relationship:roots-semantic` use the same projection in
`propagators/semantic_trace/projection.clj`. Its executable rules are in
`propagators/semantic_trace/logic.clj`; the read-only runtime adapter is
`propagators/semantic_trace/facts.clj`. Evaluation uses `leapfrog-pure/semi-naive`.
The older traversal helpers are removed from `relationship_dataflow.clj`.

## Operator protocol

All three observers are ordinary callable installers with declared input/output
selectors. `relationship:roots` takes a seed cell and returns a raw graph cell;
`relationship:roots-semantic` takes a seed cell and returns the shared projection of
that graph. `relationship:dataflow` takes an existing raw graph cell and returns
its projection. The historical `relationship:semantic` name aliases the unified
root-semantic operator. They support expression, network-local, and higher-order
calls. The gallery uses `(display (relationship:roots-semantic seed))` directly,
without an intermediate graph binding.

The unified root-semantic operator publishes `:dataflow/representation :network`:
a bipartite graph of transformations. Calls, routing operators, returned-member
copy propagators, and displayed cells retain actual runtime IDs. Private
compiler-owned returns used only by a definition are represented by their named
binding cell; definition syntax does not add an operation to this view. The new `network-rules` derive copy ports as
cell-to-propagator and propagator-to-cell edges. Names are labels, never generated
value captions; payloads remain metadata. Explicit child inspection preserves
this representation and uses the same nearest-application ownership rule.
The lower-level compact `relationship:dataflow` projection retains its existing
contract for callers that explicitly choose it.
The first two preserve the legacy multi-seed explicit-output form: with two or
more arguments, the final argument is the output cell. No arguments is an error.
Local parameter seeds resolve to callers through actual declared inbound copies;
definition aliases resolve through declared definition copies. The same finite
Leapfrog return-reachability rule resolves these paths without opening bodies.

## Abstraction contract

A call is opaque at its caller's level. Root views select calls with no enclosing
application, regardless of intervening compiler wrappers. Opening an occurrence
selects its immediate application children, traversing ownership wrappers and
stopping at every application boundary. Lexical nesting of a returned closure
is not the ownership of a later invocation of that closure.

For `make-adder`, the root chain is:

```text
10 -> make-adder -> add-ten = network [x] -> add-ten -> result = 15
                                             ^
                                          input = 5
```

Opening `make-adder` shows its return, not the `+` inside the returned closure.
Opening the invocation of `add-ten` shows that `+` and the captured bias.
For accumulator recursion, root shows one `sum-to` call. Opening it shows `<=`,
`if`, and the selected-network invocation. Opening that invocation shows `-`,
`+`, and one recursive `sum-to` occurrence. Each occurrence retains its own ID.

Selecting a node reads only its boundary metadata. **Open this call body** enters
one level. **Chase child calls** explicitly requests the bounded occurrence report
(64 calls / 32 levels). It never changes the graph scope automatically.

## Facts and strata

All application and cell identities are `[network-path node-id]`. Application
facts come from connected Compiler 2 application topology and ordered port
metadata. Parent facts come from `Net.relationship`. Copy facts come from actual
compiler definition copies and declared caller/returned-member boundaries.
Neither source text, displayed labels, equal values, nor callable body text
establishes ownership or producer identity.

1. Extract ownership ancestors of calls and copy propagators. This restriction
   preserves every path relevant to those owner queries; it stops at no barriers.
2. Derive the closest application owner through transparent wrappers. Classify
   root calls with a closed-world anti-join over the complete owner result.
3. Select root calls or the immediate children of the explicitly opened parent.
   Instrumentation is identified by its runtime operator declaration, so an alias
   of `display` or the projector does not become an ordinary program operation.
4. Derive semantic edges, produced callable values, and producer reachability
   through copies. A computed operator becomes a data input to its application;
   static operator declarations stay implicit.
5. Derive aliases for private definition results and returned input boundary
   ports using their separate sole-consumer rules. Fan-out and ordinary output-only
   member ports retain their identities.
6. Derive the undirected component containing the observed seed cells, retaining
   the direction of every displayed edge.

The engine supports positive rules. Closed-world root classification and
single-successor aggregation are explicit finite snapshot strata in the adapter;
they are not a claim of native negation or aggregation support in Leapfrog.

These are actual rule declarations, not pseudocode:

```clojure
{:head [:owner [:node :app]]
 :body [[:parent [:node :wrapper]]
        [:transparent [:wrapper]]
        [:owner [:wrapper :app]]]}

{:head [:visible [:app]]
 :body [[:owner [:app :parent]]
        [:opened [:parent]]
        [:eligible [:app]]]}

{:head [:producer [:target :app]]
 :body [[:copy-back [:target :source]]
        [:producer [:source :app]]]}

{:head [:edge [:cell :app]]
 :body [[:operator-use [:cell :app]]
        [:visible [:app]]
        [:producer [:cell :producer]]]}
```

`projection/explain` returns the EDB, derived relations, ownership, aliases, and
final graph for an immutable Net. `projection/project` returns just that graph.
Terms are interned bijectively before entering Leapfrog's string-ordered tries:
a symbol and a same-spelled string, or the same local ID at different network
paths, cannot collapse. Rules use distinct predicate occurrences because the
existing engine's delta-source API is keyed by predicate; unsupported reuse is
rejected explicitly.

## Observation and publication

Visibility and subscription coverage are separate. Pending calls still expose
subscription targets at their interface, so arrival of their operator can refresh
the trace without writing an argument. Observers use the same ownership rules
as projection. Public ports can belong structurally to an opaque call without
making its body visible.

Successful source loads refresh the registered root observers, including newly
appended calls whose inputs did not change. Requests are ordinary compiler
name-binding declarations; stable observer IDs make refresh idempotent. Failed
loads do not refresh the rejected candidate. Projection itself remains read-only.

In unversioned graph union, an incoming `nothing` sample cannot erase a known
cell value. This handles queued partial observations of monotone ordinary cells.
TTMS publication remains responsible for its premise versions and retractions;
the projection does not invent support or claim an execution history.

Graph-valued cells retain their identities and edges in sampled topology, but
snapshot value metadata records `{:semantic-trace/value :graph}` instead of
embedding the graph again. This prevents an observer returned by a network from
recursively containing its own snapshot. Cell contents and publication support
are unchanged; ordinary value inspection can still read the original graph.

Queries do not evaluate activations or mutate graph, environment, dictionary,
or relationship state. Scope and `:dataflow/projection :leapfrog` metadata survive
graph union; an explicitly mixed-scope union is marked `:mixed` and cannot be
reprojected without choosing one scope. Program replacement retains its existing
session/generation boundaries.

## Verification

```sh
clojure -M:test propagators.semantic-trace.logic-test graph.lain-editor.semantic-scope-test graph.lain-editor.semantic-gallery-test graph.lain-editor.call-inspection-test propagators.relationship-dataflow-test propagators.relationship-observer-test propagators.experimental.compound-tracer-test propagators.experimental.tracer-topology-test
```

The rule tests cover scope barriers, compiler wrappers, producer aliases,
static operators, fan-out, term identity, and bottom-value merge order. Runtime
oracles cover factory creation versus invocation, recursion, nested and repeated
calls, cycles, branching, multiple returned ports, late operators, native/TTMS
publication, and repeated reads without Net mutation or topology growth.

## Compiler-owned cell names

Literal and expression cells carry bounded source names from the compiler.
Returned output ports use the local binding name where available, otherwise the
source cell name or a positional output name. These are declaration metadata,
not value-derived captions, aliases, or identity. Named declarations are additive:
they preserve existing content, strongest values, and explicit names. Seeding and
effect replay retain those names. Anonymous network declarations may remain
unnamed. Application fallback IDs remain stable but are only allocated as cells
when selected as the actual return.

The `:compiler-2/source-cells` name scope records which cells received source
names. The legacy compact projection uses this provenance to preserve its
existing contraction policy; adding a source name does not turn an intermediate
return into an explicit binding. The unified transformation projection applies
the separate binding rule below. Every original runtime identity remains
available for inspection.

## Binding syntax versus transformations

`bindings/explain` extracts definition-copy facts, compiler source-cell provenance,
and the snapshot-local sole-consumer relation. Leapfrog derives `binding-alias`
only when a private source cell's only consumer is that definition copy. The
resulting graph uses the real destination cell and retains the original cell in
`:node-aliases`; the Net is never mutated. Independent named source cells and
fan-out do not qualify. Their definition copy stays explicit as the ordinary
`->` data movement operation. Explicit routing, application propagators, and
returned-member boundaries retain their roles unless the returned-input rule below
represents them at their caller cell. The same binding rule applies at a root
or an explicitly opened child scope, without changing ownership or eligibility.

The editor serializes aliases separately from nodes and edges. Every displayed
edge still connects a cell to a propagator or a propagator to a cell. Rounded
cell frames and square propagator frames are presentation only and share the
same solver bounds and text size.

## Returned input ports and reciprocal arrows

`inout-port-rules` recognizes a returned member port copied to an argument of
that same application, provided the return copy is the port's sole consumer.
The caller-level semantic graph uses the actual caller cell for that port and
retains the hidden port's runtime ID in `:node-aliases`. This removes only the
boundary port and its copy from the projection. It never merges a cell with a
propagator, rewrites the Net, or infers equivalence from names or equal values.
Ordinary output-only members, copies belonging to another call, and ports with
fan-out retain their separate identities. Child projection uses the same rule
inside the explicitly selected scope.

```clojure
{:head [:port-alias [:port :caller :copy]]
 :body [[:member-output [:app :port]]
        [:return-copy [:app :port :copy :caller]]
        [:argument [:app :caller]]
        [:sole-consumer [:port :copy]]]}
```

`reciprocal-edge-rules` joins each selected edge with the reversed edge relation.
The graph retains both directed edges for upstream/downstream traversal and
publishes the matching pairs in `:bidirectional-edges`. Graph union and tracing
preserve this annotation. The editor emits one connection with
`bidirectional: true` per reciprocal pair and draws two arrowheads at its
midpoint. One-way edges retain one arrowhead. Graph updates include direction
in their identity, so changing a connection's direction refreshes the geometry
and highlights its endpoints. Vijual receives the same undirected constraints
as before; the rule changes topology presentation, not the layout objective.
