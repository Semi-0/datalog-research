# GX10 live Lain harness smoke trial — 2026-11-01

## Fresh observer-attached live acceptance: trial 13 (2026-11-02 local)

Trial 13 passed **3/3 framework assertions in 176,926 ms**, using the fresh fixture `1093 587` and child `sa_mvyglagb_1`; the child wrote `1680`. The actual owner is `gx10-lain-editor-trial-13.service`, session `lain-trial/ci-13-20261102`, under `/home/lain/experiments/gx10-blazux-lain-test-20261031/editor-trial-13/`. This is a new live execution, with an unused bridge root `/logs/agent/trial-ci-13-20261102`; trial 12's answer, fixture and configuration were preserved separately before preparation.

Chrome selected that exact owner from **Reopen running session before SUBMIT**. The editor reported `Session reopened · live runtime preserved`, showed empty initial facts and supported nothing, then showed framework assignment `a1` connected to the runtime during execution. The running screenshot `editor-trial-13/editor-running.jpg` on GX10 was saved at epoch `1793575637.0847776`, after admission at `1793575521.7680717` and before assessment; its selected observation is `288311 ms` relative to the owner's captured history, not trial elapsed time. The owner remained attached through completion, when ongoing assignment returned to supported nothing. The ten-frame history remains bounded; the separately saved running screenshot and filtered lifecycle facts preserve the earlier evidence. Result, transcript, status, command, control and feedback fields remain folded. The screenshot includes a denied native fullscreen status and partial capture; these do not establish complete field capture.

Public facts in `lifecycle.json` show `runtime/requested` order 5, `runtime/started` order 6, `runtime/completed` order 15, and `harbor/assessed` order 16, with assignment states pending, running, awaiting assessment and assessed. The first synchronous pump left `status.json` at ready while execution was underway; fresh bridge receipts, model counters and the attached graph established activity before that status file advanced. No ingress recording was restored into this owner.

Fresh model counters increased from zero to **2,279 generated tokens and 30,849 prompt tokens**. Admission saw **29.141 GiB** available against a **24.325 GiB** reserve plus a 1 GiB admission margin, with zero running/waiting model requests. All five RAM guard samples in the submission-to-container-cleanup window passed the reserve check; their minimum available memory was **27.296 GiB**. The model configuration was unchanged. Only one child was admitted. All four owner cleanup components closed; Pi cleanup was verified. The old and fresh uses of `lain-trial-1` ended with PID 0. The model is idle, and the actual completed owner remains available at the protected endpoint for inspection.

All **1,479 candidate blobs** matched backend `f0fb42ac05faf07559f752a09ee785d5311e5920`; frontend remains `9774d843272496478e1e100c8bebdcd588853cf4`. No driver or production source change was required. This trial verifies the two-integer smoke fixture and live observer attachment, not the full coding benchmark. The remote evidence bundle in `editor-trial-13/` contains the framework status, cleanup, admission, filtered lifecycle, code-version, token and RAM summaries plus running/completed screenshots; full receipts and artifacts remain on GX10. Existing tracer, accumulator and visualizer responsibilities and ten-value history semantics were unchanged.


Latest acceptance, trial 12: the independently owned MAIN Lain session launched one fresh Blazux Pi child and passed **3/3 framework assertions in 156,151 ms (2 min 36 s)**. Chrome reopened the actual owner and showed **3 components, 6 connections, 10 observations**, with the settled `on-going-assignment` cleared to supported nothing at revision 3 instead of ambiguous. New model counters and a new launch receipt prove fresh inference. This is the two-integer file smoke fixture, not a full Harbor coding benchmark. Earlier trial evidence is retained below.

## Observed acceptance

| Framework check | Result |
| --- | --- |
| Answer file present | Passed |
| Arithmetic correct | Passed |
| Child provenance present | Passed |

The framework selected and published the assessment through the main composition. The host driver admitted one assignment, pumped effects, polled receipts, and collected evidence. It did not create a second model coordinator or write the answer. Pi, Harbor, socket-write, and socket cleanup receipts all report closed; Pi cleanup is verified.

