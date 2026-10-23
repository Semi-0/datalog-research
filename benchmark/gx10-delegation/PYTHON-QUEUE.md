# Python orchestrator queue — 2026-10-22

Per the user's clarification, run the orchestrator test with the existing Python harness after the current benchmark completes. The Lain program remains a separate design/inspection example. The scheduled test does not require the Lain controller.

Verified deployed queue:

- Service: `gx10-delegation-python-queue-1.service`, active/running, PID 290176 at verification.
- State: `waiting_for_benchmark`.
- Waits for `gx10-datalog-benchmark-queue-5.service` and its durable `stage: complete` checkpoint, including all phases of that queue. A failed previous service or infrastructure failure prevents launch.
- Queue output: `/home/lain/experiments/gx10-delegation-python-queue-1`.
- Frozen Python snapshot: `/home/lain/experiments/gx10-delegation-python-harness-1`.
- Archive SHA256: `c28b9337dc62dc4128b43134c892c47d81c0f0e3cb78aed7cbc433f64174ce7d`.
- Snapshot dependencies reuse the existing GX10 context lifecycle and NVFP4 backend files through the same source references as the prior pilot. The active solo harness was not edited.
- Started through `scripts/run-background.sh`; `Linger=yes` verified. A second SSH connection confirmed the service remained running after the launch connection closed.

Schedule: Flash plans → verified unload → NVFP4 two-worker smoke → four concurrent workers with private checkouts → collect owned patches → verified unload → Flash integrates → own tests and independent evaluator → verified cleanup and durable result.

Existing settings remain: 128K per agent, Pi Vigilant and the package's process-backed sandbox manager, 120-second command timeout, no overall coding deadline, request/progress supervision, 200 ms RAM monitoring, max(16 GiB, 20% physical RAM) reserve, host experiment lock, and native/request token metrics. The two-worker smoke uses the same backend provisioned for four requests as the implementation phase. More workers need not improve per-agent speed.

The queue also waits for Bubblewrap admission. The last live probe failed with `bwrap: setting up uid map: Permission denied`. If the administrator's narrow AppArmor setup remains pending, the queue will change to `waiting_for_sandbox_administrator` rather than launch unconfined workers. After this probe, the child repeats the real Pi package sandbox check and takes the experiment lock before loading models. No model-backed delegation result exists yet.

Validation: 33 Python tests passed on GX10, zero skips, using `GX10_CLOJURE=/home/lain/.local/bin/clojure`. The unchanged independent evaluator passed its known-good fixture with 7 tests / 32 assertions and rejected wrong and syntax-error fixtures. The printed Python plan was checked without loading any model.

Commands from the project root:

```sh
python3 benchmark/gx10-delegation/orchestrator.py --plan
python3 benchmark/gx10-delegation/run.py run --output NEW_DIRECTORY
```

GX10 monitoring:

```sh
systemctl --user status gx10-delegation-python-queue-1.service
journalctl --user -u gx10-delegation-python-queue-1.service -f
cat /home/lain/experiments/gx10-delegation-python-queue-1/status.json
```

`orchestrator-plan.json`, per-step receipts in `steps/`, phase inference files, memory logs, patches, evaluator output and `result.json` are retained in the pilot output. Existing attempts are never overwritten. Stop on failed or uncertain cleanup; interrupted retries need new output directories.
