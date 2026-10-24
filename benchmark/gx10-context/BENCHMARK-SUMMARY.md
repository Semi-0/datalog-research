# GX10 benchmark: verified results and lessons so far

**24 October 2026 assisted Harbor pilot:** [sandbox blocker resolved and verified](../harbor-assisted/BENCHMARK-SUMMARY.md). The exact plugin passed confinement under the dedicated enforcing AppArmor/seccomp policies, with zero capabilities and no-new-privileges. Cleanup was verified; no model or cohort ran. This adds no correctness score or coding-time measurement.

Updated locally on **23 October 2026, 23:21 UTC** with the later Events, Harbor pilot and completed task-migration evidence below. The original matrix/solo tables retain their **20:43 UTC** snapshot and raw classifications. This report is a snapshot, not a live dashboard; the update itself does not start scored model runs.

## Latest update: Harbor pilot and migration

The standalone **Harbor 0.24.0 + Pi 1.3.0 + Qwen3.6 NVFP4 pilot now passes**. It is an unscored four-assertion integration task, separate from the original Distinct/Ordering/Events evaluators and from the older solo cohorts.

| Harbor run on GX10 | Result | Independent assertions | Execution | Cleanup |
| --- | --- | --- | --- | --- |
| `gx10-harbor-pi-pilot-1` | Oracle reward 1.0; Pi reward 0.0 | Four-assertion pilot | Pi rejected with 401 Unauthorized, zero generated tokens | verified |
| `gx10-harbor-pi-authfix-1` | Oracle and Pi reward 1.0 | 4 passed, 0 failures, 0 errors | 4,426 generated tokens; 15 assistant messages; normal completion; no provider errors; exit 0 | verified |

The failed invocation supplied a different local API key from the backend. The fixed runner derives the client key from the actual backend command, verifies authenticated model discovery (200), deliberately checks invalid credentials (401), and inspects Pi's transcript rather than trusting exit zero, settlement or reward alone. Fourteen focused runner tests passed on the Mac and GX10. Minimum available RAM during the successful run was 81,695,637,504 bytes against a 26,118,804,275-byte reserve; swap deltas were zero and no owned containers/models remained. See the [fix report](../harbor-pilot/AUTHENTICATION-FIX-2026-10-24.md), [result](../harbor-pilot/evidence/authfix-1/result.json), and [audit](../harbor-pilot/evidence/authfix-1/verification-audit.json). The original failure evidence remains unchanged.

A later explicitly authorized standalone **Qwen3.6 Events** attempt also exists at `/home/lain/experiments/gx10-qwen36-events-finish-1/events/result.json`. It exhausted its request/token budget without correctness: `outcome=execution_outcome=budget_exhausted`, `correct=false`, 2,187.99 coding seconds, invalid timing, and 15 executed independent assertions with 0 failures and 6 errors (the known-correct total is 42; exceptions prevented later assertions). Cleanup was verified. This does not resolve the older Ordering abort classification or turn the original priority queue into a completed successful cohort.

The LangGraph API/lgview infrastructure and synthetic two-worker control test are separate: services and checkpoint persistence work, and synthetic delegation made zero parent model requests, but trusted coding-tool confinement, live Flash/Qwen phase integration and production recovery remain incomplete. See the [delegation installation status](../gx10-langgraph/README.md). No full delegation score or model-generated Distinct Harbor assessment is claimed.

The three original coding tasks are **migrated to Harbor and validated**, preserving the original contracts, allowed paths and evaluator bytes:

| Migrated task | Reference solution | Original independent assertions | No-op candidate |
| --- | --- | ---: | --- |
| Distinct | reward 1.0 | 14 passed | reward 0.0 |
| Ordering | reward 1.0 | 17 passed | reward 0.0 |
| Events | reward 1.0 | 42 passed | reward 0.0 |

The latest model-free validation passed eight Harbor trials: the six reference/no-op cases above, a scaffold-change case that preserved correctness while recording workflow failure, and a candidate attempt to forge the verifier score that was denied. The primary reference cases passed **73 original assertions** in fresh verifier containers. Seven Python migration checks passed on both the Mac and GX10. Package/source/evaluator hashes and container cleanup were verified. No model was loaded or queried, so these are migration checks, not new model correctness or speed results.

