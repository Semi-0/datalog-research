# Resume the assisted Qwen3.6 Harbor pilot

Paused at the user's explicit request on 24 October 2026. Resume only when the user returns and asks to continue. **No actual Qwen load, Harbor coding smoke or scored three-task cohort has run for this pilot. There is no correctness score.**

The original intent is in the conversation's “Codex-assisted Qwen3.6 concurrent Harbor pilot” plan: Pi 1.3.0 + pi-better-subagents 0.19.1 + Harbor 0.24.0; three separate Harbor task containers, one non-LLM Pi control parent and one Qwen child in each `/app`; one shared pinned NVFP4 Qwen model, MTP off, thinking, temperature 1, three 131072-token contexts. Frozen Codex outlines at most 128 Qwen tokens; no live coaching, shared solutions, evaluator feedback or automatic correctness retries. Preserve 20% physical RAM reserve, 200ms monitoring, experiment lock and verified cleanup. No overall coding deadline; finite commands/request/token/progress budgets remain. Run one unscored integration smoke before exactly one three-task cohort. Keep hidden evaluators unchanged (14/17/42 assertions), report correctness independently from workflow/execution/timing, preserve raw evidence and publish the local benchmark summary. No Flash model is needed.

## Verified state

- Enforcing sandbox fix passed: `gx10-harbor-bwrap-v1 (enforce)`, seccomp active, UID 1000, all capability sets zero and no-new-privileges. Inside writes/renames/deletes worked; absolute/relative/symlink/shell writes outside `/app` were rejected. See `SANDBOX-FIX.md` and `evidence/enforcing-admission-1-summary.json`.
- Real required-tool admission passed **inside confinement**: structural form discovery/editing, clj-kondo, Lens Clojure LSP navigation/diagnostics, DuckDuckGo search, official reference fetching and private Gortex indexing/source reads. An outside structural edit was rejected and the parent sentinel stayed unchanged.
- Tool admission receipt: GX10 `/home/lain/experiments/gx10-assisted-tool-admission-3/result.json`; outcome `tools_verified`, cleanup verified, zero model requests. Reusable image: `gx10-assisted-tools-10238f52414d`. Full build/probe logs and binary hashes are in that directory.
- Outlines were frozen **before any actual inference** using the pinned Qwen tokenizer: Distinct 62, Ordering 68, Events 72 tokens. Authoritative frozen receipt: `/home/lain/experiments/gx10-assisted-frozen-outlines-1.json`. It contains exact public contracts, token IDs, tokenizer hash and task/evaluator file hashes. Local source: `outlines.json`. Do not rewrite/refreeze hints based on later results.
- Latest focused checks actually run: **20 Python tests and 10 Node tests passed**. Earlier relevant Harbor pilot checks: 14 tests passed. Selected new Python modules compiled; the complete orchestration has not been exercised end to end.
- Real Pi/plugin **synthetic, no-inference** fixtures passed:
  - `/home/lain/experiments/gx10-assisted-control-fixture-3/result.json`: normal completion, one synthetic worker response, zero parent model requests, durable transcript, empty registry, cleanup verified.
  - `/home/lain/experiments/gx10-assisted-control-auth-1/result.json`: real 401 surfaced as `authentication_error`, despite the plugin reporting lifecycle completion and exit 0; zero parent model requests, cleanup verified.
  - `/home/lain/experiments/gx10-assisted-control-interrupt-1/result.json`: interruption surfaced as failure, child ownership cleared, zero parent model requests, cleanup verified.
- A partial-launch fixture verifies durable ownership, rejection of uncertain receipt reuse and cleanup. Execution tests reject false exit-zero success, missing output, aborted/truncated responses and budget exhaustion.
- Last live inspection before saving: **no Docker containers were running and no assisted backend/attempt service was running**. The only running GX10 services were the unrelated pre-existing `gx10-langgraph-api`, `gx10-lgview-lan`, and `gx10-lgview`; preserve them.

## Files and deployment

All current local work is saved under `benchmark/harbor-assisted/` in the datalog research workspace. These files are **untracked**, not committed or pushed. Preserve unrelated working-tree edits. HEAD at checkpoint inspection: `aa5cf06448f6965df684664fa61c731e7d8df3e9` (this is not a commit of the pilot).

Main pieces:

