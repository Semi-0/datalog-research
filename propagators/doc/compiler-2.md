# Compiler 2

Source files:

- `propagators/compiler_2/language/parser.clj`
- `propagators/compiler_2/language/ast.clj`
- `propagators/compiler_2/cps_core.clj`
- `propagators/compiler_2/compiler/handlers.clj`
- `propagators/compiler_2/compiler/predicates.clj`
- `propagators/compiler_2/compiler/declarations.clj`
- `propagators/compiler_2/operators/call_graph.clj`
- `propagators/compiler_2/runtime/application.clj`
- `propagators/compiler_2/model/closure_value.clj`
- `propagators/compiler_2/model/context.clj`
- `propagators/compiler_2/model/env.clj`
- `propagators/compiler_2/compiler/basis.clj`
- `propagators/compiler_2/tms_behavior.clj`
- `propagators/datastructures/scope_source.clj`
- `propagators/datastructures/dependency.clj`
- `propagators/gur.clj`
- `test/propagators_compile_2_test.clj`
- `test/propagators/compiler_2_call_graph_test.clj`
- `test/propagators/compiler_2_gur_linked_list_test.clj`

## Status

Compiler 2 treats compilation as network expansion. It does not turn an AST into
an opaque runtime function. It declares cells, retained IR data, slot topology,
application propagators, and closure data inside the propagator network.

The current surface language is intentionally small:

```clojure
(+ 1 2)

(let-cell [out]
  (<-> out (+ a 2))
  out)

(:: [x]
  (+ x 1))

(cell-expr [x]
  (+ x 1))

(network [x] [out]
  (<-> (+ x 1) out))

(def-net inc [x] [out]
  (<-> (+ x 1) out))

(def apply-out
  (distributed-premise-closure
    (network [f x] [out]
      (f x out))
    premise-id
    epoch))
```

`::` and `cell-expr` are zero-output closure forms: applying them returns the
closure body's result cell. `network` and `def-net` are declared-output network
forms: applying them requires explicit output cells as the tail of the applicant
list. The parser still produces AST data; `core.clj` compiles that AST directly.

`call-graph` (also bound as `p:call-graph`) is an ordinary primitive
propagator. It combines potential call sites stored in a closure body with
realized calls published by retained application declaration IR:

```clojure
(let-cell [f out graph]
  (def-net f [x] [out]
    (+ x 1))
  (f 2 out)
  (call-graph f graph)
  graph)
```

The result is a semantic graph value. Potential calls are available as soon as
the closure value arrives. Each application declaration carries an optional
`:application/caller` cell ID and installs a named call-fact publisher. If a
late operator cell later receives a closure, that publisher reactively refines
the same graph. Recursive calls point back to the caller closure ID and appear
as graph cycles. No tracer, TUI, or scheduler hook is required.

For example:

```clojure
(let-cell [same next]
  ((network [x] [same next]
     (<-> x same)
     (<-> (+ x 1) next))
   4 same next)
  next)
```

The call above supplies `x`, `same`, and `next` as applicants. The network body
declares relationships among those cells. Returning `next` is a separate source
expression; the network call does not synthesize a hidden result object with
`same` / `next` slots.

Distributed TMS is the default compiler-2 path. The compiler-facing TMS
operators live in `propagators.compiler-2.operators.tms`, behavior operators live in
`propagators.compiler-2.operators.behavior`, and the old
`propagators.compiler-2.tms-behavior` namespace is now only a compatibility
facade. `default-env` binds distributed premise/content inputs, premise
believe/retract, `tms-closure`, and distributed `premise-closure`.
`distributed-premise-closure` remains as the explicit long name for the same
operator.

The behavior+TMS env also binds `behavior-point`, which constructs a behavior
value from ordinary compiler-2 source. Because it is just an operator, it can be
wrapped in a normal compiler-2 network closure:

```clojure
(let-cell [a b out]
  (def-net make-point [t v] [out]
    (behavior-point t v out))
  (make-point 6 2 a)
  (make-point 6 7 b)
  (<-> (+ a b) out)
  out)
```

For reducer-shaped behavior, the same env binds `behavior`, `behavior-event`,
`behavior-empty-state`, `behavior-add-event`, and `behavior-retain-last`.
`behavior` takes an event source and a compiler-2 network closure; each reducer
step runs that closure as the one-time merge network:

```clojure
(let-cell [events out]
  (def-net retain-event [acc update] [out]
    (behavior-add-event acc update out))
  (behavior-event 6 2 events)
  (behavior-event 8 3 events)
  (behavior events retain-event (behavior-empty-state) out)
  out)
```

The merge network can also be written from lower-level behavior operators when
the policy should be explicit in compiler-2 source:

```clojure
(def-net retain-event-low [acc update] [out]
  (let-cell [known tick value next]
    (behavior-state-events acc known)
    (behavior-update-tick update tick)
    (behavior-update-value update value)
    (behavior-assoc-event known tick value next)
    (behavior-state-from-events next out)))
```

Changing the reducer closure changes retention policy while keeping the same
behavior construction path:

```clojure
(def-net retain-window [acc update] [out]
  (let-cell [full]
    (behavior-add-event acc update full)
    (behavior-retain-last full 2 out)))
```

The live runtime env also binds XR widget IO operators for browser/XR-driven
behavior sources. Widget IO does not let the browser write arbitrary cells; it
registers view/event-source pairs and the runtime injects epoch-keyed behavior
events only through those declared channels:

```clojure
(let-cell [a-events b-events c-events a b c out widget]
  (def-net retain-event [acc update] [out]
    (behavior-add-event acc update out))
  (behavior a-events retain-event (behavior-empty-state) a)
  (behavior b-events retain-event (behavior-empty-state) b)
  (behavior c-events retain-event (behavior-empty-state) c)
  (slider-panel-io "mix"
    "a" a a-events
    "b" b b-events
    "c" c c-events
    widget)
  (<-> (- (+ a b) c) out)
  out)
```

For a panel, each user edit re-emits the latest known channel values at one
runtime-owned epoch, so sparse behavior arithmetic receives aligned events
while still preserving the underlying event history.

`premise-closure` is sugar for a premise-marked network operator. It takes a
wrapped `network`, a premise cell, and an epoch cell. Application runs the
wrapped closure against an internal output cell, then emits only the
premise-marked distributed TMS update to the explicit output cell:

```clojure
(let-cell [x out]
  (def value 5)
  (def input-premise :premise/input)
  (def definition-premise :definition/inc)
  (def epoch0 0)
  (premise-input value input-premise epoch0 x)
  (def-net inc [x] [out]
    (<-> (+ x 1) out))
  (def apply-inc
    (premise-closure
      (network [f x] [out]
        (f x out))
      definition-premise
      epoch0))
  (apply-inc inc x out)
  out)
```

The sugar produces a canonical accumulating-GUR closure. Its body composes the
ordinary application propagator with premise-marking propagators. For
declared-output networks, it marks the explicit output applicant rather than the
hidden application result cell.

Later `(premise-retract definition-premise epoch1 out)` or
`(premise-retract input-premise epoch2 x)` adds new premise-state facts. Old
claim facts remain in the output cell; distributed strongest projection returns
`nothing` while the needed premise is inactive.

Repeated `def` / `def-net` / `def-cell` forms shadow the name with the newly
compiled value cell. They do not reuse the old name cell. That means an earlier
application keeps the operator cell it already referenced, while later
applications see the new binding:

```clojure
(let-cell [out]
  (def p-one :definition/plus-one)
  (def p-ten :definition/plus-ten)
  (def epoch0 0)
  (def op
    (premise-closure
      (network [f x] [out] (f x out))
      p-one
      epoch0))
  (op plus-one x out)
  (def op
    (premise-closure
      (network [f x] [out] (f x out))
      p-ten
      epoch0))
  (op plus-ten x out)
  out)
```

The two applications emit separate distributed TMS claims because the
premise-marked closure claim identity includes the premise. Retraction/bring-in
of `p-one` and `p-ten` selects the active definition through distributed TMS
projection.

Centralized reducer-cell TMS is still available as legacy compatibility through
`propagators.compiler-2.tms-behavior/legacy-central-tms-env` or the thin
`helpers/legacy-central-tms-env` export. That path overrides `premise-closure`
with the legacy centralized implementation, which emits TMS reducer-cell
claim/premise facts into an explicit storage cell:

```clojure
(let-cell [out]
  (def p :definition/inc)
  (def tms)
  (def apply-out
    (premise-closure
      (network [f x] [out]
        (f x out))
      p
      tms))
  (def-net inc [x] [out]
    (<-> (+ x 1) out))
  (apply-out inc 4 out)
  tms)
```

## Two-Stage Compilation Model

Compiler 2 now has an explicit conceptual split:

1. Retain an inspectable IR in the network.
2. Lower that IR into executable propagator topology.

Today those two stages still happen in one `compile-source` / `compile-expr`
call, but the data boundary is present. Every application gets an application
IR cell, and one uniform application propagator is installed alongside it.

`propagators.compiler-2.main/compile-source` remains the raw compiler-2 entry
and uses `helpers/default-env`. To compile with behavior arithmetic plus
distributed TMS primitives by default, use:

```clojure
(main/compile-source-with-behavior-tms source {:net n})
```

The matching AST entry is `main/compile-expr-with-behavior-tms`.

The retained application declaration has slots:

```clojure
{:application/operator-ast  operator-ast
 :application/operator-cell operator-cell
 :application/args          argument-object-cell-id
 :application/arg-cells     [arg-cell-ids...]
 :application/output        result-cell-id
 :application/context       context-cell-id}
```

This is a declaration fact. It exists before scheduler evaluation and can be
inspected without running the application. Compiler 2 independently installs
`gur/p:apply-closure` from the operator and argument cells to the result cell.
The declaration is not an evaluator input. Primitive and network callables are
both canonical accumulating-GUR closure values, so application does not classify
or unwrap the operator.

## Composable Compiler Declaration

Compiler-2 keeps its public `g:compile` MultiFn, but built-in compilation is
assembled from ordinary functions. The default compiler uses continuation
passing behind the same synchronous facade:

```clojure
compile-k [state expr k] -> thunk
k         [state binding] -> thunk-or-result
compile*  [state expr] -> [state binding]
```

`propagators.compiler-common.cps/on` turns a predicate and CPS handler into a
rule that delegates non-matches. `compose-rules` builds the rule chain and
`make-compiler` drives it with Clojure's `trampoline`.
`propagators.compiler-2.cps-core/compiler-dispatch` is the production rule
composition. Its predicates, handlers, and declaration primitives live in
separate namespaces under `compiler-2.compiler`. A local CPS variant can
prepend another `on` rule without
adding or replacing a global `defmethod`, then pass the resulting compiler as
`:compiler` to `compile-expr`.

The old `core` and `compiler.core` façades are archived as
`deprecated.legacy-core` and `deprecated.compiler-core`. `predicate-core`
remains a deprecated compatibility façade. The synchronous implementation is
under `propagators.compiler-2.deprecated`; its `g:compile`, `g:apply`, and
`g:advance` retain their public identities, but none selects expressions on
the production CPS path.

The environment has one internal authority: `:env` in compiler state. The
three-argument `g:compile` methods remain compatibility adapters and copy their
explicit environment into state before calling a handler.

Selected compilers are captured by delayed installers. Closure bodies, lazy
`when`, direct list operands, and `execute-sub-env` therefore continue through
the same local handler composition when compilation happens during scheduler
activation.

Operators that intentionally compile raw operand forms may provide the
functional `:operator/direct-compiler` strategy:

```clojure
[compile-k state operand-forms out-id k] -> thunk
```

The built-in list and constraint operators use it. The existing
three-argument direct installer remains an opaque compatibility fallback.

Verified in `propagators.compiler-2-composition-test` and
`propagators.compiler-2-cps-test`, including public MultiFn identity,
independent compiler instances, delayed local compilation, parity, and deeply
nested stack-safe traversal.

## Closure Values Are Data

A compiler-2 closure cell stores a compound object, not a
`propagators.closure/Closure` runtime function:

```clojure
{:closure/env         lexical-env-id
 :closure/body        body-ast
 :closure/inputs      [x y]
 :closure/output      out-or-nil
 :closure/scope       lexical-scope
 :closure/declaration declaration-cell-id
 :gur/captured-cell-ids [lexical-env-id ...]}
```

The lexical environment and declaration are attached through compound-object
slots. Captured cells cross the accumulated frame boundary by ID. This keeps
closure data slot-backed while application uses the canonical GUR value and
scheduled argument cell IDs directly.

Declaration and evaluation stay separate:

- closure declaration creates data and slot topology
- closure declaration does not compile or run the body
- closure application is the only place body evaluation happens

## Application Flow

`core/g:apply` has one ordinary application path. It compiles operator and
operands into cells, records application IR, and installs
`gur/p:apply-closure`. Contextual primitive wrappers include their context cell
in the canonical closure's declared boundary.

