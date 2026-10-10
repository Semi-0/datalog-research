# Functional web integration trial — 2026-10-10

## Delivery status

The non-TUI functional migration is implemented in the isolated integration
checkout. The final checkpoint below supersedes the earlier delivery-status
claims while preserving their measurements. This is **not a green full-suite
release**: TUI is deferred by the user, and the unsupported whole-owner TTMS
compound experiment is not promoted. The original dirty checkout was not edited.

The integration checkout is
`/Users/linpandi/.codex/worktrees/layered-dispatch-publish/cloj-leapfrog`.
The integration combines experiment `2b6eba8` and main `ce2cd7c`; the publication
receipt is the merge commit in Git. Do not assume the original dirty checkout
has been advanced merely because remote main has been published.

## What changed, and why

- Experimental roots/dataflow bindings use the existing CPS `:compiler-operands`
  contract. The obsolete direct-installer API is gone. Decorated observer
  activations are recorded before declaration lowering, including cell watchers.
- Layered, branching and clause adapters record their seeded closure cells and
  slot propagators through the existing declaration-effects builder. Installer
  receipts retain the application as their first returned ID.
- The opt-in TTMS environment composes whole-datum forwarding into `->`, reusing
  `stdlib.prop/forward-activation`. Plain/event/compound forwarding still delegates
  to the original implementation; this is not a production default change.
- The clause environment installs that same extension. Returning projection
  evidence instead of retained TTMS content previously broke its proof consumer.
- Demo networks now return their scalar expression. Explicit named forwarding
  keeps the compound example's outer interface readable. Test sources affected
  by removal of `def-net`/`def-cells` use functional syntax; semantic assertions
  were not removed or weakened.
- Graph selection includes declared interfaces of top-level calls, even when a
  returned cell has a structural parent. It does not recursively unfold bodies
  or rewrite ancestry. Graph projection identifies routing from the declared
  operator identity, not a displayed label, so `->` and `<->` retain direction.

The runner, cell evaluator, compiler/application implementation, Net shape,
compound synchronization, TTMS projection/dominance, distributed TMS policy and
generic layered dispatcher were not changed by this migration slice. Existing
intentional layered materialization remains. New handwritten helpers have at
most four arguments; existing protocol arities are preserved.

## Verified working paths

| Check | Result |
|---|---|
| Functional tracer, idempotence, external-edit topology reload | 3 tests / 17 assertions passed |
| Compound levels: exact directed outer/child graphs, values, compiled calls | 3 tests / 28 assertions passed |
| XR views, selection/clear, stale selection, external full-env reload | 10 tests / 68 assertions passed |
| TTMS branching | 7 tests / 81 assertions passed |
| TTMS scalar/function lifecycle, lexical capture, withdrawal/recovery | 2 tests / 62 assertions passed |
| Higher-order visualization composition | 11 tests / 33 assertions passed |
| Combined layer/TTMS/composition selection | 80 tests / 1293 assertions passed |
| Clause proofs and compound/list terms | 20 tests / 291 assertions passed |
| Real WebSocket initial publication/selection/clear/reselection | 1 test / 5 assertions passed |
| Renderer input, symbols, labels, portrait layout and view modes | 20 Node tests passed |

The new reload test uses a separate `cp` process to replace a one-call source
program with a two-call source program. The output changes from 3 to 4, the graph
from one to two application nodes, and occurrence identities are replaced. The
captured old immutable state still describes the old chain.

A live browser at phone-sized width rendered all six compound panels vertically
in 2D. The 3D row remains tiny at that width; this is not physical-phone, headset
or mobile-3D usability validation. Existing Node checks cover portrait/landscape
layout and controls. No renderer source changes were needed for this migration.

## Remaining compatibility failures

The 51-namespace compiler/runtime selection ran 480 tests: 1742 passing
assertions, 22 failures, 27 errors. Its complete
`[namespace test-var failure-type] -> count` maps are identical to clean main.
That proves parity for this selection, **not that it is healthy**.

