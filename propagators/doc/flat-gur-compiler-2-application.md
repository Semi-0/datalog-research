# Flat GUR and Compiler 2 Application

Status: current architecture, 2026-09-12.

`propagators.gur` is the public flat-GUR facade. Compiler 2 application and
local-first lexical access declare additive topology in the active `Net`.
`propagators.gur.accumulating` remains an explicit alternative for programs
that need an accumulated child-network value.

## Boundary

```clojure
{:compiler
 {:input '[compiler-state application-ast continuation]
  :output '[compiler-state result-cell-binding]
  :effect :declare-topology}

 :application
 {:input '[operator-cell context-cell argument-cells result-cell]
  :output '[flat-declaration-effects]
  :effect :extend-active-net}

 :runtime
 {:input '[Net scheduled-propagators]
  :output '[Net messages]
  :effect :propagate}}
```

The dependency direction is:

```clojure
'[[compiler.handlers -> runtime.application]
  [compiler.declarations -> runtime.application]
  [runtime.application -> model.env]
  [runtime.application -> propagators.gur]
  [model.env -> compound-object]
  [propagators.gur.flat -/> compiler-2]
  [scheduler -/> compiler-2]
  [tms -/> compiler-2]]
```

## Application protocol

Compiler callables are flat recursive closures whose body dispatches through a
Compiler 2 protocol. The protocol keeps primitive, closure, and constraint
topology extension explicit.

```clojure
(defprotocol ApplicationTopology
  (application-effects
    [application gur-context invocation-ids result-id]))

(deftype PrimitiveApplication [installer declaration]
  ApplicationTopology
  (application-effects [_ context invocation result-id]
    (primitive-application-effects
     installer declaration context invocation result-id)))

(deftype ClosureApplication
  [compile* declaration-id lexical-env-id closure-info]
  ApplicationTopology
  (application-effects [_ context invocation result-id]
    (closure-application-effects
     compile* declaration-id lexical-env-id closure-info
     context invocation result-id)))
```

Application installation is one same-network declaration:

```clojure
(gur/apply-closure-effect
 operator-id
 (into [context-id] argument-ids)
 result-id)
```

The effect has a stable semantic identity and named relations for operator,
arguments, context, frame, and result. Inspection reads those relations with
`application-topologies`; it does not require a parallel retained-application
object.

```text
operator cell
  -> flat GUR application
  -> concrete inbound arguments
  -> live scope frame
  -> compiled body topology
  -> concrete outbound values
  -> result cell
```

Unavailable and contradictory operator information waits in flat GUR. No
Compiler 2 operator classifier, unwrap layer, pending reader, child applied
network, or scheduler branch participates in readiness.

## Live compound environments

Every compiler environment is a compound object stored in a normal cell.
Canonical locals are declared as live slot topology:

```clojure
(env/p:declare-canonical-local 'x frame-id value-id)
```

The graph shape is:

```clojure
{:frame-env
 {:env/parent captured-env
  :env/local-bindings '#{x}
  'x {:value value-cell}}}
```

Imported host environments use `import-environment-topology`. Each imported
slot points to a binding descriptor, and the descriptor points to the bound
value cell:

```text
environment --slot(x)--> binding-slot
binding-slot --slot(:value)--> binding-descriptor
binding-descriptor --binding-id--> value-cell
```

Callers schedule every returned `:prop-id`, so later values refine the same
environment topology.

## Recursive lexical access

Local-first lookup is itself a flat recursive declaration authored with
`propagators.install`:

```clojure
(-> ctx
    (i/slot :env/local-bindings :local-names :frame)
    (i/install :contains-local
               (p:contains-binding? sym)
               :local-names
               :local-present)
    (i/install :missing-local
               (p:missing-binding? sym)
               :local-names
               :local-missing)
    (i/when-named :local-binding
                  :local-present
                  (local-binding sym :frame :out))
    (i/when-named :parent-frame
                  :local-missing
                  (parent-binding :frame :out)))
```

A declared local blocks parent traversal while its descriptor or value is
unavailable. A definitely missing local recursively applies the same closure
to the parent frame. Named availability gives each branch a stable identity,
waits for `nothing` or contradiction, and declares its body once when usable.

Compiler symbol evaluation remains two visible steps:

```clojure
(-> network
    ((env/p:lexical-access-local-first sym environment-id binding-id))
    ((env/p:binding-value binding-id value-id)))
```

## Inspection and compatibility

Connected named topology is the application IR. Call graph, session repair,
retraction inspection, and TUI publishing traverse the same relations that
execute the call.

`gur/p:apply-closure` remains as a deprecated installer-shaped adapter over
`gur/apply-closure-effect`. Accumulating GUR is imported explicitly from
`propagators.gur.accumulating`.

Historical retained-application, closure-frame, lexical-application, and
application-layer modules have no production callers and were deleted.

## Multi-repository delivery gap

