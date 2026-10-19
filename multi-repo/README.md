# Lain multi-repository workspace

Google `repo` manages independent checkouts of `Semi-0/lain-infrastructure`,
`lain-compiler`, `lain-runtime-clojure`, `wired`, and `datalog-research`.
The manifest follows the first four projects' applicable default branches:
`main` everywhere except `wired`, which currently defaults to
`codex/xr-regression-followup`.

Install `repo` with `brew install repo`, then run from this research checkout:

```sh
bash tools/workspace init
bash tools/workspace sync
```

The default destination is the sibling directory `../lain-workspace`.
Use `bash tools/workspace --workspace /absolute/path init` for another location.
Initialization creates a local Git repository containing only the manifest at
`WORKSPACE/.manifest-source`, and copies the launcher to `WORKSPACE/workspace`.
Its bootstrap commit is local setup metadata; it does not commit this project
or create a remote manifest repository. Git must already have access to the
private infrastructure and compiler repositories.

From the new workspace:

```sh
./workspace list
./workspace status
./workspace sync
./workspace forall -c 'git status --short'
./workspace test
```

Commands select **all five projects by default**. Selection uses native `repo`
arguments, including project paths and manifest groups:

```sh
./workspace test lain-compiler
./workspace forall -g compiler,runtime -c 'git log -1 --oneline'
./workspace sync wired
```

`test` runs each project's declared `clojure -M:test` command serially,
continues through failing projects, and returns a failing exit status if any
project fails. ClojureScript and Rust checks remain separate commands:

```sh
./workspace forall datalog-research -c 'clojure -M:editor/cljs-test && node target/lain-editor/tests.js'
./workspace forall datalog-research -c 'cargo test --locked --manifest-path leapfrog-rs/Cargo.toml'
```

The new checkouts contain remote committed revisions. Existing local changes
in the original research checkout remain there and are not copied or committed.
Sync does not use force-reset options. Make topic branches inside the selected
projects before committing work; `repo` may initially leave projects detached
at their manifest revisions.

Dispatch runs commands in each project directory. It does not copy patches
between projects, infer code ownership, start coding agents, or override
Clojure's pinned Git dependency revisions with sibling working trees. Those
are separate decisions; this setup preserves each project's dependency contract.

The local manifest and launcher are not published. Their source templates live
in this research repository so the setup can later be reviewed and committed.
Update manifest revisions deliberately, then update the local manifest source
and commit it before syncing. Do not blindly change `wired` to `main`.

## Committing changes with Google repo

Run these commands from the new workspace root. `repo` coordinates independent
Git repositories: each project has its own branch and commits. There is no
atomic cross-repository commit.

Before editing, create a topic branch in the projects involved:

```sh
repo start codex/my-change lain-infrastructure lain-compiler
# Alternatively, create the branch in every project:
# repo start codex/my-change --all
```

Review changes and run the relevant checks before staging:

```sh
./workspace status
./workspace forall -c 'git diff --stat'
./workspace test lain-infrastructure lain-compiler
```

Stage only reviewed paths and commit in their owning repository. Replace the
example paths and message below with the actual change; repeat for each project.
Avoid blanket staging when unrelated work is present.

```sh
git -C lain-infrastructure add -- src/example.clj test/example_test.clj
git -C lain-infrastructure diff --cached
git -C lain-infrastructure commit -m "Describe the infrastructure change"
```

For shared commands, `./workspace forall PROJECT... -c 'COMMAND'` dispatches
to the selected repositories. Keep staging and commit review explicit per
project; dispatch does not determine which files belong in a commit.

For cross-project changes, commit dependency changes first, then deliberately
update consumers' pinned Git revisions where required. Sibling checkouts do not
automatically replace pinned dependencies. Run the affected consumers' checks
against the intended revisions before committing them.

Pushing is a separate, explicit step; neither committing nor `repo sync`
publishes local commits. Changes in the original research checkout also remain
separate from this workspace until explicitly transferred.