[Task packaging and commands](../harbor-tasks/README.md), [validation result](../harbor-tasks/evidence/validation-2/result.json), [package/input hashes](../harbor-tasks/evidence/validation-2/migration.json), and [audit](../harbor-tasks/evidence/validation-2/verification-audit.json) are retained locally. Generated runnable tasks are at `/home/lain/experiments/gx10-harbor-tasks-v1-protected/`; full trials and frozen source are under `/home/lain/experiments/gx10-harbor-migration-validation-2/`. The first seven-trial migration validation is preserved separately. The new minimal images use pinned Clojure 1.12.4 / CLI 1.12.4.1618 and Java 21, with two CPUs and 2,048 MiB per container; their timing is not directly comparable with the older host-based cohorts. No overall agent deadline is introduced, while verifier commands remain bounded at 120 seconds.

The full structural-editing/LSP/private-Gortex tool profile and live LangGraph integration still require their own setup and verification. The earlier four-assertion pilot is not a substitute for these migrated tasks.

## Results at a glance

- **Flash IQ4 completed all three coding tasks correctly**, with normal workflow and valid recorded timing in the later 128K protocol-6 pilot. The old 32K timeouts below are historical and no longer describe its latest completed coding run.
- The **12 coding cells of the four-configuration 128K matrix all have results**. The surrounding queue did not finish its context phase; its failed service status does not mean that the coding matrix is missing.
- In that matrix, independently correct implementations were Flash **3/3**, Ornith NVFP4 **2/3**, Ornith Q4 **2/3**, and Ornith BF16 **1/3**. Q4 Events was correct despite request-budget exhaustion. These correctness counts are separate from workflow success and timing eligibility.
- Under later structural-editing/repetition-recovery tooling, Qwen3.8 27B NVFP4 produced **no verified correct solution in three scheduled cells**; two evaluators lacked completed assertion summaries. Ornith NVFP4+MTP produced **2/3 correct implementations**, including a retained earlier Distinct cell; Events failed after a bounded fresh retry.
- **Qwen3.6 35B-A3B NVFP4 passed Distinct (14 assertions) and Ordering (17)** in its original priority queue. That queue did not admit Events after an unresolved abort. A later standalone Events attempt exhausted its budget without correctness, as recorded in the latest update above; there is no 3/3 pass.
- No completed Flash/worker orchestration result has been collected. Solo results cannot establish concurrent-agent quality or capacity.

All tasks are Clojure: stable distinct-by-key, deterministic dependency ordering, and event projection. Their known-correct fixture totals are respectively **14, 17, and 42 assertions**. Failing implementations can execute fewer assertions because exceptions prevent later assertions. This evidence does not establish multilingual coding performance.

## How to read the measurements

Implementation correctness follows the unchanged independent evaluator. Later runs use the user's correctness-only pass policy: an implementation can pass even when workflow diagnostics fail. Preserve `execution_outcome`, workflow and timing fields alongside that pass; a correctness pass does not clear an unresolved runtime error for scheduling.

`Agent seconds` is recorded coding execution, excluding separately recorded setup, loading, preloading, evaluation and cleanup. A stopped/interrupted/budget-exhausted attempt measures time until the stop, not time to a finished solution. Invalid timing is retained descriptively and excluded from clean completion-speed claims. There is one scheduled cell per task in each cohort, with explicit retries below; no speed medians or statistical model ranking are justified.

Decode and prefill/refill rates are aggregate token-count/time ratios over completed backend requests, including thinking and auxiliary compaction calls where recorded. Incomplete/error requests are excluded, so these rates do not account for all wasted output. **Ollama/llama.cpp prefill counts uncached processed tokens; vLLM prefill counts submitted prompt tokens including cache hits. These prefill rates are not directly comparable.** Token throughput is different from end-to-end task completion speed.

## Completed 128K coding matrix (protocol 6)

Evidence root on GX10: `/home/lain/experiments/gx10-datalog-benchmark-queue-5/`. Flash cells are in `flash-pilot-0f48d36b/cells/`; the other nine cells are in `matrix-a534746b/cells/`. Each cell has `result.json`, and the matrix has `summary.json` and `REPORT.md`. Flash rows in the matrix report refer to the same pilot executions; they are not additional attempts.

