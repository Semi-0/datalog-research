# Protocol v3 validation and rollout

Current work: [protocol-4 Flash context-first Pi pilot](PI-PILOT.md). The protocol-3 material below is retained evidence; its full-matrix launcher is stopped.

The active protocol is [EXPERIMENT.md](EXPERIMENT.md), with one authoritative
[configuration](config.json). The fresh outcome/occupancy report is
[PROTOCOL-V3-REPORT.md](PROTOCOL-V3-REPORT.md); it is generated after the live gate
passes and updates during coding/context work. Earlier results remain exploratory.

## Current checkpoint (2026-10-21)

The first scored root has six recorded coding outcomes: one full pass, four runtime
errors, and one structurally verified nonterminating own test. The latter retains
raw interruption records and a separate adjudication receipt; its finite independent
evaluator completed 42 assertions with one failure. Its own test was not rerun.

A context scheduling regression was fixed before context probing began: a completed,
resource-safe request with incorrect retrieval now contributes to the resource-fit
lower bound without becoming a retrieval success. GX10 deterministic run
`~/experiments/gx10-v3-deterministic-6` passed all **45 tests** (39 plus 6), with no
Linux skips. The original harness is archived in scored-1/harness-v5-source.tar.

`gx10-v3-live-validation-5.service` is running fresh smoke and all 16 transitions
against those current hashes. `gx10-v3-chain-2.service` waits for that proof before
launching `gx10-v3-scored-2`, using `--continue-from /home/lain/experiments/gx10-v3-scored-1`.
Completed records are retained; BF16 and Flash supply the remaining six coding
cells, followed by context probing. No context result has yet been obtained.

The sections below document the preceding validation and launch, rather than the
current active service names.

## Deterministic evidence

GX10 run `~/experiments/gx10-v3-deterministic-5` passed **44 tests**: 38 lifecycle,
context and evaluator tests plus 6 existing isolation/resource/nontermination tests.
There were no skipped Linux checks. Known-correct fixtures passed the unchanged
independent evaluators with exactly **14 distinct, 17 ordering, and 42 events
assertions**. Raw text, commands, runtime version and exact harness hashes are in
that directory's `deterministic.json`, `gx10-context.txt` and
`ornith-nvfp4-mtp.txt`.

The checks cover all 16 ordered configuration pairs, delayed unload acknowledgement,
empty inventory with lingering processes, delayed/short RAM recovery, API/startup
failures, reserve-stop classification after aborted I/O, repeated cleanup, conflicting
workload preservation, nested tool cgroups with an unrelated sentinel, assertion
summaries, incomplete/output-limited endings, recognized infinite input, swap timing,
interruption and recovery during coding/context/setup/cleanup publication. A completed
worker/primary result is retained after a new controller verifies clean recovery;
an interrupted retry always has fresh directories and Pi history.

## Live evidence and bounded interpretation

Active live root: `~/experiments/gx10-v3-live-4`; service
`gx10-v3-live-validation-4.service`. It runs four unscored Pi smoke sessions,
then a tested 17-load path covering every ordered pair exactly once, including
same-model transitions. `validation.json` is written only after all gates pass
and the current harness hashes still match deterministic evidence. Per-cell
lifecycle logs retain API responses, available RAM, swap, owned process identities,
configuration checks, preloads, service logs and cleanup proofs.

Smoke verifies reading, editing, executing assertions, autonomous search/fetch and
thinking. A complete output-token-limit ending may retain that primitive evidence;
it remains an explicit workflow failure and invalid speed observation. Incomplete
streams, unknown failures, resource stops and failed assertions do not establish
a successful smoke gate. Scored full passes still require both implementation
correctness and the complete workflow.

The logout-persistence receipt is `~/experiments/gx10-v3-persistence.json`.
`Linger=yes` and completion after the launching SSH exec session returned were
verified. The authenticated ControlMaster transport remained open; forced closure
of every login session was not tested.

## Findings retained from harness validation

All earlier unscored attempts remain on GX10; they are not scored coding attempts.

- `gx10-v3-live-1`: interrupted NVFP4 setup exposed a controller stop timeout
  shorter than systemd's shutdown grace. Scheduling stopped. Cleanup now requests
  an asynchronous stop, waits five seconds, then kills only the owned cgroup if
  needed and verifies that it is empty.
- `gx10-v3-live-2`: the NVFP4 arithmetic implementation passed its four own
  assertions and independent smoke assertion, then exhausted the final response
  budget. The result remains correctness true/workflow false. Cleanup exposed a
  repeated stop of a transient unit that had already disappeared; cleanup is now
  idempotent and retains the cgroup path for verification.
- `gx10-v3-live-3`: Q4 was incorrectly refused by comparing resident GPU allocation
  with larger artifact download bytes. Its runtime inventory was 100% GPU and its
  log reported all 42 layers offloaded. Verification now uses Ollama's resident
  size/VRAM accounting plus actual layer-placement logs. The installed runtime
  launches a separate `llama-server`, which is explicitly tracked by PID and start
  identity alongside legacy runner forms.

Ollama's [processor reporting implementation](https://github.com/ollama/ollama/blob/main/cmd/cmd.go)
uses resident `SizeVRAM == Size` for its 100% GPU classification. Artifact bytes
remain part of the conservative budget estimate, rather than a resident-memory
denominator. These corrections do not change model artifacts, context, thinking,
sampling, prompts, independent evaluators or allowed agent tools.

## Fresh run and publication

The detached `gx10-v3-chain-1.service` runs
[`scripts/gx10_protocol_v3.py`](../../scripts/gx10_protocol_v3.py). It waits for
live validation and refuses a failed gate. The scored suite independently checks
the receipt against current hashes before scheduling anything. Its configured
fresh root is `~/experiments/gx10-v3-scored-1`, with unit
`gx10-v3-scored-1.service` and a separate compact recorder. Exactly three tasks ×
four configurations run once each, then the context scan and boundary refinement.

```sh
bash scripts/run-background.sh gx10-v3-chain-1 -- \
  /usr/bin/python3 /home/lain/projects/KIROSHI/scripts/gx10_protocol_v3.py \
  --validation-root /home/lain/experiments/gx10-v3-live-4 \
  --scored-root /home/lain/experiments/gx10-v3-scored-1 \
  --unit-prefix gx10-v3-scored-1
```

That original controller has stopped at the recorded checkpoint; a duplicate service/root is refused. Historical monitor commands:

```sh
bash benchmark/gx10-context/check-status.sh /home/lain/experiments/gx10-v3-scored-1
systemctl --user status gx10-v3-live-validation-4 gx10-v3-chain-1 gx10-v3-scored-1
```

The chain publishes compact validation receipts, every completed outcome and the
corrected occupancy/RAM report into this KIROSHI checkout. Raw reasoning, fetched
page bodies and process snapshots remain outside Git. No LSP or clj-kondo was
added; this is a common compiler/test baseline and Clojure-only coding evidence.

The old r1/r2/r3 directories were archived unchanged in
`~/experiments/gx10-protocol-v3-preservation/exploratory-runs.tar`, SHA-256
`7e1b0c3e4b54f2b61be4c03779177855af08b20fd1c874beeacee34f5a221412`.
Its external `interruption.json` marks the stopped 128K setup interrupted without
altering those original directories. Older transition-dependent skips are
inconclusive; delayed unloading remains a hypothesis for those observations.
