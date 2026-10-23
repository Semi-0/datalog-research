# Enhanced BF16 queue, 2026-10-22

This supersedes the waiting Python queue 1 configuration. The existing solo/context queue 5 is not modified or interrupted.

## Roles and order

1. Wait for queue 5's service to exit and its durable complete checkpoint.
2. Run the three existing independent coding tasks once each with BF16, 128K context, temperature 1.0, thinking, pi-vigilant, web, Gortex, pi-lens/Clojure LSP and clj-kondo. Existing independent evaluators are unchanged. Every cell uses suite.run_cell's cleanup/load/preload/execute/evaluate/save/cleanup lifecycle and the host experiment lock.
3. Wait for a successful real Bubblewrap probe before delegation. Refused user namespaces never fall back to unconfined workers.
4. Flash plans and assigns four disjoint lanes, then unloads. BF16 serves bounded implementors, repair and assessment in private candidates. A two-worker smoke precedes four concurrent workers. BF16 unloads before Flash integrates patches, resolves integration issues, runs independent evaluation and reviews the reports.

Flash receives web access and the existing finite-command/progress supervision. BF16 implementors receive private Gortex state and Clojure LSP through pi-lens, plus clj-kondo and web. Flash does not start the workers' graph/LSP services. The independent evaluator remains a harness operation.

Both profiles use temperature 1.0. Coding has no overall wall deadline. Bash commands remain bounded at 120 seconds; existing request, output, progress and RAM guards remain active. Each concurrent BF16 request has 128K context; the backend requests four slots and verifies the runner configuration. This is one shared backend, not four weight copies. Startup and budget failures remain explicit outcomes.

## Tool configuration and evidence

Pinned isolated tools prefix: `/home/lain/experiments/gx10-agent-tools-1`.

- pi-lens 4.4.4; pi-linter 0.2.7; Pi 1.3.0.
- Clojure LSP 2026.10.15-11.42.38, official aarch64 archive checksum verified; provenance in `lsp/provenance.json`.
- Existing clj-kondo 2026.10.20, pi-web-access 0.43.0, private pi-gortex 0.4.0 and existing pi-vigilant are reused.
- pi-linter checks human prompt-editor input and is inactive in RPC mode. Actual Clojure linting is provided by clj-kondo, not pi-linter.
- Lens formatting/autofix and automatic test running are disabled, preserving lane ownership. Automatic dependency/tool installation is disabled. Only generated cache directories are excluded from Git status.
- Enhanced implementors use Pi's per-run `--approve` for their fresh harness-owned candidate, because `--no-approve` deliberately disables LSP project trust. Extension discovery is disabled and only pinned explicit extension paths load; MCP/skill/context-file discovery remains disabled. No global trust setting or compatibility shim is used.
- Tool registration alone is insufficient: worker evidence must include successful web search/fetch, private graph query, clj-kondo, LSP navigation and an active Clojure LSP diagnostic probe. An empty session diagnostic cache or unavailable server cannot satisfy the gate.
- Each attempt records extension/binary hashes, package-lock hash, effective sampling, tool activity and LSP probe details under `artifacts/`.

Live infrastructure checks on GX10 (unscored, no benchmark model loaded):

- `/home/lain/experiments/gx10-lens-check-BhJBSQ/`: active Clojure diagnostic and documentSymbol returned `smoke` and `twice`.
- `/home/lain/experiments/gx10-tools-pi-check-zilvlkjz/`: real Pi against a mock inference endpoint; all required tools registered, temperature 1.0 observed on the wire, normal completion. Its private Gortex daemon was stopped afterward.

These checks establish tooling integration, not BF16 task correctness or sandboxed concurrency. Model-backed checks remain queued.

## Launch

The frozen replacement snapshot is `/home/lain/experiments/gx10-delegation-python-harness-4`; its deployment.json identifies the source commit and transferred archive checksum. Queue output is `/home/lain/experiments/gx10-delegation-python-queue-2`.

```sh
bash /home/lain/projects/datalog-research/scripts/run-background.sh gx10-delegation-python-queue-2 -- /usr/bin/python3 /home/lain/experiments/gx10-delegation-python-harness-4/benchmark/gx10-delegation/schedule.py --solo-first --worker-model ornith-bf16 --after-unit gx10-datalog-benchmark-queue-5.service --after-root /home/lain/experiments/gx10-datalog-benchmark-queue-5 --output /home/lain/experiments/gx10-delegation-python-queue-2
```

Queue 1 remains stopped and its evidence retained. The new queue writes solo/results.json after every completed cell and waits at the sandbox gate if administrative setup is missing. Cleanup uncertainty, interruption or a reserve breach stops further scheduling.

Bubblewrap currently reports `setting up uid map: Permission denied`. The previously prepared narrow AppArmor profile can be installed by an administrator:

```sh
sudo install -m 644 /home/lain/projects/datalog-research/benchmark/gx10-delegation/gx10-bwrap.apparmor /etc/apparmor.d/gx10-delegation-bwrap
sudo apparmor_parser -r /etc/apparmor.d/gx10-delegation-bwrap
```

No global user-namespace restrictions or swap settings are changed by the harness. The real sandbox probe must pass afterward; profile installation alone is not treated as proof.