All cells used 131,072 configured context tokens. This protocol removed the overall coding deadline in favor of progress supervision and finite request/token budgets; shell and evaluator commands remained bounded at 120 seconds. Later tooling and recovery policies changed, so the later tables are separate cohorts.

| Model | Task | Recorded outcome | Correct | Workflow | Timing valid | Agent seconds | Decode tok/s | Prefill/refill tok/s |
| --- | --- | --- | --- | --- | --- | ---: | ---: | ---: |
| Flash IQ4 | Distinct | passed | yes | yes | yes | 1,006.81 | 27.74 | 553.87 |
| Flash IQ4 | Ordering | passed | yes | yes | yes | 1,374.74 | 27.52 | 531.48 |
| Flash IQ4 | Events | passed | yes | yes | yes | 921.18 | 27.82 | 532.81 |
| Ornith NVFP4 | Distinct | passed | yes | yes | no | 492.92 | 45.33 | 21,785.96 |
| Ornith NVFP4 | Ordering | runtime_error | no | no | no | 1,753.46 | 47.25 | 52,347.46 |
| Ornith NVFP4 | Events | passed | yes | yes | no | 1,757.06 | 41.47 | 45,830.20 |
| Ornith Q4 | Distinct | passed | yes | yes | yes | 1,273.65 | 56.38 | 1,338.28 |
| Ornith Q4 | Ordering | budget_exhausted | no | no | no | 1,223.24 | 58.16 | 1,162.61 |
| Ornith Q4 | Events | budget_exhausted | yes | no | no | 2,025.89 | 56.67 | 1,170.09 |
| Ornith BF16 | Distinct | passed | yes | yes | yes | 920.26 | 31.12 | 589.21 |
| Ornith BF16 | Ordering | task_failed | no | no | yes | 313.67 | 32.15 | 635.68 |
| Ornith BF16 | Events | stalled | no | no | no | 3,513.23 | 29.59 | 560.85 |

A fast failed Ordering attempt is not faster successful problem solving. Likewise, the high vLLM prefill figures include prompt-cache hits and do not establish hardware prefill superiority. The raw `timing_valid` flags above are preserved even where correctness passed.

## Later solo reliability trials

These used the evolving Python/Pi harness with structural editing, lint/LSP and web tooling, anti-repeat monitoring and, in the follow-on runs, bounded recovery. Tool availability does not establish tool use; workflow diagnostics retain omissions. They are not repetitions under an identical protocol to the matrix above.

`D/O/E` means Distinct / Ordering / Events. Logical correctness passes below retain their separate execution classifications.

| Cohort/model | Task | Logical outcome / execution outcome | Independent evaluation | Workflow | Timing valid | Agent seconds | Decode tok/s | Prefill/refill tok/s |
| --- | --- | --- | --- | --- | --- | ---: | ---: | ---: |
| Qwen3.8 27B NVFP4, trial 4 | D | stalled / stalled | unmeasured: no complete assertion summary | no | no | 2,628.39 | 11.34 | 8,406.03 |
| Qwen3.8 27B NVFP4, trial 4 | O | stalled / stalled | failed: 5 failures, 10 errors / 15 assertions | no | no | 3,039.16 | 11.49 | 6,072.94 |
| Qwen3.8 27B NVFP4, trial 4 | E | stalled / stalled | unmeasured: no complete assertion summary | no | no | 2,655.13 | 11.21 | 8,566.08 |
| Ornith NVFP4+MTP, retained follow-on 5 cell | D | passed / runtime_error | passed: 14 assertions | no | no | 487.11 | 50.15 | 17,103.22 |
| Ornith NVFP4+MTP, continuation 6 | O | passed / task_failed | passed: 17 assertions | no | yes | 1,576.35 | 34.31 | 26,075.98 |
| Ornith NVFP4+MTP, continuation 6 | E | task_failed / task_failed | failed after retry | no | no | 616.57 | 50.20 | 20,160.21 |
| Qwen3.6 35B-A3B NVFP4, priority 1 | D | passed / task_failed | passed: 14 assertions | no | yes | 460.71 | 62.14 | 82,261.44 |
| Qwen3.6 35B-A3B NVFP4, priority 1 | O | passed / runtime_error | passed: 17 assertions | no | no | 2,224.86 | 60.64 | 58,676.25 |
| Qwen3.6 35B-A3B NVFP4, priority 1 | E | not started in this queue | see separate standalone attempt below | — | — | — | — | — |
| Qwen3.6 35B-A3B NVFP4, standalone Events finish 1 | E | budget_exhausted / budget_exhausted | failed: 0 failures, 6 errors / 15 executed assertions; full successful total 42 | — | no | 2,187.99 | — | — |