- `admission.py`, `security.py`, `gx10-harbor-bwrap-v1.apparmor`: initial sandbox admission and scoped policies.
- `toolbox.py`, `runtime-bwrap.py`, `run-tool-probe.mjs`, `tool-probe.mjs`, `tool-environment.ts`: reproducible tool image, private runtime and actual tool probes.
- `outlines.json`, `freeze.py`: frozen public-contract assistance and tokenizer measurement.
- `companion.ts`: delegates via the existing `../gx10-langgraph/control.mjs`; publishes only admitted trusted tools, forbids parent inference, disables cache warming.
- `parent.mjs`, `execution.mjs`: deterministic SDK control using `/gx10-control`, one plugin child, durable collection/transcript, progress supervision and independent execution classification.
- `inference_proxy.py`: per-worker 128-request/200000-generated-token budgets (including compaction), first-request barrier, sampling policy and stream timing. Missing usage closes the budget and marks usage incomplete rather than inventing 200000 consumed tokens.
- `harbor_agent.py`: custom Harbor BaseAgent adapter, public-contract hash matching and selected frozen hint only; no overall coding timeout.
- `backend.py`: **implemented but not live-tested** three-sequence backend admission/load using the existing GX10 lifecycle guard.
- `cohort.py`, `pilot.py`: **implemented but not end-to-end verified** staging, smoke/cohort orchestration, native trials, overlap metrics, grading and timing collection. Review the remaining points below before launching.

GX10:

- Host/user: `lain@gx10-ac8b.local`.
- Existing SSH control socket: `/tmp/codex-gx10-ac8b-20261020.sock`. Verify it still works; if expired, keep password entry private and user-controlled.
- Main deployed code mirror: `/home/lain/experiments/gx10-assisted-pilot-code-1/benchmark/harbor-assisted/`. **It is not fully synchronized with the latest local files.** Sync current sources before resuming; do not assume newly written `pilot.py`, `backend.py`, `cohort.py` or later edits are deployed.
- Its companion dependency is `/home/lain/experiments/gx10-assisted-pilot-code-1/benchmark/gx10-langgraph/control.mjs`, copied unchanged from the existing local controller.
- Earlier flat sandbox setup: `/home/lain/experiments/gx10-assisted-admission-code-1/`. Pinned seccomp base: `docker-seccomp-base.json` there, SHA256 `6416b47770785a41ac59073cdc77d9fe98517df2799dc83ef207e622de3053f6`.
- Protected scored task root: `/home/lain/experiments/gx10-harbor-tasks-v1-protected/{distinct,order,events}`.
- Unscored four-assertion smoke source: `/home/lain/experiments/harbor-pilot-20261023`.
- Existing lifecycle source defaults to `/home/lain/projects/KIROSHI/benchmark/gx10-context/`, with its `scripts/run-background.sh` launcher. Verify and preserve that harness; do not overwrite unrelated remote changes.
- Durable local backup: `benchmark/harbor-assisted-checkpoint-20261024.tar.gz`, with checksum/provenance in the adjacent `.json` manifest. It contains this source folder plus the unchanged controller dependency. Restore into a fresh staging directory; do not overwrite unrelated/newer workspace changes.
- The attempted GX10 backup transfer to `/home/lain/experiments/gx10-assisted-checkpoint-20261024.tar.gz` **failed when the SSH master connection broke** (`Permission denied (publickey,password)`). Treat any remote file there as unverified/possibly partial. The control connection now needs private user-controlled re-authentication before further remote work. Do not ask for passwords in chat. Local source, archive and this document are authoritative.

## Important implementation decisions and gotchas

1. The user loaded the **new named enforcing AppArmor profile** with sudo. No global sysctl or Docker daemon security setting changed. It is loaded transiently, not installed under `/etc`; after a reboot it may need loading again. Avoid privileged containers, SYS_ADMIN, unconfined final profiles, or globally disabled seccomp/AppArmor.
2. Native GraalVM tools need `/proc`, and networking needs resolver/CA files. The runtime adapter adds only fixed **read-only** mounts: `/proc`, `/etc/resolv.conf`, `/etc/hosts`, `/etc/nsswitch.conf`, `/etc/ssl/certs`. Docker supplies the task's PID namespace; host PIDs are not exposed. The pinned plugin source is unchanged. Its fixed `/usr/bin/bwrap` entry delegates to the original binary at `/usr/local/lib/pilot/bwrap-real` through this adapter.
3. Required runtimes are relocated under `/usr/local/lib/pilot` because outsideProject=off hides `/opt`. Java/Clojure/Maven are made available read-only; native tool binaries use their copied compatible loader/libraries. Clojure's launcher overrides only the infrastructure Maven cache path, not task source/dependencies.
4. The plugin requires the **matching active Pi SDK CLI on PATH**. Include `/usr/local/lib/pilot/node_modules/.bin`. The admitted image was built before that PATH fix. The normal/auth/interruption fixtures override PATH and passed. `toolbox.py` and the derived-task Dockerfile in `cohort.stage_task` now include the fix; verify it in the actual Harbor smoke.
5. **Do not drop all capabilities on the verifier container.** The unchanged protected `tests/test.sh` needs root `chown`/grader setup. Instead, the adapter starts the agent with root `setpriv --reuid=agent --regid=agent --init-groups --bounding-set=-all --no-new-privs`. `parent.mjs` checks UID/profile/NoNewPrivs/CapEff/CapBnd before proceeding. Agent tools were already proven under cap-drop ALL in isolated admission containers.
6. Parent control is model-free, `callback:false`, `allow_nested:false`, one child per parent, and cache warming is disabled. Each child receives only its own public contract and frozen outline. Gortex/tool state is private under that child's runtime; no prior experiment histories/solutions are mounted.
7. Synthetic fixture results are not model benchmark outcomes. In particular, plugin exit zero is insufficient: the actual auth fixture demonstrated why independent assistant-message checks are required.

