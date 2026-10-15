# TTMS consumer migration — October 1 checkpoint

This is an opt-in extension, not a compiler-default or shared-runtime migration.
No commit, push, or KIROSHI model mutation accompanies this checkpoint.

## Implemented composition

`demo/options` installs `visualization.extension/ttms-extension`, the existing
TTMS scalar/branching extension, and dependency-aware TTMS `+`/`or` procedures.
The ordinary visualization extension remains available and tested.

- Pure relationship sampling is composed with source stamping and message lift.
  An unchanged snapshot retains its source epoch. Dataflow projection consumes
  that support; it does not generate another source epoch.
- Collection readers, membership gates, retained callback results, and observation
  propagators transport explicit premise-state updates when payload computation
  is blocked. Computation and transport remain separate.
- Graph candidates have stable membership cells. Removed nodes become excluded;
  reappearing nodes reuse their identities. Edges require both visible endpoints.
- Higher-order arithmetic composes the existing dependency procedure as the base
  procedure of a TTMS layered application. Callbacks remain network definitions,
  including lexical capture; neither compiler application nor shared layered
  application was changed for this consumer slice.
- Selection is a dedicated TTMS source cell. Select/clear uses increasing explicit
  timestamps. Published view identity, revision, environment generation, and
  selected item are validated. Clear does not require an item ID.
- XR receives declarations pointing at current cells. Supported graph withdrawal
  resolves to an empty graph with `withdrawn` status. Newly diagnosed dataflow
  errors retain their reasons; a fresher valid input repairs the projection.
- The browser displays statuses and sends selection/clear commands. It does not
  implement support semantics or transform the underlying collection.
- TTMS dependency publication uses its compact operation identity to name wrapper
  slots, then inserts the unchanged raw payload through the existing cell API.
  It does not serialize whole nested networks as identity seeds. Shared dependency
  construction/merge is unchanged, and payload/source equality remains explicit.

## Verification

The focused TTMS view suite initially passed 8 tests / 50 assertions. It covers map identity
withdrawal/recovery, captured filter decisions, arithmetic support/provenance,
graph removal/reappearance, stable topology, selection conflicts/stale commands,
complete graph replacement, projection diagnostics, and idempotent trace stamping.

The additional collection/composition/dataflow/structural/premise batch reported
751 passes and the two known legacy dataflow error-reporting failures. Structural
fields still pass all 551 assertions; premise transport passes all 80.
The expanded TTMS consumer suite passes 9 tests / 54 assertions, including the
opaque-payload identity check. Event, visualizer, and source-publication
regressions add 65 passing assertions with no failures or errors.

The XR suite now executes both legacy and TTMS environments. It passed 10 tests /
68 assertions, including the full eight-view example, compound-child ancestry,
selection/clear through the runtime, semantic zoom, and file replacement by a
separate OS process. Full reload discards selection and rejects old commands.
The complete-chain clear test is bounded at 10,000 runner transitions; the bound
is test instrumentation only, not an execution policy.

The final unrestricted full suite reported **3,713 passes, 1 failure, 2 errors**.
Its registered consumer/XR/WebSocket tests contribute 54 + 68 + 5 passing
assertions. All three remaining
issues are the existing `ttms-compound-boundary-test` slot-wrapper experiment:
one nested-slot readiness failure and two domain `:base`/`:support` classification
errors. The structural-data-field extension is a separate path; this slice does
not claim to repair the old wrapper. Assertions were not weakened.

A sandboxed full run additionally had two localhost socket permission errors;
those disappear with the socket-capable run. They are not runtime regressions.

Browser contract checks pass, including clear wire format and graph withdrawal/
diagnostic labels. Live localhost selection populated the semantic zoom. Mobile
menu layout was inspected at 390×844; the canvas cards were unreadable in both
2D and 3D during that check. Do not interpret headless correctness as mobile
rendering acceptance. The renderer currently fits eight horizontal cards into
one viewport; a mobile framing/navigation follow-up remains.