For zero-output closures, the result cell is the implicit output. For declared
`network` / `def-net` closures, the tail argument cells are explicit outputs.
The same application propagator observes operator, arguments, and output, so
late information and backward information wake it through normal adjacency.

When sufficient information is available, the propagator emits an application
request into the accumulating subnet. The runner declares one deterministic
frame containing scope relations, captured-cell projections, body-local cells,
body propagators, and explicit output adapters. Repeated activation reuses the
same request and frame facts.

For declared-output network values, only input cells need usable values before
forward activation. Output cells are boundary outputs, so output-first backward
information can also declare and run the same frame. The declared output symbols
bind to those explicit output cells; no structural output slots are created under
the hidden application result.

## Runtime Blocks And Semantic Tracing

The current runtime prototype is in:

- `propagators/compiler_2/runtime.clj`
- `graph/compiler_2_runtime_server.clj`
- `graph/compiler_2_tui.clj`
- `graph/compiler_2_semantic_repl.clj`
- `propagators/semantic_trace.clj`

The runtime owns one growing compiler-2 program environment. Multiple
TUI/socket clients can connect to that same runtime. Each client instance owns
its own block list and view state; it is not a shared document UI. Authored
blocks from those instance-local lists are still compiled into the same runtime
env/net, so definitions from one instance can become part of the shared program
state seen by later rebuilds.

What exists now is a live runtime surface, not just a REPL transcript:

- blocks are runtime cells in linked instance-local block lists;
- `(def name)` creates a named free cell that later blocks can constrain;
- `def`, `def-net`, and `def-cell` bind names to newly compiled value cells;
  later definitions shadow earlier bindings instead of mutating the old name
  cell;
- `block-at` lets compiler-2 code read and write block cells through the same
  propagator network;
- `trace` produces a semantic graph value that can itself be stored in a block
  cell and rendered by the TUI;
- trace blocks are recompiled after a full rebuild so they can react to later
  upstream relationships;
- trace traversal follows semantic nodes backed by the same runtime cell across
  different source blocks, so a chain like `(+ 1 2) -> b -> a` appears when
  tracing upstream of `a`;
- clients can communicate through block cells by writing values or graph traces
  into another client's block with `block-at` and `instance`;
- TUI rendering uses Vijual stress-majorization with the current compiler-2
  semantic opts: spacing `1.7`, stress iterations `200`, refine iterations
  `200`.

Normal blocks contain compiler-2 source. There are no runtime source special
forms: authored block text always goes through compiler-2. Runtime reflection is
available only through operators installed into the compiler environment. Each
client instance is bound by client id, and `%` is bound to the instance that owns
the block currently being compiled.

```clojure
(let-cell [v]
  (block-at % 1 v)
  (block-at (instance tui-b) 1 v)
  v)

(let-cell [next g]
  (inc1 4 next)
  (trace next g)
  (block-at (instance tui-b) 1 g)
  g)
```

`block-at` is a compiler primitive operator over an instance's linked block
list. Blocks are dumb cells: text, graph values, contradictions, and `nothing`
are displayed directly from the block cell. No runtime path creates generated
output blocks or copies compiler results into display blocks.

For REPL ergonomics, a normal expression block with a following block is compiled
with an implicit language-level output relation to that following block. Typing:

```clojure
(+ 1 2)
```

behaves like:

```clojure
(let-cell [out]
  (<-> (+ 1 2) out)
  (block-at % 1 out)
  out)
```

Top-level `def-net` blocks are not wrapped, so definitions still extend the
growing compiler environment rather than being hidden inside a local cell scope.

A second instance can use a network declared by the first instance, trace a
local application cell, and write the graph into one of its own blocks with
normal compiler-2 code:

```clojure
(let-cell [next g]
  (inc1 4 next)
  (trace next g)
  (block-at (instance tui-b) 1 g)
  g)
```

`trace` is also a compiler primitive operator; direction defaults to upstream,
and `(trace next :downstream g)` follows outgoing semantic graph edges.
Rendering is not part of propagation: trace propagators produce graph data, and
the TUI/view layer renders graph values with Vijual stress-majorization layout.
The trace graph is topology plus projection data, not a cell-content dump. Cell
`content` is internal merge evidence; cell `strongest` is the readable truth.
TUI and XR renderers may show only strongest-derived lightweight summaries, and
runtime UI pulses should use explicit changed cell/node ids from the completed
transaction rather than diffing raw serialized cell values.
Top-level `(trace a :upstream g)` traces the semantic label `"a"` rather than
only the local input cell of the trace expression. Concrete cell tracing remains
available through explicit trace requests at the runtime API boundary.