The standalone Qwen3.6 Events attempt ran for **36 minutes 27.99 seconds** before exhausting its request/token budget and did not pass. Its timing is censored; the duration is time until stopping, not successful completion time. The later Harbor Events **42/42** result used the reference solution to validate task migration. Qwen3.6 has not yet been assessed on Events through the migrated Harbor task.

All later prefill rates in this table use vLLM submitted prompt tokens including cache hits. Qwen3.6 used a no-MTP reliability baseline; it must not be described as the same serving configuration as Ornith+MTP. The tested Qwen3.8 artifact was not the proposed ASUS-optimized alternative.

Qwen3.8 trial 4 completed its schedule, not its coding tasks: the smoke passed in 208.77 seconds, then all three coding executions were stopped as stalled. `correct:false` without a complete evaluator summary is not proof that a finite independent test suite ran and failed. Qwen3.6's smoke passed in 38.24 seconds.

Ornith continuation 6 retained Distinct from follow-on 5 rather than rerunning it. Events attempt 1 ended `runtime_error` after 412.17 seconds (42 assertions, 23 failures, 5 errors); the fresh attempt 2 ended normally as `task_failed` after 204.39 seconds (39 assertions, 28 failures, 10 errors). The logical cell totals 616.57 coding seconds across both attempts, and its combined timing remains invalid. Thus a recorded reload/retry completed the schedule but did not produce a correct Events implementation.

### Original priority queue stopping point (20:43 snapshot)

`gx10-qwen36-priority-1.service` was still failed at the live check, with no `gx10*` unit in running substate. Its queue says `stopped`, task `order`, error `Failure('Unresolved predecessor outcome: runtime_error')`. Historical `active/exited` units and stale status files are not evidence of running agents.

Qwen3.6 Ordering had Pi exit 0, an observed agent end and final response, all 17 independent assertions passing, zero swap deltas and verified cleanup. However, one assistant message retained `Request was aborted`, with no matched recovery event. The anti-repeat log recorded detection and a corrective intervention, not a terminal repetition stop. The controller checks execution safety separately and therefore did not admit Events. See [the detailed queue audit](../QUEUE-AUDIT-20261023.md).

This is an inspected recovered-abort classification gap. It does not prove why the model repeated, and it is not evidence of a RAM failure. The Python fix remains pending; this report changes no classifications or raw results. The local Clojure policy prototype is not a deployed Python/Pi fix.

## Interrupted runs, retrospective evaluation and delegation

| Evidence/run under `/home/lain/experiments/` | Recorded disposition |
| --- | --- |
| `gx10-datalog-benchmark-queue-1` | Early Flash smoke attempts resource-stopped; preserve separately from the later successful pilot. |
| `gx10-datalog-benchmark-queue-2` | All three 32K coding attempts timed out under the former 20-minute deadline; detailed historical section below. |
| `gx10-datalog-benchmark-queue-3`, `-4` | Flash setup/smoke interrupted, then runtime failure respectively; no additional completed coding cohort. |
| `gx10-delegation-python-queue-1`, `-2` | Waiting-stage records; no completed delegation result. |
| `gx10-delegation-python-queue-3` | BF16 solo Distinct runtime error after 1.54 seconds; Ordering interrupted. This is not a measured BF16 coding comparison. |
| `gx10-delegation-python-queue-4` | NVFP4 Distinct runtime error after 1,741.22 seconds. |
| `gx10-delegation-python-queue-5` | NVFP4 Distinct originally task_failed after 1,589.57 seconds; Ordering interrupted. |
| `gx10-distinct-posthoc-evaluation-1` | Separate unchanged-evaluator retrospective evaluation of queue-5 Distinct succeeded, exit 0 in 1.40 seconds; original record preserved. |
| `gx10-delegation-python-queue-6`, `-7` | Waiting/replacement continuation. Queue-7 retains that same Distinct implementation as correct with 14 assertions, execution task_failed and invalid timing; Ordering interrupted. Do not count the copied Distinct as a new implementation attempt. |
| `gx10-structural-reliability-1` | Ordering interrupted during the structural-tool reliability work; no completed result. |
| `gx10-delegation-queue-1` | Waiting for the benchmark; no completed orchestration result. |
| `gx10-qwen38-trial-1`, `-2`, `-3` | Smoke interrupted, runtime_error, runtime_error respectively; trial 4 is the completed schedule reported above. |
| `gx10-ornith-follow-on-1`, `-2`, `-3` | First two interrupted; third stopped at smoke (implementation correct but execution runtime_error). No additional three-task cohort. |
| `gx10-ornith-follow-on-5`, `-6` | Follow-on 5 stopped after Distinct's runtime classification; continuation 6 retained Distinct, completed Ordering and retried Events. Count three logical cells, not two complete independent cohorts. |
| `gx10-qwen36-priority-1` | Two correct coding implementations; Events blocked at Ordering's unresolved execution status. |

