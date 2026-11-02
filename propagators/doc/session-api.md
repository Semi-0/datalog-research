# Lain session API reference

Status: source-grounded against the current working tree, 2026-11-02. This reference describes the implemented Clojure APIs; it does not certify remote deployment or an OS sandbox. Read [the implementation/acceptance guide](immutable-compiler-sessions.md) for the previously observed baseline and candidate results. KIROSHI and coding agents should use this document as a source reference, not as a new approved architecture fact.

## Choose the correct layer

| Layer | Responsibility | Owner |
| --- | --- | --- |
| `propagators.compiler-2.session` | Immutable declarations, real compiler/environment/network, pure advance | Caller retains returned snapshots |
| `.session.operators` | Primitive/async declarations lowered through canonical GUR | Compiler installs topology |
| `.session.dependencies` | External start, normalization, completion and cleanup behavior | Host adapter owns mutable resources |
| `.session.files` | Trusted file/module preparation before a compiler transaction | Trusted host; this can execute native code |
| `.session.host` | One live commit owner, bounded input admission, delivery, receipts and cleanup | Application/service, independently of views |
| `graph.lain-editor.runtime.session` | Existing local editor registries and adapters | Registry ownership rules below |
| `graph.lain-editor.server` | Existing loopback HTTP editor transport | Server, not an isolation boundary |

The compiler/session API is not a security sandbox. Uploaded Clojure can execute arbitrary JVM/process/IO operations. Use OS-enforced isolation before accepting experimental native modules.

Do not reconstruct environments or compound values as host snapshots. Use named constructors/accessors; do not access `deftype` fields, instantiate implementation types directly, reset the compatibility atom, or fabricate internal binding/import structures.

## Immutable compiler API

Require `[propagators.compiler-2.session :as s]`.

| API | Input and result | Contract |
| --- | --- | --- |
| `create-session []` | New session with generated session/run UUIDs | Does not dispatch host callbacks |
| `create-session [options]` | Options map; returns a new session | **Supply both `:id` and `:run-id` when using this arity** |
| `environment [s]` | Current compiler environment identity/reference | Live canonical slots, not a materialized host map |
| `network [s]` | Current immutable propagator network | Keep old snapshots valid |
| `advance [s inputs]` | Returns a public `Turn` | Does not mutate `s` or invoke dependency callbacks |
| `extend-session [s extension]` | Returns a candidate/new session | Atomic, monotone catalog extension; throws on failure |
| `dependency-declarations [s]` | Dependency ID → capability map | Declarations, not active transport handles |
| `source-files [s]` | File identity → source string map | Includes prepared imported sources |
| `rerun [s fresh-run-id]` | Rebuilds current declarations with the same session ID | Fresh run identity required; does **not** stop a live host's resources |
| `session-identity [s]` | `{:id ... :run-id ...}` | Distinguish session identity from a run and editor generation |
| `session-declarations [s]` | Current declaration view | Contains catalogs, recorded extension IDs, bootstrap/options/limits |
| `compiler-state [s]` | Existing Compiler-2 runtime state view | Infrastructure adapter API; not a domain update mechanism |
| `with-compiler-state [s state]` | New snapshot preserving identity/declarations | Compatibility adapter; validates a real runtime network |
| `binding-cell [s symbol]` | Canonical binding value-cell identity | Symbol required; unknown binding resolution throws |
| `binding-value [s symbol]` | Current strongest value in that cell | May carry support/dependency state or be pending/contradictory |
| `cell-input [cell-id update]` | Explicit ordinary runtime cell input | NodeId required; preserves the supplied domain update |
| `normalize-input [input]` | Normalized ordinary cell input | Accepts `cell-input` output or native `Message`; rejects ambiguous forms |
| `turn [s next-inputs requests]` | Public `Turn` value | Named constructor for the boundary result |

`Turn` has deliberately public fields `:session`, `:next-inputs`, and `:requests`. Keep the returned `:session`. `:next-inputs` is currently empty for direct compiler advance; do not turn that implementation detail into a future protocol restriction. Requests remain declarations until a host commits and delivers them.

`create-session` options:

