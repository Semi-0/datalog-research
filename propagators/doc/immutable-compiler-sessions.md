# Immutable Lain compiler sessions

For the complete callable surface, options, return values and current ownership limitations, read the [session API reference](session-api.md). KIROSHI's source-document entry point is [system-model/session-api.md](../../system-model/session-api.md).

The compiler session is an immutable value containing declarations and the real Compiler-2 environment/network state. `advance` returns another session, deferred inputs, and dependency requests. Host services admit external input, commit the candidate, and deliver requests afterward. Lain composes the execution; the host boundary owns delivery, receipts, cancellation, and cleanup.

The implementation is in `propagators.compiler-2.session`, with operators, dependency adapters, file preparation, and host admission in its focused child namespaces. Types use flat external `extend-type` forms. No compiler grammar, global merge rule, or durable KIROSHI state changes are involved.

## Compiler and operator contracts

```clojure
(defprotocol CompilerSession
  (environment [session])
  (network [session])
  (advance [session inputs]))

(defprotocol SessionExtension
  (extend-session [session extension]))

(defprotocol SessionDependencies
  (dependency-declarations [session]))

(defprotocol OpenSession
  (source-files [session])
  (rerun [session fresh-run-id]))

(defprotocol Operator
  (as-propagator-operator [operator binding-name hooks]))
```

Use `create-session`, `session-extension`, `prim-op`, `async-op`, and `callback-dependency` to construct values. `environment`, `network`, `session-identity`, and the declaration accessors expose the supported views. `compiler-state` and `with-compiler-state` serve the existing runtime/editor adapter; domain callers do not reset the adapter's mutable host reference.

The curried maker interface remains:

```clojure
(defn operator-maker [declare-call]
  (fn [f]
    (fn [cells]
      (declare-call f cells))))
```

A primitive constructor receives the canonical primitive maker. An async constructor receives a request-producing maker keyed by a declared dependency. Declaration does not invoke the host callback. The optional declaration/binding hooks preserve the cells-to-output-cell builder contract; default lowering uses the existing canonical GUR installer.

```clojure
(operators/prim-op (fn [make] (make inc)))
(operators/async-op (fn [make] (make :lookup)))
```

Bindings remain live references through environment slot, binding descriptor, value cell, and GUR. Missing-name lookup reservations are reused when a primitive arrives later. Installing a primitive over an already-defined source alias fails explicitly, protecting the alias's caller-owned cell. Compound owners and their fields are not reconstructed as host snapshots.

## Flexible output timing and normalized input

`Dependency` has two operations:

```clojure
(start! [dependency context emit!])
(normalize-output [dependency external])
```

A handler may emit zero, one, or many events, immediately, later, or while its `start!` call is still running. Returning from `start!` does not finish the stream. The context supplies request identity, arguments, run identity, `:complete!`, and `:cancelled?`.

The host uses a serialized commit owner and a separate bounded delivery pool. A long-running handler therefore cannot prevent its earlier output from entering a subsequent round. `open!` alone does not start requests; `step!` makes the initial commit and schedules delivery.

Every emitted event passes through `normalize-output` before admission. A fixed-output async call accepts a domain value/source publication or an ordinary cell input targeting that output. An external subscription must normalize to an ordinary cell input. `cell-input` constructs that input; native `Message` values are also accepted. Ambiguous maps and writes to another invocation's output are rejected.

Normalization runs outside the commit lock. The host checks run/request eligibility again afterward. A callback never recursively advances the compiler. Even an immediate reply is queued for a later committed round; completion preserves already-admitted replies and rejects new emissions.

This executable example retains one stream callback so its producer can emit whenever data becomes available:

```clojure
(require '[propagators.compiler-2.session :as session]
         '[propagators.compiler-2.session.dependencies :as dependency]
         '[propagators.compiler-2.session.host :as host]
         '[propagators.compiler-2.session.operators :as operators]
         '[propagators.stdlib.source-publication :as publication])

(def emitter (promise))
(def updates
  (dependency/callback-dependency
    (fn [_context emit!]
      (deliver emitter emit!)
      nil) ; no external resource requiring cleanup
    (fn [{:keys [source revision value]}]
      (publication/active source revision value))))

(def compiled
  (session/extend-session
    (session/create-session)
    (session/session-extension
      {:id :lookup-capability
       :primitives {'lookup (operators/async-op (fn [make] (make :lookup)))}
       :dependencies {:lookup updates}
       :files {"main.lain" "(define result (lookup 21))"}})))

(def boundary (host/open! compiled {:auto-advance? false}))
(host/step! boundary)
(def emit-output! (deref emitter 5000 nil))
(when-not emit-output!
  (throw (ex-info "Dependency did not start" {})))

(emit-output! {:source :example-stream :revision 1 :value 42})
;; result remains pending until the next committed round
(host/step! boundary)

(emit-output! {:source :example-stream :revision 2 :value 43})
(host/step! boundary)
(host/close! boundary)
```

Use the existing source/publication algebra for source identities, revisions, support, contradictions, and withdrawal. The boundary does not invent an ordering or turn an unknown list tail into a closed list. Request/input changes fence obsolete callbacks; unrelated monotone extensions preserve valid ones.

`emit!` returns `:accepted`, `:backpressured`, or `:closed`. Backpressure means the event was not admitted. Producers own their retry policy. The input queue defaults to 256 entries, the delivery pool to four workers, and cleanup timeout to 5000 ms; `open!` accepts `:capacity`, `:delivery-threads`, `:cleanup-timeout-ms`, and `:auto-advance?`.

`start!` returns nil or one cleanup function. A cleanup function must verify closure and return `{:status :closed}`. Cancellation and completion run it once. Startup uncertainty and cleanup failure remain inspectable through `receipts` and `failures`; they are never reported as successful closure or automatically replayed. `reconcile!` accepts an explicit transport reconciliation function that must verify `{:status :closed}`. A successful rerun fences the old run, verifies cleanup, publishes the new snapshot, and drops the old receipt set.

## Atomic extension and source preparation

`extend-session` joins primitive, dependency, import, and file declarations together and compiles one candidate using the real compiler/runner. Failure publishes none of that candidate. Equal additions are idempotent; conflicting identities or source definitions fail. A new extension ID is recorded even when its capabilities already exist, while reusing the same environment/network references; a later conflicting use of that ID is rejected. Bare pending declarations may be refined. Existing network identities and live slots remain in place.

Inline, already-prepared source can use `session-extension` directly. Source containing `load-lain` or `load-primitive-environment` uses `files/prepare-extension!` first. That explicitly effectful host operation snapshots trusted files and import manifests under an allowed root. The compiler then resolves those frozen declarations without filesystem IO or namespace reload. Imported primitive equality uses a trusted content-addressed artifact identity.

Extensions accumulate files; changing the text under an existing file identity is a conflict. An explicit rerun rebuilds from current declarations and requires a fresh run identity. Compiler input history is disabled by default; opt in with `create-session`'s `:runtime-options {:record-inputs? true}`. Runtime-history persistence is not required.

Propagation is bounded by default at 100000 steps and 60000 ms per operation. Override these through `:propagation-options` on `create-session`. The deadline is checked between runner steps; trusted primitive implementations must terminate. Exhaustion throws and cannot publish a partial extension.

## Editor attachment

The editor's existing backend remains the default. `run` with `:compilerSession true` opts into the immutable compiler backend. Existing typed panes keep that backend for extension and rerun.

A host can register an independently owned, already-running boundary through `editor/register-compiler-session!`. Registration performs no compilation or dependency launch. The existing session picker attaches the editor to that owner. Detach leaves execution alive; reopen observes its current values and declarations. Opening the editor does not introduce another orchestrator.

The pinned frontend is `3025b0e3f571dbc60358048c73136f3792e97925`, published on `Semi-0/lain-editor` branch `codex/immutable-session-picker`. It applies the existing upstream dispatcher repair to the formerly pinned revision: session-picker actions are validated using their current `:reopen-running` and `:stop-running` names. Its regression covers actual controller dispatch.