No `orchestration.json` was present in the inspected delegation experiment directories. Sandbox, tool activation, structural-editing and repetition-guard checks are infrastructure evidence; they are not a successful end-to-end Flash orchestrator plus concurrent-worker benchmark. The requested Qwen3-Coder 30B-A3B NVFP4, Qwen3 30B-A3B and Qwen3-Coder-Next NVFP4 candidates have no scored results in this snapshot.

## Latest recorded Flash loading time

The latest saved Flash IQ4 load measurement found on GX10 is **28.7333 seconds**, followed by **1.5729 seconds** for the preload/warm-up request: **30.3062 seconds combined**, excluding separately recorded setup (4.3328 seconds), unload and settlement gates. This is the 22 October 2026 Flash→Flash transition at 131,072 configured context tokens, not a fresh measurement from this documentation update. The preceding Ornith BF16→Flash transition recorded 28.7897 seconds loading and 1.5754 seconds warming up, consistent with roughly 29 seconds loading under those conditions.

Evidence: `/home/lain/experiments/gx10-datalog-benchmark-queue-5/live-validation-7add8d97/transition-15-flash-iq4-to-flash-iq4/phase-durations.json` and its `load-response.json` (completed load receipt at `2026-10-22T12:10:02.997830855Z`). The manifest says `os_file_cache_cleared=false`; these measurements do not establish cold-start latency after reboot or a completed Flash→Qwen→Flash delegation cycle. No model was loaded for this update.

## Context occupancy and memory evidence

Flash's synthetic scan selected **131,072 configured tokens**, with 120,682–120,708 actual prompt tokens in the successful 128K probes. Seeds 42/43/44 all passed at 32K, 64K and 128K. Boundary budget declines and historical full-agent reserve stops are described below. The later 128K coding pilot succeeded under a changed serving/harness configuration; the earlier full-agent smoke failures are not a universal 32K limit.

The matrix's Ornith NVFP4 retrieval records show:

| Configured context | Seed | Outcome | Actual prompt tokens | Minimum available GiB |
| ---: | ---: | --- | ---: | ---: |
| 32,768 | 42 | passed | 22,405 | 85.11 |
| 32,768 | 43 | passed | 22,410 | 84.81 |
| 32,768 | 44 | passed | 22,408 | 85.37 |
| 65,536 | 42 | task_failed: wrong retrieval | 55,158 | 81.38 |
| 65,536 | 43 | passed | 55,166 | 81.58 |
| 65,536 | 44 | interrupted | unknown | unknown |

The user cancelled the context phase to prioritize solo/orchestration work. The later `gx10-nvfp4-context-tail-1` through `-4` records remain waiting-stage snapshots; no completed full NVFP4 boundary scan was collected. A successful 128K coding request does not establish reliable retrieval at every occupied context size, a maximum context, or concurrent capacity.

Minimum host `MemAvailable` during coding, not model-only resident size:

| Cohort | Observed range across reported cells (GiB) | Swap observations |
| --- | ---: | --- |
| Flash 128K matrix pilot | 24.39–24.53 | zero deltas |
| Ornith NVFP4 matrix | 72.23–72.97 | zero deltas |
| Ornith Q4 matrix | 90.91–93.20 | Events: 2 swap-in pages, no swap-out; others zero |
| Ornith BF16 matrix | 48.69–48.80 | zero deltas |
| Qwen3.8 trial 4 | 57.86–75.28 | zero deltas |
| Ornith later retained/retry cohort | 67.74–71.38 | zero deltas |
| Qwen3.6 priority 1 | 73.87–75.36 | zero deltas |

These measurements include host/backend/tool workloads and cannot be converted directly into model weights or the number of safe concurrent agents. The reserve policy is `max(16 GiB, 20% of physical RAM)` (about 24.33 GiB on this host), sampled during owned work. No swap does not by itself make a stopped execution's timing valid.

## What we have learned and what remains

Flash is the only reported configuration with three correct, normal-workflow, timing-valid cells in the completed matrix. Ornith's higher token throughput did not consistently produce correct finished tasks. Qwen3.6 has promising observed throughput and two correct implementations, but an unfinished schedule and an unresolved abort prevent a completed-cohort conclusion. The experiments support these observations, not a fair ranking across changed tools, backends and recovery policies.

Repeated Clojure syntax repair, compaction overhead, repetitive reasoning, output/request budgets and stream bookkeeping are distinct failure modes. Tool installation does not ensure tool use. Structural editing and guard interventions need outcome-based validation; a reload can recover scheduling without repairing a wrong implementation.

Source drift was an additional reproducibility problem: the harness had been untracked and diverged between hosts, while actual runs used frozen deployments. Both project checkouts were synchronized to `4676c62fce4480a364b3b78e9ce3e02ae74c85eb`, with 198 source files SHA-256 verified. This does not retroactively change the frozen harness used by earlier runs or synchronize unrelated local uncommitted editor work.

At that source-sync checkpoint, Python validation discovered 227 tests: local 200 passed / 27 skipped; GX10 215 passed / 12 skipped. The Clojure prototype passed 23 tests / 120 assertions. These are harness checks, not scored model results. This documentation update did not repeat those tests or run model inference.

The evidenced recovered-abort classification gap remains unresolved and still needs focused regression work without weakening unknown-error or cleanup gates. The later standalone Events attempt and Harbor migration are recorded in the latest update above; neither reclassifies the older Ordering abort. A comparable concurrent-subagent benchmark still requires its own completed orchestration run and measured memory budget. The successful migration checks do not establish that result.

## Evidence locations and verification

This update downloaded **492 result/report/status/manifest/checkpoint files** from 28 experiment roots into the local snapshot:

`/Users/pandilyn/Documents/ChatGPT/gx10-benchmark-results/summary-update-20261023/`

The archive is `benchmark-summary-evidence-20261023.tar.gz`, also saved under `/home/lain/experiments/` on GX10. Files preserve their experiment-relative paths. The selected snapshot intentionally excludes source checkouts, model weights, private agent configuration and full Pi streams; original streams remain in each attempt's `artifacts/events.jsonl` and guard logs on GX10. Status checks and the Flash selected-context record were also inspected live.

Primary result references, relative to `/home/lain/experiments/` and the downloaded snapshot:

- `gx10-datalog-benchmark-queue-5/flash-pilot-0f48d36b/cells/{distinct,order,events}/result.json`
- `gx10-datalog-benchmark-queue-5/matrix-a534746b/{summary.json,REPORT.md}` and `cells/*/result.json` (the interrupted context cell may be newer than the generated report).
- `gx10-qwen38-trial-4/solo/{distinct,order,events}/result.json` and `status.json`.
- `gx10-ornith-follow-on-5/ornith-nvfp4-mtp-reload/solo/distinct/result.json`.
- `gx10-ornith-follow-on-6/ornith-nvfp4-mtp-reload/solo/{order,events}/result.json`, Events `attempt-{1,2}/result.json`, and `status.json`.
- `gx10-qwen36-priority-1/qwen36-35b-a3b-nvfp4/solo/{distinct,order}/result.json` and root `status.json`.
- `gx10-qwen36-events-finish-1/events/result.json` (later standalone attempt, inspected live; outside the original selected archive).
- `gx10-distinct-posthoc-evaluation-1/result.json` and `gx10-delegation-python-queue-7/solo/distinct/result.json`.
- `gx10-datalog-flash-context-1/{context-results.json,selected-context.json}` (live-inspected source, outside the selected archive).