- `:id`, `:run-id`: required in the options arity.
- `:native-extensions`: existing trusted runtime extension bundles, installed in order.
- `:runtime-options`: existing runtime options. `:record-inputs? true` opts into input recording; recording is off by default in direct compiler advance.
- `:propagation-options`: `{:max-steps 100000 :timeout-ms 60000}` by default. Both limits must be positive integers. Deadlines are cooperative between runner steps; primitive implementations must terminate.

`binding-value` is an observation, not an input to copy back into an owner. `propagators.experimental.visualization.data/payload` is useful for displaying a supported value; do not use observation unwrapping to discard support, contradiction, availability or retraction state on publication.

### Extension declarations

`session-extension [{:id id :primitives ... :dependencies ... :files ... :imports ...}]` returns an `ExtensionDeclaration`. Inspect it through `extension-id` and `extension-parts`.

- `:id` must be non-nil. Reusing it with different parts fails, including after an installation that added no new topology.
- `:primitives` is a symbol-keyed map or ordered `[symbol declaration]` pairs. Duplicate pair names fail.
- `:dependencies` maps IDs to values implementing `Dependency`.
- `:files` maps string identities to source strings, **not filesystem paths to read**. Source is parsed when constructing the declaration.
- `:imports` is a trusted prepared manifest. Normal callers obtain it through `files/prepare-extension!`; do not hand-invent it.

Equal catalogs accumulate without reinstalling existing operators. A new extension ID can produce a new session value while reusing the identical environment/network. Repeating that recorded extension is a no-op. Conflicting primitive/dependency/file/import identities fail. A new primitive cannot replace an existing value-bearing source definition or source alias. A bare `(define name)` can be refined. Supported unresolved lookup reservations are reused so an already-compiled GUR chain can become ready after late installation.

New file identities accumulate. Replacing text under an existing file identity is not an edit operation. Source redefinition conflicts fail; create an explicit fresh program/run when changing declarations rather than weakening global merge rules.

### Minimal pure example

```clojure
(require '[propagators.compiler-2.session :as s]
         '[propagators.compiler-2.session.operators :as op]
         '[propagators.experimental.visualization.data :as data])

(def initial (s/create-session))
(def compiled
  (s/extend-session initial
    (s/session-extension
      {:id :example
       :primitives {'increment (op/prim-op (fn [make] (make inc)))}
       :files {"main.lain"
               "(define input) (define result (increment input))"}})))
(def committed
  (:session (s/advance compiled
              [(s/cell-input (s/binding-cell compiled 'input) 10)])))
(assert (= 11 (data/payload (s/binding-value committed 'result))))
;; compiled remains pending; committed is the new value.
```

## Operators and output cells

Require `[propagators.compiler-2.session.operators :as op]`.

| API | Use |
| --- | --- |
| `prim-op [constructor]` | Declare pure primitive activation; constructor must be a function |
| `async-op [effect-interpreter constructor]` | Declare an async operator with an explicitly injected interpreter |
| `async-op [constructor]` | Existing declared-dependency adapter only: constructor uses `(make :dependency-id)` |
| `as-propagator-operator [operator binding-name hooks]` | `Operator` protocol; lower a declaration |
| `operator-maker [declare-call]` | `f → cells → output-cell`, calling `declare-call f cells` |
| `primitive-operator-maker [declare-primitive]` | Primitive operator builder |
| `async-primitive-maker [effect-interpreter]` | Async operator builder; its sole argument implements `EffectInterpreter` |
| `cell-output [update]` | Operator selects ordinary output-cell messages in the current round |
| `effect-output [input]` | Operator selects a message for its effect receiver after commit |
| `interpret-effect! [interpreter owner context input emit!]` | Host-side `EffectInterpreter` protocol; never called by pure propagation |
| `next-round-input [update]` | Interpreter selects bounded later-round admission |
| `dependency-input [dependency-id input]` | Interpreter selects an explicitly declared dependency |
| `effect-route`, `effect-input-value`, `effect-dependency` | Named accessors for normalized interpreter inputs |
| `frozen-primitive [artifact implementation]` | Trusted module binding wrapper; equality/hash use artifact identity |
| `binding-implementation [binding]` | `FixedBinding` protocol accessor |
| `compiler-binding [name declaration context]` | Infrastructure lowering; unwrap fixed bindings, lower `Operator`, preserve other declarations |
| `callback-kind` / `callback-request? [request]` | Infrastructure tag/predicate for these dependency requests |
| `argument-state [network input-ids]` | Infrastructure inspection: `:values`, `:contents`, `:bases` |
| `eligible? [network request]` | Checks current raw argument contents and usable bases; never use an old outbox entry as authority |