## Resume sequence and unfinished review

Do **not** start by rerunning the whole pilot blindly.

1. Verify SSH, Docker, the enforcing profile and absence of conflicting inference processes; preserve unrelated services. Read this checkpoint and the original user plan. Use Gortex MCP for indexed source as required by AGENTS.md.
2. Review and finish the new orchestration/reporting code. Known unfinished items:
   - `cohort.run_phase` currently saves/returns its phase result before the `finally` cleanup has finished. Include final cleanup success/failure and cleanup end time in acceptance and total batch wall time. Avoid a phase result claiming acceptance if cleanup fails.
   - `pilot.run` performs some admission/frozen-input validation before its main try/finally; make preflight failures durable/inspectable without implying resources were started or cleaned up.
   - Verify native Harbor trial/artifact paths, native phase timestamps and parser assumptions with the unscored smoke. Preserve final source artifacts/hashes and verify allowed-file scope independently where needed. The current `trial_result` collector has not run on an actual new Harbor trial.
   - Ensure one malformed/missing evaluator record does not fabricate a 0/3 score or erase the other inspectable trials; keep unknown correctness explicit.
   - Verify fresh `/metrics` supplies `vllm:num_requests_running`/waiting before releasing scored workers. Proxy request overlap alone includes queuing; require actual backend-running overlap.
   - Review worker timing extraction and label stopped/budget-exhausted durations as time until stopping, not successful completion times.
   - Review newly written code for correctness, bounds, failure preservation and the user's plain-control-flow/style rules; some larger functions remain review signals.
3. Sync the current local sources to the mirrored GX10 code directory. The immutable frozen outline receipt must remain unchanged. Re-run the focused tests after any changes.
4. Keep the existing successful synthetic receipts, or rerun only synthetic/infrastructure checks if changed code warrants it. These are no-inference tests, not correctness retries.
5. Verify the new `backend.py` against the installed lifecycle/launcher. Retain artifact revision `1355db6a052410cfd62085d94b58866fd0f2c3c5`, 131072 context and max-num-seqs=3. Fresh capacity proof is still required. The old single-sequence log reported 1519675 KV tokens (~11.59 full contexts), which is encouraging historical evidence, not the fresh three-sequence admission.
6. Once the above is ready, use `pilot.py --help` for its required receipt arguments. Inputs are the tool admission 3 receipt, normal fixture 3 receipt, auth fixture 1 receipt, interrupt fixture 1 receipt, the frozen outline receipt, and the pinned seccomp base. Choose a fresh output directory. No pilot output directory has yet been launched.
7. Under the experiment lock and 200ms RAM guard: verify pinned artifact hashes, load one three-sequence backend, establish three full contexts including container/parent/worker/tool overhead, warm it, run the one-worker unscored Harbor smoke, and only if admitted run the single scored three-task cohort. Do not silently reduce context/concurrency or rerun wrong solutions.
8. Wait for all native Harbor trials, independently verify cleanup, preserve transcripts/hints/source hashes, record actual inference overlap, and publish results/timing definitions in `BENCHMARK-SUMMARY.md`. This three-task Clojure pilot is descriptive, not a statistically reliable correctness estimate.

Useful focused checks from the repo root:

```sh
python3 -m unittest discover -s benchmark/harbor-assisted -p 'test_*.py'
node --test benchmark/harbor-assisted/test_execution.mjs benchmark/harbor-assisted/test_partial_launch.mjs benchmark/gx10-langgraph/test_control.mjs
```

No commit, push, model load, smoke or scored run was authorized as part of this pause/save step. Resume the already-authorized pilot only when the user returns.
