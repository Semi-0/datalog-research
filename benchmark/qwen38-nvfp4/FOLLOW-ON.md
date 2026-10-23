# Ornith follow-on comparison, 2026-10-22

Finish the active Qwen3.8-27B NVFP4 trial before admitting another model. The requested comparison is task completion reliability and time as evidence for a later orchestration decision. These solo tasks do not directly measure planning, delegation, or integration ability.

User-confirmed schedule: only Ornith NVFP4 with MTP acceleration after Qwen. Run Distinct, Ordering (`order`), and Events, one attempt per task. The initial two-cohort queue was stopped while waiting; no Ornith attempt or model load had started. Q4_K_M is excluded.

All runs retain the shared lifecycle, enhanced Pi tool profile, structural editing, Gortex, Clojure LSP, linter, web access, Vigilant, anti-repeat 0.2.1, independent evaluators, correctness-based acceptance, 128K context capacity, temperature 1.0, and existing tool/request/resource budgets. There is no overall coding deadline. Record backend differences: GGUF uses Ollama; NVFP4 uses vLLM, FP8 KV cache and MTP. Qwen uses its required xhigh reasoning mapping; Ornith retains high.

The queue waits until the predecessor service has exited successfully, its status records three completed coding cells, and each cell has a durable result and complete cleanup checkpoint. It then takes the host experiment lock and calls the same `suite.run_cell` used by Qwen. Shared transition verification still runs before each load. Missing results, unresolved infrastructure/resource/interruption outcomes, or incomplete cleanup stop scheduling. A task correctness failure can proceed after clean cleanup. Each cohort starts with an unscored smoke test. Results are saved before advancing. A stopped queue is inspected before any retry; retries require new directories.

## Deployed queue

- Predecessor: `/home/lain/experiments/gx10-qwen38-trial-4`, service `gx10-qwen38-trial-4.service`, frozen harness 16.
- Follow-on: `/home/lain/experiments/gx10-ornith-follow-on-3`, service `gx10-ornith-follow-on-3.service`.
- Declared plan: `/home/lain/experiments/gx10-ornith-follow-on-plan-3.json`.
- Frozen follow-on harness: `/home/lain/experiments/gx10-delegation-python-harness-17`.
- Deployment receipt: `follow-on-deployment.json` in that snapshot records the parent snapshot, plan hash and 294 benchmark file hashes.
- Background launcher verified `Linger=yes`; invocation `be2549e676e04869b8f4465e658f5476`. Plan 3 supersedes plans 1 and 2. The frozen snapshot receipt seals plan 1. Plan 2 removes GGUF; plan 3 selects the existing frozen weight-only artifact manifest. Both earlier queues stopped while waiting, before any model load.

Harness 16 remains unchanged. Harness 17 copies its execution harness and adds the sequential queue, tests, temperature-1.0 Ornith MTP command, and corrected report renderer. Report version 2 uses completed error-free requests and labels cached prefill/refill explicitly. Neither raw results nor historical classifications are rewritten.

```sh
systemctl --user status gx10-ornith-follow-on-3.service
cat /home/lain/experiments/gx10-ornith-follow-on-3/status.json
journalctl --user -u gx10-ornith-follow-on-3.service -f
```

For a new declared plan and fresh output directory, the launch form is:

```sh
bash /home/lain/projects/datalog-research/scripts/run-background.sh NEW_UNIT -- \
  /usr/bin/python3 /home/lain/experiments/gx10-delegation-python-harness-17/benchmark/qwen38-nvfp4/follow_on.py \
  --plan NEW_PLAN_JSON --output NEW_DIRECTORY
```

## Verification and interpretation

Local and GX10 runs of the Qwen directory's unittest suite each executed 17 tests: 16 passed, one opt-in real-Pi payload test skipped. Five queue tests verify waiting for service exit, missing/incomplete result rejection, shared lifecycle dispatch, preservation of ordinary task failures, and stopping after infrastructure/resource failure. Existing setup and report checks also pass. The actual Ornith setup path was also exercised without launching inference: previously verified hashes were supplied by a test double, and the launch boundary was intercepted after confirming the artifact manifest, MTP, served identity, temperature 1.0 and 128K arguments. Evidence: `/home/lain/experiments/gx10-ornith-follow-on-config-validation.json`. Live queued cohorts have not yet passed validation; live weight hashing, smoke and readiness/configuration checks remain mandatory.

Compare independent correctness first (14/17/42 assertions). Keep workflow and timing validity separate. Compare execution and total durations with loading, preloading, evaluation and cleanup visible separately. Retain generated-token counts, decode rate and refill basis. Do not rank vLLM cached submitted-token rates against Ollama uncached processed-token rates. Single attempts provide observations rather than speed medians. Quantization, serving and MTP differences mean this is a deployed-configuration comparison, not a controlled causal measurement of quantization alone.