Generated follow-on reports inherited the stale heading “Qwen 3.8 27B NVFP4 trial” even for Ornith/Qwen3.6. Their row identities and raw JSON establish which model ran; that heading is not authoritative. Tables above were checked against per-cell records rather than relying on report titles or queue service status alone.

## Historical snapshot retained from 22 October (protocol 5)

The remaining sections preserve the earlier 32K timeout investigation. Their current-run language and proposed next steps refer to that historical checkpoint; the 128K matrix and subsequent trials above supersede it as the latest status. Older raw outcomes remain unchanged.

### Historical queue status

Queue: `/home/lain/experiments/gx10-datalog-benchmark-queue-2`.
Pilot: `flash-pilot-754f64ed`.
The controller has `MainPID=0`, `ActiveState=failed`. Its final failure is the Flash completion/evaluation gate, not an uncaught model response-stream failure. The three coding cells have the terminal outcome `timed_out`. No other configuration was admitted, and the fresh 12-cell matrix did not start. Nothing was committed or pushed by the overnight watcher.

The ownership ledger has no remaining units, runners, groups or outstanding attempt results. Available host RAM recovered to approximately 118.6 GiB at this check. Historical model aliases in the ledger are names, not evidence that those models remain resident.

All 77 deterministic harness checks passed on GX10 before the pilot. The local publication watcher passed five separate safety tests, including committing/pushing only scoped files to a temporary Git remote. Those checks establish tested harness behaviors; they do not establish coding task success.

## Flash capacity and throughput

| Workload | Result |
| --- | --- |
| Synthetic retrieval at 32K, 64K and 128K | All three seeds passed at each size: 9 successful probes |
| Actual input at 128K | 120,682–120,708 prompt tokens; output and rendering space reserved |
| 139,264 tokens and larger boundary candidates | Clean conservative budget declines in this protocol-5 run; not observed OOM |
| Full Pi/plugin smoke at 128K and 64K | RAM reserve crossed; owned processes stopped and cleanup verified |
| Full Pi/plugin smoke at 32K | Passed, normal ending, 10 own assertions and 1 independent assertion, no stream failure or swap delta; 661.33 seconds of coding |

A separate preliminary 32K smoke passed in 404.67 seconds with 5 own assertions and 1 independent assertion. These are different agent executions and are not repeated scored coding measurements.

| Configured context | Backend generation rate across three seeds |
| --- | ---: |
| 32K | 28.2–28.3 tokens/s |
| 64K | About 27.0 tokens/s |
| 128K | 25.4–25.5 tokens/s |

Rates are `eval_count / (eval_duration / 1e9)` from the Ollama context-response counters, including thinking generation. They exclude prompt processing, tools and compaction. Coding records contain token totals and request timestamps, but no separate complete pure-decoding-rate metric. Token totals for timed-out coding attempts cover completed assistant messages and omit unfinished output and some auxiliary work; dividing them by 20 minutes is not a clean inference-speed measurement.

## Flash coding attempts at 32K

All tasks required implementation and tests in two permitted files, thinking, autonomous web search and an official-reference fetch. Attempts had a 1,200-second limit; shell/evaluation commands had 120-second limits.

| Task | Outcome | Completed agent messages | Tool calls | Recorded output tokens | Completed compactions | Compaction seconds / share of deadline |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Stable distinct by key | Timed out | 13 | 16 | 13,797 | 7 | 525.64 / 43.8% |
| Deterministic dependency ordering | Timed out | 15 | 17 | 17,103 | 6 | 394.32 / 32.9% |
| Event projection | Timed out | 16 | 19 | 17,022 | 6 | 433.27 / 36.1% |

The events attempt also began a seventh compaction that did not finish before the deadline. Compaction times above are sums of matched `compaction_start`/`compaction_end` elapsed timestamps in the saved Pi event streams. They are observations, not an estimate inferred from token throughput.

The traces show continued activity and repairs, not a demonstrated infinite-input execution. All three reached an own-test command that reported a syntax error:

- Distinct: EOF while reading its generated test file.
- Ordering: EOF while reading its generated implementation.
- Events: unmatched closing delimiter in its generated test file.

