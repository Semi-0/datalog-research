# Contract review A2 — bounded read-only (owner B), recovery-20261030

Scope: candidate working tree only; no JVM, no source edits. All paths relative to `candidate/`.
S/o: TASK.md items 1–4, receipt-W22. Fresh W23 supervisor result noted: `w23-current-test-result.json`
exit 124, 21 pass / 4 fail / 1 error — test-owner seam, referenced below only where it bounds proof.

## 1. No host eligibility recomputation; 3 handlers register on load — OK (1 test-side gap)
- Registration is extension-native: `open!` → `load-declaration` installs the bundle
  (`harness_session.clj:162`); `install-session-extension!` writes `:environment/handlers`
  (`extension.clj:269,287`); the bundle concatenates socket+harbor+pi `:effects`
  (`harness_session.clj:166-172`), so all three `[port kind]` keys register before any pump.
- Pump reads registrations from the session registry, never a global table
  (`harness_session.clj:306-313,334-341`); the only eligibility check is exact intent equality
  against the graph's current value (`harness_session.clj:315-318`) — no domain policy in the host.
  Graph owns eligibility: `pi:current-requests`/`harbor:current-requests`/`socket:current-writes`
  (`pi_extension.clj:161-172`, `harbor_extension.clj:151-160`, `socket_extension.clj:176-180`).
- Nit: `pending-effects` silently drops a request whose registration is missing
  (`harness_session.clj:334-335` → `:quiescent`), the exact W22 "zero emissions, pump-error nil" shape.
  Minimal fix: in `open!`, after load, one assertion that `(keys (:environment/handlers state))`
  contains all three `effect-routes` keys → fail at open instead of silent idle. Also missing as an
  assertion in `drivers/test/w23_contract_test.clj` (TASK item 4 requires it; production side is OK).

## 2. Distinct per-intent receipt BEHAVIOR — OK
- Per-intent native slots: `intent-effect-operator` spreads each current intent into its own
  `effect-slot-key` of a compound-object outbox message (`primitives.clj:164-178`); slots are
  independent retractable cells, not one last-value cell.
- Delivery behavior (not just labels): `dispatch-effect!` runs once per request
  (`harness_session.clj:338-341,372-410`); receipts land in per-boundary ledgers keyed by intent id
  (`effect_boundary.clj:315-323`, `pi_transport.clj` trials/polls maps) and `receipted?` is
  per-intent (`harness_session.clj:320-324`). The runtime's collapsing path
  (`boundary/effects.clj:393-401 delivery-key`) is applied only in `perform-boundary-effects*`,
  which records xr/tui/clock kinds and ignores `:harness` requests — no merge of harness intents.
- Behavior-level tests exist: `test/propagators/compiler_2/runtime/session_extension_test.clj:401,417`
  (two concurrent intents → two requests, two receipt-slot keys, distinct deliveries; retractability).

## 3. Zero external transport outside pump; claim-before-call; cancellation; identity; reconcile — OK (1 narrow gap)
- Transport only under pump ownership: dynamic `*transport-ownership*` armed solely in `pump!`/`poll!`
  (`primitives.clj:100-118`), armed only by `with-transport-ownership` in `pump!`/`poll!`
  (`harness_session.clj:425,442`) and required in `dispatch-effect!` (`harness_session.clj:375`);
  all three handlers `deferred-receipt` when unarmed
  (`pi_extension.clj:182-184`, `harbor_extension.clj:177-179`, `socket_extension.clj:189-191`).
  load/open/observe/replay perform no transport: `socket_transport/start!` binds only in `:live`,
  loopback-only (`socket_transport.clj:133-148,181-185`); `pi_transport/poll!` returns `[]` in
  observe/replay (`pi_transport.clj:107-108`); `effect-boundary/open!` does file I/O only in exec modes
  (`effect_boundary.clj:234`).
- Durable claim-before-call: `effects/dispatch!` writes the `:intent` record (fsync) before invoke
  (`effect_boundary.clj:323` then `:341` invoke path); Pi: launch/stop publish `requested-fact`/
  `cancel-requested-fact` BEFORE `handle-effect` (`harness_session.clj:398-405`,
  `pi_extension.clj:169-182`), then `boundary/dispatch!` records the intent pre-invoke
  (`boundary.clj:48`).
- Current cancellation validation: currency revalidated immediately before claim
  (`harness_session.clj:383`); stop request must carry owned session/`:stop` op
  (`pi_extension.clj:184-189`); `:stopped` requires `cleanup-verified true`
  (`model.clj:78-80`, `pi_extension.clj:33-35`).
- Stable/replay/collision identity: `effect-id = [session op]` (`model.clj:45,91`) validated on
  dispatch (`boundary.clj:45-47`); native identity `[kind intent-id]`
  (`pi_extension.clj:206`, `harbor_extension.clj:197`, `socket_extension.clj:193`) and handlers
  re-check it (`*_extension deliver-intent`); replay dedup via stable slot + ledger
  (`harness_session.clj:320-341`); `effects/dispatch!` rejects id reuse with a different payload
  (`effect_boundary.clj:314-315`).