Both builders preserve **`make → behavior → cells → output-cell`**. A constructor receives its builder, supplies behavior, and returns the resulting cell function. Canonical GUR invokes that function during installation and preserves its caller-owned output cell. A primitive declares one call; compose calls in Lain. Construction bookkeeping is local to one installation and closes when installation returns.

The async operator's behavior is a **pure function of current argument values**. It must explicitly return `cell-output` or `effect-output`. Direct cell messages propagate in the current round. Effect-receiver messages are recorded as requests; the host publishes the compiler result before invoking the interpreter. Ordinary values and callback functions are not silently interpreted as a routing decision.

The injected host interpreter contains the existing ingress atom and reads the owner's current dependency declarations. It creates no second queue, runner or orchestrator. Its routing function chooses a later-round input or a dependency request. Dependency handlers receive normalized `emit!`, not the raw atom, interpreter or dependency table. Immediate, delayed and streaming callback outputs all enter later admitted batches.

### Runnable async operator builder example

```clojure
(require '[propagators.compiler-2.session :as s]
         '[propagators.compiler-2.session.operators :as op]
         '[propagators.compiler-2.session.host :as h]
         '[propagators.experimental.visualization.data :as data])

(def owner (h/open! (s/create-session) {:auto-advance? false}))
;; Pure routing policy. It receives only the effect input selected by the operator.
(def interpreter (h/effect-interpreter owner op/next-round-input))
(def later
  (op/async-op interpreter
    (fn [make]
      (make (fn [n]
              (if (neg? n)
                (op/cell-output 0)             ; current round
                (op/effect-output (inc n)))))))) ; interpreter handles after commit

(try
  (h/extend! owner
    (s/session-extension
      {:id :async-example
       :primitives {'later later}
       :files {"main.lain" "(define result (later 10))"}}))
  ;; Delivery is asynchronous even when auto-advance is disabled. Bound the wait.
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (when (zero? (h/pending-count owner))
        (assert (< (System/nanoTime) deadline) "Effect delivery timed out")
        (Thread/sleep 2)
        (recur))))
  (h/step! owner)
  (assert (= 11 (data/payload (s/binding-value (h/current-session owner) 'result))))
  (finally (h/close! owner)))
```

For dependency delivery, declare a `Dependency` in the session and inject a routing policy such as `(fn [input] (op/dependency-input :lookup input))`. The handler receives the selected payload under `:input`, while `:arguments` preserves the invocation arguments. The dependency's normalizer runs before existing ingress normalization and support handling. It may produce zero, one or many updates over time; the operator does not choose the handler's output timing.

`h/effect-interpreter` belongs to one boundary and rejects delivery by another boundary. Unrelated monotone extensions retain valid callbacks. Rerun uses the same owner/interpreter with a fresh run and fences old callbacks. A backpressured immediate next-round route records an inspectable delivery failure; there is no automatic replay. Streaming handlers inspect `emit!`'s result and own their retry policy.

The single-argument `async-op` preserves the earlier declared-dependency-ID adapter; it does not accept inline callback behavior. Custom `:declare-primitive` / `:declare-async` hooks and `:bind` retain their existing installer contract. Normal compiler users let `extend-session` supply canonical run/outbox context. Frozen native module bindings are unwrapped and lowered through `Operator`; an interpreter-bound module must receive its interpreter explicitly from its owner, rather than resolving an ambient host or dependency registry.

Do not invoke an effectful function inside a primitive constructor or pure activation. Do not fabricate an equal frozen artifact identity for a different implementation. Existing instantiated propagators keep their installed implementations; loading a new namespace does not retroactively replace them.

## Dependencies and flexible timing

Require `[propagators.compiler-2.session.dependencies :as dep]`.

- `callback-dependency [start]` uses identity normalization.
- `callback-dependency [start normalize]` requires two functions.
- `start! [dependency context emit!]` and `normalize-output [dependency external-input]` are the `Dependency` protocol.

