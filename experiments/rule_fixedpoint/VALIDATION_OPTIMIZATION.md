# Indexed-delta validation optimization

This records the validation-optimization revision. The subsequent
[complete Lain declaration follow-up](LAIN_DECLARATIONS.md) changes the source
files and removes the generated constant-true guard from unconditional AMB
questions. The saved timings/hashes below are historical, not measurements of
that later template revision.

## Scope and mechanism

This follow-up changes only the isolated rule experiment and its opt-in tests.
Net, compiler/application semantics, runner, cell evaluator, layered dispatcher,
compound synchronization and global TTMS policy are unchanged. No commit, push
or KIROSHI mutation is included.

The declarations, not the solver, identify an unconditional rule:

```clojure
(rule-set
  (copy-rule :edge :reach)
  (step-rule :reach :reach :edge))
```

`step-rule` now accepts either three arguments (no guard) or the existing four
arguments (a Lain guard). Only a nil/omitted guard is unconditional. An explicit
`(network (x y z) true)` remains a genuine callable and is still evaluated. No
compiler inspection, constant-folding guess, host guard or new syntax is used.

### Slice 1: unconditional indexed rules

`solvers/matching-result` returns the indexed matcher's bindings directly when
the guard is absent. The matcher owns body membership, repeated-variable
equality, head demand and required-delta admission. In particular, frozen
`:require-delta?` checks no longer depend on a compiled validator to reject
unchanged bindings, including indexed-full and initial delta requests.

Source/proof transport is not bypassed: `engine/consequences`, rule authority,
grounded TTMS support, consequence publication and recursive feedback are the
same. An empty join also avoids initializing a guard.

### Slice 2: genuine guard evaluation

The genuine Lain guard is retained, including lexical capture. Once per matcher
request, `query/ready-question` initializes an immutable compiled question.
Every candidate starts from that same checkpoint, not a preceding candidate.

`query/accepted-batch?` packages three ordinary TTMS source updates using existing
publication and cell-evaluation operations, queues them, then runs propagation
once. The reference validator settles initially and after each input (four
runner invocations); the batch performs one invocation per candidate. It also
avoids repeating question initialization for every candidate.

This is only for the experiment's pure, fully bound guards. It does not change
the sequencing of external events, AMB partial choices or effectful programs.
False still rejects a binding; unresolved guards still raise an error. Invalid
candidate lengths fail explicitly. Membership/ordinal decoding inside genuine
compiled questions has not yet been removed.

## Reference and measurement

`validation-reference/matching-result` preserves the original compiled checking
path for tests/benchmarks. It is not selected by the normal solver and is not a
production compatibility facade. The original sequential `query/accepted?`
remains its oracle. All six solver strategies still share declaration and
publication semantics; they need not execute identical redundant checks.

`UNCONDITIONAL_RESULTS.edn` records the completed first slice, before guard
optimization. Its source hashes describe that historical revision. Median
end-to-end milliseconds, one warmup and five alternating-order samples:

| Workload | Compiled reference | Unconditional fast path | Speedup |
|---|---:|---:|---:|
| Chain 8 | 2,150.427 | 53.131 | 40.5x |
| Cycle 4 | 2,789.578 | 44.153 | 63.2x |
| Installed dataflow 9 | 3,624.350 | 94.917 | 38.2x |

These are measured, not the earlier estimate from phase timing. Answers, proof
counts and fixed outer topology match. They do not establish a scaling law or
competitiveness with standalone Datalog engines.

The final three-variant matrix is written to `VALIDATION_RESULTS.edn`: original
compiled validation; only the unconditional fast path; then batched genuine
guards. It additionally includes an eight-node chain with a real `<` guard and
an arithmetic guard that rejects a candidate and lexically captures a ceiling.
All **15 combinations completed**, with **75 measured samples** and no failures
or incomplete rows. Recorded source hashes matched the validation-optimization
implementation when that matrix completed.

Median end-to-end milliseconds in the final matrix:

| Workload | Compiled reference | Only unconditional fast path | Final (also batched guards) | Reference/final |
|---|---:|---:|---:|---:|
| Chain 8 | 2,232.238 | 55.194 | 54.541 | 40.9x |
| Cycle 4 | 2,856.263 | 46.449 | 45.100 | 63.3x |
| Installed dataflow 9 | 3,661.944 | 95.476 | 95.493 | 38.3x |
| Chain 8, genuine `<` guard | 2,485.667 | 2,477.632 | 1,063.735 | 2.3x |
| Selective arithmetic + lexical capture | 297.001 | 298.336 | 155.681 | 1.9x |