The full selected-default run before the final added reload test ran 575 tests
across 71 namespaces: 4090 passing assertions, 113 failures, 28 errors. Clean
main's default selection ran 535 tests across 68 namespaces: 3367 passing
assertions, 41 failures, 34 errors. Raw count differences are not a regression
delta: six previously un-loadable experimental namespaces now reach their
assertions, and the experiment adds suites. Do not call newly exposed failures
pre-existing semantic failures without evidence, or dismiss them as wrong tests.

The final standard `clojure -M:test` run, including the added external-edit
reload test, completed with **4098 passing assertions, 113 failures, 28 errors**.
Its log is `/private/tmp/lain-functional-final-suite.log`. Both staged and
unstaged diff checks passed. The temporary browser preview server was stopped
after verification; the documented launch starts it again.

Outstanding cases include:

- **Production TTMS forwarding/compound transport:**
  `support-transport-test` has the same 31 failures and one error as clean main.
  Its direct production compiler forwarding and ordinary compound-link tests
  intentionally bypass the opt-in extension. They were not switched to a
  different implementation to make them green.
- **Nested compound readiness:** `slot-retraction-is-visible-to-downstream-readiness`
  still fails on both revisions. Three other clean-main errors in that namespace
  no longer occur, but this is not complete compound/TTMS compatibility.
- **Broader tracer scenarios:** high-level/late-callable cases have 26 failing
  assertions. Compound/nested/recursive cases have 46 failures and two errors.
  Clean main fails to load these suites at the obsolete tracer API, so baseline
  parity cannot establish their semantic correctness. The single verified demo
  is not evidence for all these scenarios.
- **TTMS view fixtures:** eight errors remain after the namespace starts loading,
  including retained-content normalization and removed source syntax. These
  still require individual migration/diagnosis.
- **Dataflow diagnostic merging:** two focused assertions still fail when an
  existing empty graph receives a diagnosed contradiction. They remain unchanged
  and are reported separately; this slice does not repair that merge policy.
- **Maintained runtime/UI failures:** environment IO, versioned TUI, block
  compilation, inspection and session replay still fail in the compiler/default
  selections. Matching clean-main locations are not permission to remove them.

Production compiler forwarding and ordinary compound synchronization are fixed
boundaries of the approved trial. Repairing those paths requires a separately
reviewed plan/renewed approval. Consumer-only remaining work may be proposed
without altering those boundaries. Main publication remains blocked until the
relevant gates pass; no force push, facade or test deletion is an alternative.

## Reproduce

From the integration checkout:

```sh
clojure -M -m examples.lain.visualization-combinators.compound-levels 45670 127.0.0.1
```

Open `http://127.0.0.1:45670/relationships`; choose **Views → 2D View** for the
vertical mobile layout. Stop with Ctrl-C. The existing file watcher recompiles
the complete selected `.lain` file into a fresh environment. Invalid reloads
preserve the last good environment.

```sh
clojure -M:test propagators.experimental.functional-web-migration-test propagators.experimental.compound-levels-demo-test propagators.experimental.view-xr-test
clojure -M:test propagators.ttms-branching-test propagators.ttms-primitives-test graph.ttms-view-live-test
clojure -M:test examples.lain.relational-datalog.proof-experiment-test examples.lain.relational-datalog.clause-terms-test
clojure -M:test propagators.relationship-dataflow-test
clojure -M:test
node --test test/graph/view_layout_test.mjs test/graph/xr_2d_input_test.mjs test/graph/xr_mobile_controls_test.mjs test/graph/card_symbols_test.mjs test/graph/card_labels_test.mjs test/graph/xr_static_update_test.mjs
git diff --check
git diff --cached --check
```

Comparison receipts/logs are in `/private/tmp/lain-migrated-{compiler,full}.edn`,
`/private/tmp/lain-clean-main-full.edn`, and their `.log` siblings. They are
local verification artifacts, not versioned architecture evidence. KIROSHI
grounding was read-only: runner composition remains supported; TTMS compaction
is approved but needs verification; layer-blind dispatch remains a candidate.
No constraint approval, supersession or hierarchy mutation was performed.

## 2026-10-10: premise-state composition migration follow-up

This follow-up supersedes the earlier current-status claims above, not their
historical measurements. It implements the subsequently approved transport plan;
it does not establish complete main-publication compatibility.

### Ownership and implemented composition

- `stdlib/supported_procedure.clj` owns compiler-free layered installation and
  observation publication. The experimental environment adapter invokes it and
  records its final propagator declarations through the existing recorder.
