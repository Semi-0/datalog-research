# Delegation pilot status — 2026-10-22

UPDATE: The user requested queuing through the existing Python harness. The replacement is now deployed and queued as `gx10-delegation-python-queue-1.service`, verified `waiting_for_benchmark`. All 33 GX10 Python tests passed with zero skips. See [current Python queue and commands](PYTHON-QUEUE.md). The Lain-only deployment statements below describe the earlier preparation stage and are superseded by this update.

The canonical workflow is now executable Lain: see [the high-level workflow and commands](../../examples/lain/gx10_delegation/README.md). The current local entrypoint delegates to that controller. The existing protocol-6 solo matrix is unchanged. No live delegation result exists yet.

Current verification: six Clojure tests / 25 assertions; Python discovery runs 27 tests, with the Linux evaluator check skipped on this Mac. The high-level Lain source was loaded and evaluated in the hosted editor at http://localhost:4781/. The JSON subprocess test uses a simulated host and provides no model-performance evidence.

- GX10: 20 Python tests passed, zero skips. Known-correct independent fixture: 7 tests/32 assertions, zero failures/errors. Incorrect-result and syntax-error fixtures rejected.
- Installed pi-sandbox 0.28.0 admission tests passed with process startup mocked: two/four-worker caps, overflow rejection, depth-one nesting restriction, slot release.
- JavaScript syntax and Python compilation checked. The actual Bubblewrap AppArmor profile passed parser validation without loading it.
- Real sandbox admission: setup_declined, uid-map permission denied. Never fell back to an unconfined worker.
- Isolated extension prefix: /home/lain/experiments/gx10-delegation-packages-1. socat/libwrap extracted there; global Pi settings unchanged.
- Frozen deployed pilot: /home/lain/experiments/gx10-delegation-harness-3. Archive SHA256 d0376d1273ce17a804f12b68331c2fd0f95a993b68387e4f9e77a58596e4cb73. It imports the unchanged protocol-6 lifecycle through read-only source references. Earlier deployment directories are exploratory snapshots, not the scheduled runner.
- Validation receipt: /home/lain/experiments/gx10-delegation-validation-3/validation.json. Failed admission: /home/lain/experiments/gx10-delegation-admission-3.
- Old service: gx10-delegation-queue-1.service was stopped while waiting_for_benchmark. Its frozen snapshot and output /home/lain/experiments/gx10-delegation-queue-1 are retained. The new Lain controller is local and has not been deployed or queued.
- A fresh SSH check on 2026-10-22 confirmed the solo queue5 service active, the old delegation queue1 inactive, and Bubblewrap still failing with uid-map permission denied.

Before scheduling the replacement, deploy the complete project runtime and Lain files, verify its dry run on GX10, require the solo queue's durable complete checkpoint, and pass real Bubblewrap admission. The Lain workflow then plans with Flash, verifies unload, runs a two-worker smoke and four-worker implementation on a backend provisioned for four requests, collects patches, verifies cleanup, and reloads Flash with saved handoffs for integration and independent evaluation. The integration is a fresh Pi session. No service currently schedules this replacement automatically.

The administrator action is in README.md and was requested privately through the user-input prompt. Until it is completed and real sandbox/concurrency gates pass, neither isolation nor multi-agent model performance is claimed as validated. No LSP ran; the standalone clj-kondo binary is explicitly supplied to every pilot agent. No delegation commit or push has been made.
