# Pi subagent comparison setup

Isolated pinned dependencies, without changing global Pi settings or loading both plugins into the same session:

| Component | Version |
| --- | --- |
| Pi | 1.3.0 |
| pi-subagents (nicobailon) | 0.81.0 |
| pi-better-subagents | 0.19.1 |

The npm package names are plural. `package-lock.json` pins the dependency tree. Installation uses `npm ci --ignore-scripts`; extension code runs during the explicit registration test. No parent prompts or child launches occur in that test.

## Install and verify

```sh
cd benchmark/pi-subagent-comparison
npm ci --ignore-scripts --no-audit --no-fund
npm test
```

`smoke.test.mjs` loads each extension in a separate process, temporary workspace, and agent directory. It checks extension load errors and the required tool inventory, then disposes the session and removes its temporary state. This verifies registration compatibility only; it does not verify worker launch, confinement, tool inheritance, cancellation, or coding correctness.

GX10 installation: `/home/lain/experiments/pi-subagent-comparison-20261024`. Use `/home/lain/.local/bin` on PATH for Node/npm. This is separate from the existing `gx10-langgraph-install-1/packages` installation and does not resume the paused assisted pilot.

## Reuse the Harbor harness

The existing harness is **not yet switchable** between these packages. Do not pass pi-subagents to its existing `--plugin` option and assume equivalence: the admission and controller expect pi-better-subagents-specific versions, tools, policies and receipts.

Keep task staging, protected hidden evaluators, frozen outlines, model proxy, request/token limits, resource reserve, timing collection and lifecycle guard in `../harbor-assisted/`. Add a narrow controller adapter with the same operations for both packages: launch, status, collect transcript/result, and stop/verify cleanup. Do not duplicate the task runner or weaken the existing sandbox.

| Boundary | Existing implementation | Required comparison change |
| --- | --- | --- |
| `companion.ts` | Imports pi-better-subagents; publishes its sandbox/trusted-tool policy | Load exactly one selected plugin; keep the parent inference prohibition; give each adapter explicit admitted tool policy |
| `parent.mjs` | Uses spawn-batch/result/stop receipts and pi-better configuration | Delegate lifecycle operations and transcript collection to the selected adapter; preserve progress supervision and independent execution classification |
| `admission.py` and tool-image construction | Pins pi-better package/version and sandbox | Add package-specific version/launch admission; run the actual required-tool and containment probes for each variant |
| `execution.mjs` / graders | Separates normal execution from correctness | Preserve shared execution criteria and hidden evaluator hashes; normalize lifecycle evidence before classification |

For pi-subagents 0.81.0, activation is `subagents_enable({})`; delegation is the `subagent` tool with an explicit agent, task, exact provider/model, cwd and async setting. Background lifecycle queries use `subagent({action: "status", id})` and `subagent({action: "stop", id})`. This differs from pi-better's `subagent_spawn_batch`, `subagent_result`, and `subagent_stop`. Use a benchmark-specific agent profile with identical instructions and admitted tools rather than a built-in reviewer/scout profile. Agent directories and transcripts must be separate per task, package and repetition. Verify actual child tool inventory; parent inventory alone does not establish inheritance.

A fair first comparison is one deterministic control parent and one child solving the same task for each package. Keep identical Pi/model versions, quantization, context size, thinking/sampling settings, hints, tool set, request/token/progress budgets, sandbox guarantees and evaluator. Run variants in counterbalanced sequential order with clean task copies; do not run both simultaneously against the shared model backend. Keep 20% physical RAM reserve. Record model warm/cold state. Retain raw worker transcripts, proxy usage (including compaction), launch/run/collection time, task correctness and verified cleanup. Do not compare the old unassisted runs directly to newly assisted runs.

Before scored trials:

1. Repeat offline registration checks on GX10 (this setup).
2. Exercise both adapters against the existing synthetic fixtures: normal completion, authentication error, interruption, partial launch and owned-process cleanup. Require zero parent inference.
3. Repeat real confinement and required-tool admission inside each final task container. The existing pi-better AppArmor/bubblewrap admission does not establish pi-subagents confinement. Failure is a reported admission failure, not permission to relax security for one variant.
4. Run the same unscored integration smoke for both packages.
5. Only then run matched fresh Distinct/Ordering/Events trials and publish correctness separately from execution success, latency, tokens and resource use. Keep the paused pilot and its prior evidence intact; authorize actual inference separately.

This directory supplies installation and registration tests, **not** the controller adapters or a completed benchmark. No performance or correctness ranking follows from registration success.

Upstream references: [pi-subagents](https://github.com/nicobailon/pi-subagents), [tool reference](https://github.com/nicobailon/pi-subagents/blob/main/docs/tool-reference.md), [pi-better-subagents](https://github.com/1aboveio/pi-better-harness/tree/main/packages/pi-better-subagents).