Two shell pipelines nevertheless printed `exit=0`/`EXIT=0`. The harness did not accept that as success: no completed assertion summary was present. All three were still unfinished at the deadline, and the worker skipped its post-agent independent evaluation after the deadline fired. Therefore independent implementation correctness remains unmeasured in these run results. The saved patches permit a separate, bounded retrospective evaluation without changing the original outcomes.

Available RAM during coding stayed above the 24.33 GiB reserve. Distinct and events recorded zero swap-counter deltas; ordering recorded one swapped-in page and no swap-out. The earlier broad statement that all three had no swapping was too strong. Their timing was already invalid/censored because of the deadline.

## What the evidence supports

1. Synthetic context capacity and complete agent capacity require separate measurements. Flash retrieved correctly at 128K, while the complete Pi/plugin smoke passed only at 32K under this reserve policy. This does not establish an absolute hardware maximum or concurrent-agent capacity.
2. Optional backend caches must be explicit in the memory policy. Protocol-4 logs exposed an 8 GiB host prompt cache absent from the estimate. Protocol 5 disables and verifies that optional cache, preserves the KV cache, model artifacts, sampling, thinking and plugins, and adds owned-process/cgroup memory attribution. Successful 32K smokes followed; larger full-agent smokes still hit the guard. The cache was a concrete omitted cost, not a proven explanation for every prior failure.
3. Repeated compaction is a measured large cost at 32K: one third to nearly half of these deadlines. The current compaction reserve is 16K tokens in a 32K context, with a 12,288-token maximum response setting and sizable tool definitions. These settings warrant a controlled configuration experiment; simply extending the deadline would not diagnose the overhead.
4. Agent test construction and repair also failed within the allotted time. The syntax errors and repeated rewrites are observed; algorithmic correctness cannot be inferred from the timeout or token rate.
5. Assertion parsing and explicit outcomes prevented false passes. The deadline and RAM guards stopped owned work, preserved patches and diagnostics, and the queue correctly refused to admit the other models after the unresolved Flash gate.
6. The evaluation path has a measurement limitation: an agent deadline currently prevents the independent evaluator from running. A separately bounded evaluation of preserved snapshots would retain useful correctness evidence while keeping workflow failure and censored timing unchanged.

## Earlier exploratory configurations

The archived protocol-3 snapshot recorded six coding outcomes. Ornith NVFP4/MTP had one full distinct pass, an ordering implementation pass with an unfinished output-budget ending, and an events failure. Ornith Q4 had a distinct implementation pass with a response-stream failure, an unmeasured ordering result, and an events failure involving a structurally recognized infinite-input own test. BF16 was pending in that snapshot. These were different harness/protocol conditions; they cannot be combined with the Flash pilot into a fair ranking.

## Next controlled work

Preserve this run. First evaluate the saved implementations independently with the unchanged finite evaluators and bounded execution, recording new retrospective evidence separately. Then investigate 32K compaction pressure and run one representative coding task with measured compaction, prompt processing, generation and tool time. Keep the reserve, original task contracts, independent evaluators and 20-minute agent deadline. Require complete agent endings and meaningful evaluation before attempting the cross-model rerun gate again. These are proposed next steps, not actions already performed.

## Evidence

- [Final queue status](results/gx10-datalog-benchmark-queue-2/status.json)
- [Pilot coding results](results/gx10-datalog-benchmark-queue-2/flash-pilot-754f64ed/results.json)
- [Context results and backend counters](results/gx10-datalog-benchmark-queue-2/flash-pilot-754f64ed/context-results.json)
- [Full-agent smoke outcomes](results/gx10-datalog-benchmark-queue-2/flash-pilot-754f64ed/smoke-contexts.json)
- [Pilot report](results/gx10-datalog-benchmark-queue-2/flash-pilot-754f64ed/REPORT.md)
- [Queue protocol and launch commands](QUEUE.md)
- [Archived earlier results](PROTOCOL-V3-REPORT.md)

The raw per-task Pi streams remain on GX10 at `<pilot>/cells/<task>/attempt/artifacts/events.jsonl`; syntax-error evidence is in the matching `tool_execution_end` events. The collector retains compact result, evaluation and patch artifacts locally; it intentionally excludes complete checkouts, private agent configuration and web caches.