The first two fast columns execute the same path on unconditional rules, so
their small differences are measurement variation, not another optimization.
The guard-only gain is measured separately: 2,477.632 -> 1,063.735 ms (2.3x),
and 298.336 -> 155.681 ms (1.9x). Explicit guards are never bypassed.

Median cumulative allocation in MiB (not peak heap):

| Workload | Compiled reference | Final |
|---|---:|---:|
| Chain 8 | 3,386.2 | 90.8 |
| Cycle 4 | 4,464.2 | 77.6 |
| Installed dataflow 9 | 5,829.0 | 167.9 |
| Guarded chain 8 | 4,001.9 | 1,709.1 |
| Selective guard | 478.9 | 247.8 |

Join probes, accepted candidates, proof entries and outer Net size are unchanged
between variants. For chain 8 they are respectively 189, 28, 35 and 31; for
installed dataflow, 329, 44, 53 and 42. The speedup removes redundant inner
network work, not derivations, proof evidence or TTMS withdrawal behavior.

All variants reuse the same prepared catalog/templates. Compilation is measured
separately, outside execution timing. Timed execution includes evaluator setup,
source injection, matching, guard work, proof publication and recursive closure.
Caches are disabled, the heap is 2 GiB, and existing activation/time/proof/Net
budgets remain enforced. Allocation is cumulative main-thread allocation, not
peak heap. These solvers use no worker threads. Results are a local repeated
experiment, not an isolated-machine statistical study.

The recorded catalog/template setup observations are 2,342 ms for reachability
(shared by the chain and cycle), 472 ms for dataflow, 298 ms for the guarded
chain and 187 ms for selective arithmetic. Template compilation is not optimized:
all strategies remain prepared for switching. Charging the shared reach setup
to one chain execution gives approximately 4,574 ms reference vs 2,397 ms final
(1.9x), not 40.9x. That arithmetic still excludes JVM startup. The larger ratios
apply to execution after compilation, not a cold process launch.

## Verification

The focused batch passes 49 tests / 541 assertions, zero failures or
errors. Coverage includes independent tuple enumeration, initial/frozen delta
requirements, repeated variables, demands, empty joins, explicit false/true
guards, lexical capture, unresolved guards, candidate isolation, one settle per
candidate, malformed candidate lengths, exact physical proof identities and
withdrawal/recovery. Existing tests cover six-way solver switching, cycles,
alternative proofs, source contradictions, stale updates and no topology growth.

The broader experiment/proof/AMB/TTMS/event regression command passes **185
tests / 3,268 assertions**. Five maintained compiler/application suites pass
**122 tests / 471 assertions**. These disjoint batches total **307 tests / 3,739
assertions**, zero failures or errors; the focused batch is included, not added
again. The full default suite was not rerun in this follow-up; its known legacy
TUI/environment-I/O baseline remains documented separately.

Source inspection checked 322 parameter vectors, with none over four declared
parameters. The largest experiment source remains 225 lines. Explicit inspection
found no trailing whitespace in experiment sources/tests/results/docs, and
`git diff --check` passed. The production Clojure diff is empty.

```sh
clojure -J-Xmx2g -M:test rule-fixedpoint examples.lain.relational-datalog.proof-experiment-test
clojure -J-Xmx2g -M -m examples.lain.rule-fixedpoint.validation-benchmark experiments/rule_fixedpoint/UNCONDITIONAL_RESULTS.edn unconditional 5
clojure -J-Xmx2g -M -m examples.lain.rule-fixedpoint.validation-benchmark experiments/rule_fixedpoint/VALIDATION_RESULTS.edn guards 5
clojure -J-Xmx2g -M:test propagators.compile-2-test propagators.compiler-2-application-runtime-test propagators.compiler-2-cps-test propagators.compiler-2-rest-parameters-test propagators.compiler-2-composition-test
```

The broader AMB/TTMS regression command is the one in `VERIFICATION.md`; the
`rule-fixedpoint` alias now includes the added validation test namespace.

The original six-strategy matrix in `RESULTS.edn` is historical and is not
rewritten by this optimization. Known legacy TUI/environment-I/O baseline
failures are documented in `VERIFICATION.md`; these changes do not fix them.

## Remaining work

- Profile residual genuine-guard costs before a further optimization. Guard
  computation, ordinal decoding, membership and layered/TTMS work still execute.
- Benchmark larger joins before claiming a trie/LFTJ improvement. Joining was
  negligible in the earlier eight-node phase diagnostic, not necessarily at scale.
- Dynamic external guard dependencies/effects remain excluded. Their semantics
  cannot be discarded in the name of batching.
- The finite binary-rule/three-variable scope and potentially exponential proof
  alternatives remain unchanged.