The existing frontend build provider can resolve generated HTML/CSS as its own input during a repeated build. Clean those generated assets before rebuilding, or restore them from the pinned dependency afterward. This build limitation is separate from session semantics. Fresh builds resolve the source assets normally.

## Observed acceptance — 2026-11-01 to 2026-11-02

| Check | Result |
| --- | --- |
| Shared baseline before implementation: session extension, canonical GUR, GUR/TMS, supported compound `if`, high-level tracing, detached editor, trace inspection | 54 tests, 668 assertions, zero failures/errors |
| Candidate `clojure -M:test compiler-session`: same shared gates plus production compiler/session and editor acceptance | 80 tests, 863 assertions, zero failures/errors |
| Additional final compiler/boundary/clock/combinator/harness/supported-slot/TMS/TTMS/conditional gates | 94 tests, 474 assertions, zero failures/errors |
| Scoped frontend dependency JVM contracts | 33 tests, 241 assertions, zero failures/errors |
| Scoped frontend dependency ClojureScript tests | 118 tests, 939 assertions, zero failures/errors |

The new immutable protocol and normalization APIs did not exist in the baseline. Their 26 candidate tests and 195 assertions are new coverage, not baseline support inferred from the earlier 20 controlled prototype assertions.

New regressions cover atomic conflicts/compile failure, input-history opt-in, late lookup resolution, source-alias ownership, live chained compound callables, inactive updates and stale revisions, supported retraction/recovery, bounded propagation, immediate replies followed by completion, output while the handler is still running, streaming normalization, queue backpressure, duplicate claims, cancellation, zero-output completion, sticky cleanup failure, explicit reconciliation, and obsolete-run rejection. Live tracer assertions compare traced and untraced network values across updates, retraction, and recovery and inspect graph connections/current values.

A contradictory captured argument blocks canonical GUR and exposes a pending base while its cell retains meaningful support/state; recovery resumes the existing output identity. The regression checks the actual source contradiction, retained output content, and recovery. It does not assume every blocked closure publishes a plain contradiction value.

The additional compatibility command ran these namespaces:

```sh
clojure -M:test \
  propagators.compiler-2.runtime.functional-network-effects-test \
  propagators.compiler-2.runtime.environment-io-test \
  propagators.compiler-2.runtime.boundary-test \
  propagators.compiler-2.runtime.clock-test \
  propagators.compiler-2-combinator-test \
  propagators.compiler-2-harness-session-test \
  propagators.compiler-2-harness-effect-boundary-test \
  propagators.compiler-2-harness-primitives-test \
  propagators.supported-slot-test \
  propagators.tms-test \
  propagators.ttms-publication-test \
  propagators.compiler-2-conditional-syntax-test
```

The active runner rejects failures, exceptions, and zero assertions. New propagation and lifecycle waits have explicit limits and throw on exhaustion or timeout. No production behavior-changing overrides are used to pass these gates.

### Real browser evidence

An isolated loopback editor attached session `3715d780-d3af-4fe4-82eb-3f1c52dcb75e` created headlessly. The UI showed the selected high-level graph with two nodes and one edge, input 3 and output 4. Applying `(define extended (+ output 1))` through the editor retained the owner and produced 5.

After UI detach, the host admitted input 7 with zero editor attachments. Output became 8 and the extension became 9. Reopening the same session displayed input 7/output 8 and retained the extension. Explicit UI rerun preserved the owner, created a fresh run identity, and displayed pending inputs. New input 20 then produced graph output 21 and extension value 22. A screenshot of that final graph was saved with the implementation report.

These are local production-facing acceptance results. They do not claim a live GX10 deployment or a green full repository suite. The separate `apply` completion issue remains outside this change and was not scored by these gates. KIROSHI grounding remains provisional at the earlier inspected revision 232; no durable KIROSHI changes were made.
