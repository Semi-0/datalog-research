# Current queue: NVFP4 solo, Flash/NVFP4 delegation, then context

User steering on 2026-10-22 supersedes the BF16 selections in earlier queue notes. Both the three solo tasks and bounded delegation workers now use NVFP4. Flash handles planning, assignment, integration and review. Gortex, Clojure LSP/Lens, clj-kondo, pi-vigilant and public web access remain configured for implementors. Temperature remains 1.0 and coding context 128K per request; finite commands/progress/request/token/RAM guards remain active, with no overall coding deadline.

Order:

1. NVFP4: distinct, ordering and events solo cells using the existing suite.run_cell lifecycle and unchanged independent evaluators.
2. Flash/NVFP4 orchestration: Flash plan/unload, NVFP4 two-worker smoke and four bounded workers/unload, Flash integration/evaluation/review/unload.
3. NVFP4 context scan: retain completed probes from original matrix-a534746b; retry interrupted probes in fresh directories and continue the original context sizes and boundary refinement.

Deployment uses a new frozen `gx10-delegation-python-harness-5` snapshot. Main service/output: `gx10-delegation-python-queue-4.service` and `/home/lain/experiments/gx10-delegation-python-queue-4`. The context tail service/output is `gx10-nvfp4-context-tail-1.service` and `/home/lain/experiments/gx10-nvfp4-context-tail-1`.

Queue 3 was stopped after the enhanced solo startup exposed a missing GX10_AGENT_ROOT environment variable required by observe.mjs. No useful BF16 coding attempt was produced. Evidence is retained, not rewritten as a model failure. The shared enhanced configuration now establishes that observer root for standalone and concurrent paths; a focused regression covers it. A runtime_error now stops solo scheduling to avoid repeating infrastructure failures. Fresh retry directories and histories are mandatory.

Original NVFP4 context interruption and verified cleanup remain recorded in CONTEXT-CANCELLATION-20261022.md and `/home/lain/experiments/gx10-context-cancel-release-1/`. Queue 4 uses the existing cancellation release gate plus the standard initial cleanup/host lock. Queue 3 is inactive and its owned backend groups were emptied before retry.

The separate context tail waits for queue 4's service to finish successfully, its durable final checkpoint and an orchestration result. It never loads during solo/delegation work, and refuses continuation after uncertain predecessor failure. The real Bubblewrap permission gate still applies before orchestration; context remains waiting behind it until the earlier scheduled work is resolved. No unconfined worker fallback or global trust/swap changes are introduced.

The NVFP4 delegation loader now also aligns its server temperature default and preload request with the protocol's temperature, rather than retaining the old 0.6 server default. Each Pi request independently verifies temperature 1.0 on the wire.
