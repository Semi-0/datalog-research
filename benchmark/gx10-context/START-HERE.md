# Start here: GX10 benchmark progress

> Historical progress log. Statements about running queues below describe older
> snapshots. Use [the source entry point](../README.md) and
> [the 23 October queue audit](../QUEUE-AUDIT-20261023.md) for the current source
> organization and the most recently inspected stopping point.

Current work: [protocol 6: measured 128K coding with progress supervision](PROTOCOL-6.md). Queue `gx10-datalog-benchmark-queue-5.service` is running with an explicit 128K default, no overall agent wall-clock deadline, 120-second command/evaluation limits, and unchanged physical RAM reserve. Progress supervision warns/steers/stops at 10/15/30 minutes without qualifying progress; request and generated-token budgets provide a final bound. All 91 deterministic checks passed on GX10 without skips.

The run first attempts a full 128K Flash smoke and the three coding tasks, with no context downgrade. Complete Flash outcomes and measured inference counters admit the other three configurations. The final matrix retains those three Flash cells and runs nine additional coding cells; generation and prefill/refill throughput are recorded per request, including compaction calls. The new run is incomplete until its durable final status says complete.

## Preserved queue-2 findings

Live recheck: `gx10-datalog-benchmark-queue-2.service` is stopped with a failed Flash completion/evaluation gate. All three Flash coding cells reached their 20-minute deadline. The agent traces show substantial compaction overhead and syntax-error repair; no completed independent evaluation was recorded for those coding cells. The other configurations were not admitted, and the fresh 12-cell matrix did not start. Cleanup completed and the ownership ledger contains no live owned services or runners.

Read the [verified result summary and lessons](BENCHMARK-SUMMARY.md). The watcher downloaded [the final queue evidence](results/gx10-datalog-benchmark-queue-2/status.json) and stopped without committing or pushing. The benchmark remains incomplete. Synthetic 128K retrieval success is separate from full-agent 32K smoke success.

## Archived protocol-3 snapshot

The material below is a downloaded snapshot from GX10 on 21 October 2026, around 23:18 Amsterdam time. Its launcher is stopped; these exploratory results do not update automatically.

**Six of the twelve earlier coding outcomes were recorded.**

Each model attempts three Clojure tasks: remove duplicates while preserving order (`distinct`), order dependencies (`order`), and apply a sequence of events (`events`).

| Configuration | Task | Implementation | Overall outcome and reason |
| --- | --- | --- | --- |
| Ornith NVFP4 + MTP | distinct | Passed | Full pass |
| Ornith NVFP4 + MTP | order | Passed | Output token limit; agent workflow unfinished |
| Ornith NVFP4 + MTP | events | Failed | Incorrect implementation and output token limit |
| Ornith Q4 | distinct | Passed | Response streams ended without a finish reason; workflow unfinished |
| Ornith Q4 | order | Unmeasured | No completed evaluator assertion summary; output token limit |
| Ornith Q4 | events | Failed | Agent's own test used an infinite input; stopped with structural evidence |
| Ornith BF16 | all three | Pending | Awaiting the new live validation gate |
| Flash IQ4 | all three | Pending | Awaiting the new live validation gate |

“Implementation passed” means the unchanged independent evaluator passed. A full pass also needs a completed agent workflow, its own tests with real assertions, thinking/web evidence, and allowed file changes. Three implementations passed so far; only one achieved a full pass. These partial results do not establish a model ranking.

The Q4 events test placed an infinite sequence before the invalid event it expected to reach. Source evidence, the active command, and a JVM stack justified stopping it. That own test was not rerun. A separate finite independent evaluation finished with 42 assertions and one failure. Its elapsed time is censored and cannot establish comparative speed.

The corrected harness passed 45 deterministic tests on GX10. The previous version also passed all 16 live model transitions. After a context-refinement scheduling fix, fresh live validation is running. A detached controller will continue the six pending coding cells only if that gate passes, then run the context scans. There are no context results yet.

## Read further

- [Detailed results and phase timings](PROTOCOL-V3-REPORT.md)
- [Validation, changes, and continuation evidence](VALIDATION.md)
- [Protocol and commands](EXPERIMENT.md)
- [Machine-readable six-cell summary](results/gx10-v3-scored-1-summary.json)
- [Latest deterministic validation receipt](results/gx10-v3-deterministic-6/deterministic.json)
- [Raw deterministic test output](results/gx10-v3-deterministic-6/gx10-context.txt)

The downloaded evidence is local. Full per-attempt checkouts, patches, reasoning histories, process snapshots and lifecycle traces remain on GX10; this download contains the report and compact evidence. Earlier exploratory results remain separate.
