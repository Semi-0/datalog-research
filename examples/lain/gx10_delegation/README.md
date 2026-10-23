# GX10 delegation in Lain

Current scheduling: at the user's request, the queued GX10 test uses the existing Python harness directly. See [Python queue status](../../../benchmark/gx10-delegation/PYTHON-QUEUE.md). `run.py run` now invokes that Python controller. This Lain example and its explicit Lain CLI remain available for design/inspection; the deployment status below is historical.

The workflow is executable Lain in `delegation.lain`. It constructs a plan without starting models. `constraints.lain` defines admission and explicit requirements; `fixtures.lain` exercises admission before implementation detail. The hosted editor can run these files safely: effects require the explicit live CLI.

The order is Flash planning → verified unload → NVFP4 two-worker smoke and four private worker checkouts → collect owned patches → verified unload → Flash integration and unchanged independent evaluation. The two-worker smoke and four-worker attempt share a backend provisioned for four requests. Integration uses a fresh Pi session with saved handoffs.

## Boundaries

`workflow.lain` provides the three public variants: sequence, model scope, named step. `host.clj` reads public Lain values through the existing runtime, validates supported variants, and traverses them. Model scopes always request cleanup. A failed or uncertain effect stops traversal. Before parallel admission the controller evaluates `admit-workers?` in Lain using four explicit booleans returned by the verified host setup. Unknown facts are rejected.

`benchmark/gx10-delegation/lain_host.py` implements named effects. It reuses the existing model lifecycle, 200 ms RAM guard, process ownership, independent evaluator, and installed Pi sandbox manager. The physical reserve is max(16 GiB, 20%). The guard remains active across the full loaded model scope, including controller overhead. Worker commands retain the 120-second limit and Pi Vigilant supervision; no overall coding deadline is introduced. JSON requests use stable workflow paths and cannot repeat an effect in the same attempt. Sources and the concrete plan are recorded before loading; effect receipts are flushed and fsynced before returning. Interrupted retries require a new output directory.

The editor declares the plan; the explicit interpreter performs effects. No global Lain runtime/compiler change was needed. The prototype does not implement a general delegation contract or resume an interrupted effect.

## Commands

From the datalog research root:

```sh
# Pure replay: no SSH, models, agents, or sandbox required.
clojure -M benchmark/gx10-delegation/lain.clj

# Focused Lain constraints, interpreter, and subprocess protocol checks.
clojure -M -e '(load-file "examples/lain/gx10_delegation/host.clj") (load-file "examples/lain/gx10_delegation/host_test.clj") (gx10-delegation.host-test/-main)'

# Existing guards/contracts plus the named host adapter tests.
python3 -m unittest discover -s benchmark/gx10-delegation -p 'test_*.py'

# GX10 only, after OS sandbox admission and the solo queue have completed.
clojure -M benchmark/gx10-delegation/lain.clj --live /home/lain/experiments/NEW_DIRECTORY
```

`run.py run --output NEW_DIRECTORY` is a compatibility entrypoint to the same Lain controller. Its former Python orchestration has been removed. A deployment must include the project runtime, the Lain files, the delegation directory, and the original benchmark dependencies; copying only run.py is insufficient. The frozen old harness is preserved separately and must not be treated as this implementation.

## Verification and live status

On 2026-10-22 the real editor runtime compiled the workflow and evaluated it in Safari at `http://localhost:4781/`. Six Clojure tests passed 25 assertions, including unknown admission, bounded ownership, cleanup on failure, stopping before later model entry, deterministic replay, and the JSON subprocess bridge with a simulated host. Python discovery passed 26 tests with one Linux evaluator check skipped on this Mac (27 total). These cover duplicate requests, durable setup failure, preserving existing attempts, missing measurement evidence, conflicting model scopes and cleanup failure.

Live delegation is not validated yet. The old waiting delegation controller `gx10-delegation-queue-1.service` was stopped at its waiting boundary; the solo benchmark was preserved. The new Lain controller has not been queued on GX10. The previously observed OS sandbox failure was `bwrap: setting up uid map: Permission denied`; the prepared narrow AppArmor profile still needs administrator enablement and a fresh sandbox probe. No unconfined fallback is permitted. Run the real two-worker and four-worker checks only after that gate and after validating the complete project deployment. Dry-run receipts are explicitly simulated and are not benchmark results.
