# Ornith NVFP4 with MTP: isolated coding experiment

Authorized 2026-10-21: use a separate vLLM environment, enable MTP, and run coding
before maximum-context measurements. **No MTP-off comparison. No coding
wall-clock cutoff.** All coding configurations use 32K and thinking enabled.
This is an additional configuration; the Ollama artifacts remain intact.

## Artifact and runtime

- Official checkpoint: `ornith-ai/Ornith-1.5-35B-A3B-NVFP4`.
- Pinned revision: `94e431d9cc47fa1986a7a1a4e9a80f7f118b03aa`.
- Three safetensors shards total **23,420,419,700 bytes**. This checkpoint uses
  mixed precision including NVFP4; its name does not mean every tensor is FP4.
- Target runtime: native **vLLM 0.28.0**, installed in a dedicated user virtual
  environment. Installed package versions and actual launch arguments must be
  saved before scoring. No system Python packages or Docker permissions change.
- GPU verified on GX10: GB10, compute capability 12.1; CUDA toolkit 13.0.3.
- Loopback endpoint separate from Ollama. One sequence/request at a time.
- Base MoE backend: Marlin; attention and GDN prefill: Triton. MTP draft MoE:
  FlashInfer CUTLASS, speculative method `mtp`, one speculative token (K=1).
- Thinking enabled/high, `qwen3` reasoning parser, `qwen3_xml` tool parser.
  Temperature 0.6, top_p 0.95, top_k 20, min_p 0, repetition penalty 1,
  presence penalty 0, seed 42. Response allowance 8192 tokens.
- Context exactly 32768 in server and Pi, compaction reserve 8192. KV cache
  precision is a separately recorded runtime choice (initial target FP8 E4M3).

The launch profile is based on a published GB10 recipe, not on a locally
verified speed claim. Validate argument availability against the installed
version. Record all adjustments and errors. Do not silently disable MTP or
replace the checkpoint/head to obtain a passing result. Confirm actual draft
proposals/acceptance via runtime logs or metrics, not just a command-line flag.

## Execution and resource boundaries

Check disk and physical-memory budget before downloads, and available RAM
before model loading. Verify every shard's SHA-256 against the pinned metadata.
Unload the previous benchmark model before starting this server. Keep at least
26,118,804,275 bytes available (20% of GX10 physical RAM). A watchdog must run
during startup, inference, and agent tools, and stop only this experiment's
processes if the reserve is crossed. Do not change global swap settings.

Start with one sequence and a conservative cache allocation, not the recipe's
16-sequence/262K allocation. Actual maximum context will be measured later,
after coding. System swap transfers invalidate timing, not the saved correctness
outcome. Distinguish a resource stop, transport failure, unfinished task, and
semantic failure. Never declare success merely because Pi exits zero.

Each coding task gets a newly generated Pi config, session directory, checkout,
temporary directory, and web cache. No sessions are imported or resumed and no
previous solutions or results are supplied. The unchanged task contracts,
prompts, and evaluators are documented in
[../gx10-context/CODING-TASKS.md](../gx10-context/CODING-TASKS.md). Remove the
historical prompt's ten-minute sentence for this new run and save that exact
revised prompt separately; historical prompts remain unchanged.

First run stable-distinct-by. Thinking and autonomous DuckDuckGo research are
enabled. The agent must choose and successfully fetch its own official source.
No source URL or solution is supplied by the supervisor. Run agent-written and
independent tests and inspect the changed-file list after the final response.
Record elapsed time without a session deadline; do not terminate merely because
the earlier ten-minute budget has elapsed. Memory/resource stops remain active.

Save command/config fingerprints, checkpoint hashes, dependency versions,
startup logs, MTP acceptance metrics, timestamped Pi events, final patch,
independent results, peak memory, and swap deltas. Evaluate task completion
and protocol compliance separately. A runtime switch and a new quantization
mean this is not a controlled BF16-versus-Q4 comparison.

## Flash-Next NVFP4 feasibility (not a launch authorization)

Metadata inspection found these safetensors totals:

| Publisher | Revision | Bytes |
| --- | --- | ---: |
| NVIDIA | `fc694b54fb0174e0913e6adf86691ef85a4ead47` | 132,680,249,378 |
| RadixArk | `7b719225242aacd3dbd3f9407468c2ee9a9d2594` | 135,195,303,851 |
| Inferact | `103a7608316173ca6edd49929544244de7ffda70` | 182,779,284,200 |

