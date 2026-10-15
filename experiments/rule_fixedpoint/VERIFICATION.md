# Verification record

## Publication verification — 2026-10-15

The pending experiment and compiler/session integration checks passed **201
tests / 1,210 assertions, zero failures/errors**:

```sh
clojure -J-Xmx2g -M:test rule-fixedpoint \
  examples.lain.relational-datalog.proof-experiment-test \
  propagators.experimental.amb-compiler-checkpoint-test \
  propagators.experimental.amb-concurrent-compiler-builder-test \
  propagators.compiler-2.runtime.session-extension-test \
  propagators.compile-2-test propagators.compiler-2-application-runtime-test \
  propagators.compiler-2-cps-test propagators.compiler-2-rest-parameters-test \
  propagators.compiler-2-composition-test
```

The full default suite, with temporary localhost sockets permitted, reported
**4,688 passes, 3 failures, 16 errors**. The failures remain confined to the
three legacy TUI/environment-I/O namespaces listed below. This is not a claim
that the full suite is green. No production compiler or runtime source is
modified by this publication; the new operators use an opt-in session extension.

A fresh run using `git show HEAD:run_tests.clj` reproduced the same totals and
the exact same failing test names and locations. Comparing the two logs' `FAIL`,
`ERROR` and `TOTAL` lines produced no differences. Thus this publication adds no
observed default-suite regression; it does not repair the baseline failures.

Staged whitespace validation passed. Document relocations were recognized as
renames, with no unmatched deletions. Generated third-party `.clj-kondo/imports`
files are excluded from publication.

## Earlier verification

Date: 2026-10-14. This experiment does not change production runtime sources.

The latest [complete Lain declaration follow-up](LAIN_DECLARATIONS.md) passed
646 focused assertions after the final extension/guard-control change. Its
broader regression run passed 3,838 assertions; these overlapping runs are
reported separately. The actual dataflow demo verified withdrawal/recovery and
fixed topology. The original timing matrices remain historical, not rerun
performance claims for the changed declarations/question template.

The subsequent [validation optimization](VALIDATION_OPTIMIZATION.md) passed
307 tests / 3,739 assertions across the updated targeted experiment/AMB/TTMS and
maintained compiler/application batches. Its 75-sample benchmark and source
hashes are saved separately in `VALIDATION_RESULTS.edn`. No production runtime
source changed. The remaining sections record the original implementation
verification; their historical counts/hashes are not claimed to describe the
updated implementation.

## Experimental tests

```sh
clojure -M:test rule-fixedpoint examples.lain.relational-datalog.proof-experiment-test
```

- New experiment: **23 tests, 131 assertions, zero failures/errors**.
- Existing proof relation: **15 tests, 206 assertions, zero failures/errors**.
- Combined command: **337 passes, zero failures/errors**.

The experiment verifies:

- range-restricted declarations and correlated bindings;
- all six strategies executing in **one** evaluator Net, with unchanged source
  identities/topology and evidence that each selected matcher actually ran;
  retained proof content is identical across strategies, including head demand;
- compiled Lain guards, immutable lexical capture, and rejection of host guards
  or a missing shared query adapter;
- an unresolved guard fails explicitly rather than becoming false;
- recursive closure and parity with independent BFS on chains, diamonds,
  cycles and disconnected components;
- grounded cyclic withdrawal, alternative derivations, late grounding and
  same-tuple/new-proof deltas;
- source and rule-authority withdrawal/restoration, delayed stale updates,
  duplicate injection and quiescent reruns;
- exact physical grounding-cell IDs and original timestamps;
- canonical binding order, equal source sets with deliberately colliding UUID
  hashes, and fixed-domain representatives for equal node encodings;
- same-epoch conflicting payloads invalidating already-published consequences
  as supported contradictions, then recovering at a newer version;
- occurrence-sensitive self-join deltas and existing pure LFTJ parity, including
  distinct heterogeneous identities such as number `1` and string `"1"`;
- reactive head demands narrowing matches without abduction;
- bidirectional addition domains, withdrawal-driven widening, local contradiction
  and repair, using existing layered procedures;
- an explicit aggregate activation budget, not incomplete-as-empty answers.

## Existing targeted regressions

