# Composable tracers and supported graph facts

Import the tracer library once in a Lain session:

```lisp
(load-lain "propagators/compiler_2/runtime/lain/tracers.lain")
```

Configure a tracer, apply it to a caller-owned cell, then display the result:

```lisp
(display
  ((tracer:roots
     modifier:high-level
     modifier:with-values
     (modifier:select
       (list "user" "runtime" "evaluation-framework")))
   user))
```

The names in this example belong to the harness composition. They are boundary
labels, not built-in component names. The actual expression in
[`composition.lain`](../../examples/lain/subagents/composition.lain) wraps this
observation in `(trace:history-latest ... 10)` for the replay visualizer.

## Constructors and modifiers

A constructor accepts zero or more immutable modifier declarations and returns a
callable tracer. Applying that callable acquires or reuses an observation of the
original caller. The factory's implementation is excluded from its own trace.

| Constructor | Acquired relationship |
| --- | --- |
| `tracer:roots` | Connected caller/root relationships |
| `tracer:semantic` | Semantic caller relationships |
| `tracer:children` | Immediate child relationships of the selected occurrence |
| `tracer:dataflow` | Dataflow projection of the supplied graph |

| Declaration | Contribution |
| --- | --- |
| `modifier:with-values` | Supported captured-value facts and a value-inspection declaration |
| `modifier:high-level` | A declaration requesting connections through hidden intermediate nodes |
| `modifier:caller` | A caller-view declaration |
| `(modifier:select selectors)` | Supported boundary membership |

`selectors` is the ordinary built-in Lain list. Use `(list)` for a known empty
selection. Strings resolve labels; absent or ambiguous boundaries produce an
explicit error. Host callers can also use an existing node identity. Repeated
selections union membership. List order and duplicate members have no semantic
effect.

```lisp
(define selected
  (tracer:roots
    (modifier:select (list "user" "runtime"))
    modifier:with-values
    (modifier:select (list "runtime" "evaluation-framework"))))
(display (selected user))
```

An unresolved head or tail keeps the selector pending. Observing a prefix does
not establish that it is the complete selection:

```lisp
(define tail)
(define selected (modifier:select (cons "user" tail)))
;; selected is pending here
(-> (list "runtime") tail)
;; selected now contains user and runtime
```

Modifier declarations form a join: adding one retains the preceding information;
order, duplication, and grouping do not change the result. These laws use the
existing TTMS support ordering and subsumption rules. They do not compare private
accessor topology or define a second ordering.

Selection affects visibility. Source nodes and edges remain queryable. Projected
connections are separate derived facts. Adding a boundary can split a displayed
connection while retaining the supported path information that established it.
`display` interprets the declarations; there is no public `trace:render` operation.

## Results and host accessors

Public tracer results are immutable observation captures containing graph-fact
scopes. Semantic, child/caller, directional, session, call, and history tracers use
this boundary. A capture is data rather than an executable named network.
Captured values remain opaque and belong to their observation.

Use the named host interfaces instead of reading private map slots:

```clojure
(require '[propagators.semantic-trace.capture :as capture]
         '[propagators.semantic-trace.graph-facts :as facts]
         '[propagators.datastructures.layered-value :as datum]
         '[propagators.stdlib.premise-state :as state])

(def graph (capture/root-graph captured))
(def id (facts/assertion-id (facts/observation graph)
                            (facts/scope graph)
                            :graph/edge [from to]))
(def evidence (facts/current graph id))
(facts/base-value evidence)
(datum/support-of evidence)
(state/states-of evidence)
```

`capture/observation-id`, `capture/scopes`, `capture/scope`, `capture/support`, and
`capture/provenance` expose capture information. `facts/assertions` exposes the
qualified TTMS assertion index; `facts/source-assertions` distinguishes extensional
inputs, including modifier declarations, from derived conclusions. `facts/relations` and `facts/source-relations` expose current relation
membership. `facts/declarations`, `facts/selected`, and `facts/enabled?` expose the
view declarations. `facts/equivalent?` and `facts/subsumes?` compare graph facts
through TTMS information. Capture equivalence also checks observation ownership.