There are also installed traces for reactive inspection. An installed trace
stores a tracing propagator with a clock/epoch cell. The epoch ticks on the
configured interval, so downstream traces can expand as the aggregate semantic
graph grows.

Useful commands:

```bash
clojure -M -m graph.compiler-2-semantic-repl \
'(let-cell [same next] ((network [x] [same next] (<-> x same) (<-> (+ x 1) next)) 4 same next) next)'

clojure -M -m graph.compiler-2-runtime-server server 45555

clojure -M -m graph.compiler-2-runtime-server request 45555 \
'{:op :compile/source :source "(let-cell [same next] ((network [x] [same next] (<-> x same) (<-> (+ x 1) next)) 4 same next) next)"}'

clojure -M -m graph.compiler-2-runtime-server graph 45555
clojure -M -m graph.compiler-2-runtime-server trace 45555 next

clojure -M -m graph.compiler-2-runtime-server request 45555 \
'{:op :semantic/trace/install :label "next" :direction :upstream :interval-ms 5000}'

clojure -M -m graph.compiler-2-runtime-server udp-request 45555 \
'{:op :agent/send-block :client-id "agent" :text "(+ 1 2)"}'

clojure -M -m graph.compiler-2-runtime-server udp-request 45555 \
'{:op :agent/blocks :client-id "agent"}'

clojure -M -m graph.compiler-2-tui 45555 tui-1
```

Shortcut aliases:

```bash
clojure -M:wired/server
clojure -M:wired/client -name A
clojure -M:wired/xr
```

`:wired/server` starts the shared compiler-2 runtime on the default port. When
compiled code installs an `xr-io` propagator and it emits an XR launch effect,
the server starts the XR/browser projection on demand against the same runtime
session.
`:wired/client` starts a TUI client against that runtime; `-name A` selects the
client instance name. `:wired/xr` starts a standalone browser/XR projection
server for isolated XR testing.

The runtime server also starts an EDN-over-UDP endpoint on the same numeric
port by default. It is intended for lightweight LLM-agent integration with a
running block session. UDP commands use the same response envelope as TCP:

```clojure
{:ok true :result ...}
{:ok false :error "..."}
```

Agent block commands:

```clojure
{:op :agent/send-block :client-id "agent" :text "(+ 1 2)"}
{:op :agent/send-block :client-id "agent" :mode :append :text "(def x)"}
{:op :agent/blocks :client-id "agent"}
{:op :agent/blocks :client-id "agent" :indexes [0 2 3]}
{:op :agent/block :client-id "agent" :index 1}
```

`:agent/send-block` defaults to submit-mode, matching the TUI prompt behavior:
it writes into the current input block, compiles, and leaves the next blank
prompt block ready. Append-mode directly appends a new source block.

## Parallel GUR Linked-List Probe

`test/propagators/compiler_2_gur_linked_list_test.clj` is a prototype slice, not
the active compiler-2 lowering. It keeps the existing compiler path intact and
demonstrates the next target shape with canonical accumulating GUR and public
compound-object linked-list accessors.

The test builds the declaration source as cells plus `obj/p:cons` /
`obj/p:car` / `obj/p:cdr`:

```clojure
[:compound add-bias x + x bias]
```

It does not seed a materialized `subenv/cons-list-value` or read the source list
back into Clojure data during compilation. The experiment declarations use the
existing `compile/def-recursive` source DSL, which now targets accumulating
GUR. A small GUR compiler closure walks that linked-list declaration through
accessor topology, emits a GUR closure value, then applies that closure with
`propagators.gur/p:apply-closure`.

The compiled closure demonstrates lexical access without materializing the
environment. Its body receives an accessor-backed env cell, installs
`compiler-2.env/p:lexical-access` for `bias`, and composes the result with
stdlib `prop/+`. The passing assertion is:

```clojure
((compiled-add-bias 5) with bias = 10) => 15
```

This proves the short path: linked-list declaration traversal, compound
propagator declaration as a GUR closure value, GUR application, and accessor
lexical lookup. It does not yet prove dynamic AST operator dispatch or recursive
construction of every possible lexical accessor.

## Prototype Readiness