### Live delivery regression and local fix

A real WebSocket regression initially passed initial publication/selection but
failed to receive clear/reselection within its 20-second waits. Thread sampling
showed the effects thread blocked on the session lock while the client thread
printed nested graph data in `dependency/stable-node-id`, called from dataflow
publication. No serialization exception or propagation deadlock was established.
The earlier suspicion about TTMS observation sorting was not the captured cause.

The local dependency-wrapper extension above removed that printing from consumer
publication. The same real WebSocket test then passed all five assertions. It
uses the full eight-view example and verifies that focused/source/zoom views clear
and can be selected again. Temporary diagnostic redefinitions were removed.
The regression is registered in the graph suite. A further direct test prevents
payload printing during identity construction and checks source/slot preservation.

## Performance

Run `clojure -M -m examples.lain.visualization-combinators.ttms-compiled-benchmark`.
The fixture compiles one `let-cell` arithmetic/`if` chain, then measures initial,
update, withdrawal, and recovery separately. Every phase asserts its terminal
base. Counts include nested runner activations. Plain cells are compared only
for initial propagation; their semantics do not offer TTMS replacement/retraction.

Measurements are instrumented samples with other test processes running
concurrently, not statistically isolated throughput claims. All 10/100/1,000-stage
cases passed. All phases added zero runtime nodes; TTMS recovery retained one
output observation. Times below are milliseconds, rounded.

| Chain | Stages | Plain initial | TTMS initial | Update | Withdrawal | Recovery |
|---|---:|---:|---:|---:|---:|---:|
| Arithmetic | 10 | 3.5 | 59.7 | 61.0 | 67.9 | 61.3 |
| Arithmetic | 100 | 19.1 | 421.3 | 426.7 | 383.0 | 402.2 |
| Arithmetic | 1,000 | 131.6 | 3,082.6 | 3,213.5 | 3,207.4 | 3,386.2 |
| If | 10 | 2.9 | 109.1 | 54.4 | 57.6 | 56.4 |
| If | 100 | 20.8 | 916.5 | 588.0 | 464.5 | 462.4 |
| If | 1,000 | 260.4 | 7,018.9 | 3,567.3 | 3,453.7 | 3,618.4 |

At 1,000 stages TTMS arithmetic update used 64,010 runner activations and
withdrawal 63,010; branching used 70,010 and 69,010 respectively. These include
layered/nested execution, not just outer user applications. The experiment is
substantially more expensive than plain propagation; no performance parity is
claimed. Source condition is held true in the branching performance fixture;
branch switching remains covered by semantic tests, not this timing table.

Setup at 1,000 stages took 96.4s plain/76.9s TTMS arithmetic and 75.4s plain/85.3s
TTMS branching. Compilation/setup is a separate scaling issue. The fixture uses
one `let-cell` expression; an earlier, discarded fixture performed one session
commit per stage and mixed that additional cost into setup.

## Removal and remaining boundaries

Visualization selection has no reducer dependency. The maintained lexical
reducer still calls reducer-cell; it must remain. Legacy graph-union merge still
serves the ordinary observer environment; do not remove it globally based only
on opt-in TTMS parity. Legacy dataflow error-reporting failures remain separate.

Remaining gate: mobile card framing/readability. Cleanup is bounded by the
maintained legacy observer and lexical-reducer callers described above.
Whole-compound snapshot/accessor migration, clock/TUI/event producer migration,
`when` topology withdrawal, and cross-repository promotion are not claimed.

Fixed boundaries remain Net, runner, cell evaluator/equality, shared layered
application/readiness, compound synchronization, compiler lowering/defaults, and
distributed-TMS policy. KIROSHI grounding at revision 476 retained explicit
projection, runtime-owned effects, bidirectional slots, and reviewed TTMS dominance;
no component reparenting or model mutation was performed.