The maintained AMB runtime/search/Lain/checkpoint/strategy/parallel/enumeration/
swappable/brute-force/transitive/learning/concurrent-learning suites were run:
**79 tests, 1,029 assertions, zero failures/errors**.

Support, support collection, layered support, TTMS primitives, support transport,
TTMS branching and events were run: **57 tests, 1,698 assertions, zero
failures/errors**.

The final combined targeted command passed **174 tests / 3,064 assertions, zero
failures/errors**. It includes the four groups above, so their totals must not be
added again as if they were disjoint verification.

```sh
clojure -M:test rule-fixedpoint examples.lain.relational-datalog.proof-experiment-test \
  propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test \
  propagators.experimental.amb-lain-test propagators.experimental.amb-checkpoint-test \
  propagators.experimental.amb-strategy-test propagators.experimental.amb-parallel-test \
  propagators.experimental.amb-enumeration-test propagators.experimental.amb-swappable-solver-test \
  propagators.experimental.amb-lain-brute-force-test propagators.experimental.amb-transitive-solver-test \
  propagators.experimental.amb-learning-test propagators.experimental.amb-learning-checkpoint-test \
  propagators.experimental.amb-concurrent-learning-test propagators.support-test \
  propagators.support-collection-test propagators.layered-support-test \
  propagators.ttms-primitives-test propagators.support-transport-test \
  propagators.ttms-branching-test propagators.event-test
```

The final matrix completed with 16 rows, 81 complete mode/row results and 15
budget-incomplete results. All 405 measured samples are retained. Source-hash
comparison returned true. Signature inspection found 93 signatures and zero
over-four-parameter declarations. `git diff --check` passed.

## Default-suite baseline (not green)

```sh
clojure -J-Xmx2g -M:test
```

Inside the filesystem/network sandbox: **4,650 passes, 3 failures, 22 errors**.
Socket-binding tests could not create temporary local servers.

With permission for temporary local sockets: **4,688 passes, 3 failures, 16
errors**. The same result was reproduced with the **committed** test runner,
which does not register or load the new experiment:

```sh
git show HEAD:run_tests.clj | clojure -J-Xmx2g -Sdeps '{:aliases {:baseline-check {:extra-paths ["test"]}}}' -M:baseline-check -e "(load-string (slurp *in*)) (run-tests/-main)"
```

Remaining failing namespaces:

| Namespace | Passes | Failures | Errors |
|---|---:|---:|---:|
| `propagators.compiler-2.runtime.tui.block-compiler-test` | 0 | 0 | 5 |
| `propagators.compiler-2.runtime.tui.versioned-commit-test` | 42 | 0 | 8 |
| `propagators.compiler-2.runtime.environment-io-test` | 11 | 3 | 3 |

Their fixtures use removed forms (`def`, `def-net`, `def-constraint`, `def-cells`)
or expect those sources to load. This task neither restores compatibility forms
nor migrates the legacy TUI tests. The default suite must not be described as green.

## Development findings

Focused tests exposed and drove fixes for three local integration mistakes:

1. A retracted authority's empty payload support must not mean “authority not
   required.” Publication now requires an active, usable authority.
2. A retractable domain computation must retain separate justifications before
   TTMS compaction; this is an activation/publication extension, not a TTMS change.
3. Same-epoch payload conflict changes the source base but not its clock/status.
   Indexed routing now observes that change and propagates a contradiction with
   the original proof support; it does not fabricate a fresh epoch or retraction.
4. The shared stable-ID helper hashes printed data; callers must canonicalize
   bindings/source sets first. Local keys now normalize declared variable order,
   source UUID insertion and fixed-domain representatives. The shared helper is
   unchanged; old interrupted measurements were discarded.

The budget test also caught a mistaken vector where the budget wrapper required
the existing task-queue type. The final wrapper and fixture use actual queues and
node ID tokens.

`SMOKE.edn` is an earlier one-sample harness check, not final performance evidence.
Interrupted development timing runs were discarded before the canonical-key
audit was finished. Only `RESULTS.edn` is used for the final benchmark report.
The current harness also writes `RESULTS.edn.partial.edn` after each completed
case row; that checkpoint is explicitly marked partial, not final evidence.
