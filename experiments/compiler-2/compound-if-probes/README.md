# Compiler-2 compound-object and `if` probes

Current reusable transformations live in `propagators/compiler_2/runtime/lain/list.lain`: `map-list`, `filter-list`, `zip-with`, and `zip`. Load that file through `load-lain`. `(list)` denotes the known empty list, and `(list ...)` closes its final tail with that value. `empty-list?` observes completion explicitly without inspecting the internal marker. An unresolved cell remains unknown; use `cons` with an unresolved tail for an open prefix. The list networks publish available mapped prefixes and propagate known completion through reactive `if` relationships.

Supported return publication now publishes the owner with immutable live accessor references. Canonical access combines current owner and field support/state, watches the current referenced cells, and withdraws a selected tail when that branch no longer supplies it. Open list prefixes do not imply final lengths. No object graph is materialized. `when` retains its topology-installation semantics; value-dependent list map/filter/zip returns use `if` with the public `empty-list?` predicate.

## Active acceptance (2026-10-25)

`propagators.compiler-2-supported-compound-if-test` is registered in `run_tests.clj` and uses production runtime code without value-changing overrides. Its matrix covers direct `if` and `choose`, zero/one/two relays, direct/piped slots, membership/source-publication ingress, missing/independently unknown fields, inactive updates, stale revisions, retraction/recovery, and stable topology on replay. It checks the entire nested sequence `unknown → unknown → 10 → 10 → 11 → 20 → 20 → 21 → unknown → 12`. Additional cases check meaningful field contradictions, closed legacy interface rejection, long/short/open-tail selection with current owner availability and predicates, and actual live growth after a selected prefix has already published.

```sh
clojure -M:test propagators.compiler-2-supported-compound-if-test \
  propagators.compiler-2-supported-publication-test propagators.supported-slot-test
```

With the known-empty constructor contract, the combined probe passes 14/14 checks, including the seven formerly failing dynamic nested-object checks. The chain and simple controls passed 14/14 and 8/8 respectively. The predicate runner passed 61 assertions across 3 tests. The registered matrix/predicate/shortening/contradiction/interface acceptance run passed 7 tests / 425 assertions, including an explicitly open short tail that later becomes known empty. The zero-assertion guard was verified with `clojure -M:test clojure.string`, which exited 1 with `Test suite ran zero assertions`.

The active chain, simple, combined, and predicate entry points exit nonzero for any failed check, exception, exceeded propagation budget, or zero assertions. Propagation phases are bounded to 100,000 advances / 60 seconds. The standard test runner also rejects zero-assertion suites.

Before the known-empty constructor update, the conditional, lazy-conditional, GUR, GUR/TMS, combinator, canonical slot, TMS, TTMS, and new supported-slot gates passed 686 assertions across 12 suites (0 failures / 0 errors). These results establish the preceding supported-owner baseline; the constructor integration verification below is separate.

Before the known-empty contract was restored, the `apply` baseline had 11 failures / 0 errors in the functional-network suite (74 assertions) and 7 failures / 0 errors in the rest-parameters suite (55 assertions). That baseline treated every `(list)` tail as unresolved. Current rest-list tests require known empty tails, and variadic pipeline/compose examples use `empty-list?` instead of the internal marker. The fresh constructor integration run passed 661 assertions across functional-network (74), rest-parameters (55), GUR (68), lazy-conditional (39), and supported compound selection (425), with 0 failures / 0 errors. All 18 former failures pass under known completion and the public predicate. `compiler-2-empty-list-test` and `compiler-2-open-apply-test` are also registered in the default runner for the explicit predicate and live open-call contracts. Open `apply` connects a declared fixed-arity network once its required cells are addressable, and passes a live rest tail to variadic networks. Primitive operators without a declared input interface require a completed argument list; their arity is not guessed.

The final dedicated open-call suite passed 13 tests / 71 assertions, including live variadic growth, delayed and zero-argument fixed calls, reverse constraints, operator/source switching and retraction, plain/supported cycle rejection, and constant-return callees. Availability and support of consumed cons owners participate in the call even when a callee ignores its arguments. Primitive calls also depend on their explicit completion marker, so retracting that marker withdraws the result; declared fixed-arity calls do not depend on an unresolved final tail. Meaningful supported nothing and contradictions remain observations with their support/state, while information-free bottom publishes state only.

The remaining sections preserve historical diagnostic observations.


## Local open-tail experiment (2026-10-25)

