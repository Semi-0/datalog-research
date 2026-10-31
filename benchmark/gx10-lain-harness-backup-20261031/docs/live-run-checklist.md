# Live single-child trial checklist (execute only when every gate is green)

Authorized scope: **exactly one** worker, same model as the parent
(`gx10-flash-next-autoround/qwen3.8-flash-next-autoround`), no grandchildren, no replacement
retries, dispatched and collected by the **main Lain harness** (not a standalone `pi -p`).

## A. Pre-flight (all must PASS, record output)
1. Gates: every suite has an assertion-count receipt with 0 failures / 0 errors at the same
   candidate revision; synthetic dry-run completed; safety review has 0 blocking defects.
2. Memory: `MemAvailable >= 20%` of `MemTotal` (`free -m`; reserve = 24.3 GiB here).
3. Reconcile, don't duplicate: `sg docker -c 'docker ps -a --filter name=lain-trial-1'`;
   `pgrep -fa "pi_bridge.mjs|lain.trial|flash"`; confirm 0 harness children from earlier attempts
   (`receipts/`, `container-logs/trial-a1/`). Never start a second worker while one exists.
4. Admission unchanged: `aa-exec -p gx10-harbor-bwrap-v1 -- cat /proc/self/attr/current` =
   `gx10-harbor-bwrap-v1 (enforce)`; container flags unchanged
   (`--security-opt apparmor=… --security-opt no-new-privileges=true --cap-drop ALL --user 1000:1000`).
5. Shared things untouched: model server PID/flags identical (`--max-model-len 131072
   --max-num-seqs 6 --kv-cache-memory-bytes 8g`, MTP 3); editor still 4785/4782; release tree
   has no new files (`find <release> -newermt "-3 hours" -type f | head`).

## B. Run (plumbing only)
```
cd /home/lain/.local/share/lain-editor-deploy/current/backend
timeout 1300 clojure -M -e "(load-file \"$HOME/experiments/lain-harness-flash-once-20261027/drivers/flash_once.clj\")" -m lain.flash-once
```
Log: `logs/flash-once.jsonl`. The loop only calls `pump!`/`poll!` (host timing duty,
HARNESS.md:130-131) and stops when the **framework's** `:harbor/assessed` fact appears;
the 20-minute guard is a safety stop, never a phase decision.

## C. Evidence to capture (public only, no hidden reasoning, no credentials)
- session identity (`lain-trial/a1`), assignment id `a1`, the submitted `:socket/command` fact
- derived requests (bridge `:call!` payloads: tool + effect-id) and replies (status only)
- child launch receipt (`:launched` + child id), status transitions, final `:completed` result
- the framework's assessment fact from `artifacts` (authoritative), plus host-side checks clearly
  labeled as non-authoritative evidence (host grading is invisible to the model)
- dispose receipt with `:cleanup-verified true`; `hs/close!` result; bridge process exit
- timing (start→launch→collect→assessed) and min `MemAvailable` during the run
- `vllm:num_requests_running` peak and `vllm:kv_cache_usage_perc` peak (KV pressure, not RAM)

## D. Stop conditions (halt, then diagnose; do not retry)
- memory below the 20% reserve → stop only trial-owned children, keep model/editor alive
- a second launch attempt for the same assignment, or any grandchild
- a `:failed`/`:uncertain` status that would trigger a relaunch (reconcile must never relaunch)
- any bridge frame that the mapping cannot classify (fail loud, do not coerce to success)

## E. Rollback
1. `hs/close!` (refuses unless cleanup verified), kill bridge process, `docker stop lain-trial-1`.
2. Nothing else changes: no server flags, no security settings, no release edits, no editor restart.

## F. Report
`report.json`: status, revision, gate receipts table, session identity, one-child provenance,
Lain dispatch/collect receipts, framework assessment, timings, memory minimum, cleanup status,
and an explicit statement of what was **not** proven.
