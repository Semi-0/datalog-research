# Harbor / Pi integration pilot

This is an **unscored four-assertion integration task**, separate from the existing
14/17/42-assertion benchmark. It checks a small Clojure stable-distinct function.
The independent verifier accepts exactly one test and four successful assertions;
zero assertions, exceptions and shell timeouts cannot establish success.

## Installed and verified on GX10, 23 October 2026

- Harbor **0.24.0**, in `/home/lain/.local/opt/harbor-eval/` (Python 3.12 venv).
- The installed Harbor distribution includes its Pi adapter.
- Docker **29.6.2** responds after the user backed up its broken BuildKit cache
  and restarted the daemon. Socket access was granted to user `lain`.
- The reference-solution Harbor trial finished with reward **1.0**, no trial
  exception, and **1 test / 4 assertions / 0 failures / 0 errors**.
- The unchanged starter implementation, run with Harbor's `nop` agent, finished
  with reward **0.0** and no infrastructure exception. It is deliberately wrong.
- Six focused Python adapter tests passed locally and on GX10 before scheduling.

Raw reference and negative trials are under:

```
/home/lain/experiments/harbor-pilot-oracle-20261023/{reference,negative}/
```

These are infrastructure/verification checks. They are not evidence that Pi or a
model completed the task. The real Pi pilot is scheduled separately below.

## Scheduled Pi pilot

Task deployment: `/home/lain/experiments/harbor-pilot-20261023/`.
Controller: `gx10-harbor-pi-pilot-1.service`.
Output: `/home/lain/experiments/gx10-harbor-pi-pilot-1/`.

`validate.py` waits for `gx10-qwen36-events-finish-1.service` to finish, verifies
its selected attempt's cleanup receipt using the existing harness, then takes
the host-wide experiment lock. It reruns the reference gate before loading the
pinned Qwen3.6 model with the existing lifecycle and RAM guard. Harbor runs Pi
1.3.0 with high thinking and a 24-turn cap, executes the independent verifier,
and saves the trajectory and trial result. The small pilot has an explicit
30-minute agent limit; this does not change the scored Events task's absence of
an overall coding deadline. One connection-closed retry is allowed and recorded.

The Docker task shares host networking to reach the existing loopback-bound
model endpoint. It has a separate container filesystem and a 3 GiB memory
allocation. This does not provide network isolation from host services. Owned
containers carry `gx10.benchmark.owner=harbor-pilot-20261023`; cleanup removes
only matching containers. Container cleanup and backend cleanup stay inside the
host lock. The model uses the existing reserve policy (20% physical RAM, minimum
16 GiB). Harbor telemetry is disabled and no Hub upload/launch is requested.

Inspect the result, not just the service exit code:

```bash
cat /home/lain/experiments/gx10-harbor-pi-pilot-1/status.json
cat /home/lain/experiments/gx10-harbor-pi-pilot-1/result.json
journalctl --user -u gx10-harbor-pi-pilot-1 -n 40 --no-pager
```

A missing result or recorded trial exception is not a pass. Harbor handles trial
execution and grading; the adapter retains only GX10-specific model ownership,
verified transitions, RAM protection and owned-container cleanup. This pilot
does not validate the full Pi extension profile or token-throughput comparison.

## Missing Events cell under the existing harness

`../qwen36-nvfp4/finish_events.py` runs only Events in a fresh directory using
frozen harness 20 and the original Qwen3.6 priority-plan manifest. The previous
Distinct and Ordering results remain byte-for-byte unchanged and referenced by
SHA-256. The old Ordering `runtime_error` is explicitly retained, not silently
cleared. This is a user-authorized standalone cell, not an assertion that the
old queue passed its continuation gate.

Controller: `gx10-qwen36-events-finish-1.service`.
Output: `/home/lain/experiments/gx10-qwen36-events-finish-1/`.
The original 42-assertion evaluator, 128K context, temperature 1.0, structural
editing, enhanced tools, progress supervision, command limits and bounded
repetition-reload policy are unchanged. Three focused launcher tests passed on
both hosts before launch. Source files in frozen harness 20 were not edited.

## pi-better-subagents inspection

Downloaded **pi-better-subagents 0.19.1** for inspection with `npm pack
--ignore-scripts`. It was not enabled in either test's Pi configuration.
Review artifact: `/home/lain/experiments/pi-better-subagents-review-20261023/`.
Npm SHA-1: `71a5f69072d09bd40ba4e74bfde0d7f388bfed17`.

The package offers detached Pi child processes, durable results, batch spawning,
configurable concurrency (default 4), per-run timing controls, and Linux
bubblewrap confinement. Its Pi peer requirement is >=0.82.1. Confined children
do not inherit arbitrary extensions; the packaged defaults set
`inheritExtensions:false` and expose built-in editing/shell tools plus specific
trusted web tools. Consequently, our Gortex, Lens and structural-editing tools
cannot be assumed to propagate into workers. A concurrency cap is not a RAM
budget and it does not manage vLLM model unloading/loading. Those compatibility
and host-resource gates need a live worker test before orchestration admission.

Sources: [package](https://pi.dev/packages/pi-better-subagents),
[usage](https://github.com/1aboveio/pi-better-harness/blob/main/packages/pi-better-subagents/docs/usage.md),
[Harbor Pi integration](https://docs.harborframework.com/agents/pre-integrated-agents),
[Harbor error policy](https://docs.harborframework.com/jobs/error-classification).

Multi-worker Flash/Qwen orchestration remains deferred by the user's follow-up
request to validate one Harbor task and finish the current Qwen3.6 test first.