A handler may emit zero, one or many times, immediately, later, or while `start!` is still running. Returning from `start!` does not complete the stream. Return nil for no owned external resource, or a cleanup function that must verify closure and return `{:status :closed}`.

Context includes request `:id`, `:run-id`, `:arguments`, `:input-ids`, `:input-contents`, `:complete!`, and `:cancelled?`; external subscriptions also carry `:subscription`. Interpreter-routed dependencies additionally receive the selected `:input`. Context does not expose the dependency table, interpreter or raw ingress atom. Treat request identity as opaque. Do not assume argument contents are host-materialized compound graphs.

`normalize-output` converts one external event to a domain value/source publication for a fixed output, or an ordinary cell input. Fixed-output explicit messages must target that invocation's output. Subscriptions without a fixed output must normalize to ordinary cell messages. Use existing source-publication/support/retraction algebra for versioned input.

`emit!` returns `:accepted`, `:backpressured`, or `:closed`. Accepted means queued, **not yet committed**. There is no recursive advance from a callback. Completion admits no new emissions but preserves valid already-admitted replies; cancellation fences queued callback messages. The producer owns its retry policy for a backpressured event.

## Live host boundary

Require `[propagators.compiler-2.session.host :as h]`.

| API | Result and lifecycle |
| --- | --- |
| `open! [s]`, `open! [s options]` | New application-owned boundary; opening alone dispatches nothing |
| `current-session [h]` | Current immutable compiler snapshot, including legacy observation updates |
| `effect-interpreter [h]`, `effect-interpreter [h route]` | Inject this owner's ingress and current dependencies; pure route defaults to identity |
| `step! [h]` | Commits one admitted batch, then delivers eligible requests; returns a compiler session, **not** a `Turn` |
| `submit! [h input]` | Normalizes then queues; returns `:accepted` or `:backpressured`; closed boundary throws |
| `extend! [h extension]` | Prepares/publishes extension and steps; returns current compiler session |
| `extend-source! [h text]` | Snapshots imports using current root, assigns a fresh file/extension identity, then extends |
| `subscribe! [h id dependency-id]` | External source without fixed output; normalizer must produce cell inputs |
| `subscribe! [h id dependency-id output-cell]` | External source with fixed target |
| `complete! [h request-id]` | Marks active/starting receipt complete and performs available cleanup; do not rely on its return value |
| `cancel! [h request-id]` | Fences active/starting receipt, performs available cleanup, returns the receipt |
| `reconcile! [h request-id reconcile-fn]` | Explicit uncertain-start/failed-cleanup reconciliation; returns verified result or throws |
| `rerun! [h fresh-run-id]` | Retires old callbacks/resources, rebuilds, publishes and steps a fresh run |
| `close! [h]` | Serializes with commit/observer setup; fences input and checks owned cleanup; returns `{:status :closed}` or throws |
| `receipts [h]` | Current request ID → lifecycle receipt map |
| `pending-count [h]` | Number of queued input messages |
| `failures [h]` | Recorded delivery/pump failures; inspect before claiming success |
| `runtime-session [h]` | Compatibility atom for existing runtime/editor adapters only |
| `boundary-for [runtime-atom]` | Boundary attached to that adapter atom, or nil for a legacy session |
| `wake! [h]` | Infrastructure scheduling helper; normal callers do not need it |

Host options: `:capacity 256`, `:delivery-threads 4`, `:cleanup-timeout-ms 5000`, `:auto-advance? true`. Capacity/threads/cleanup timeout must be positive integers. Delivery uses a separate bounded pool so a handler can keep running while output commits.

`subscribe!` returns `:admitted` when scheduled, `:backpressured` if not invoked because the delivery queue is full, and nil for an existing/ineligible claim. Its receipt ID is namespaced by the run; obtain it from `receipts` rather than treating the caller's subscription ID as the complete receipt key. Repeating a subscription ID in the same run does not duplicate an invocation.

Receipts may report `:starting`, `:active`, `:completed`, `:cancelled`, `:uncertain`, `:reconciling`, or `:reconciled`. Inspect cleanup separately (`:pending`, `:running`, `:closed`, `:not-required`, `:failed`). Failed cleanup remains failed on subsequent close; a second call is not proof that the resource closed. Reconciliation receives the original request and must independently verify `{:status :closed}`; it never authorizes replay of an uncertain launch.