The current system is good enough to continue building compiler-2 as a
prototype on top of accumulating GUR, with a narrow target. GUR should be used
for recursive compiler machinery: walking linked-list/AST declarations through
`obj/p:cons` / `obj/p:car` / `obj/p:cdr`, constructing recursive lexical
accessors, expanding macro-like declarations, and declaring higher-order
compiler topology. Ordinary compiled programs use the same accumulating-GUR
application boundary for primitive and network callables.

The prototype boundary is still real. Compiler-2 should not yet assume a final
general recursion substrate for all user code, automatic GC of accumulated GUR
frames, or dynamic dispatch over arbitrary AST operators. Those are migration
targets. The safe next step is to incrementally replace hard-coded compiler-2
probes with GUR-backed declaration traversal and lexical accessor construction
while keeping existing compiler behavior green.

## Semantic Shortcut Ledger

This ledger is the cleanup order for compiler-2 shortcuts that make propagation
look correct locally while losing semantic identity or provenance.

| Status | Shortcut | Symptom | Propagator-native replacement | Proving test |
| --- | --- | --- | --- | --- |
| fixed | Compiler 2 closure/application materialization and strongest-value dispatch. | Closure calls could collapse accessor identity, wait in hidden readers, or bypass ordinary propagation readiness. | Retain closure/application IR, store canonical accumulating-GUR closures in callable cells, and install `gur/p:apply-closure` immediately from operator and argument cells to the result. | Direct-GUR late-operator, late-argument, contradiction, output-first, nested, and lexical application tests. |
| fixed | Runtime `trace` closes over a per-block graph sidecar. | `(trace out g)` can trace block plumbing instead of the runtime env graph. | Bind one stable runtime semantic graph cell in the compiler env and have `trace` read it. | `block-language-traces-def-net-application-dependence-graph`. |
| fixed | Runtime `block-at` uses host `head-id->blocks` lookup. | Cross-session block access depends on runtime maps rather than linked block cells. | Implement indexed linked-list access as a primitive propagator installed through compiler-2 env. | `cross-session-block-at-writes-only-target-block`. |
| fixed | Default arithmetic unwraps `scope-source` / dependency values. | `(+ scoped-x 1)` can lose scope/provenance. | Make the default primitive env provenance-aware or explicitly use the contextual primitive wrapper. | `compile-2-default-arithmetic-preserves-operand-dependencies`. |
| fixed | Runtime output/block copy and generated-block reset bridge. | Display block maintenance can look like semantic program flow. | Blocks are dumb cells; users connect values to blocks with compiler primitives like `block-at`. | `cross-session-block-at-writes-only-target-block`, `tui-view-is-monotone-linked-blocks`. |
| fixed | `semantic-trace` value-label fallback. | Equal values can conflate unrelated cells. | Trace by cell id or explicit label only. | `semantic-trace-does-not-target-by-equal-value`. |
| fixed | Per-block semantic graph ids collide. | Later trace blocks can overwrite labels from earlier expression graphs, hiding constants like `1` in `(<-> (+ 1 2) a)`. | Namespace semantic graph ids per source block before graph union. | `submitted-trace-keeps-upstream-literal-constants`. |
| fixed | Trace traversal treats same runtime cell in different blocks as unrelated nodes. | Tracing upstream of `a` through `b -> a` misses later upstream edges into `b`. | Preserve all semantic-node aliases per runtime cell and expand traversal through alias-equivalent nodes. | `trace-block-reacts-through-intermediate-cell-chain`. |
| fixed | TUI trace blocks compile before the complete runtime semantic graph exists. | `(trace a :upstream g)` can stay empty when upstream relations are added later. | Recompile trace source blocks after the full source-block rebuild pass and seed the stable runtime graph cell with the accumulated graph. | `trace-block-reacts-to-later-upstream-relationships`. |
| open TODO | Live runtime expression blocks are recompiled in a second pass after declarations. | A block that mentions a later `def-net` can compile to `nothing` without recording an error, so error-only retry misses it. The second pass proves behavior but is a design workaround. | Track block dependencies against env/runtime cell identities and invalidate/rebuild only affected expression/watch blocks when later declarations extend the environment. | `block-order-is-top-to-bottom`, `later-def-net-updates-earlier-free-cell-watch`. |
| open | Layered/generic procedure materialization. | Other procedure systems duplicate the same activation-local materialization pattern. | Later shared application substrate after compiler-2 closure application is stable. | Shared substrate tests, not part of this slice. |

## Lexical Environments

Environments are explicit lexical frames. A frame has metadata slots:

