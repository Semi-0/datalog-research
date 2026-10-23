# Harness startup repair and exact solo verification

This supersedes earlier queue status notes. The requested schedule remains NVFP4 solo distinct/order/events, then Flash/NVFP4 delegation, then NVFP4 context continuation. Context stays 128K per coding request, temperature 1.0, thinking/web/tooling enabled, finite-command and progress budgets retained, and the 200 ms RAM guard active. Independent evaluators are unchanged.

## Confirmed failures and fixes

The failed queue 4 was an infrastructure failure. Enhanced solo configuration overwrote the graph policy previously written by prepare_gortex. Its explicit Pi CLI tool allowlist also omitted clj-kondo and Lens. Pi caught extension-hook exceptions and continued generating, so logging an exception was not an admission gate.

- Enhanced configuration now constructs a complete effective tool policy, including read-only Gortex names and required implementation tools, regardless of whether the private graph entry already exists.
- The solo command is built by agent_command from that effective policy. The exact production invocation is exercised in integration tests.
- Concurrent job specs carry the same effective tool names; the bounded manager no longer substitutes a base-tools-only list.
- Both inference proxies check required startup evidence before forwarding any generation request. Missing graph/tool registration or unverified temperature writes startup-failure.json and returns HTTP 412 without spending inference budget.
- Owned-process monitors stop invalid attempts/batches. Startup failure is setup_declined with correctness unknown, and stops solo scheduling rather than cascading into two more invalid cells. A clean memory-budget decline remains a separate scheduling case.

## Observed validation on GX10

Frozen runtime: `/home/lain/experiments/gx10-delegation-python-harness-7`.
Archive SHA-256: `d1182a7d44e7f505093482dea3bd14f12eb57cd67b41d440d163ee079b6784e9`.
Baseline commit: `2e2e26a6f6e5a1e4cb57ae4e69d10e569365f959`; the archive contains working-tree benchmark changes. These changes have not been committed or pushed.

Covering suites on that snapshot: delegation 48 tests (one skipped), core 82 passed, coding runner 14 tests (three live opt-in tests skipped by default). All suites exited zero. Raw output: `/home/lain/experiments/gx10-harness7-regressions.json`.

The three live opt-in tests were then explicitly enabled through the existing background launcher:

```sh
GX10_LIVE_SOLO=1 python3 -m unittest discover -s benchmark/ornith-nvfp4-mtp -p test_live_solo.py -v
```

Service `gx10-exact-solo-validation-2.service` exited successfully: three tests passed, zero skips. It invokes coding.execute_attempt and its exact standalone command builder, with real Pi, private Gortex, clj-kondo, Clojure LSP/Lens, public search/fetch, real own tests and the unchanged evaluator. The model endpoint and native counters are scripted, so these are unscored harness checks, not model reasoning or speed evidence.

Positive fixture: `/home/lain/experiments/gx10-exact-solo-xhk0j5nb/`. It completed 12 model requests at temperature 1.0, four own assertions and all 14 independent assertions, with workflow success. Both deliberately corrupted startup cases (missing graph policy and excluded clj-kondo CLI tool) admitted zero upstream generation requests and recorded setup_declined/startup_failure. Their artifact directories are listed by EXACT_SOLO_EVIDENCE in the validation service journal. Each test stopped its private daemon afterward.

The installed bounded-manager test also passed for two/four concurrency, rejecting overflow and nested delegation. This does not establish OS sandbox admission or real model concurrency.

Gortex impact, signature verification, change detection, selected tests, guards and contract checks were run. No guard rules are configured. Contract analysis warns about the broad call graph; default proxy behavior remains unchanged when no admission callback is supplied. Constructor instantiation coverage is exercised by the HTTP proxy tests despite static graph coverage not finding those constructor edges. Git change detection omits these untracked benchmark directories, so explicit symbol checks and archive identities are retained. Unrelated working-tree changes were preserved.

## Restart

A separate `gx10-harness7-validation-release.service` verified the test receipts, previous queue inactivity and clean backend transition under the host lock. It records a validation completion, not a fabricated benchmark completion.

Current main service/output: `gx10-delegation-python-queue-5.service`, `/home/lain/experiments/gx10-delegation-python-queue-5`.
Current deferred context service/output: `gx10-nvfp4-context-tail-2.service`, `/home/lain/experiments/gx10-nvfp4-context-tail-2`.
Both use frozen harness 7 and were launched with verified Linger=yes. The first observed main checkpoint was solo/NVFP4 Distinct setup; the tail was waiting_for_solo_and_orchestration. Actual benchmark startup and tool evidence are retained under solo/distinct/attempt/artifacts; final correctness remains an independent evaluator outcome.

Live restart verification subsequently reached NVFP4 Distinct coding: pi-runtime logged gortex_ready, the effective tool set included clj_kondo/lens_diagnostics/lsp_navigation and the read-only graph tools, and actual sampling rows recorded temperature 1.0. stderr was empty, with no startup-failure artifact. The model completed successful bash, read, autonomous web_search and fetch_content calls. This confirms actual startup/generation, not a final scored task pass.

A fresh OS probe still returned `bwrap: setting up uid map: Permission denied`. The OS Bubblewrap permission gate still applies before orchestration. No unconfined fallback, global trust change or swap/cache manipulation was added. The deferred context scan remains behind the scheduled solo/orchestration work and stops on uncertain predecessor failure.
