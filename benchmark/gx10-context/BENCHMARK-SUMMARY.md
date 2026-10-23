# GX10 benchmark: verified results and lessons so far

Status checked live on GX10 on 22 October 2026 after restoring SSH authentication. This is an incomplete benchmark, with exploratory older runs kept separate. No model ranking or speed comparison between configurations is justified.

## Current run

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