- `stdlib/premise_publication.clj` separates usable observations from independent
  premise-state updates. Nothing and contradiction can carry state without
  publishing an unusable payload.
- `stdlib/observation.clj` owns primitive identity forwarding. Supported cells
  publish their current local projection, never their retained observation
  history. Ordinary and legacy two-layer forwarding remain separately tested.
- Compiler definitions, application boundaries and returned-result connections
  install that primitive activation. The compiler does not interpret TTMS
  evidence. The opt-in environment no longer replaces `->` with a special
  compiler forwarding operator.
- `be:block` now declares its implicit result/source cell as an input. Previously
  it read that cell but did not wake when a late value arrived through `->`.
  Its existing display effect handler is unchanged.

The shared composition is:

```clojure
(prop/compose-activation
  activation
  (message/lift-message publication/content)
  publication/transport-states)
```

Installation uses the existing `layered/p:apply-layered`, `net/assoc-net-prop`
and declaration recording contracts. No private executor or source clock is
introduced. Net, runner, cell evaluator, layered dispatcher, compound
synchronization, TTMS compaction/projection and distributed TMS policy are fixed.

### Verified improvements

The latest focused selection passed **631 assertions, 0 failures, 0 errors**:
observation transport (211), functional network effects (33), compiled support
transport (206), relationship/dataflow (38), TTMS primitives (62), and branching
(81). It covers active/update/retract/reactivate, joint-source conflicts and
fresh repair, retained-history isolation, empty state publication, duplicate
updates, lexical capture, late display and existing effect delivery.

Functional tracer fixtures now use returned cells, rather than passing an output
argument to a functional network. Child projection normalizes declared body
return routes without inventing self-loops. Earlier focused checks established
226 high-level topology assertions and 210 compound/child assertions; the latter
still has two full-TTMS recursive errors and is not a green suite.

### Remaining gates; no assertion weakening

1. **Full-TTMS recursion:** the flat-GUR `when-effect` gate recognizes only bare
   nothing/contradiction, whereas a layered unusable value is a Net. This can
   declare an inactive recursive body. A temporary, process-local readiness
   substitution is diagnostic only. Changing the fixed GUR gate requires renewed
   approval; the existing 30,000-transition test limit is not increased.
2. **Proof diagnostics:** local contradictory binding evidence is valid, but
   the proof example expects that evidence to cross ordinary functional identity
   boundaries. Approved transport intentionally suppresses contradiction
   payloads. The proof consumer needs an explicit diagnostic observation design;
   forwarding retained history or changing TTMS policy is not a repair.
3. **Maintained UI/versioned consumers:** the earlier 480-test compiler selection
   reproduced the same 22 failures and 27 errors as its clean-main comparison.
   Baseline parity is not passing compatibility. The new late-display repair must
   be accounted for in a refreshed comparison before publication.
4. **Whole supported compound owners:** structural premises remain the intended
   path. Unsupported same-slot owner experiments are not promoted by this work.

Latest focused log: `/private/tmp/lain-remaining-focused.log`. No commit, push,
model mutation, compatibility facade or test removal is part of this follow-up.

The full-suite run before the final TTMS view fixture syntax migration reported
**4,435 passed assertions, 10 failures, 27 errors**
(`/private/tmp/lain-remaining-full.log`). This is not a green integration result.
The view fixtures still used removed `def-cells`/`::` syntax; their migration and
focused rerun must be reported separately rather than retroactively changing this
full-suite count.

### Confirmed recursive gate diagnosis / proposed boundary exception

A process-local `with-redefs` wrapped only the generated `when` activation with
the existing `value/unusable?` predicate. Both recursive tests then passed:
**178 assertions, zero failures/errors**, including all four exact TTMS child
graphs and the control without tracing. The same 30,000-transition guard was
retained. Diagnostic log: `/private/tmp/lain-recursive-gate-diagnostic.log`.

Proposed narrow change, **not implemented**:

```clojure
;; In propagators.gur.flat/when-effect:
(cond
  (value/unusable? condition) []
  (when-declared? network when-key) []
  :else [(bind-name when-scope when-key condition-id) (body-f)])
```

