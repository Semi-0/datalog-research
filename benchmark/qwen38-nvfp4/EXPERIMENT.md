# Qwen 3.8 27B NVFP4: current-harness reliability trial

User authorized trying Qwen 3.8 27B NVFP4 against the three existing coding tasks and comparing the collected evidence. This adds a model; it does not replace or rewrite earlier results.

## Frozen configuration

- Artifact: `nvidia/Qwen3.8-27B-NVFP4`, revision `482ca0f3832238542f8f5295dde86b5f22711d80`.
- Weights: 21,921,697,280 bytes; NVIDIA's checkpoint mixes NVFP4 MLP/head layers with FP8 attention. This is a dense 27B model.
- Private install: `/home/lain/experiments/qwen38-27b-nvfp4-20261022/model`; all upstream files downloaded from the immutable revision and verified against upstream LFS SHA256 or Git blob identities. Prepared SHA256 manifest is authoritative at load time.
- Existing private vLLM environment; one request, 131,072-token maximum context, 16 GiB explicit FP8 KV allocation. Actual memory remains subject to the unchanged 20%/16 GiB reserve guard and verified cleanup gate.
- Thinking enabled; Pi `high` maps to Qwen's `xhigh` through the model declaration and native Pi `thinkingLevelMap`. Temperature 1.0, top-p 0.95, top-k 20. `qwen3` reasoning parser and documented `qwen3_coder` tool parser.
- MTP disabled for the initial reliability baseline. This is a serving difference from Ornith NVFP4/MTP and must stay visible in comparisons.
- Same Pi 1.3, Vigilant 0.8.1, web access, Lens, linter, clj-kondo, Gortex and pinned cljform profile. The first unscored smoke uses the existing basic smoke runner; all three coding cells use the enhanced profile.
- No overall coding deadline. Existing command/evaluation limits, request/token budgets, meaningful-progress supervision, process ownership and RAM guard remain unchanged.

Sources: [NVIDIA model card](https://huggingface.co/nvidia/Qwen3.8-27B-NVFP4), [vLLM recipe](https://recipes.vllm.ai/Qwen/Qwen3.8-27B). Published examples use other GPUs/concurrency; the checked-in command is the GX10 single-request adaptation, not a claim of prior GX10 validation.

## Reasoning-effort correction after trial 2

Trial 2 loaded the model but failed its first preload request with HTTP 400: `Unexpected reasoning effort high. Supported types are xhigh (default), medium, and low.` No coding cells started. This was a harness compatibility failure; it establishes no model correctness result. Cleanup completed and the original trial remains unchanged.

The pinned model declaration now maps Pi `high` to backend `xhigh`. Preload uses that declaration; Pi model configuration reads the same declaration from the cell's saved manifest. Pi's native `thinkingLevelMap` and explicit sampling parameter agree. A stale server-template default is rejected before loading. Other models retain `high`. Tests validate generated Pi configuration, actual Pi wire payload and the pinned tokenizer's template before GPU startup.

Trial 3 (harness 15) verified successful model loading and the corrected preload, then exposed a smoke-dispatch bug: the Qwen controller passed `distinct` to the smoke-only contract, whose task ID is `smoke`. This failed before Pi started. The call now uses the correct contract ID; a full controller test asserts smoke admission followed by all three coding tasks. Fresh trial 4 uses frozen harness 16 and `/home/lain/experiments/gx10-qwen38-trial-4`, service `gx10-qwen38-trial-4.service`. The anti-repeat protection from protocol 8 remains enabled.

## Sequence and evidence

`model.json` declares the artifact and serving identity. `server-command.json` declares the launch settings. The shared `model_setup.py` now accepts explicit artifact/command paths while retaining the old Ornith defaults. It rejects mismatched served identity/path or MTP enabled for this baseline.

`download.py` prepares the private checkpoint. `run.py` waits for successful verified preparation, acquires the existing host experiment lock, runs an unscored smoke, then fresh Distinct, Ordering and Events attempts through `suite.run_cell`. A failed smoke, infrastructure failure, resource stop or uncertain cleanup stops scheduling. Every completed cell is saved before the next starts. Interrupted retries require a new directory; this controller does not resume histories.

Independent correctness determines a task pass. Workflow, execution outcome, token measurements, timing eligibility and diagnostics remain separate. Unchanged evaluator assertion counts are Distinct 14, Ordering 17 and Events 42.

Trial 1 used Vigilant alone; its loop guardian observes completed tool calls and did not cover Ornith's in-progress repeated-thinking-token failure. At the user's request, protocol 8/trial 2 adds pinned pi-anti-repeat 0.2.1, verifies actual saved-stream replay, and coordinates cancellation with Vigilant. Trial 1 was interrupted during smoke setup before any coding task. Fresh trial 2 uses frozen harness 14; see [streaming protection and validation](../gx10-context/ANTI-REPEAT-20261022.md). This does not establish model stability or task correctness before observing results.

Deployment and launch:

```sh
# Download service uses the frozen preparation snapshot (12).
bash ~/projects/datalog-research/scripts/run-background.sh gx10-qwen38-download-1 -- \
  ~/.local/opt/vllm-ornith-nvfp4/bin/python \
  ~/experiments/gx10-delegation-python-harness-12/benchmark/qwen38-nvfp4/download.py

# Trial uses frozen validated snapshot (13), retaining snapshot 12 untouched.
bash ~/projects/datalog-research/scripts/run-background.sh gx10-qwen38-trial-1 -- \
  /usr/bin/python3 \
  ~/experiments/gx10-delegation-python-harness-13/benchmark/qwen38-nvfp4/run.py \
  --output ~/experiments/gx10-qwen38-trial-1
```

Root artifacts: `manifest.json`, `status.json`, `comparison.json`, `REPORT.md`, `smoke/`, and `solo/{distinct,order,events}/`. Each cell retains logs, model/config verification, frozen source/evaluator hashes, raw inference records, RAM samples and cleanup evidence. A separate `failure.json` explains a stopped controller.

Comparison imports the completed coding records from queue 5's Flash pilot and 12-cell matrix, plus later enhanced/structural attempts when their cell metadata exists. Original classifications are preserved, including correctness true with a budget/workflow failure. The report retains raw-result paths and labels historical cohorts. Harness/tool differences preclude a controlled speed ranking; one attempt cannot establish a speed median or reliable pass rate. Decode and prefill/refill rates use measured token/time sums from completed error-free requests; missing measurements remain unknown. Report version 2 reuses the shared metric aggregation and displays each backend's prefill basis explicitly: vLLM counts submitted prompt tokens including cache hits, whereas Ollama's processed-token count measures uncached work. These prefill rates must not be ranked as equivalent throughput. The running harness 16 remains unchanged; the corrected report renderer can regenerate the final comparison from preserved raw records after the controller exits.

Validation: six focused adapter/report tests, plus the existing lifecycle, delegation and coding-runner suites. Actual results are recorded in `/home/lain/experiments/gx10-qwen38-validation.json`; live smoke and task completion must be read from the trial artifacts, not inferred from a successful deployment.