A fact identity is `[observation scope predicate tuple]`. Its assertion preserves
TTMS observations, producer timestamps, independent supports, premise state, and
contradictions. Advancing or retracting a source uses the existing revision rules.
Information-free bottom is distinct from supported pending or contradictory
information. Known pending captured cells remain available for inspection.

The source adapter accepts legacy EDB and call-graph relations. Overlapping
qualified claims join their TTMS evidence rather than overwriting each other.

## Queries, transformations, and completeness

`propagators.semantic-trace.fact-queries/query` uses the existing positive
Leapfrog evaluator. Derived facts retain contributor evidence, including
independent alternative proofs. It recomputes proofs from current source facts
when evidence changes; recursive conclusions cannot seed their own survival.
Proof-budget exhaustion throws an error.

`fact-queries/transform` accepts a pure transformation, including a Meander
rewrite, that returns derivation receipts:

```clojure
{:predicate :application/relationship
 :tuple [from to]
 :base true
 :contributors #{source-fact-id}}
```

Each emitted fact requires valid contributors from the same observation and
scope. Emit a separate receipt for each alternative derivation. Invalid or absent
evidence is rejected. Use `facts/derive` for one receipt and
`facts/contributor` to name existing evidence.

Absence requires supported completeness. `facts/complete` establishes that
premise; `facts/derive-absence` requires it. Withdrawing completeness withdraws
its conclusions. New membership in a complete predicate requires advancing or
withdrawing the completeness evidence first.

## History and expansion

```lisp
(display
  (trace:history-latest
    ((tracer:roots modifier:high-level modifier:with-values) result)
    10))
```

History retains immutable frames. `:history/frame [position observation]` facts
identify frames; `:history/next [before after]` facts preserve their order. Frame
membership has its own receipt evidence, so a later live-source revision does
not erase a retained past frame. Bounded recording preserves sequence identities
when older frames leave the window.

Expansion uses the retained observation's frozen adapter. It does not repair a
historical frame by reading the current live network. Missing or closed sources
produce an explicit partial/missing diagnostic. Source refinements are published
separately from view declarations: expanding a view with values cannot enable
values or change the selection in another view sharing that observation.

The observation publisher owns delivery and cleanup through the existing effect
mechanism. The editor renders and inspects observations. Frozen-source retention
has the session's lifetime; the replay frame bound is not a separate guarantee
about all source-registry memory. Closing the session releases that registry.

## Existing entry points

The library retains `trace:roots`, `trace:semantic`, `trace:root-semantic`,
`trace:children`, `trace:caller`, `trace:dataflow`, `trace:with-values`, and
`trace:high-level` entry points. `trace:upstream` and `trace:downstream` retain
their observation for inspection. `trace:downstream-live` uses disposable callback
topology. `trace:session` aliases the existing explicit-output `trace` operator;
`trace:calls` aliases `call-graph`.

`trace:history root start end`, `trace:graph-history graph start end`, and
`trace:history-latest graph count` feed the existing replay consumer. Their
results now use observation-qualified frame facts. Host consumers that previously
read `(:nodes result)` must first use `capture/root-graph` or `capture/scope`.
Rendering consumers then use `high-level/display-graph`; source queries use the
public fact accessors.

## Verification and measurements

Run the focused acceptance gate with `clojure -M:test tracers`. It rejects
failures, errors, and zero assertions. The gate covers modifier laws, supported
withdrawal/recovery, completeness, recursive proof cleanup, empty/open selectors,
shared acquisition, isolated expansion, capture/history, call graphs, editor
inspection, replay, and the actual harness composition.

Run `clojure -M -m bench-tracer-facts` for observation-only measurements of
acquisition reuse, construction, single-fact updates, withdrawal, allocations,
and forced-GC heap deltas. The benchmark does not replace acceptance tests.
Reported timing is local JVM timing, not GX10 model throughput or a deployment
claim. See the [migration verification report](evidence/composable-tracers-2026-11-02.md)
for measured costs, the browser result, and independently reproduced baseline failures.