`Semi-0/lain-compiler` is the canonical Compiler 2 repository. The extraction
renamed `propagators.compiler-2.*` namespaces to `propagators.compiler.*`. It
contains the language parser and AST, CPS compiler, compiler model, application
lowering, behavior compiler, call-graph and TMS operators, public compiler API,
tests, and compiler documentation. Its declared dependency is
`Semi-0/lain-infrastructure`.

The implementation originated in monorepo commit
`3eac0243745cf1a6a21495e4f6e77a5bbef5900d` on
`Semi-0/datalog-research`, branch
`codex/compiler-2-application-runtime-cleanup`. Its dependency-ordered
extraction has now published topic branches for infrastructure, the canonical
Compiler 2 repository, and runtime. None of these topic branches is described
as merged to `main`.

The monorepo commit cannot be copied wholesale into `lain-compiler`. Its files
cross the established extraction boundaries:

```clojure
{:implemented-in
 {:monorepo {:repository "Semi-0/datalog-research"
             :commit "3eac0243745cf1a6a21495e4f6e77a5bbef5900d"}
  :infrastructure-repository
  {:repository "Semi-0/lain-infrastructure"
   :branch "codex/flat-gur-default"
   :commit "4df5523f709289854e44676a91f10a96b68603f5"
   :port-status :published}
  :compiler-repository
  {:repository "Semi-0/lain-compiler"
   :branch "codex/compiler-2-flat-gur-application"
   :commit "3ec0685c638a5ff36b8e458a1133fc9945f05282"
   :port-status :published}
  :runtime-repository
  {:repository "Semi-0/lain-runtime-clojure"
   :branch "codex/compiler-2-flat-gur-runtime"
   :commit "e3d726c45a82b3cfb3a00ccd5855ffae6db5d5c2"
   :port-status :published}
  :tui-repository
  {:repository "Semi-0/wired"
   :branch "codex/compiler-2-flat-gur-tui"
   :port-status :blocked-uncommitted}}

 :required-port-boundaries
 {:lain-infrastructure '#{propagators.gur
                          propagators.install
                          shared-builder-tests}
  :lain-compiler '#{compiler-language
                    compiler-cps
                    compiler-model
                    compiler-lowering
                    compiler-operators
                    compiler-tests
                    compiler-documentation}
  :lain-runtime-clojure '#{compiler-runtime
                           compiler-session
                           runtime-tests}
  :wired '#{compiler-tui
            tui-tests}
  :datalog-research '#{graph-demos
                       research-documents}}

 :delivery-state
 {:ownership-classification :complete
  :history-preserving-port :partial
  :cross-repository-verification :failed-at-wired
  :published-through :lain-runtime-clojure}}
```

The published compiler port passed 70 standalone test vars under a three-second
per-test guard. The runtime port passed its 135-test standalone inventory under
the same guard. The `wired` verification exposed two integration gaps:

1. Its semantic graph adapters delegate to runtime inspection that does not yet
   interpret callable-wrapped flat-GUR declarations and application names in the
   form expected by the existing TUI trace views.
2. Existing TUI consumers expect selected values from compound outputs, while
   several calls now expose accessor compounds or `nothing`. This affects
   linked-list projection, block targets and watches, slider arithmetic, and
   web-client routing.

The exploratory runtime inspection fix was reverted because it would have
expanded the port into a new runtime design. `wired` remains uncommitted and
unpushed. Completing it requires a separate ownership decision for runtime
semantic inspection and compound-result projection; weakening or deleting its
integration tests would not prove the port.

## Known limitation: dictionary-backed cell protocols

Compiler 2 lowers a topology assembled in a temporary `Net` with
`runtime.topology-effects/network-diff`. The lowering preserves new cells,
propagators, lexical names, and cell messages. It does not preserve arbitrary
network-dictionary entries.

The experimental `clock-in` primitive exposes this limit. Its installer marks
the selected output with `event/mark-protocol-cell`, which stores the cell ID in
the `:pi/event-cells` network dictionary. The flat topology diff drops that
dictionary update:

```text
clock installer
  -> mark temporary output as an event-protocol cell
  -> lower temporary Net to bounded flat effects
  -> event-cell dictionary declaration is omitted
  -> active Net receives event content in an unmarked cell
  -> auto-output/block-premise selects the ordinary display route
  -> TUI display remains nothing
```

Live inspection reproduced an event-bearing clock result for which
`event/protocol-cell?` was false, followed by an empty block-result display
source. Deterministic clock ticks therefore do not appear in the versioned TUI.
Editing the clock interval waits for the same unavailable display value.

This is a limitation of the experimental clock integration, not evidence
against flat application or recursive lexical access. The reliable clock
contracts remain covered: clock updates are source-aware event facts, and a
retracted block disables its subscription. The unsupported event-to-TUI
crossing is retained as the deprecated diagnostic function
`loaded-clock-display-result` rather than as a release gate.

Fixing the crossing robustly requires a separately reviewed representation for
dictionary-backed protocol declarations. Extending the fixed flat-GUR kernel,
generic cell protocol, or scheduler remains outside this refactor boundary.
