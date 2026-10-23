# Protocol 6: measured 128K coding with progress supervision

This supersedes the protocol-5 20-minute agent cutoff for new runs. Earlier results remain unchanged. The user's requested coding context is 131,072 tokens for every configuration; there is no automatic context downgrade and no overall wall-clock coding deadline. Setup readiness remains bounded separately. Pi's per-request socket-idle bound uses the manifest's 1,800 seconds, allowing long 128K prefill while remaining distinct from an overall agent deadline.

## Execution and supervision

Each Bash call is capped at 120 seconds. Test commands also use the explicit GNU timeout wrapper from the prompt. A command timeout returns failure to the agent, which may repair it and continue. The RAM guard remains `max(16 GiB, 20% physical RAM)`, sampled every 200 ms, and cleanup still covers owned service groups and nested tool processes.

The progress supervisor warns after 600 seconds without qualifying progress, sends a workflow steering message at 900 seconds, and stops at 1,800 seconds without qualifying progress. These are inactivity thresholds, not an overall task deadline. A productive attempt can run longer. Stops are recorded as `stalled`; elapsed time is not evidence of nontermination.

Qualifying observations are a bounded set of 12 novel successful reference/read results, or a new/improved completed Clojure assertion summary. Repeated identical results, token streaming, compaction and file rewrites alone do not reset the clock. Assertion counts are a useful observable heuristic, not a proof that generated tests are semantically good; the unchanged independent evaluator remains authoritative for correctness.

A separate generous ceiling of 128 inference requests or 200,000 generated tokens bounds unattended work. All calls through the agent-local proxy, including compaction, count. Budget stops are `budget_exhausted`, retain the reason and are timing-censored. A completed request can overshoot the token ceiling by at most that response's output allowance; the request ceiling is observed when a request begins.

After a supervisor stop, the worker still attempts bounded independent evaluation while the resource guard remains active. Known nonterminating own tests are not rerun. RAM or infrastructure failures can prevent evaluation; that absence remains explicit. An evaluation timeout is separate from an agent progress stop. Correctness, agent workflow and timing validity remain independent dimensions.

Pi-vigilant remains enabled for continuation/repetition steering. The harness retains one authoritative Bash timeout enforcer. No extra timeout extension is installed.

## Per-request measurements

An agent-local serial HTTP proxy forwards the OpenAI-compatible request and response stream. It does not alter sampling, thinking, model artifacts or tool results. The proxy reads the owned backend's Prometheus counters immediately before and after each inference request, including auxiliary compaction calls, and saves `artifacts/inference-requests.jsonl` and `artifacts/inference-summary.json`.

Recorded data include request count, submitted message count/output allowance, terminal marker, API usage/cache data when provided, first stream event latency, request wall time, processed prompt tokens, prompt-processing seconds, generated tokens and generation seconds. Counter resets or missing metrics are explicit measurement failures. A Flash completion cannot unlock later models without complete measurements.

For llama.cpp/Ollama, generation rate is `tokens_predicted_total` delta divided by `tokens_predicted_seconds_total` delta. Prefill/refill rate uses `prompt_tokens_total / prompt_seconds_total` deltas: actual processed uncached tokens, not total submitted context.

For vLLM, the proxy records generation-token and request decode-time deltas, plus prompt-token and request prefill-time deltas. The vLLM prompt counter includes submitted cached tokens and the decode-time definition excludes prefill/first-token work. These backend definitions and limitations are recorded per request; their prefill rates are not directly interchangeable with llama.cpp's uncached processing rate. The report displays counter/time ratios across completed requests within one attempt, not speed medians across repeated benchmark attempts. Aborted or incomplete requests retain partial observations but are excluded from completed-request speed comparisons.

The worker additionally records completed compaction count, compaction wall time and whether a compaction was interrupted. Agent elapsed time still includes tools, reference lookup and compaction. Setup, load, preload, evaluation, cleanup and transition durations remain separate.

## 128K memory policy

The model weight artifacts and KV-cache precision remain unchanged. Owned Ollama services explicitly use F16 KV caches, disable the optional host prompt cache, cap recurrent-state context checkpoints at one, and request/verify batch and microbatch sizes of 256. Large binary artifact hashing streams 1 MiB blocks instead of allocating the whole binary at once. The explicit Ollama workspace estimate is 3.5 GiB for this bounded batch/checkpoint policy, replacing the old 4 GiB estimate. It is an admission estimate, not measured available RAM or a safety limit; the independent physical RAM guard remains authoritative. These changes target observed memory overhead; they do not establish that 128K will fit every configuration. The runtime reserve is unchanged.

The effective runner command, selected inherited environment variables, requested context, artifact identity and GPU offload are checked and recorded. The metrics endpoint must be available. Backend execution logs are preserved before cleanup. A requested 128K configuration that cannot pass these gates records the failure; coding is not silently run at 32K.

## Queue and continuation

The queue runs fresh deterministic validation, then a Flash smoke at the explicit 128K context and the three Flash coding tasks. It does not repeat the prior synthetic context scan before coding. The older completed context scan is only an initial scheduling prerequisite, not imported capacity evidence under the new backend policy.

After all three Flash attempts have normal complete endings, meaningful independent evaluation and complete inference measurements, the queue performs live validation across the four configurations and all 16 ordered transitions. It reuses the identical verified Flash smoke. Then the matrix retains Flash's three completed cells and runs the nine cells for the other three configurations under the same manifest and frozen harness. Thus the final coding matrix contains exactly 12 cells rather than 15 attempts. Genuine incorrect implementations remain data; unresolved runtime, resource or supervision stops do not unlock the Flash gate.

The existing context scans/refinement run after coding. Clean affordability declines remain explicit and do not enter correctness denominators. Lifecycle or uncertain cleanup failures stop further scheduling. Continuation requires the same manifest and harness hashes, preserves completed cells and uses fresh directories for interrupted attempts.

A detached user service and host locks keep the controller alive across SSH logout. A local watcher collects the compact results, throughput records and diagnostics. The user's conditional commit/push authorization applies only after completion checks pass; unrelated working-tree changes remain outside publication.

## Validation

Run `python3 benchmark/gx10-context/validate.py --output NEW_DIRECTORY` on GX10. Added tests cover progress transitions and evidence limits, a productive run outliving a fixed cutoff, request/token ceilings, runtime steering delivery, transparent streamed forwarding and counter arithmetic, and exact reuse of three Flash cells before nine additional cells. The Linux worker integration test actually stops a running agent process and proves that independent evaluation still executes, with a censored `stalled` outcome and preserved patches. The existing unchanged evaluator fixtures, transition/ownership, stream recovery and plugin integration tests remain required.