A live owner must use `h/rerun!`, not publish `s/rerun` directly over active callbacks. Unrelated monotone extensions preserve valid callbacks. Changed argument versions and fresh runs reject obsolete emissions. A successful rerun clears the old pending batch and receipts. Lifecycle failure can have external effects even when the replacement compiler value is not published; inspect/reconcile rather than blindly retrying.

The host currently delivers its callback-kind requests and schedules the existing native clock/trace observers. It is not a universal replacement for all native effect-port interpreters. Pure advance exposes declared requests for their owning infrastructure.

## Trusted file and native module preparation

`[propagators.compiler-2.session.files :as files]` exposes:

```clojure
(files/prepare-extension!
  {:id :revision-1
   :root "."
   :files {"main.lain" "...inline source..."}
   :read-files ["another.lain"]
   :primitives {'name declaration}
   :dependencies {:service capability}})
```

`:root` defaults to `"."`; `:files`/`:read-files` can be combined. Optional `:factory-arguments` maps a qualified factory entry keyword to an explicit argument vector, for example `{:experiment-v1/bindings [interpreter]}`. The default is `[]`, preserving zero-argument factories. Arguments participate in frozen artifact identity; reuse the same stable capability objects when repeating preparation. Changing injected arguments conflicts with an existing import identity. This is a trusted Clojure preparation API, not a client-selected transport argument list. Static top-level `load-lain` imports are snapshotted transitively. Canonical paths outside the allowed root fail. A `load-primitive-environment` entry must be a qualified keyword; its factory must resolve on the classpath and return ordered `[symbol implementation]` pairs.

Preparation uses `requiring-resolve`, invokes the factory, computes a file digest and builds frozen binding/import declarations. It never explicitly reloads an already-loaded namespace. **This operation is trusted executable code, not a pure transaction or an OS isolation mechanism.** A factory may mutate vars, launch processes, perform IO or terminate the JVM before an extension is accepted. Atomic catalog publication does not undo those side effects.

Use a fresh module namespace and fresh binding names for an experimental revision. Extending with an incompatible implementation under an existing binding conflicts. An import revision marker does not replace instantiated propagators or force namespace reload. Existing live instances keep their old implementation. Promote tested source through the normal release process rather than patching a deployed module in place.

## Existing editor adapter and HTTP contract

Require `[graph.lain-editor.runtime.session :as editor]`. These are local in-process adapters, not remote runtime references.

| API | Behavior |
| --- | --- |
| `prepare [source extensions]` | New legacy runtime entry; loads declarations |
| `prepare-compiler [source extensions]` | New immutable compiler boundary/entry with editor capabilities; compiles and steps |
| `register-compiler-session! [detached boundary source]` | Adds an existing boundary to the detached registry; no declaration replay or new callback owner is launched |
| `run! [editors command]` | Explicit compile/run; opts in with `:compilerSession true`; same source on an existing typed pane explicitly reruns |
| `extend! [editors command]` | Requires matching generation, extends current session, returns updated generation/session ID |
| `detach! [editors detached command]` | Moves an entry to detached registry without runtime execution |
| `list-detached [detached]` | Returns detached session summaries |
| `reopen! [editors detached command]` | Moves entry to a free pane, preserving generation and runtime |
| `stop-detached! [detached command]` | Explicitly closes and removes the detached runtime |
| `remove! [editors pane-id]` | Closes and removes an attached runtime |
| `close! [registry]` | Closes every runtime in that registry |
| `close-session! [runtime-atom]` | Stops owned capture/host/clock/trace resources |
| `restore! [editors workspace-document]` | **Recompiles** executed declarations into new sessions; not a live reconnect operation |
| `spawn-slots! [editors command]` | Plans slots and may extend; requires generation |
| `load-checked! [runtime-atom source]` | Legacy loader adapter; executes source loading, not an immutable extension API |
| `require-pane [editors pane-id]` | Returns entry or throws |
| `require-generation [entry generation]` | Throws with `{:stale true}` on mismatch |
| `detached-entry [detached session-id]` | Returns detached entry or throws |
| `promote! [editors pane-id candidate]` | Infrastructure ownership/observer transition; may close previous/candidate resources |

