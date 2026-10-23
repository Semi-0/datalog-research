# Streaming repetition protection: protocol 8

The user requested `pi-anti-repeat` after Ornith NVFP4 emitted repeated `Db` fragments inside an unfinished reasoning response. Vigilant 0.8.1's tool-call loop checks did not cover that stream.

## Installation and scope

Installed `pi-anti-repeat@0.2.1` privately at `/home/lain/experiments/gx10-anti-repeat-tools-1` with Node 24 and:

```sh
npm install --prefix /home/lain/experiments/gx10-anti-repeat-tools-1 \
  --ignore-scripts --legacy-peer-deps --save-exact pi-anti-repeat@0.2.1
```

Upstream npm integrity: `sha512-XnaHBZ9GBaqNZDHpw3X/2Odt9BqcJjvFypq0Kt4j0omDncf4CbRfA4BXLfMsGER/LVtrlduve4avI1UnibqLOA==`.
The installed file manifest is sealed by SHA256 `76ee2416c0aa1a88255cfa49f82eec86be60e7359f36fb71a1d70aa1c530f000`, recorded in `config.json`.

No global Pi package discovery or user settings were changed. Benchmark Pi processes disable extension discovery, so the shared `prepare_vigilant` operation now prepares and explicitly loads the combined guard entry when the anti-repeat profile is present. Both solo coding and delegation roles use this operation. Every attempt receives private copies with checked hashes. Both upstream packages remain unchanged.

`anti-repeat.ts` registers anti-repeat before Vigilant and coordinates their policies. During cancellation, Vigilant callbacks and message injection pause. The corrective run resumes Vigilant; an anti-repeat terminal stop leaves it paused. Default anti-repeat policy remains one correction, then stop on the second detection in the same epoch. Logs record detection, correction, stop and suppressed Vigilant hooks.

Enhanced solo and concurrent-worker inference admission checks `anti-repeat-ready.json`. Missing initialization refuses model requests rather than silently running without protection. The package manifest, wrapper hash and settings remain in each attempt's artifacts.

## Verification and limits

- Replayed the actual saved Ornith stream through the installed detector. It detected a reasoning-repeat period of one at 219.784 seconds into that attempt, after 809 words in the affected reasoning block. The original manual stop occurred later. Raw replay: `/home/lain/experiments/gx10-anti-repeat-tools-1/replay.json`.
- Three real Pi/scripted-provider tests cover first-loop cancellation and correction, second-loop stop with no Vigilant restart, and unaffected normal thinking. Evidence: `/home/lain/experiments/gx10-anti-repeat-wire-validation.json`.
- Preparation tests reject changed manifests/packages and missing readiness. Existing lifecycle, delegation, coding runner and Qwen adapter/report checks are retained. Raw regression and exact enhanced-tooling integration evidence: `/home/lain/experiments/gx10-anti-repeat-validation.json`.

These tests validate detection and integration, not the model's coding ability. Repetition detection is heuristic. This version watches streamed thinking and repeated completed runs; it is not a general proof of productive reasoning or termination. Existing command timeouts, request/token budgets, meaningful-progress supervision and RAM/cleanup guards remain necessary.

## Qwen restart and reporting

Qwen trial 1 was interrupted during unscored smoke setup, before any coding cell. It remains unchanged, with an interruption receipt and verified cleanup; the checkpoint download completed successfully. A fresh trial 2 uses frozen harness 14 with protocol 8, the same 128K configuration, temperature 1.0, thinking enabled, no MTP, and unchanged independent evaluators. It repeats the smoke gate before all three coding tasks. Prior results remain historical cohorts; adding anti-repeat is another documented harness difference.

Trial service: `gx10-qwen38-trial-2.service`.
Trial root: `/home/lain/experiments/gx10-qwen38-trial-2`.
Per-attempt guard artifacts: `artifacts/anti-repeat-package.json`, `anti-repeat-ready.json`, `anti-repeat.jsonl`; guard events also appear in `pi-runtime.jsonl`.

Upstream: https://github.com/osolmaz/pi-anti-repeat
