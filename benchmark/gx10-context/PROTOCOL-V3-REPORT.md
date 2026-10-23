# GX10 protocol v3 report

Status: **stopped with an inspectable failure**. Completed coding records: 6/12.
One fresh attempt per coding cell. Coding has no wall-clock deadline. No speed medians are calculated.
Correctness, workflow and timing are independent. Unmeasured implementation results are excluded from correctness denominators.
Setup declines are excluded from correctness denominators. Interrupted and nonterminating execution times are censored.

The previous r1/r2/r3 runs remain exploratory, archived unchanged on GX10. Older transition-dependent skips are inconclusive.
Archive: `~/experiments/gx10-protocol-v3-preservation/exploratory-runs.tar`; SHA-256 `7e1b0c3e4b54f2b61be4c03779177855af08b20fd1c874beeacee34f5a221412`.

## Validation

The validated harness and deterministic/live evidence are bound by the hashes in `/home/lain/experiments/gx10-v3-live-4/validation.json`.
Deterministic checks cover evaluator fixtures (14/17/42 assertions), all 16 directed pairs, delayed unloading, runner identities,
RAM settlement, process ownership, reserve stops, incomplete endings, nontermination, interruption and continuation.
Live smoke preserves output-token exhaustion as a workflow failure even when required tool primitives and assertions succeeded.
The SSH persistence receipt verifies a service completing after its launch session returned, with Linger=yes;
the authenticated ControlMaster transport remained open. No forced all-login logout is claimed.

## Coding

| Model | Task | Outcome | Implementation | Workflow | Timing valid | Agent s | Censored | Min available GiB | Own assertions | Evaluator assertions |
| --- | --- | --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: |
| ornith-nvfp4 | distinct | passed | True | True | True | 96.95 | False | 85.11 | 24 | 14 |
| ornith-nvfp4 | order | runtime_error | True | False | False | 483.02 | False | 84.74 | 24 | 17 |
| ornith-nvfp4 | events | runtime_error | False | False | False | 124.84 | False | 84.83 | 0 | 39 |
| ornith-q4 | distinct | runtime_error | True | False | False | 1508.46 | False | 87.03 | 0 | 14 |
| ornith-q4 | order | runtime_error | unmeasured | False | False | 1958.45 | False | 85.56 | 0 | 0 |
| ornith-q4 | events | nonterminating | False | False | False | 2365.66 | True | 84.92 | — | 42 |

## Phase durations

| Model | Task | Transition s | Setup s | Load s | Preload s | Agent s | Evaluation s | Cleanup s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ornith-nvfp4 | distinct | 10.17 | 10.72 | 201.42 | 0.63 | 96.95 | 1.77 | 11.47 |
| ornith-nvfp4 | order | 10.23 | 10.67 | 167.29 | 0.67 | 483.02 | 2.97 | 11.53 |
| ornith-nvfp4 | events | 10.22 | 10.67 | 200.38 | 0.65 | 124.84 | 2.36 | 11.62 |
| ornith-q4 | distinct | 10.17 | 4.34 | 8.16 | 0.63 | 1508.46 | 1.24 | 11.66 |
| ornith-q4 | order | 10.18 | 4.39 | 7.9 | 0.64 | 1958.45 | 1.22 | 11.51 |
| ornith-q4 | events | 10.22 | 4.3 | 7.94 | 0.66 | 2365.66 | 0.67 | 12.45 |

## Actual context occupancy and RAM

| Model | Context | Seed | Outcome | Input tokens | Target input tokens | Min available GiB | Retrieval | Timing valid |
| --- | ---: | ---: | --- | ---: | ---: | ---: | --- | --- |

## Context evidence bounds

| Model | Largest tested all-seed success | Conservative budget declines | Observed reserve stops |
| --- | ---: | --- | --- |
| ornith-nvfp4 | — | [] | [] |
| ornith-q4 | — | [] | [] |
| ornith-bf16 | — | [] | [] |
| flash-iq4 | — | [] | [] |

A conservative decline is not an observed OOM or a proven maximum. A semantic or infrastructure failure is not a resource boundary.
A tested success reports observed input occupancy, not a claim that every advertised token was filled.
No LSP or clj-kondo was added; all configurations use the same compiler/test baseline.

## Commands and raw references

Protocol and standalone commands: [EXPERIMENT.md](EXPERIMENT.md). Authoritative configuration: [config.json](config.json).
Validated live root on GX10: `/home/lain/experiments/gx10-v3-live-4`.
Fresh coding/context root on GX10: `/home/lain/experiments/gx10-v3-scored-1`.
Raw code, patches, events, lifecycle samples, test outputs and model metadata stay in per-cell directories on GX10.
Compact results and validation receipts are under `results/`. Raw reasoning, fetched pages and process snapshots are excluded.

Failure receipt: `/home/lain/experiments/gx10-v3-scored-1/failure.json`; {'outcome': 'interrupted', 'error': "Failure('Scheduling stopped at /home/lain/experiments/gx10-v3-scored-1/cells/ornith-q4-events-32768-None-a7184eaa')"}
