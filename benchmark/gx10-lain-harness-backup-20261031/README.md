# GX10 Lain harness experiment backup — 2026-10-31

This is a backup snapshot, not a completed live experiment or deployment.

## Snapshot
Source experiment: /home/lain/experiments/lain-harness-flash-once-20261027.
Candidate base: c1ed279b095d87efc65c7fd5a60f6d34197444a7.
Harness fixes and new regression tests retain their repository paths. This directory
preserves selected external drivers, operator documents, recovery reports, launch scripts,
and final gate summaries. snapshot-manifest.json records the copied source-file hashes.
The original coordinator checkout and index were left untouched.

## Observed verification
Saved October 30 battery-v2 summary records:
- Integrated Clojure suites: 91 tests, 648 passing assertions, zero failures/errors.
- Driver W17: 7 tests; W19: 19 tests / 182 assertions; W23: 3 tests / 26 assertions.
- Node: 9 passing tests, zero failures.
- The recorded candidate tree remained stable during that battery.

These are historical results, not newly run tests on this backup checkout.
Backup verification checks file hashes and Git diff formatting.

## Incomplete live acceptance
The gated main trial failed before driver startup: AppArmor profile gx10-harbor-bwrap-v1
was missing after reboot. October 31 inspection still found no loaded profile, an exited
trial container with that error, and an empty live event log. No successful real-child
completion, framework grading, or cleanup proof was established. Controlled/synthetic
artifacts are not live acceptance. The prior editor observation session was lost during
a backend restart; live protected-editor attachment to the independent main remains unverified.

## Current model handoff
The coordinator was reopened with Blazux after AutoRound was stopped. This does not
convert the saved AutoRound child configuration or authorize a new live trial.
Do not automatically execute the archived TASK or launch scripts. Read current state,
resource policy, code, and claim/receipt ledgers before resuming.

## Restore limitations
Drivers and scripts retain original absolute GX10 paths and external dependencies.
This is an archival backup, not a portable one-command installer. Historical PIDs,
session IDs, and status must be revalidated.

## Exclusions
No model weights, download caches, Docker layers, credentials, environment files,
private session transcripts/reasoning, raw agent logs, live control ledgers, or editor
workspace state were copied. Selected files were scanned against known editor/Pi
credentials and private-key/token patterns before inclusion.

## Backup verification note
All 52 copied source/report files match their source SHA-256 hashes. Git diff --check
reports two pre-existing trailing spaces: harness_session.clj line 84 and
LAUNCH-SCRIPT-RECHECK.md line 15. They were preserved
to keep the backup byte-for-byte faithful. No runtime tests were rerun for this archival copy.
