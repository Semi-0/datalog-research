# Compiler-2 compound-object and `if` probes

Standalone diagnostic scripts copied unchanged from the temporary probes discussed on 2026-10-25. They preserve observed failures for inspection; they are not registered in `run_tests.clj` or presented as passing CI regressions. Run each script in a separate Clojure process from the repository root.

| File | Purpose |
| --- | --- |
| [compiler2-if-chain-controls.clj](compiler2-if-chain-controls.clj) | Compare one, two, and three recursive `if`-based map stages; check preconstructed nested objects through chained calls; isolate membership versus publication input transport. |
| [compiler2-if-compound-controls.clj](compiler2-if-compound-controls.clj) | Eight simple controls for lists/nested structs returned by `if`, chained accessors, and another function call. |
| [compiler2-compound-if-probe.clj](compiler2-compound-if-probe.clj) | Combined three-stage mapping and a live conditional-object scenario, including planned branch-switching and retraction checks. |

## Run

```sh
clojure -Sdeps '{:aliases {:compound-if-probe {:extra-paths ["test" "editor/src"]}}}' \
  -M:compound-if-probe experiments/compiler-2/compound-if-probes/compiler2-if-chain-controls.clj

clojure -Sdeps '{:aliases {:compound-if-probe {:extra-paths ["test" "editor/src"]}}}' \
  -M:compound-if-probe experiments/compiler-2/compound-if-probes/compiler2-if-compound-controls.clj

clojure -Sdeps '{:aliases {:compound-if-probe {:extra-paths ["test" "editor/src"]}}}' \
  -M:compound-if-probe experiments/compiler-2/compound-if-probes/compiler2-compound-if-probe.clj
```

The scripts reuse existing test helpers, hence the additional test source path. No live Harbor or Pi handlers are installed.

## Read the results

The two `controls` scripts print expected/actual values and catch individual exceptions. Their process exit status can be zero even when a case prints an unexpected value or an error. Inspect the records; exit zero is not an all-pass result.

The combined probe exits nonzero for failed checks if it reaches its summary. An uncaught exception can stop it earlier. Its propagation guard allows at most 100,000 advances or 60 seconds per phase. Checks after an exception have not run.

## Observed baseline

Against source revision `92baa48` on 2026-10-25:

- All eight simple compound/`if` controls passed.
- A single recursive increment-map resolved its first element as `2`, but later elements remained `:bool4/nothing`. The probed elements in two- and three-stage chains remained unresolved.
- Preconstructed nested structs selected through `if` and two relay calls produced `10`.
- Late struct fields updated through the existing publication helper produced `10`, with and without `if`.
- Membership-form input updates raised `Cannot merge membership evidence with this value`, with and without `if`. This is a separate transport-compatibility observation, not evidence that `if` caused it.
- The combined probe stopped at that membership merge error, so its later branch-switching and retraction checks were not verified.

These are bounded observations, not a root-cause diagnosis or a compiler fix.