**Verified ownership gap:** the current registry API transfers entries between registries; `remove!`, registry `close!`, server shutdown and some replacement operations close their runtimes, including a registered headless boundary. Registration alone is not a non-owning editor lease. Do not put a remotely or externally owned runtime in this registry and assume closing the view preserves it. The sandbox integration must keep ownership in its sandbox service and expose observation leases or a sandbox-local backend. It must not force remote references into the deployed editor's registry.

The existing `graph.lain-editor.server/start!` has arities `[port]` and `[port {:keys [authorize? project-root]}]`. It binds loopback; the authorization callback runs before body parsing. Omitting it permits local unauthenticated access. The existing server is not itself a sandbox, and a sandbox-facing gateway must enforce bounded request sizes and sandbox-scoped credentials.

`POST /api` accepts JSON commands and returns `{:ok true :result ...}` or `{:ok false :error ...}`. Direct `command!` arities accept editors plus optional layouts/developments/detached registries; use the full five-argument form for session-registry operations. Serialize those operations through the existing editor command lock. `server/assets` lists the served frontend asset routes; `respond!`, `handle!`, `api!`, `project-root` and `-main` are infrastructure/launch APIs, not compiler operations.

Important command fields:

- `run`: `:pane`, `:source`, optional vector `:extensions`, optional `:compilerSession`.
- `extend`: `:pane`, **`:generation`**, `:source`.
- `detach-session`: `:pane`, **`:generation`**.
- `list-sessions`: no pane; lists detached entries only.
- `reopen-session`: free `:pane`, existing `:sessionId`; no source replay.
- `stop-session`: `:sessionId`; explicit destruction.
- `close`: `:pane`; closes its runtime.
- `restore`: `:workspace`; recompiles, so do not use for reconnect/refresh.
- `inspect`: `:pane`, **`:generation`**, nested `:display` (example below), optional `:parent`, `:node`, `:chase`, `:recordedObservation`.
- `layout`: pane/generation and existing layout request fields; requires layout registry.
- `spawn-slot`: pane/generation.
- `save-block` / `save-workspace`: existing file APIs scoped to server project root.

Inspection example (a top-level `:mode` is **not** the inspection display contract):

```clojure
{:op "inspect" :pane "view" :generation generation
 :display {:cell "result" :mode "graph" :direction "both"}}
```

Use `:cell "@display"` to inspect the program's display cell when present. Direction is `"upstream"`, `"downstream"`, or `"both"`. `:chase` is nil/boolean; do not synthesize arbitrary node identities. Results include generation, status (`"waiting"`, `"contradiction"`, `"ready"`), described cell/value and runtime-node count, with graph/replay/stack/value-grid details as appropriate. Inspection and refresh must remain observations; never invoke `run`, `restore`, module preparation or extension merely to refresh a value.

## Agent checklist and verification scope

1. Choose pure snapshots or one existing live host. Do not create another owner to attach a view.
2. Preserve session ID, run ID and generation as three distinct concepts.
3. Prepare imports/native code only in a trusted environment or inside the OS sandbox.
4. Use constructors, extension conflicts and canonical live bindings; version native modules explicitly.
5. Commit before delivering effects. Normalize every external event and retain support/state semantics.
6. Inspect receipts after interruption. No automatic retry of a mutating remote request; the current in-process API does not supply durable remote mutation receipts.
7. Detach a view without closing its externally owned runtime. The existing owning registry requires an explicit adapter for this.
8. Verify cleanup. A successful network disconnect, editor close or HTTP response alone is not proof that child processes terminated.

Observed after the async operator builder correction, 2026-11-02: `clojure -M:test compiler-session` passed **88 tests / 920 assertions**, including **8 async builder tests / 57 assertions**. These are production-code local compatibility checks. They do not certify sandbox deployment, OS containment or the remote browser workflow; the separate `apply` issue and full repository suite are outside this result.

Source authority: `propagators/compiler_2/session.clj`, `session/{operators,dependencies,files,host}.clj`, `editor/src/graph/lain_editor/{runtime/session,server,inspection/core}.clj`. Existing production-facing acceptance is registered as `clojure -M:test compiler-session`; see the implementation guide for exact observed counts. A live GX10 sandbox, non-owning browser leases, scoped remote credentials, transport mutation receipts and containment/resource probes are separate integration gates, not implied by those local tests.