This replaces its two bare-marker checks with the already established generic
usability contract. It does not interpret support, invent timestamps, change
when's availability semantics or implement topology withdrawal. Because flat GUR
is a fixed dependency of this migration plan, the grounding skill requires
renewed approval before applying even this narrow correction.

Final view-fixture verification: `propagators.experimental.ttms-view-test`
passes all **9 tests / 54 assertions** after migration to `define` and `network`.
The real WebSocket selection/clear/reselection check separately passes **1 test /
5 assertions**. Final whitespace validation passes. The full suite has not been
rerun after this final fixture-only change; its older 27-error count includes the
eight view-test errors now resolved by the focused check.

### User refinement: compose readiness at the `when` expression boundary

The GUR-edit proposal above is superseded by the user's narrower direction:
leave GUR unchanged and compose layered readiness into the expression adapter.
`compiler_2/runtime/lazy_topology.clj` now wraps the generated declaration's
activation using the existing `prop/concrete-propagator`:

```clojure
(update (gur/when-effect when-key condition-id body-f)
        :activate prop/concrete-propagator)
```

The existing `value/unusable?` layered method already rejects layered nothing,
contradiction, retracted support and incompatible source versions. No duplicate
usability rule, TTMS-specific compiler branch, layered-dispatch policy, or GUR
source change is needed. GUR still owns once-only declaration; the composed
expression activation waits for usable inputs. A usable false value remains
available, matching historical `when` semantics. Withdrawal after construction
remains deferred. Focused verification is recorded after the run completes.

Focused result on the actual composed implementation: **544 assertions, zero
failures/errors** (observation transport 219, compound/recursive tracer 270,
functional-network GUR 55). All four TTMS recursive child graph oracles pass,
along with the no-tracer recursion control. No temporary redefinition or increased
test limit is used in this result. `git diff --check` passes; flat GUR has no diff.

Full verification after the expression fix and view fixture migration:
**4,553 passed assertions, 10 failures, 17 errors**
(`/private/tmp/lain-expression-when-full.log`). The two recursive errors and
eight view-fixture errors are resolved. Remaining failures cover organization,
unsupported whole-compound readiness, versioned/TUI lowering, environment IO and
instance replay. No assertions are removed or weakened, and no main-publication
compatibility claim is made.

## Final non-TUI migration checkpoint — 2026-10-10

The user explicitly excludes TUI for this migration. No TUI test was removed,
disabled or weakened. Current supported structural premises, ordinary compound
slots, recursive tracing, primitive forwarding, functional compilation and XR
publication are the acceptance paths, not the old whole-owner TTMS slot adapter.

### Boundaries and KIROSHI inquiry

Read-only KIROSHI inquiries used the canonical repository model through revision
592. The application and compound component explanations and exact ownership
assignments were inspected in addition to a bounded general context query.
No proposal, approval, supersession or hierarchy transaction was performed.

| Owner | Migration responsibility | Excluded responsibility |
|---|---|---|
| Primitive layer | Layered application, current observation publication, independent premise-state transport | Compiler syntax or scheduling |
| Compiler 2 | Install/record declarations and connect application interfaces; compose concrete readiness for `when` | TTMS evidence interpretation, source epochs, GUR policy |
| Relationship observation | Path-sensitive graph selection and pure snapshots | Merge policy, renderer layout or scheduler control |
| Runtime assembly/file loader | Web providers and stable-file fresh-session replacement | Mutable payloads inside Net or incremental compiler policy |

The queried runner semantic-composition constraint, immutable Net decision,
explicit projection, runtime-owned effects and preserved bidirectional compound
constraints apply. TTMS support dominance remains approved but its stored
evidence is marked as needing verification. Layer-blind dispatch remains a
**candidate**, not a newly approved constraint.

The model still contains
`:constraint/compiler-application-supports-ordinary-and-lazy-gur`, referring to
accumulating GUR with evidence needing verification. That stale fact needs a
separate model review; this migration does not resurrect accumulating execution
or silently supersede the fact. GUR, runner, cell evaluator/merge, TTMS policy,
compound synchronization and generic layered dispatch have no migration diff
from the experiment parent.

### Final fixes

- Organization assertions now identify the graph assembly as the owner of web
  providers; headless runtime construction stays independent.