The editor at `https://100.83.165.122:8443/` reopened the owner's actual session, `lain-trial/a1`, without rerunning its declaration. Desktop 2 contains its observer; earlier panes remain on desktop 1. Browser inspection showed **3 semantic components, 6 connections, and 10 retained graph observations**. The UI currently labels the component field values `unavailable` and the capture partial; the graph is evidence of topology/history, not complete field-value visibility. The passing assessment is established by the framework receipt.

## Integration repairs

- Select the installed pinned SDK CLI from its `.bin` directory. A wrapper earlier on `PATH` fails Pi's active-SDK identity check.
- Keep the bridge session equal to the MAIN identity; validate session, provider, model, workspace, and declared tools before opening the bridge.
- Install the trusted bridge at `/usr/local/lib/pilot/lain-bridge/pi_bridge.mjs`, mounted read-only. Pi protects extension-code directories. Placing the provider extension under writable `/app` causes its sandbox to deny writes to the whole project.
- Apply the existing `benchmark/harbor-assisted/security.py` container policy. The default Docker seccomp profile blocked bubblewrap's namespace creation. The existing pinned profile admits its scoped user/mount namespace operations while retaining enforcing `gx10-harbor-bwrap-v1`, UID 1000, no-new-privileges, and zero capabilities. A controlled bubblewrap preflight verified project reads/writes and non-writable bridge code before inference.
- Explicitly admit the pinned worker's mandatory `failure_disposition` diagnostic in the child loadout. It is absent from the parent's inventory. Unexpected operational tools, hidden callable tools, and model/workspace mismatches remain rejected.
- Include the concrete admitted `child` in the completed result payload alongside its transcript. The envelope child routes the response; Harbor grades the nested result and requires its provenance.
- Preserve durable `not-launched` failures as terminal only when the receipt proves no jobs launched. Incomplete evidence stays uncertain and must never trigger a new launch.

## Resource and ownership boundaries

Blazux remains on the approved 4-slot, 4 GiB KV, 128K configuration. Only one child was admitted per attempt, with fresh model-idle checks and available memory above the 20% reserve plus a 1 GiB admission margin. The model RAM guard remained active. Its 66 samples during the final trial showed a minimum 26.6 GiB available, always above the 20% reserve. After verified child cleanup, the model had zero running/waiting requests; the idle trial container was stopped while the editor owner remained available for inspection. The old container was preserved before recreating it with the existing Harbor policy. Each failed attempt and its answer/receipts were preserved separately; prior cleanup was verified before a new attempt.

The observer cannot run, extend, or clean up the external owner. Closing an editor view does not stop the MAIN session. Observation extensions are installed before compiling the main declaration; the single display in `composition.lain` composes `trace:high-level`, `trace:with-values`, and `trace:history-latest` with capacity 10.

## Evidence on GX10

Run directory:

```text
/home/lain/experiments/gx10-blazux-lain-test-20261031/editor-trial-8/
```

- `admission.json`: fresh resource/idle checks and one-child admission.
- `status.json`: framework-owned passing assessment and elapsed milliseconds.
- `artifacts.json`: main-session facts and delivery receipts.
- `cleanup.json`: verified component cleanup.

Bridge receipt directory:

```text
/home/lain/experiments/lain-harness-flash-once-20261027/container-logs/trial-a1-result-provenance-20261101/
```

The serving owner is `gx10-lain-editor-trial-8.service`. Earlier failed attempts remain unscored. Trial 5 exhausted its one-hour deadline after tool sandbox failures; trial 6 failed strict tool admission; trial 7 was assessed at 2/3 because result provenance was missing. No failed assessment was rewritten or credited to the final trial.

## Focused verification observed

- Observer, external-session ownership, and network replay: **13 Clojure tests / 93 assertions**, all passed.
- Profile, bridge configuration, and protected code/workspace separation: **3 Clojure tests / 21 assertions**, all passed.
- Pi bridge and admission contracts: **12 Node tests**, all passed, including negative admission and no-relaunch cases.
- Existing Harbor security policy: **5 Python tests**, all passed.
- Actual Lain/Pi/Harbor smoke trial: **3 framework assertions**, all passed.

