# Structural editing reliability rerun

The user requested stopping the active agent, installing structural editing, and rerunning the entire current Ordering task. Reliability takes priority over comparable benchmark numbers. This is a changed-harness trial, not another comparable attempt in the earlier matrix.

## Preserved earlier work

Queue 7's controller and deferred context tail were paused, the active Ordering subprocess was interrupted, and the solo scheduler unwound through owned-process/model cleanup. Earlier checkouts, partial patches, logs, results, and interruption evidence remain under `/home/lain/experiments/gx10-delegation-python-queue-7`. Distinct's separately verified correctness pass is unchanged. Earlier scheduling is stopped while this single-task reliability trial runs.

## Added extension

- Upstream: https://github.com/gtrak/cljform
- Pinned commit: `a0c30e4be43a0c96d9b3f1fee44184b6feeb5600`.
- Isolated installation: `/home/lain/experiments/gx10-structural-tools-1`.
- Rust toolchain installed inside that directory, without changing global shell configuration.
- CLI and extension identities, Rust version, Cargo lock hash, and npm lock hash are recorded in `manifest.json` there.
- No global Pi extension registration or symlinks. The harness loads the pinned extension explicitly.

The five tools are `clj_forms`, `clj_tree`, `clj_get`, `clj_draft`, and `clj_edit`. The effective tool allowlist exposes them, and startup admission requires all five before model requests can pass upstream. The prompt directs the agent to edit forms by handle and use explicit repair candidates rather than count brackets. Kondo runs after successful `clj_edit` calls; structural calls are audited separately. Existing Lens, Gortex, vigilant, public web access, RAM monitoring, cleanup, and command limits remain.

The extension's own structural edit operation refuses an invalid result. Its hook on ordinary text edits reports syntax/shape problems after the write; that hook does not make all possible Bash/text writes transactional. Correct structure does not establish semantic correctness.

## Fresh trial

Frozen harness: `/home/lain/experiments/gx10-delegation-python-harness-11`.

Run directory and service: `gx10-structural-reliability-1`.

The task is the full original `order` contract, from its original pinned checkout/stubs and a new Pi history. Model remains Ornith NVFP4 with MTP, 128K context, temperature 1.0, one request at a time. There is no overall coding deadline. Individual shell commands/evaluation and progress supervision retain their existing bounds. Root `trial.json` marks this as changed-harness, fresh-start, and ineligible for cross-run speed comparisons; core cell artifacts reuse the existing coding lifecycle.

Success still means passing the unchanged independent evaluator. Workflow and tool-use evidence remain separate. A successful structural smoke proves tool operation, not that the model will use it effectively or solve Ordering.

## Validation evidence

Upstream `cargo test --locked --jobs 2` and the release build passed; raw output is retained in the installation directory. An opt-in real Pi wire test exercises `clj_get`, `clj_edit`, and `clj_forms` against the pinned binary, while asserting that all five tools are presented to the provider. Another test proves malformed content is refused without writing, and explicit repair produces a parseable file with a repair diff.

The harness also tests artifact identity checks and refusal of generation when a structural tool is missing. Live tool tests use scripted provider responses; the native NVFP4 rerun supplies the model-reliability evidence.