- Uncertain-launch reconciliation without duplicate launch: `:uncertain` phase yields `:reconcile`
  (`model.clj:82-87`), reconcile carries `:launch-effect-id` (`pi_transport.clj:63-65`), the launch
  id keeps its uncertain receipt and is never re-invoked (ledger hit `boundary.clj:42`); pi is
  deliberately excluded from `require-successful-effects!` so uncertain doesn't poison the pump
  (`harness_session.clj:350-358` — list is harbor+socket only).
- GAP (narrow): `boundary/dispatch!` returns the prior receipt on id reuse WITHOUT comparing payloads
  (`boundary.clj:42-43`), unlike `effect_boundary.clj:314` ("Conflicting reused effect identity").
  A same-id/different-payload Pi request silently gets stale observations. Minimal fix: mirror the
  one-line `(= request (:request prior))` check there.

## 4. Grading + cleanup framework-owned — OK
- Grading is graph-derived: `grading-requests` from the trial schedule
  (`harbor_extension.clj:42-54`) → native intent → `deliver-intent` → durable `effects/dispatch!`
  → grade callback publishes `:harbor/assessed` observation (`harbor_extension.clj:127-139`) →
  `assignment-updates` `:assignment/assessed` (`harbor_extension.clj:85-101`). No host-side grading;
  `harness_session` only invokes the admitted callbacks (`harbor-invoke`, `harness_session.clj:207-214`).
- Cleanup verified: boundary close demands `cleanup-verified` and in-flight drain
  (`effect_boundary.clj:344-377`), Pi disposal refuses unverified (`pi_transport.clj:143-147`),
  harness `close!` aggregates and fails honestly (`harness_session.clj:487-513`).

## 5. Editor reuse, no second coordinator — OK
- Observer attaches via plain `add-watch`/injectable `*rpc*` (`harness_glue.clj:124,128,229-264`) —
  no new session, no construction, no clock. Lifetime/clock ops are refused before any endpoint is
  consulted (`harness_glue.clj:90-96,132-136`), so no editor path can pump, close, or schedule.
  Detach removes the watch only (`harness_glue.clj:267-296`); pane detach/reopen remain the existing
  editor mechanism (`editor/src/graph/lain_editor/runtime/session.clj:143-170`) — glue does not
  duplicate them. Tests cover idempotence, last-reference detach, lifetime refusal
  (`test/editor/harness_glue_attach_detach_test.clj:102-296`).
- Nothing here needs a second coordinator: MAIN lifetime stays with `harness-session` (its locks,
  clock atom, close claim). One nit: `activate!` is a process-wide `alter-var-root` of
  `load-checked!` (`harness_glue.clj:60-67`) — idempotent-guarded, but per TASK it must not be
  silently deployed over the user's running editor; keep it a candidate-side startup path only.

## 6. harness_session.clj patch correctness/scope — OK
- Reuses existing loader/path APIs: `loader/source-forms` for reading (`harness_session.clj:137`),
  canonical-file semantics identical to `environment_io/canonical-file` (`:96,126`); no second
  compiler — the rewrite produces the same `pr-str`-normalized source `load-source!` itself builds
  (`file_loader.clj:42-45`). No domain objects materialized (text-in/text-out).
- Cycle-safe: load-stack cycle check + depth 32 + diamond dedup (`harness_session.clj:108-136`).
  Composition remains source of truth; nested directives resolve to absolute paths under the
  classpath-derived root (`harness_session.clj:64-73,99-101`); closure verified: composition.lain →
  tracers/evaluation-framework/user-socket/runtime-pi → combinator.lain; the only other path
  directives are `load-primitive-environment` .clj paths, which `absolute-directive` absolutizes and
  readability-checks (`harness_session.clj:79-98`; combinator.lain:3-4). No other path-taking
  directives occur in the closure (grep over all closure `.lain` files).
- Failure surfacing is explicit before transports: `declaration-failures` combines `:load-result`
  error and FAILED `environment` receipts (`harness_session.clj:143-155`; failure shape confirmed at
  `boundary/effects.clj:194-201`), and `open!` throws before any transport is opened
  (`harness_session.clj:236-242` — pi/socket/harbor owners are constructed only afterwards).
- Nits (no action): root is nil for non-`file:` (jar) classpaths → text falls back unchanged, but
  the failure then still lands in `declaration-failures` (`:104-105`); per-directive
  `(declaration-root)` re-`io/resource` calls are cheap.

## MISSING LIVE SEAM (production code paths not provable from current tests)
1. Foreign-cwd `open!` end-to-end: `declaration-root`/`declaration-source` under the drivers launch
   config is exercised only by `drivers/test/w23_contract_test.clj:71-123`, which in the fresh run
   TIMES OUT (exit 124, 4F/1E). Until that suite is green, the exact root-cause fix path —
   `open!` succeeding from a non-project-root cwd via expanded absolute directives — has no green
   proof anywhere. (Owned by the test-owner; production-side review found no reason it cannot pass.)
2. All-three-handler registration under the EXACT production launch cwd/config (TASK item 4) is not
   asserted by any suite (see §1 nit); `test/propagators/compiler_2_harness_session_test.clj` runs
   only with project-root cwd and in-JVM classpath.

## Nits (explicitly not gaps)
- §1 open-time registration assertion (one line) would convert W22's silent-`:quiescent` shape into
  an open-time error.
- §3 one-line collision check in `boundary.clj` dispatch.
- `require-successful-effects!` intentionally ignores Pi failures (FSM owns `:runtime/failed`) —
  correct as designed, worth a code-comment only.
