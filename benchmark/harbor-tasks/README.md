# Original GX10 coding tasks on Harbor

Distinct, Ordering and Events are migrated as reproducible Harbor task packages. The original contracts, allowed implementation/test paths and independent evaluator bytes are retained. This is a new assessment cohort; it does not replace or reclassify older solo results.

**Migration validation passed on GX10 with Harbor 0.24.0. No model was loaded or queried.** Seven Python migration tests also passed on the Mac and GX10.

| Task ID | Reference reward | Original independent assertions | No-op reward |
| --- | ---: | ---: | ---: |
| `distinct` | 1.0 | 14 passed | 0.0 |
| `order` | 1.0 | 17 passed | 0.0 |
| `events` | 1.0 | 42 passed | 0.0 |

All three reference solutions passed their own finite unit tests as well. A seventh Harbor trial deliberately changed `deps.edn`: its correct implementation retained reward 1.0, while `scope_valid` and `workflow_pass` were false. An eighth trial appended candidate code that tried to forge `/logs/verifier/reward.txt`; the write was denied and the correct implementation still passed. These are controlled fixtures, not model attempts. No-op failures can execute fewer assertions because exceptions prevent later assertions.

[Validation result](evidence/validation-2/result.json), [package/source-input manifest](evidence/validation-2/migration.json), and [preservation/cleanup audit](evidence/validation-2/verification-audit.json) are available locally. Full Harbor jobs, lock files and logs remain under `/home/lain/experiments/gx10-harbor-migration-validation-2/`. The earlier seven-trial validation is retained separately. All owned migration containers were removed. The source and evaluator hashes were checked against both the GX10 and local inputs.

## Package and verifier boundaries

`build.py` generates `distinct/`, `order/` and `events/` under a fresh output directory. Each has `instruction.md`, `task.toml`, `environment/`, `solution/` and `tests/`. Runtime bundles are build artifacts, not committed source. The verified generated packages are currently at `/home/lain/experiments/gx10-harbor-tasks-v1-protected/` on GX10.

Only `environment/` enters the agent image. The original evaluator and known-correct solution are not included there. Harbor collects the candidate `/app` project after agent completion, then starts a **separate fresh verifier container** and uploads the hidden tests there. Candidate code runs as the unprivileged `grader` user. The Python verifier runs as root and keeps the score directory inaccessible to the grader. The grader uses a fixed Java classpath rather than trusting candidate edits to `deps.edn`.

`verifier.py` checks the frozen evaluator hash and scaffold/allowed-file changes, rejects symlink candidates, and records independent correctness, own-test and scope outcomes separately. Reward follows the unchanged independent evaluator. A missing/ambiguous assertion summary, zero assertions, timeout or verifier failure cannot produce a pass. The agent receives no independent evaluator feedback in the same attempt.

The original task prompt and agent-workflow text are retained. The obsolete ten-minute phrase is removed with the same transformation as the existing Python harness. Task agent timeout is unset (`None` in Harbor); each evaluator/own-test command has a 120-second timeout, and the combined verifier phase has a 300-second timeout. The validation runner's bounded oracle/no-op setup commands do not impose a coding deadline on future model agents.

Images use Clojure **1.12.4**, the installed CLI distribution **1.12.4.1618**, and Java 21 from the digest-pinned Temurin image in the manifest. Each container declares two CPUs and 2,048 MiB RAM. These minimal projects and container resources are a changed environment; model timing must not be compared directly with older host-based cohorts. `source_commit` records the original contract provenance; the agent receives a minimal task project, not a full checkout of that commit.

## Rebuild and validate

Run on GX10 from the repository root, choosing fresh output directories:

```sh
python3 benchmark/harbor-tasks/build.py \
  --output "$HOME/experiments/gx10-harbor-tasks-NEW" \
  --clojure-prefix "$HOME/.local" \
  --java-image eclipse-temurin@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0

python3 benchmark/harbor-tasks/validate.py \
  --tasks "$HOME/experiments/gx10-harbor-tasks-NEW" \
  --output "$HOME/experiments/gx10-harbor-migration-validation-NEW"
```

The builder refuses evaluator drift, unpinned Java images and existing output directories. It records hashes for generated files and original inputs. The validator acquires the existing GX10 experiment lock, runs oracle/no-op trials sequentially with retries disabled, preserves every Harbor trial and checks container cleanup before proceeding. It also runs the scaffold and score-protection fixtures. It never schedules Pi or an inference backend.

For one model-free task check, Harbor accepts an individual generated task directory with `-a oracle` or `-a nop`, for example:

```sh
HARBOR_TELEMETRY=0 "$HOME/.local/opt/harbor-eval/bin/harbor" run \
  -p "$HOME/experiments/gx10-harbor-tasks-v1-protected/distinct" \
  -a oracle -n 1 -k 1 -r 0 -o /tmp/gx10-harbor-oracle-NEW
```

Future Pi/model assessments still need the existing GX10 model/RAM/request/progress supervisor and [authenticated endpoint checks](../harbor-pilot/AUTHENTICATION-FIX-2026-10-24.md). The preserved workflow asks for web and coding tools; these task images do not automatically install or validate the full structural-editing/LSP/private-Gortex profile. Harbor reward alone does not prove normal Pi execution: retain the independent transcript/error check. The larger LangGraph delegation integration remains separate and incomplete.

The [central benchmark summary](../gx10-context/BENCHMARK-SUMMARY.md) distinguishes this migration validation from scored model results.