```clojure
:env/parent
:env/scope
:env/scope-chain
:env/depth
```

Each symbol slot stores a binding slot, not a scoped value:

```clojure
x -> {:value {:binding/type :cell, :binding/id x-cell}}
```

The env answers "which cell does this name designate?" The pointed-to cell
answers "what is its current value and provenance?" Refinement happens in the
cell, not by replacing the env slot.

`scope-source` is only lexical lookup metadata. It does not carry arithmetic
provenance or dependency information. Lexical access wraps the current binding
payload with the frame source and active chain:

```clojure
scope-source(source-frame, active-chain, payload-from-bound-cell)
```

Dependency/provenance should live in the bound cell's value, for example as a
`dependency-value`. The lexical access result can then be a `scope-source`
whose payload is already dependency-aware.

### Propagator Lexical Mindset

Propagation is simultaneous and monotonic. If both child and parent lexical
edges are installed, a parent value can fire before the child binding/value has
refined enough to make the parent path invalid. That earlier parent message
cannot be retracted; a later child value can only add information or contradict.

So lexical shadowing must be structural:

```text
wrong: install all frame reads, rank strongest values later
right: parent traversal is blocked by local binding presence
```

For lexical scope, the guard is "this frame binds `x`", not "this frame's `x`
cell currently has a value." Binding presence is structural and monotonic;
value availability is not.

The current experiment records that structural guard in each frame's
`:env/local-bindings` slot. `p:sub-env` creates an empty local declaration set,
and `p:bind-local` creates a same-scope binding frame whose local declaration
set contains the bound symbol. `p:lexical-access` reads this metadata through
slot accessors:

- if the frame declares the symbol, install only the local scope-source path;
- if the frame metadata is known and does not declare the symbol, recurse to
  `:env/parent`;
- if the metadata is unknown, emit nothing.

This keeps late binding payloads and late closure values possible: the binding
frame can exist before the cell it points at has refined. It does not solve
truly late same-frame declaration, such as replacing a frame's local binding
set from "does not bind `x`" to "binds `x`" after parent traversal has already
emitted. That case needs a monotonic declaration representation, reducer
claims, or TMS-style retraction later.

Do not write read-local `scope-source` back into the bound cell. The same cell
can be accessed from different lexical chains, so access context belongs on the
access result, while derivation provenance belongs in the cell value.

Practical rules:

- Env slots store binding addresses only.
- Bound cells carry value refinement and derivation provenance.
- Lexical access adds access-context as `scope-source`.
- Parent lexical traversal must be blocked by local binding presence.
- Contradiction means the graph allowed incompatible facts to meet; it is not
  something an imperative overwrite would have fixed.

## Dependency Arithmetic

`helpers/default-env` remains raw and compatible. For example:

```clojure
(main/compile-source "(+ 1 2)")
;; result strongest: 3
```

`helpers/dependency-env` installs contextual primitive operators. Each
application allocates an implicit context cell:

```clojure
{:context/scope       active-scope
 :context/chain       active-chain
 :context/application application-id
 :context/operator    operator-ast}
```

Contextual arithmetic unwraps operand bases, computes the base result, and emits
a dependency value:

```clojure
{:base               result
 :dependency/sources #{operand-sources active-context-source}}
```

The active context assigns the result dependency source. Operand dependency
layers contribute sources, but operand lexical scopes do not decide the result
scope. This keeps lexical lookup and arithmetic provenance separate.

## Shared Pattern Across The System

Compiler 2 now has the same broad shape as compound objects, layered procedures,
and generic procedures:

```text
declaration data
  -> slot-backed topology
  -> retained application/branch IR
  -> accumulating subnet request
  -> deterministic branch/body topology
  -> explicit output projection
  -> evidence-preserving diff
```

### Compound Object

`compound_object.clj` is the lowest-level slot algebra.

- A compound value is a named network with public slot cells and internal
  indexes.
- `p:slot` is a bidirectional relation between a parent cell and a slot cell.
- `p:reduce` installs internal accessors for all public slots and reduces the
  materialized slot values.

Its key abstraction is "slot topology as durable partial information, effectful
execution as activation-local work."

### Layered Procedure

`layered.clj` uses compound-object slots for both data layers and procedure
layers.

- A procedure cell is a compound object whose slots are layer names.
- `install-layered-procedure!` declares slot topology only.
- `p:apply-layered` materializes procedure layers into an activation frame,
  installs one branch per active layer, writes branch results to a result bank,
  and reduces that bank into the final layered output.

