# Persistent Lain environment experiment

Status: **callable feasibility passes with an opt-in runtime evaluator**.
File-level scalar ownership, retirement and transactional replacement are now
implemented experimentally. General callable-caller retirement is blocked; see
[FILE_REPLACEMENT_REPORT.md](FILE_REPLACEMENT_REPORT.md) for the current result,
usage, exact checks and stopping boundary. The callable feasibility evidence below
predates the file-lifecycle slice and does not establish physical retirement.
Verified on 2026-10-15, against checkout HEAD
`ed61cf9ee3d058d5331accc3d1b6ab278f5a8790`. At that verification, these additions were uncommitted.

## Question

Can independently authored files coexist in one persistent isolated Lain
session, and can one file be replaced without rebuilding other files?

Replacement must withdraw the old contributions, physically retire obsolete
private topology, and install the replacement behind stable public bindings.
TTMS handles contribution validity; lifecycle ownership handles topology removal.
Neither is a substitute for the other. Different runtime sessions remain isolated.

No sockets, peer protocol, process launcher, whole-session rebuild, or implicit
foreign execution belongs to this slice. The earlier networked-environment
experiment and the user's original sketch are untouched.

## Implemented composition

`application.clj` composes an evaluator through the existing runner transform.
It reads operator, ordered arguments, lexical context and result from the existing
application metadata in Net's dictionary. An ordered `combinator/branch` selects
layered operator calls before GUR's raw-callable check. Ordinary tasks and empty
completion delegate to the original step. Both paths use the unchanged iterator
constructor and patch evaluator, including ordinary relationship recording.

The bridge imports runtime application metadata, not compiler lowering. The
compiler remains unaware of layers and TTMS. No second semantic dictionary,
global Var replacement, AST rewriting or modified application syntax is used.

The existing `callable/activation` supplies the supported-call operation. It
installs a raw private application and composes its publication through existing
support/premise-state procedures. This is a runtime adapter plus layered result
publication, not a new generic layered dispatcher or a production default.

`callable.clj` also exposes the initial experimental facade:

```clojure
(callable/operator definition-cell-id)
```

Its value can be installed under an ordinary name through the existing session
extension API. The public operator remains a raw Compiler 2 callable. Its
definition cell holds the chosen compiled callable with TTMS support. The source
is a physical injection cell, not a fabricated premise name. This facade is no
longer necessary for ordinary supported operator cells when the evaluator is
enabled: the direct-call test uses `(selected argument)` without installing a
special callable binding.

Each invocation installs a private normal flat-GUR application for each selected
support version. It preserves the existing invocation-context convention. A
result procedure combines three inputs through the existing layered machinery:

1. The immutable version that created this private body.
2. The current selected definition and its state.
3. The private body's result.

The base procedure returns the third input. Existing support and premise-state
procedures combine dependencies; existing message lifting publishes collections.
An old body therefore cannot simply relabel its result with a new definition's
support. Repeated equivalent publications install no additional topology.

All handwritten functions have at most four arguments. No production code was
modified. In particular, the adapter does not change Net shape, runner,
constructor, cell evaluator, compiler, application runtime, layered dispatcher,
compound synchronization, or TTMS policy.

This composition **retains old version bodies**. It is a feasibility probe, not the
ownership/retirement implementation and not an acceptable final reload strategy.

## Verified behavior

The focused tests compile Lain expressions through standard sessions with the
existing opt-in TTMS primitive extension and the opt-in evaluator. The initial
facade tests additionally install its ordinary session extension binding.

```clojure
(define first-version (network [v] (+ v 1)))
(define second-version (network [v] (+ v 2)))
(define answer (f 10))
(define downstream (* answer 2))
```

Publishing `first-version` at source epoch 1 produces `answer=11` and
`downstream=22`. Publishing `second-version` at epoch 2 produces `12` and `24`
without recompiling the caller. The public result cell retains its identity and
the result carries the actual source cell's epoch-2 support.

A newer explicit withdrawal makes both results unusable. Reactivation at a newer
epoch recovers them. Duplicate publication preserves graph, relationship graph,
and node count.

Independent tests also verify:

- Successive ordinary submissions share bindings: defining `x`, then injecting
  `10`, then defining `z = 2 * (x + 1)` gives `22` and retains old node IDs.
- Updating a TTMS scalar source from `10@1` to `20@2` changes its existing
  `(+ x 1)` consumer from `11` to `21`, preserving graph, relationships and count.

## Original counterexample and verified repair

```clojure
(define make-adder
  (network [a]
    (network [b] (+ a b))))

(define ordinary-returned (make-adder 10))
(define ordinary (ordinary-returned 20))

(define live-returned (live-make-adder 10))
(define actual (live-returned 20))
```

Here `live-make-adder` is the experimental adapter bound to a source cell that
receives `make-adder@1`.

| Check | Original runner | Experimental composition |
|---|---|---|
| Ordinary higher-order application | 30 | 30 |
| Application of the live boundary's returned callable | contradiction (unsupported layered callable) | 30 |
| Returned layered value is usable | true | true |
| Its base satisfies `gur/recursive-closure?` | true | true |
| The whole layered value satisfies `gur/recursive-closure?` | false | false |

The existing flat-GUR activation reads the whole operator cell's strongest value
and dispatches on a raw recursive closure. It does not perform layered callable
application. The returned-output adapter records interfaces and forwards data;
it does not supply that missing dispatch. The original scalar result publication
correctly retained support, but the next ordinary application could not consume
that result as a callable. This was not a failure of timestamp freshness or
source retraction. The runtime composition now selects the supported operation
before that raw dispatch, without changing the stored layered value.

The original acceptance assertion still requires `30` and now passes. A separate
control disables the transform and explicitly selects raw dispatch. It now
asserts contradiction for the unsupported layered callable, isolating the repair to
runtime composition rather than a compiler or representation change.

Further tests replace the outer definition, observe the already-created inner
call change from `30` to `31`, withdraw it, then recover `30` with a fresher epoch.
The downstream result explicitly carries the retracted source state during
withdrawal, not just an unusable base. The final value retains the real
definition source's new support. Delayed stale
updates cannot restore the previous definition. Duplicate publications grow no
topology.

The direct-call test additionally updates the argument from an independent
physical source. Results carry exactly the operator and argument dependencies.
Argument changes reuse the installed body, and explicitly rerunning application
activations preserves graph, relationships and node count.

## Opt-in boundary and limitations

The test fixture binds `runner/*advance-transform*` to the composed evaluator for
session setup, compilation and subsequent injections. Future execution must use
the same opt-in driver; a session extension does not automatically install this
thread-local runner policy. Asynchronous session ownership is not established by
these synchronous tests.

The bridge scans existing application metadata during dispatch. It is not an
optimized index, and no performance claim is made. Applications without that
metadata use the original evaluator. An operator already executed as a raw,
unsupported value and later migrated to TTMS is not covered. Generic TTMS
recursion, effects, contradiction repair and arbitrary compound outputs are not
established by these focused cases. Physical retirement and atomic file
replacement remain separate unfinished slices.

## Checks actually run

```sh
clojure -M:test propagators.experimental.live-environment-test
```

8 tests, 56 assertions: **0 failures, 0 errors**. Before runtime composition the
same experiment had 5 tests, 30 assertions and one returned-callable acceptance
failure; that historical failure is now resolved by the opt-in evaluator.

```sh
clojure -Sdeps '{:aliases {:live-check {:extra-paths ["test"]}}}' -M:live-check -e '(require (quote [run-tests :as suites]) (quote [propagators.runner :as runner]) (quote [examples.lain.live-environment.application :as application])) (binding [runner/*advance-transform* application/advance-transform] (suites/-main "propagators.compiler-2-application-runtime-test" "propagators.ttms-primitives-test" "propagators.support-collection-test" "propagators.layered-support-test" "propagators.runner-test"))'
```

36 tests, 936 assertions: **0 failures, 0 errors**, with the evaluator enabled.

Additional regression selections ran with the same evaluator binding:

| Selection | Tests | Passing assertions | Failures/errors |
|---|---:|---:|---:|
| Functional networks, functional GUR, closure frames, composition, rest parameters, session extensions | 84 | 279 | 0 |
| Runtime integration file loader | 10 | 33 | 0 |

The first selection used `propagators.compiler-2-functional-network-test`,
`propagators.compiler-2-functional-network-gur-test`,
`propagators.compiler-2-closure-frame-test`,
`propagators.compiler-2-composition-test`,
`propagators.compiler-2-rest-parameters-test`, and
`propagators.compiler-2.runtime.session-extension-test`. The loader selection was
`propagators.compiler-2.runtime.integration.file-loader-test`.

The initial loader run encountered three sandbox-denied socket errors, not
assertion failures. Its authorized rerun passed all 33 assertions. These are
existing whole-environment loader tests, not proof of the proposed file-scoped
replacement lifecycle.

Total selected regressions: **130 tests, 1,248 passing assertions**. Reader checks
confirmed at most four arguments for the new functions. Tracked and new-file
whitespace checks produced no diagnostics; no-index comparisons return 1 because
the files differ from `/dev/null`. No tracked production file was edited.

The full repository suite was not run. These targeted results do not establish
complete Compiler 2 compatibility, safe file retirement, or general reload support.

## Required checks and remaining slices

This is the **original callable-feasibility checklist**, retained as history.
The file-lifecycle outcomes superseding its gated rows are recorded in
[FILE_REPLACEMENT_REPORT.md](FILE_REPLACEMENT_REPORT.md).

| Slice/check | Outcome | Completion condition |
|---|---|---|
| Persistent submissions | Passed | Existing bindings and IDs survive later submissions |
| Scalar TTMS freshness | Passed | Existing consumer updates with unchanged topology |
| Scalar-result callable boundary | Passed | Replacement, withdrawal, recovery, stable public result |
| Duplicate activation | Passed | No graph/relationship/node growth |
| Returned-callable compatibility | Passed | Live and ordinary paths both return 30 |
| Higher-order replacement/retraction | Passed | Existing inner call switches, withdraws, recovers and rejects stale updates |
| Direct supported operator/argument cells | Passed | No facade; independent support, reactive arguments, explicit rerun idempotence |
| Ownership receipts and retirement | Not run; gated | Capture actual installations/activation identities; retire only obsolete private nodes and metadata |
| Atomic file replacement | Not run; gated | Invalid source preserves working state and produces no preparation effects |
| File watching and terminal submissions | Not run; gated | Serialize changed-file replacement without rebuilding other files |
| Compound chains, cycles, late declarations | Not run; gated | Preserve current semantics through actual file replacement |
| Session isolation | Not run for new lifecycle | Updating/replacing one session never changes another |
| Replacement benchmark | Not run; gated | Latency, activations and topology growth across repeated real replacements |
| Full regression suite | Not run | Compare actual results with recorded checkout baseline |

The proposed lifecycle remains prepare, withdraw, retire, install, publish,
commit, then execute external effects. Stable public cells and actual premise
source anchors must survive retirement; private file and application-body
topology must not. Other files are not rebuilt by default. Existing immutable
snapshots are not rewritten in place. Opaque captured references and shared
topology require ownership evidence, not reachability-based deletion guesses.

## Architecture grounding

The reviewed KIROSHI grounding/evolution skills preserve explicit projections,
runtime-owned effects, immutable four-field Net, semantic composition outside the
runner constructor, and the current application/lexical boundaries. No new
component, reparenting, or System Model mutation is part of this experiment.

The bounded query returned model revision 614, provisional grounding, 19 omitted
facts, and missing evidence for:

- `:constraint/compiler-application-supports-ordinary-and-lazy-gur`
- `:constraint/compiler-avoids-materialize-and-unwrap`
- `:constraint/compound-materialization-is-pull-only`

The first still describes accumulating GUR while inspected code uses flat GUR;
that discrepancy is recorded, not silently corrected or used as proof. The
explicit-projection and policy-at-semantic-boundaries principles were current
and active. The implementation uses an explicit experimental boundary adapter;
it does not add layer-specific unwrapping to the compiler. No evidence receipt,
approval, architecture sync, commit, or push was performed.