These checks do not establish the full coding benchmark, full repository suite, complete visualizer value capture, or unrelated `apply` acceptance. Changes are in the isolated backup worktree and deployed to this experimental owner; this report does not claim a new GitHub commit or general deployment rollout.

## Field-capture follow-up: trial 9

The unavailable public fields observed above were corrected at the tracer boundary. Legacy frozen snapshot graph methods now reuse the existing public-slot capture, and high-level projection retains the frozen value/alias maps while selecting only visible topology. This fix changes neither the accumulator nor visualizer: history retains ten immutable observations, and the existing visualizer reads their captured values with its base/support formatting.

The combined capture, high-level projection, field/history integration, replay, and external-owner checks passed **41 tests / 228 assertions**. The regression includes the actual `composition.lain`, alias resolution, pending and contradictory values, support, three visible components, ten-frame eviction, and unchanged earlier frames. It is registered in the graph test runner.

A fresh real Pi child through MAIN Lain passed **3/3 framework assertions in 148,334 ms (2 min 28 s)**. All component cleanup was verified, and the idle trial container was stopped. Fifty guard samples showed a minimum **27.13 GiB** available RAM, always above the 20% reserve.

The current owner is `gx10-lain-editor-trial-9.service`; evidence is under `/home/lain/experiments/gx10-blazux-lain-test-20261031/editor-trial-9/`, including `tracer-verification.json`, `status.json`, `artifacts.json`, `cleanup.json`, and `external-container-cleanup.json`. Trial 8 receipts and its answer were preserved separately. The protected editor reopens the actual owner without executing the declaration again.

Browser verification showed three components, six connections, ten snapshots, and captured field records instead of universal `unavailable` labels. The latest observation is selected in the new pane on desktop 2. The old pane was preserved; presentation folding and pane resizing only affect the view. `on-going-assignment` currently reports an explicit `ambiguous` reference result; this change preserves that diagnostic rather than choosing one captured reference. The graph remains a partial capture.

This is still the arithmetic smoke fixture, not a full coding benchmark. The child reported a read-tool sandbox warning and completed the fixture using bash; this tracer fix makes no broader claim about every tool. The two deployed tracer files were checked against the tested local sources. No new GitHub commit or general deployment rollout is implied.

## Assignment withdrawal follow-up

The `on-going-assignment` ambiguity above was traced to live runtime state, not history accumulation or rendering. A controlled fixture compared every frozen accessor parent with its settled live cell: both showed two parents at supported `nothing` revision 3 and two retaining assignment `a` at revision 2. The compiler's live accessor copy links wrapped transport in `concrete-propagator`; that guard suppressed the supported withdrawal because its base was unusable. Pure activation probes returned no message for the new withdrawal while still forwarding the stale assignment.

The copy links now forward cell transport state without the application-value readiness guard. Plain bottom keeps its merge behavior, while supported nothing and contradictions can carry their state through aliases. No global merge policy, tracer acquisition, accumulator, or renderer behavior was changed for this correction.

A production-code lifecycle regression exercises initial availability, a running assignment, completion, and a subsequent assignment through actual `composition.lain` and frozen history. Before the fix it failed two completion assertions; afterward all **19 assertions** passed. The preserved conditional, supported compound, canonical slot, support transport, TMS, TTMS, GUR, combinator, capture, projection, and replay checks passed **137 tests / 1,304 assertions**. Together these checks ran **138 tests / 1,323 assertions**, with zero failures or errors. This is focused verification, not a full repository-suite result.

The tested `propagators/compiler_2/compiler/basis.clj` was copied to the GX10 candidate with old/new checksum guards and a backup. Its SHA-256 is `7af7af27970b2000a0fddf20f30c36350247d6c38d5e8f1f2574dd5a010019a7`. The existing editor process and model were not restarted, and no new real-child trial was launched for this correction. Trial 9 remains a recording made by the earlier loaded runtime; a fresh owner on the updated runtime is required for new live acceptance. Historical ambiguous frames are preserved rather than rewritten.

