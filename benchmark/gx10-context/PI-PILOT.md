# Pi configuration pilot: Flash context first

This is protocol 4, separate from the earlier fixed-32K protocol-3 results. The
current job is `gx10-v4-flash-pilot-2.service` on GX10, with raw output under
`/home/lain/experiments/gx10-v4-flash-pilot-2`. The full model matrix is stopped.

The pilot scans Flash IQ4 at 32K, 64K, 128K and 256K with three seeds, then
refines a known memory bracket to 8K. It selects the largest context with all-seed
retrieval success, calibrated input occupancy, no swap and preserved memory reserve.
A conservative budget decline is distinct from an observed resource failure.
256K is the declared model ceiling; passing it would establish the largest tested
context within that ceiling, rather than a machine limit beyond it. Coding and
Pi's own context metadata use the selected size. A full Pi smoke must then pass
before the three original coding tasks run. Evaluators are unchanged.

## What the investigation established

The original provider setup followed Pi's official compatible-endpoint guidance:
`openai-completions`, a local `/v1` endpoint, and a dummy key. Pi 1.3.0 already
retried the Q4 missing-finish error three times; retries were not disabled.
The original output-limit endings contained thinking without a completed answer.
These observations do not prove a single cause for every failure.

Pi documents a `supportsFinishReason` compatibility option, but disabling it
would infer success from EOF. We keep it explicitly true. Persistent truncated
streams remain errors. Upstream has reports of both nonconforming providers and
error-only stream chunks being hidden behind the same generic message. The old
run did not capture the raw provider events needed to distinguish these cases.
The new extension records request sizes/limits, terminal reasons and provider
error events without copying prompt bodies into the compact report.

Sources checked on 2026-10-21, alongside the installed 1.3.0 implementation:

- [Pi compatible endpoint and sampling configuration](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/models.md)
- [Pi compaction, timeout and retry settings](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/settings.md)
- [Pi extension lifecycle and boundary continuation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md)
- [Ollama's Pi integration](https://docs.ollama.com/integrations/pi)
- [Upstream missing-finish investigation](https://github.com/earendil-works/pi/issues/6226)
- [Upstream error-only stream report](https://github.com/earendil-works/pi/issues/11041)

## Effective policy

The authoritative values are in [config.json](config.json). Each fresh attempt
gets generated `agent/models.json`, `agent/settings.json` and
`agent/benchmark-policy.json`; personal Pi configuration is untouched.

| Setting | Value |
| --- | --- |
| Pi | Installed 1.3.0, unchanged |
| Coding context | Largest all-seed validated Flash context |
| Thinking | Enabled, high |
| Maximum output per response | 12,288 tokens |
| Compaction reserve / recent history | 16,384 / 4,096 tokens |
| Agent-level transient retries | 3, bounded exponential backoff |
| Provider-level retries | 0 |
| Token-limit continuations | pi-vigilant 0.8.1; at most 3 per unbroken length streak, within the same attempt deadline |
| Agent execution including its tools and harness verification | 1,200 seconds |
| Each bash command / harness evaluation | At most 120 seconds |
| Prompt's explicit test wrapper | `timeout --signal=TERM --kill-after=5s 110s` |
| Memory reserve | max(16 GiB, 20% of measured physical RAM) |

The 20-minute clock begins after model setup/preload and worker preparation.
Model startup and verified cleanup have separate infrastructure bounds. A
`timed_out` result is censored and does not prove an infinite loop. The structural
infinite-input detector remains conservative. Saved patches survive cleanup.

A recovered stream incident remains in the result. A normal ending plus explicit
recovery evidence can restore workflow success; historical stream incidents still
exclude that attempt from speed comparisons. A successful process exit or zero
assertions cannot establish success. If all three tasks do not fully pass, the
pilot reports that fact and leaves matrix scheduling disabled.

## Validation and commands

`~/experiments/gx10-v4-deterministic-3` passed **61 checks** on GX10: 53 lifecycle,
context, evaluator and installed-Pi wire tests, plus 8 worker/isolation checks.
There were no skipped Linux checks. The wire tests use a local mock HTTP server,
not an inference model. They verify finite length continuation, transient retry,
persistent incomplete-stream failure and an actual timed-out bash tool. The worker
test checks the 1,200-second deadline decision stops its owned process while an
unrelated sentinel survives. Exact hashes and raw outputs are in that directory.

```sh
python3 benchmark/gx10-context/pilot.py --model flash-iq4 \
  --output /home/lain/experiments/gx10-v4-flash-pilot-2 \
  --deterministic /home/lain/experiments/gx10-v4-deterministic-3/deterministic.json \
  --continue-context-from /home/lain/experiments/gx10-v4-flash-pilot-1
```

That root is already in use. Monitor the running detached job instead:

```sh
systemctl --user status gx10-v4-flash-pilot-2
cat /home/lain/experiments/gx10-v4-flash-pilot-2/status.json
cat /home/lain/experiments/gx10-v4-flash-pilot-2/REPORT.md
```

The shared test prompt is [prompts/agent-workflow.txt](prompts/agent-workflow.txt).
The extension is [pi-runtime.mjs](pi-runtime.mjs). No global Pi package was patched.
Protocol-3 source was archived before deployment at
`~/experiments/gx10-protocol-v3-preservation/harness-before-pi-pilot.tar`, SHA-256
`54726fde8653af48f30a484ec4622ec2c7c68aa1915ecd69d44a5435d7217f42`.

## pi-vigilant trial

At the user's request, [pi-vigilant](https://github.com/brunoj/pi-vigilant) **0.8.1**
now owns continuation. The published artifact identity and every packaged file hash
are pinned in [pi-vigilant-lock.json](pi-vigilant-lock.json). It is copied into a
fresh private agent directory for each attempt; no global Pi package or personal
configuration was changed. The five controlled installed-Pi wire tests also pass
with this extension: actual token-limit continuation, a finite continuation budget,
transient stream retry, persistent stream failure and an actual timed-out shell.
They assert the model sees only the allowed tools and that our former continuation
hook stays inactive. The harness retains auditing and hard timeout enforcement.

The trial enables continuation, compaction recovery, final verification and loop
steering. Spec/feedback memory and their skills are disabled; only the original
benchmark tools are exposed. The extension cannot raise the harness's shell cap.
Compaction uses the same local model, and dropping old context without a summary
is disabled. Pi still owns the three transient-error retries; vigilant does not
add a second provider-error retry loop.

All three original 32K context probes passed (22,405 / 22,410 / 22,408 actual input
tokens; minimum available RAM 27.07 / 27.01 / 26.83 GiB). The controller was suspended
while seed 44's independently guarded worker finished; its durable result was
preserved before controller shutdown and cleanup. The new root verifies cleanup,
unchanged context policy, and unchanged probe/backend/lifecycle hashes before
adopting those results. Raw interruption records remain intact. The context scan
continues above 32K; coding has not been used to select the model or context.