- Failed import/request checks verify preservation of the exact previous session,
  rather than expecting a previously initialized session to become nil.
- Non-TUI loader, observer, view and benchmark fixtures use `define` and functional
  `network` syntax. The client-list test preserves its original explicit input
  and output ports and uses the web assembly. It does not establish arbitrary
  compound-valued implicit-return compatibility.
- Proof reports encode a local contradiction as explicit diagnostic data. The
  binding remains contradictory with exact dependencies; ordinary identities do
  not forward contradiction payloads. A focused test distinguishes these cases.
- Root observers exclude their own publication cell from connected selection.
  Otherwise the compiler application interface can expose the previous snapshot
  to its own next sample, causing recursively growing values. The regression test
  asserts exclusion and has a 5,000-step diagnostic guard.
- File watching waits for two consecutive equal reads before attempting a changed
  source. The deterministic truncate/write test and external-process reload tests
  verify fresh promotion and last-good-state preservation. This is a polling
  settle rule, not a guarantee that an arbitrary writer has finished: a partial
  program stable across polls can still be reported invalid.
- Reducer-cell remains: the maintained lexical reducer still consumes it. Legacy
  graph union remains for ordinary graph consumers. Neither is removed merely
  because the TTMS view path no longer requires it.

### Verification

| Check | Actual result |
|---|---|
| Structural premises, independent states, current forwarding, dataflow, higher-order views, live WebSocket, proof/terms, functional compiler | 1,593 passing assertions, 0 failures/errors |
| Additional API, runtime, assembly, environment loading, observation and Net codec selection | 372 passing assertions, 0 failures/errors |
| Loader and XR reload selection, including transient-write regression | 95 passing assertions, 0 failures/errors |
| Renderer/mobile/layout/labels/input | 20 Node tests passed |

Selections overlap; their assertion counts must not be summed as unique coverage.
Logs are `/private/tmp/lain-final-nontui-gate.log`,
`/private/tmp/lain-final-runtime-gate.log` and
`/private/tmp/lain-final-reload-gate.log`. Full default-suite results are recorded
below separately; passing scoped gates is not a claim that every historical test
is healthy.

Final `clojure -M:test`: **4,568 passing assertions, 4 failures, 16 errors**
(`/private/tmp/lain-final-migration-full.log`). Three failures and all sixteen
errors are deferred TUI/versioned-block cases. The remaining failure is
`slot-retraction-is-visible-to-downstream-readiness` in the unsupported
whole-owner experiment. No failure remains in this run's current structural,
tracer, forwarding, view or external-file reload paths. These unresolved tests
remain registered and visible; publication accepts the user's non-TUI scope,
not full-suite health.

### Bounded compiled-chain benchmark

```sh
clojure -M -m examples.lain.visualization-combinators.ttms-compiled-benchmark 10 100
```

One warmed run, propagation without rendering, after migrating generated source
syntax. No statistical speedup or 1,000-node performance claim is made.

| 100-node chain | Initial ms / activations | Update ms | Withdrawal ms | Recovery ms |
|---|---|---|---|---|
| Plain arithmetic | 17.32 / 1,102 | not measured | unsupported | unsupported |
| TTMS arithmetic | 314.31 / 3,602 | 305.65 | 294.96 | 286.88 |
| Plain branch | 17.12 / 1,500 | not measured | unsupported | unsupported |
| TTMS branch | 951.31 / 7,069 | 295.45 | 289.35 | 293.66 |

Every measured phase at both 10 and 100 nodes passed its semantic assertion and
had zero topology growth. TTMS outputs retained one observation after recovery.
Setup at 100 nodes was 585–629 ms plain and 888–977 ms TTMS. Measurements are
machine/load dependent; the stronger result is bounded activation/topology growth
with successful update, withdrawal and recovery.

### Deferred scope

TUI/versioned-block source migration is excluded by the latest user direction.
The old whole-supported-owner compiler slot experiment remains unsupported; the
chosen structural-premises path passes its lifecycle, simultaneous-write,
incremental, chained and cyclic tests. `when` withdrawal after construction,
general event-producer migration, and full TTMS structural-topology replacement
are not newly promised features. No model mutation is implied by publication.