## CI/CD and browser acceptance follow-up

The experiment changes were committed on the backup branch as `6a0848c`, followed by documentation whitespace cleanup in `25d12c6`. A clean production commit containing the runtime/editor/test paths, excluding the experiment archive and generated Python files, was pushed to backend `main`; its final revision is `c22e40de8571bde2f0dc96064144170d442f67a7`.

The existing GX10 pipeline tested and activated that backend with frontend `938c9045755bbeb9422f4106e8bca799579dcdcf`. It completed in 1,236 seconds, reporting 7,855 backend assertions, 55 additional assertions, 131 focused editor integration assertions, and passing real-backend browser acceptance. The lifecycle regression passed all 19 assertions on GX10. The release was `/home/lain/.local/share/lain-editor-deploy/releases/ed380858217e4ee7af21ee75821db017`.

Trial 10 staged its runtime directly from that approved Git tree and verified all 1,479 blobs, the two committed driver hashes, and the protected Pi bridge. The old candidate was preserved. Blazux was restarted with unchanged configuration after CI; it was healthy and idle with sufficient RAM. The fresh independently owned session reached `ready`, but no assignment was submitted.

Manual Chrome verification then exposed a separate frontend defect: selecting the listed running session produced `Unsupported editor command` before RPC dispatch. The session picker emits `:reopen-running` and `:stop-running`, while the dispatcher still recognized older action names. The existing local two-file repair and click/Enter regression were verified with **122 tests / 962 assertions**, then committed and pushed as frontend `9774d843272496478e1e100c8bebdcd588853cf4`. Unrelated frontend tool directories were preserved.

Trial 10 was stopped before submission and recorded as `not-launched`, without scoring or replay. Blazux was unloaded again for CI. Automatic polling selected backend `c22e40d` with frontend `9774d84`; the second pair passed and activated in 1,367 seconds, including **122 frontend tests / 962 assertions**, the lifecycle regression, and real-backend browser acceptance. Both repositories report `gx10/ci` and `gx10/deploy` success. The active release is `/home/lain/.local/share/lain-editor-deploy/releases/2d7feae6c27b47fb9af9ddd402727561`.

Trial 11 exposed a separate experiment setup mistake: its owner directory was fresh, but `/logs/agent/trial-config.json` still selected trial 9's durable bridge directory. Launch receipts predated admission, and the newly restarted model produced zero tokens. Its apparent running assignment was therefore not accepted as new inference. Filtered receipt timestamps and ownership metadata were preserved, the dedicated trial container was stopped and its PID verified zero, and the attempt remains unscored.

The experiment launcher was corrected in backup commit `14ea6d8`: `LAIN_HARNESS_SESSION_ID` selects an explicit nonblank identity, and fresh-editor startup rejects an existing bridge receipt namespace or an escaping host-log path before opening the bridge. Profile, workspace, fresh-root, and identity checks passed **5 tests / 32 assertions**. This changes the experiment bootstrap, not the CI-approved runtime/frontend. A new trial must use both an unused session identity and receipt directory; an interrupted owner must be reconciled through its original receipts.

## Latest verified live acceptance: trial 12

Session `lain-trial/ci-12-20261101` used `/logs/agent/trial-ci-12-20261101`, verified absent before startup. Only fresh inventory/worker startup receipts existed before submission. The runtime matched all 1,479 blobs in the CI-approved backend tree, and the trusted Pi bridge matched its committed source. The guarded experiment drivers matched commit `14ea6d8`.

The owner admitted one assignment with a fresh answer-free fixture. Exactly one new launch receipt was created after admission, for child `sa_mvycurkt_1`. Model counters increased from zero to **1,247 generated tokens, 26,376 prompt tokens, and 9 completed requests**. The child newly wrote the correct answer; the framework assessed **3/3 passed in 156,151 ms**. All component cleanup receipts report closed, Pi cleanup is verified, and the idle trial container was stopped with PID zero. Blazux remains healthy and idle on its unchanged configuration. Five guard samples during the recorded window had a minimum **27.56 GiB** available against a **24.33 GiB** reserve; the assessment snapshot also remained above reserve.