Layered procedures are "slotful branch application plus layered-object
reduction."

### Generic Procedure

`generic_procedure.clj` also stores an extensible procedure as a compound
object.

- The generic cell has default and policy slots.
- Each method is a branch object attached under a generated method slot.
- Application materializes complete method branches, runs predicates and
  matchers in parallel, writes handler results into a result bank, and reduces
  with select-one semantics.

Generic procedures are "slotful method branches plus select-one reduction."

### Compiler 2

Compiler 2 uses compound objects for inspectable declarations and accumulating
GUR for execution.

- Closure IR retains AST, parameters, output declaration, and lexical
  environment identity before evaluation.
- A callable cell contains a canonical accumulating-GUR closure that references
  its declaration and captured cell ids.
- Application IR retains operator, arguments, output, and context separately
  from execution.
- Application immediately installs `gur/p:apply-closure`; propagation readiness
  waits for usable information and declares deterministic body topology once.
- Captured cells cross declared GUR boundaries and lexical access remains a
  composition of scope, local selection, and binding-value propagators.
- Contextual operators are ordinary propagator relations with one hidden context
  cell, not special evaluator state.

Compiler 2 is "retained declaration data plus lazy accumulating-GUR topology."

## Possible Common Algebra

The duplication across these systems suggests a common algebra can be extracted
without changing the runtime model.

### 1. Slot Materialization for Legacy Procedure Systems

Both `generic_procedure` and `layered` currently copy a collection cell plus
declared parent cells into a local frame, reinstall declared `p:slot` topology,
and run it to read a materialized compound value. Compiler 2 application does
not use this path.

Candidate extraction:

```clojure
(application/materialize-slots outer-net collection-id normalize)
;; => {:net frame-net
;;     :value materialized-value
;;     :slot-values {slot-key value}}
```

This could remove ad hoc materialization code from layered and generic procedure
dispatch without reintroducing it into Compiler 2.

### 2. Branch Application Builder

Layered and generic applications both:

- create a result bank
- install several branches
- write branch outputs to bank slots
- reduce the bank
- run the activation network
- diff one external output

`propagators.application/build-branch-application` already moves in this
direction. A stronger abstraction would expose:

```clojure
{:copy-cells ...
 :branch-source ...
 :install-branch ...
 :result-bank-policy ...}
```

Then layered procedures and generic procedures differ mainly by branch source
and reducer policy.

### 3. Reducer Policies As First-Class Algebra

`dispatch/layered-object-policy` and `dispatch/select-one-policy` are reducer
policies over result-bank slots. Compiler 2 uses explicit GUR boundary output
projection rather than a result-bank policy.

Candidate common interface:

```clojure
(install-output-policy policy frame result-source out-id)
```

Policies could include:

- copy one result
- externalize and copy escaped closure values
- select one usable branch
- copy all active layered slots
- merge dependency/provenance layers

### 4. Context-Passing Operators

Compiler 2 represents contextual behavior in the canonical callable closure's
declared boundary. The retained application object remains uniform and does not
classify operators. Layered arithmetic and dependency arithmetic can therefore
receive an implicit evaluation context without adding policy to application.

Candidate extraction:

```clojure
(operator/raw f)
(operator/contextual f)
(operator/apply operator network context-id arg-ids out-id)
```

Layered arithmetic could eventually be expressed as contextual operators that
produce layered dependency/provenance data rather than bespoke closure records.

### 5. Procedure Declarations As Inspectable Records

Layered procedures, generic procedures, and Compiler 2 closure declarations are
inspectable records, but their execution mechanisms now differ:

```text
layered/generic record -> materialize declared method slots -> reduce branch bank
Compiler 2 declaration -> canonical GUR closure -> declare boundary topology
```

A shared abstraction for layered and generic procedures could define:

- how to enumerate branch/config slots
- how to validate a complete branch
- how to install a branch
- which output policy to use

Compiler 2 should share only genuinely common declaration relations and output
projections; its application readiness remains the canonical GUR protocol.

## Cautions

The common algebra should not hide the core invariants:

- declaration must not run evaluation
- activation-local effects must not persist in durable compound values
- output copying/diffing must be explicit
- reducers must observe slots through accessors, not direct named-network peeks
- contextual metadata should be ordinary cell data, not hidden global state

Any extraction should start with small helpers local to the layered and generic
systems. It must not put materialization or procedure classification back into
Compiler 2 application.
