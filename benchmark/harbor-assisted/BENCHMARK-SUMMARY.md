# Codex-assisted Qwen3.6 concurrent Harbor pilot

**Paused by the user, 24 October 2026:** [resume checkpoint and remaining work](RESUME.md). The sandbox and real required-tool admission passed; outlines are frozen at 62/68/72 Qwen tokens; normal, authentication-error and interruption control fixtures passed without inference. Latest focused checks: 20 Python and 10 Node tests passed. The adapter/orchestration code is saved but not verified end to end. No actual Qwen load, Harbor coding smoke or scored cohort has run. Last inspection found no running pilot containers or backend/attempt services.

**Sandbox blocker resolved:** the exact plugin confinement probe passed with `gx10-harbor-bwrap-v1 (enforce)`, active seccomp, zero container capabilities and no-new-privileges. Inside write/rename/delete succeeded; all four tested outside-write routes were rejected. Container/image cleanup was verified. See the [evidence excerpt](evidence/enforcing-admission-1-summary.json) and [policy details](SANDBOX-FIX.md). No model request or cohort ran; this verifies the sandbox prerequisite only. The 13 focused tests previously passed locally and on GX10. The original failed admission below remains historical evidence.

Initial status on 24 October 2026: **setup declined at the mandatory container sandbox gate** (subsequently resolved above). No assisted smoke, scored cohort, model loading or inference request ran. Correctness is **not evaluated**; this is not a 0/3 result. Solo results remain separate.

The exact `pi-better-subagents 0.19.1` sandbox wrapper failed inside a Docker container built from the protected Harbor Distinct task environment, using the task's `agent` user, `/app`, host networking, default Docker security, two CPUs and 2 GiB RAM. Bubblewrap exited 1 with `No permissions to create new namespace`. This establishes a sandbox startup failure in this configuration; it does not identify which host kernel, AppArmor or Docker restriction caused it. The probe did not reach its inside-write/outside-write checks.

Per the approved plan, this failure declines setup. No privileges, Docker security options or global configuration were changed to bypass it. Trusted tools were never enabled. The task scaffold, public contracts and hidden evaluators were not edited.

## Evidence and reproduction

- [Raw admission result](evidence/admission-2-result.json): exact wrapper argv, error, plugin/core/probe/task Dockerfile hashes, monotonic timestamps, zero inference requests and verified container/image absence.
- Full GX10 evidence: `/home/lain/experiments/gx10-assisted-admission-2/` (`build.json`, `launch.json`, `sandbox.json`, `container-removal.json`, `image-removal.json`, `result.json`).
- [Admission runner](admission.py) and [exact-plugin probe](sandbox-probe.mjs). The runner acquires the existing experiment lock, creates uniquely owned resources, persists failed gates and cleans up only those resources. It uses finite setup command timeouts and introduces no coding deadline or correctness retry.
- An initial probe also failed with the same namespace error. The second probe places the child executable under `/usr/local/bin`, which the plugin exposes read-only inside its sandbox, and explicitly verifies image removal.

On GX10, with these files in the current directory:

```sh
python3 admission.py \
  --task /home/lain/experiments/gx10-harbor-tasks-v1-protected/distinct \
  --node /home/lain/.local/opt/node-v24.22.1-linux-arm64 \
  --plugin /home/lain/experiments/gx10-langgraph-install-1/packages/node_modules/pi-better-subagents \
  --output /home/lain/experiments/gx10-assisted-admission-NEW
```

Use a fresh output path. Exit 1 means declined/interrupted/uncertain; consult `result.json`. Exit 0 means only the sandbox probe passed, never that the whole experiment is admitted. The expected Pi/Harbor versions in the receipt are configuration requirements; this probe directly checks the plugin version only. Harbor 0.24.0 was separately observed on GX10.

## Verification performed

- `python3 -m unittest discover -s benchmark/harbor-assisted -p 'test_*.py'`: 7 tests passed. Covers failed confinement, false exit-zero success, interrupted admission, uncertain cleanup, plugin mismatch and command timeout reporting.
- `node --test benchmark/gx10-langgraph/test_control.mjs`: 5 tests passed, including model-free parent control, durable collection, missing tools and uncertain operations.
- `python3 -m unittest discover -s benchmark/harbor-pilot -p 'test_*.py'`: 14 tests passed, including authentication errors, aborted/incomplete responses and cleanup selection.
- Live exact-plugin container sandbox: failed, twice. Final container and image cleanup: verified.

## Timing and acceptance boundary

The final admission lasted 1.326 seconds from `started_monotonic_ns` to `finished_monotonic_ns`, including 0.070 seconds of cleanup. These are setup-to-decline durations, not coding or successful completion times. No model-load, warm-up, worker, verification, token, peak-resource or backend-overlap measurements exist for this cohort. No three-worker capacity claim is established.

With the sandbox prerequisite now resolved, remaining implementation and live verification are: the Harbor custom adapter, three-request backend admission, container required-tool checks, tokenizer-measured frozen outlines, readiness barrier, unscored one-worker smoke and scored cohort. The original required assertion counts remain Distinct 14, Ordering 17 and Events 42; no evaluator was run by this admission check. Do not publish a 0/3–3/3 score until all three independent Harbor trial records exist.

When the sandbox prerequisite is resolved, retain Pi 1.3.0, plugin 0.19.1, Harbor 0.24.0, the pinned Qwen3.6 NVFP4 artifact, MTP off, thinking, temperature 1.0, three 131,072-token contexts, the RAM reserve, 200 ms supervision and verified cleanup. Admission must account for container/parent/worker/tool overhead. Required tools must pass confinement checks before trust. Parent control must issue zero model requests, disable callbacks/nested delegation/cache warming, and keep each parent alive through durable result collection. Hints must be frozen from public contracts and measured at at most 128 Qwen tokens before any worker starts.

Future timing evidence must retain Harbor native timestamps and supplement them with supervisor/plugin monotonic intervals for shared loading/warm-up, each worker's startup/readiness/coding/collection, each verifier/cleanup and total batch wall time. Measure overlapping backend requests rather than inferring concurrency from worker launches. Report correctness, compliance, execution outcome and timing validity independently; interrupted or budget-exhausted intervals end at stopping. This three-task pilot remains descriptive.