Real Chrome verification reopened the actual owner through the corrected picker without re-evaluating the declaration. Desktop 3 retains its observer, with other desktops preserved. The latest selected observation shows **3 components, 6 connections, and 10 retained observations**. `on-going-assignment` has supported nothing at revision **3**, with no ambiguous or unavailable field result. Other detailed results are folded for presentation; the tracer, accumulator, and visualizer retain their separate responsibilities. The graph still honestly reports partial capture.

The current observer host is `gx10-lain-editor-trial-12.service`. The normal pull timer and frontend are active. The test host explicitly conflicts with the managed backend so a later CI deployment can reclaim its port. The independently owned harness has completed and cleaned up; its actual final session remains available for inspection.

Evidence is under `/home/lain/experiments/gx10-blazux-lain-test-20261031/editor-trial-12/`: `ci-source.json`, `admission.json`, `status.json`, `cleanup.json`, `fresh-inference.json`, `external-container-cleanup.json`, `ram-verification.json`, `browser-verification.json`, `editor-verified.jpg`, and `editor-verified.txt`. The local screenshot is `/private/tmp/gx10-editor-harness-verified-20261101.jpg`. This acceptance proves the arithmetic smoke composition and the settled editor field behavior; it does not establish a full Harbor coding benchmark.

## Reopening and refresh repair

A later reopen exposed two independent registry defects. Reopening an external owner consumed its discovery entry, so subsequent pickers were empty. Workspace restore also rejected every workspace whenever any external view existed, leaving even a blank pane with an external-ownership error. Backend `f0fb42ac05faf07559f752a09ee785d5311e5920` retains external discovery entries, preserves their owners and generations during restore, and continues to reject external source/extension changes. Owned sessions still transfer once and retain their cleanup contract.

The focused regression gate passed **14 tests / 128 assertions** locally. GX10 CI then passed **7,885 backend assertions**, the additional integration gates, and real-backend browser acceptance, and activated release `/home/lain/.local/share/lain-editor-deploy/releases/2233ceb05c924e408435d763f249ee29` with frontend `9774d843272496478e1e100c8bebdcd588853cf4`. The new external-session regression contributed **3 tests / 30 assertions** and is registered in the normal graph runner. Chrome refresh against that deployed revision cleared the prior blank-workspace error.

Deployment stopped the completed trial's original in-memory observer host. Its recorded ingress and receipts remain unchanged. Backup commit `1e10c76` adds a separate completed-recording viewer and acceptance runner. It reconstructs the main network from the saved socket/Pi/Harbor ingress using the public fact interface, in `:observe` mode, with no pump, polling, live hooks, model requests, or tool delivery. This is a new observation instance of the completed recording, not recovery of the original in-memory history or another live trial. Both local and GX10 checks passed **1 test / 10 assertions**, including the reconstructed assessment, absent transport receipts, unfinished-recording rejection, and foreign-session rejection. The history extensions were enabled in those final checks.

The completed-recording viewer is active as `gx10-lain-editor-completed-12.service` on the existing protected endpoint, temporarily replacing the managed backend with explicit authorization. Chrome opened the same external owner twice, refreshed both views successfully, closed the extra view without stopping its owner, and confirmed that the session remained in the picker. Desktop 2 retains one observer with the latest of **10 observations**, **3 components / 6 connections**, and a current supported-nothing assignment offer. The restored history has its own capture times and support revisions; it does not replace trial 12's original history. The graph continues to report partial capture.

Blazux was restored in the same container after CI, with its RAM guard active. It returned health 200, had no running or waiting requests, and retained zero generated/prompt tokens and zero completed requests throughout the browser reopening checks. Available RAM was **29.01 GiB**, above the **24.33 GiB** reserve. No additional assignment was admitted during this repair. The only completed assignment in this acceptance remains trial 12's arithmetic smoke fixture. Browser proof is `/private/tmp/gx10-editor-reopen-verified-20261102.jpg`; the editor remains open in Chrome.
