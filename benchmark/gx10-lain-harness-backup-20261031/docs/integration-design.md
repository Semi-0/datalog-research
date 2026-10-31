# Conforming integration design (no second orchestrator)

Candidate: `experiments/lain-harness-flash-once-20261027/candidate` @ `1596f7e9f13a0b8c7c649b698c6de3662b9e047e`
(all paths relative to `backend/`; verified against the deployed release tree, read-only)

## Verified contracts

1. Session extension install (Clojure side)
   - `propagators/compiler_2/runtime.clj:48` `(install-session-extension! [session bundle context])`
     → `state/mutate-session!` + `extension/install-session-extension`
   - bundle shape: `extension/extension-bundle {:id :bindings :effects}`
   - canonical example: `examples/lain/networked_environment/session.clj:25-36` (bindings include
     `['connect (operators/connect (runtime-ids/boundary-outbox-id))]`, `:effects [operators/connect-effect]`),
     installed at `:55` with `context {:outbox-id (runtime-ids/boundary-outbox-id)}`
2. Outside effects = boundary outbox (this is the RPC path)
   - `propagators/compiler_2/runtime/boundary/effects.clj`: `outbox-effects:55`,
     `receipt-message:69`, `environment-receipt-message:76`, `environment-request?:84`,
     `undelivered-environment-effects:90`, `execute-environment-request:139`,
     `perform-registered-environment-request:162`, `drain-environment-effects:206`,
     `validate-handler-result:117`
   - host provides a registered environment-request handler; Lain decides when to emit/deliver.
     Handler result is validated → receipt message routes back into the graph.
3. Editor session lifecycle (editor JVM)
   - `editor/src/graph/lain_editor/runtime/session.clj:24` `close-session!` (stops clocks/traces)
   - `:36` `prepare [source extensions]`: `runtime/new-session`, installs 6 built-in extensions,
     `load-checked!` main source, then `(doseq [extension extensions] (load-checked! session extension))`
   - `:77` `run!` requires `extensions` = vector of strings; `:84` `extend!` extends the SAME live
     session (immutable Net + binding IDs survive, new candidate atom, generation bumped)
   - registries: `editors` (pane→entry) and `detached` (sessionId→entry), built once in `start!`;
     `detach-session` / `list-sessions` / `reopen-session` / `stop-session` transfer ownership.
     No TTL/reaper. UI observes via `inspect` polling (no websocket).
4. Pi RPC seam used by the harness
   - `examples/lain/subagents/pi_transport.clj:50-57`: live mode requires hooks `:call!`, `:reconcile!`, `:dispose!`
   - `examples/lain/subagents/effect_boundary.clj`: claim-before-invoke ledger, idempotent replay by `:boundary/id`

## Correction after reading HARNESS.md (supersedes my earlier audit)

`examples/lain/subagents/HARNESS.md:129-131` documents the host API as
`(observe! owner channel fact-log)`, `(pump! owner [max-steps])` — "claim and dispatch currently
eligible requests up to a bound" — and `(poll! owner poll-id)` — "the caller owns timing and
retention". So the host legitimately owns *timing and delivery*; Lain owns *eligibility and
transition rules*. A thin host tick calling `pump!`/`poll!` is therefore conforming plumbing,
not a second orchestrator.

What remains genuinely non-conformant in `drivers/run_trial.clj` (quarantined as
`SUPERSEDED_run_trial.clj.bak`):
1. host-side grading used as the source of truth — assessments must come from the Harbor
   framework (`harbor_extension` `:prepare`/`:grade`, `evaluation-framework.lain` enriching
   assignment facts); see HARNESS.md:33 "Harbor assessments enrich the shared assignment facts".
2. the session lived in a throwaway driver JVM instead of an editor-owned session lifecycle.
3. it decided phase/termination from a wall-clock deadline rather than observing the framework's
   own assessment/terminal facts (a deadline may only act as a safety guard).

RPC vocabulary (verified, `pi_transport.clj:19-24`): status ∈
`#{:launched :reconciled :completed :stopped :uncertain :failed :running}`, response keys
`[:status :child :result :failure :cleanup-verified]`, ≤1 MiB. `validate-config` requires
`{:plugin :version :provider :model :cwd :tools}` with pinned version and absolute cwd
(`pi_transport.clj:9-16`). `poll!` is memoized per `[assignment poll-id]` and requires an
acknowledged owned child; `close!` refuses to close unless `dispose!` returns
`{:cleanup-verified true}` (`pi_transport.clj:98-149`).

## Shape of the conforming glue

- ONE Clojure entry that (a) creates/loads the MAIN session from `examples/lain/subagents/composition.lain`
  (+ `runtime-pi.lain`, `evaluation-framework.lain`), (b) installs the harness bundle via
  `install-session-extension!` with `{:outbox-id (boundary-outbox-id)}`, (c) registers ONE
  environment-request handler whose body performs newline-delimited JSON RPC to `pi_bridge.mjs`
  (launch / collect / reconcile / dispose) with stable effect ids, (d) returns.
- No host loop that decides what runs next. Delivery uses the outbox drain already provided by
  `runtime.boundary.effects`; receipts/errors re-enter the graph as facts.
- Editor observability: the session must live in the JVM that serves the editor API. The deployed
  backend cannot load new extension code (immutable tree; `run`/`extend` accept Lain source only,
  and no op adopts a foreign session object). Therefore run the candidate tree's own editor backend
  on a separate loopback port with the harness extension wired in, and attach a browser to it.
  Shared editor on 4785/4782 stays untouched.

## Gate status (must be green before any live child)

| component | receipt | status |
|---|---|---|
| session extension + isolation | `receipts/gates/session-extension.md` | PASS 17 tests / 49 assertions, exit 0 |
| primitive eligibility / late+reordered / stale intent | retry `sa_mvrz7shu_10` | pending |
| RPC + boundary + receipt idempotency + cleanup | retry `sa_mvrz7sj5_11` | pending |
| harbor/pi/socket grading + owner isolation | retry `sa_mvrz7sk1_12` | pending |
| editor/session lifetime | `sa_mvrzk1c9_14` | pending |
| RPC seam archaeology | `sa_mvrz7sl1_13` | pending |

No live child until every row has an assertion-count receipt with 0 failures/0 errors.

## Closed: the `live_effect_boundary` contradiction (ledger item 6)

**Judgement: not part of the active architecture.** Evidence (2026-10-28):

* `grep -rln "live_effect_boundary|live-effect-boundary"` over the release backend for
  `*.clj`/`*.edn` **and** `*.md`/`*.lain` -> no matches at all.
* `find candidate -name "*live_effect*"` -> no file; no AOT class under `target/lain-editor`.
* `harness_session.clj:45-49,114` installs extensions the ordinary way:
  `extension/extension-bundle` over `socket/harbor/pi` `session-extension`s, applied by
  `runtime/install-session-extension!` with `{:outbox-id (runtime-ids/boundary-outbox-id)}`.

The effect boundary actually in play is `examples/lain/subagents/effect_boundary.clj`
(`:state` / `:receipt`, idempotent replay by `:boundary/id`) plus the runtime boundary outbox in
`propagators/compiler_2/runtime/boundary/effects.clj`. The earlier `live_effect_boundary` and
`register-rpc-outside-interpreter!` references were stale assumptions from an older revision and
must not be used as design premises or as blockers.