These artifact totals fail our conservative resident-weight preflight against
GX10's 104,475,217,101-byte budget after the RAM reserve. File size is not a
measurement of exact runtime allocation; this does not establish impossibility
under repacking, partial loading, or disk offload. CPU offload alone does not
add memory on GB10's unified-memory machine. Do not download these builds,
enable disk swap/offload, prune the model, or substitute a derivative under the
existing context protocol. Keep the installed IQ4_XS artifact for its requested
32K preflight and coding test. Its 32K fit is still unverified.

## Sources inspected

- [Official Ornith NVFP4 checkpoint](https://huggingface.co/ornith-ai/Ornith-1.5-35B-A3B-NVFP4)
- [GB10 recipe and launch profiles](https://github.com/sojufx/Ornith-1.5-35B-A3B-NVFP4-DGX-Spark)
- [vLLM GPU installation](https://docs.vllm.ai/en/latest/getting_started/installation/gpu/)
- [Official NVIDIA Flash-Next NVFP4 checkpoint](https://huggingface.co/nvidia/Qwen3.8-Flash-Next-NVFP4)
- [NVIDIA's Flash-Next deployment recipe](https://docs.nvidia.com/dynamo/dev/recipes/qwen-3-8-flash-next)

Status at initial publication: dependencies/checkpoint being downloaded;
no NVFP4 inference or capability result yet. Append observed outcomes only.

## Runner and checks

`prepare_server.py --experiment REMOTE_DIRECTORY` verifies the pinned weight
hashes, records a 32K/MTP launch, and unloads only previous `gx10-*` benchmark
models. It does not start inference. `resource_guard.py --command FILE --output
DIRECTORY` runs that experiment-owned server with a RAM watchdog. Initial KV
cache allocation is 4 GiB, one sequence; record CLI compatibility adjustments.

After server readiness/preload, run a task with:

```sh
python3 benchmark/ornith-nvfp4-mtp/coding.py \
  --task distinct --output /absolute/path/to/a/new/attempt
```

The output directory must not exist. `--task order` and `--task events` select
the other documented tasks. The source checkout and pinned web package must
already exist on GX10. The runner generates minimal fresh Pi configuration,
disables discovery, preserves exact revised prompts, and records events,
memory, errors, actual elapsed time, test results, and patches. No session
timer or resume path exists. An agent ending with a stream error cannot be
counted as protocol success even if its process exits zero.

Focused verification: `python3 benchmark/ornith-nvfp4-mtp/test_runner.py` tests
non-inheritance of previous history/credentials, refusal to reuse an attempt
directory, and termination of the guard's own child on a simulated RAM breach.
These tests do not establish GPU/runtime compatibility.

## Observed setup before scoring

The pinned checkpoint's three shards passed SHA-256 verification. vLLM 0.28.0
and dependencies installed in the dedicated user environment; the exact freeze
is `installed-requirements.txt`. The first startup failed because system Python
development headers were missing. `libpython3.12-dev` version
`3.12.3-1ubuntu0.17` was downloaded from Ubuntu and extracted under the remote
experiment's `toolchain/` directory; CPATH points there. No root package install
or system-Python change occurred. `gx10-server.service` and
`gx10-server-command.json` record the effective machine-specific launch.
The loopback key `local-benchmark` in these synthetic configurations is a fixed
local placeholder, not a private service credential.

The next startup selected NVFP4 Marlin kernels, loaded the official MTP draft
head with shared embeddings/output weights, and reported 21.13 GiB model memory.
Initial CUDA compilation/warmup is setup time and must not be charged to the
coding task. These startup observations do not yet establish generation speed,
actual MTP acceptance, or a coding success. Scored results are appended later.

The first run compiled base GEMM kernels but then began a 97-target MTP fused-MoE
build. Before any scored coding attempt started, the matching ARM64 CUDA 13
`flashinfer-jit-cache==0.6.16.post3+cu130` wheel was installed from the official
FlashInfer release, SHA-256
`9bd5529b1b2c774206bde78a65d84aff29583a51ce2804226cb45d6261af43ad`.
Startup was restarted to use that cache; no model, quantization, context,
thinking, or MTP setting changed. The original waiting supervisor directory
was retained as superseded-before-inference, not scored as a coding failure.

`download_model.py --output /absolute/remote/experiment` downloads the pinned
checkpoint/configuration after RAM/disk checks. The large artifacts remain
outside Git. `ornith-artifacts.json` freezes the expected shard hashes.
`first_coding.py` records the readiness/preload/first-coding sequence used on
GX10; its paths intentionally name the recorded remote experiment directory.

## First scored task: observed partial outcome

The isolated 32K/thinking-enabled task started after readiness. Runtime metrics
confirm that MTP draft tokens were proposed and accepted (not merely configured).
The agent chose its own web queries/reference and wrote implementation/tests.
There were no stream-completion errors in the observed initial turns.

The implementation passes all 3 fixed independent tests / 14 assertions in a
read-only mid-attempt diagnostic. The agent's own suite, however, calls it with
`(map #(* % %) (range))`, an infinite input mislabeled finite. Its implementation
reduces the entire collection, so that test cannot return. The test command
remains running, without a wall-clock cutoff, pending the user's choice of
nontermination policy. No supervisor code edits, reference URL, solution hints,
or cancellation have been supplied to the retained attempt.

This is **not a completed coding success**, and time to completion is unknown.
The independent diagnostic overlapped the stalled own-test command; do not use
this interval as clean successful-task timing. A quantitative snapshot is in
`results/2026-10-21-first-coding-mid-attempt.json`. The context scan remains queued
behind coding, as requested. RAM has remained above the enforced reserve.

## First task finalized: nonterminating

The preceding section is a retained mid-attempt snapshot. On the user's explicit
instruction, the first attempt was stopped and classified `nonterminating` after
1298.87 seconds (21 minutes 39 seconds). The latest test had changed the call to
`(stable-distinct-by #(mod % 5) (range))`, which still cannot finish. None of the
agent's bash calls supplied a timeout; the last command had remained blocked for
over 11 minutes. No general coding deadline was introduced.

The attempt's service and child processes were stopped, and its checkout, full
event stream, memory samples, patch, and termination decision were preserved.
The fixed independent evaluator was rerun on the stopped snapshot: 3 tests,
14 assertions, zero failures/errors. Thus implementation correctness passed,
but completing and repairing its own tests failed the full protocol. Elapsed
time is censored and must not be treated as a successful completion latency.
There were 20 model calls, 23 tool calls, and no swap-in/out during the attempt.
The final record is `results/2026-10-21-first-coding-nonterminating.json`.

The existing sequential suite consumed this failure record and advanced to the
32K/thinking-enabled NVFP4 dependency-order task with a fresh Pi environment.
The context scan remains after the coding phase.

## Persistent monitoring during the remaining suite

At the user's request, subsequent coding runners retain the 200 ms RAM check
and also inspect agent-written tests while a matching Clojure test command is
active. After at least 60 seconds, a direct unbounded `range`, one-argument
`repeat`/`repeatedly`, `iterate`, or a recognized `map` wrapper passed as the
collection argument to the task function triggers a nontermination stop.
The 60-second observation window is not a general coding or command deadline:
slow commands without this structural evidence continue running.

`nontermination.py` is a conservative syntax detector, not a general termination
proof. It does not infer arbitrary loops, aliases or variable-bound infinite
collections. Quoted/commented forms and bounded `take`/`range` cases are excluded;
unsupported reader-discard syntax is left unclassified. Unrecognized stalls
still require review. Decisions are saved in `artifacts/termination-decision.json`,
own tests are not rerun after a nontermination stop, the fixed evaluator still
runs, and the final result distinguishes nontermination from correctness.
The sequential suite then advances normally. Stopped attempts have censored
timings and cannot count as completed successes.

Termination includes descendant process groups because Pi's bash tool can create
a group separate from Pi itself. It preserves unrelated processes. Available
RAM below 26,118,804,275 bytes stops the owned coding process tree and inference
service. Six focused tests passed on GX10, including a real process-tree cleanup
test and finite/quoted input cases. The dependency-order task was already active with the earlier runner when the
update was installed. Its RAM guard remains active; a separate persistent
`gx10-inflight-order-monitor` companion applies the direct-infinite-input check
to that attempt. Later cells load the updated runner automatically. All retain
32K context and no coding wall deadline. `monitor_inflight_order.py` records
the one-attempt companion used during this transition.
