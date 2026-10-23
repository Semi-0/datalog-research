# User-requested context cancellation, 2026-10-22

This updates the queue status in ENHANCED-QUEUE.md and VALIDATION-20261022.md. The frozen harness 4 is unchanged.

The user requested stopping the NVFP4 context scan. The suite received SIGINT during the 64K seed 44 setup, recorded an interrupted outcome and performed its normal cleanup. Original queue 5 exited nonzero due the deliberate interruption; it is not marked as a completed benchmark. All 12 durable coding outcomes and completed context probes are preserved in the original run directory.

`/home/lain/experiments/gx10-context-cancel-release-1/` records a separate cancellation receipt. Its owned service took the same host experiment lock, verified the interrupted cell, preserved the 12 coding outcomes and ran Backend.cleanup. The receipt records `cleanup_verified=true` and `benchmark_complete=false`. The service exited successfully.

The waiting queue 2 was stopped. Replacement `gx10-delegation-python-queue-3.service` uses the same frozen harness and waits on the cancellation receipt rather than a falsely completed original benchmark. Its output is `/home/lain/experiments/gx10-delegation-python-queue-3`. Observed stage: `bf16_solo`. It then runs the planned Flash/BF16 delegation test after the existing sandbox admission gate.

At cancellation, no Flash orchestration attempt had started: there were no pilot plan/result artifacts in the scheduled queue directories. Scheduler startup/waiting logs exist in the systemd journal. These are not model planning or orchestration traces. Once the pilot starts, its plan, worker histories, patches, integration activity and independent evaluation are written under queue 3's `pilot/` directory.

Capacity and accuracy remain distinct. Flash's Ollama 128K result does not verify the different NVFP4/vLLM memory layout or concurrent slot budget. A completed NVFP4 64K probe answered incorrectly without a memory stop; that is a retrieval failure, not evidence that allocation failed. The scan is being skipped by user choice, not recorded as a successful NVFP4 128K validation. Existing clean budget checks, actual configuration checks and live RAM reserve guards remain mandatory for any subsequent model load.