`compiler2-open-tail-controls.clj` exercises `(list ...)` as a cons chain whose final tail is an unresolved cell; `(list)` is also unresolved. The local change is in `compiler/basis.clj`, `declare-list`: omit the empty-marker seed and leave an empty result cell unseeded. This is a semantic experiment, not a completed migration of marker-dependent consumers.

Run from the repository root:

```sh
clojure -Sdeps '{:aliases {:probe {:extra-paths ["test" "editor/src"]}}}' \
  -M:probe experiments/compiler-2/compound-if-probes/compiler2-open-tail-controls.clj
```

The probe reads individual elements through Lain accessors. It tests one, two and three map stages, a map/filter/map pipeline, and unresolved tails. `map-open` uses `(when xs ...)` to guard recursion by information availability; it does not claim that an unresolved tail proves the list is permanently complete.

With the local open-tail change, the map stages yield `[2 3 4]`, `[4 6 8]`, and `[3 5 7]`; map/filter/map yields `[5 7]`. Re-running `compiler2-if-chain-controls.clj` still reproduces the original incomplete `if` mapper results. Removing the final marker therefore does not fix that failure.

The selected functional-network, functional-network-GUR and lazy-conditional suites ran 47 tests / 112 assertions with 4 failures and 21 errors under the open-tail change. Most errors were `List lacks addressable topology` from the existing marker-based fixture reader; additional failures concerned the explicit empty marker and application output-port counts. No full compatibility claim or deployment is made.

### Why `when` passes and the bare recursive `if` stalls

Controlled probes isolate this from the old end marker:

- `compiler2-branch-boundary-controls.clj`: `(when xs ...)` around the exact original `if` resolves all three elements. `if true`, `if false`, an explicit zero-argument network, and an identity network inside that guard also work. Without the guard, `if (value? xs)` and `if (network? xs)` still stall after the head. Therefore the marker check is not the deciding difference.
- `compiler2-recursion-input-probe.clj`: the stalled recursive invocation already receives a list with head `2`. Its conditional selector output is contradictory. A scoped instrumentation of the private `conditional/publish` boundary records an information-free pending publication followed by a plain callable. The pending publication is a support-collection representation; the subsequent plain callable conflicts with it. Guarded execution publishes plain callables without that pending publication.
- `compiler2-pending-selection-probe.clj`: a diagnostic-only `with-redefs-fn` intercept returns raw `value/nothing` instead of publishing a wrapped pending result, only when the base is nothing and both support and premise state are empty. All 9 scalar checks for one, two and three recursive `if` map stages then pass, yielding `[2 3 4]`, `[4 6 8]`, and `[3 5 7]` (63 information-free publications suppressed across those checks).

`when` delays declaring the body until its guard has information. The passing filter also uses availability guards and `switch` rather than returning a branch through a pending selector. This avoids the conflicting pending-to-callable representation transition; it does not prove that an unresolved tail is a permanent end. The production fix now applies that narrow information-free-pending check in `stdlib/conditional.clj`. Three regressions in `compiler_2_lazy_conditional_test.clj` use explicit cons chains with unresolved tails, independent of the deferred marker migration. All three failed before the fix and pass afterward. Together with the existing conditional syntax, switching, retraction and contradiction-recovery coverage: 17 tests / 84 assertions pass. The original `compiler2-if-chain-controls.clj` also resolves all nine mapped elements without overrides. Its separate membership-transport errors still reproduce. The older failing observations above describe the pre-fix baseline; the local open-tail compatibility work remains separate.

Run any of these three scripts using the same `-Sdeps` and `-M:probe` command above, substituting the filename. These are diagnostic programs, not CI regressions.

The historical end-predicate attempt used `empty?` or `nothing?`; `compiler2-end-predicate-controls.clj` now checks `empty-list?`, `network?`, and `value?` together. It accepts no predicate argument. An unavailable cell remains unknown. `(list)` is known empty, a value, and not a network; a nonempty cons is a network/value. The runner covers selection between closed lengths and an explicitly open short tail that stays unavailable until a known empty list arrives.


The three standalone controls below are active acceptance runners backed by production code. Run each script in a separate Clojure process from the repository root. The pending-selection interception, recursion-input instrumentation, branch-boundary, and `when` open-tail scripts remain observation-only diagnostics; their private overrides are never installed by acceptance tests.

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

The two `controls` scripts print expected/actual values and collect individual exceptions as failed checks. Their final summary and exit status reflect every check; zero checks also fail.

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
