# Rule fixed-point experiment

Implemented and verified against reference commit
`7a033c366dfee12d040a72133f8cba76be16b143` (2026-10-14).
The original matrix contains **16 rows, 81 completed strategy/row combinations,
15 explicitly budget-incomplete combinations and 405 measured samples**.
There were no unexpected correctness/execution failures. Its source hashes
describe the original implementation, before the subsequent
[validation optimization](VALIDATION_OPTIMIZATION.md), which has separate results.
The later [Lain declaration follow-up](LAIN_DECLARATIONS.md) makes the complete
rule heads/bodies explicit and removes invented guards from unconditional AMB
questions; no historical timing is claimed for that changed revision.

The main architectural result is positive: the same constraint/rule declarations
and proof publication support six strategies in one evaluator Net. Indexed delta
was fastest among completed modes on every tested row. This is not a proof of an
optimal general strategy, nor a numeric speedup over a timed-out competitor.

## What was implemented

Lain declares binary rules and ordinary network guards. A fixed propagator
topology connects grounding source cells, proof DB cells, matchers, and consequence
publishers. DB feedback drives recursive derivation through the existing runner
to quiescence; there is no separate host recursive fixed-point interpreter.

One strategy control cell can switch AMB repair, checkpoint, concurrent
checkpoint, concurrent-learning checkpoint, indexed full join and indexed delta
join in the **same** evaluator Net. The tests require the same source IDs and
outer topology before/after switching, and show each selected matcher ran.

The original indexed strategies use occurrence-sensitive tables/indexes and
validate their bindings through the same compiled Lain question as AMB. The
follow-up skips redundant validation for explicitly unconditional indexed rules
and batches genuine guard inputs; the tables below remain the original baseline.
Existing AMB's positive integer choices are decoded to actual node identities;
its protocol is not changed. Guards can lexically capture immutable constants.
Host callbacks are rejected as guards. Factories/session extensions, not compiler
syntax or primitive-environment replacement, provide the Lain entry point.

An independent three-port addition contractor demonstrates genuine backward
refinement and TTMS withdrawal/recovery with layered procedures. The pure LFTJ
adapter is tested for parity but is not presented as an optimized seventh mode.

## Responsibility and minimality

| Boundary | Changes for this experiment |
|---|---|
| Net, runner constructor, production runner | None |
| Compiler/application contract | None |
| Cell evaluator, global merge/TTMS policy | None |
| Layered dispatcher, compound synchronization | None |
| Test runner | Five additive opt-in alias-registration lines |
| New code | `examples/lain/rule_fixedpoint/`, one isolated test namespace |
| Documentation/results | This dedicated experiment directory |

All inference progress, retained proofs, occurrence indexes and source-to-proof
routing are immutable cell values. Pools, budgets and allocation/activation
counters remain runtime-owned, outside Net. No global multimethod is registered
by this experiment. The declaration and proof publisher do not branch on solver
strategy; binding discovery owns that choice.

## Evidence and robustness

A proof conjoins its actual grounding cell premises with an active rule authority.
Different proof entries are alternatives. This is a domain-level OR over ordinary
conjunctive TTMS evidence, not a new global TMS rule.

Proof identity is rule + full binding + flattened grounding source IDs. Epochs
and statuses remain in TTMS evidence, not the identity. Thus a grounded cycle
cannot become its own authority; repeated traversal does not create an unbounded
proof tree. Distinct source sets may still cause exponential proof growth.

Routing follows changed source states **and bases**. Withdrawal travels without
a usable payload. Same-epoch payload conflicts invalidate dependent proofs as
supported contradictions, preserving their original premises; no retraction or
epoch is fabricated. A newer source version repairs them through existing TTMS
subsumption. Same-tuple alternative proofs are meaningful deltas.

The addition contractor keeps bounds and refinements as separate justifications,
including at the computation-publication boundary. Without that boundary, a
supported result can compact the weaker computation needed after withdrawal.
This was fixed by composing publication, not changing TTMS compaction.

For `x+y=z`, `y={2}, z={5}` yields `x={3}`. Withdrawing the y assumption widens
x and y to `0..5`; reasserting `y={1}` yields `x={4}`. Infeasible domains become
local contradictions and newer assumptions can repair them.

## Verification

See [VERIFICATION.md](VERIFICATION.md) for exact commands and baseline details.
The final focused command passed **23 experiment tests / 131 assertions** and
**15 existing proof tests / 206 assertions**. Existing targeted AMB and TTMS/event
regressions also passed.

The default suite is **not green**: with local-server access it reports **4,688
passes, 3 failures and 16 errors**. The committed pre-experiment runner reproduces
the exact result. Three legacy TUI/environment-I/O namespaces still use removed
compiler forms. They were not patched or their assertions weakened here.
The final targeted regression batch passed **174 tests / 3,064 assertions**.
The signature audit checked **93 signatures**, none over four parameters; the
largest experimental source file is **225 lines** (test namespace: 307).

## Timing protocol

Recursive end-to-end measurements include evaluator/index/proof initialization,
source injection and derivation to quiescence. Independent BFS computes expected
answers only; it does not supply runtime answers. Frozen measurements isolate one
matched snapshot/occurrence-delta window and must not be called end-to-end search.

Compilation/templates and shared pool creation are outside per-sample timing and
reported separately. One warmup, five alternating-order measured samples, caches
disabled, four workers, 2 GiB JVM heap. Per sample: 30 seconds, one million
aggregate activations, 10,000 proofs, 20,000 active Net entries. Bounds are
cooperative at activation boundaries. Incomplete warmups are not repeated.

The frozen oracle independently enumerates finite tuples before timing. Raw data
retain source hashes, wall time, predicate activations (including repeated
checks), join tuple probes, proof/topology counts, activation counts and cumulative
main/completed-worker allocation. Allocation is not peak retained heap; failed
worker runs can have incomplete allocation coverage.

Platform: Java **25.0.2**, **10** available processors, **2 GiB** maximum heap.
Reachability catalog/template setup: **2.378 s**; dataflow setup: **0.502 s**.
These are separate setup observations, not amortized into the following medians.
This was a local repeated experiment, not an isolated-machine statistical study.

### Recursive execution, end to end

Median seconds, five measured completed runs. **B** means the warmup exhausted
the time or aggregate-activation budget; no median or speed ratio is assigned.

| Workload | Repair | Checkpoint | Concurrent CP | Concurrent + learning | Indexed full | Indexed delta |
|---|---:|---:|---:|---:|---:|---:|
| Chain 4 | 7.433 | 4.566 | 1.373 | 1.422 | 0.790 | 0.306 |
| Chain 8 | B | B | 20.247 | 21.239 | 10.780 | 2.067 |
| Chain 12 | B | B | B | B | B | 5.399 |
| Diamond, 1 stage | 4.842 | 3.039 | 0.925 | 0.952 | 0.388 | 0.198 |
| Diamond, 2 stages | B | 28.272 | 8.967 | 9.390 | 5.083 | 1.525 |
| Cycle 4 | 18.256 | 10.783 | 3.232 | 3.416 | 8.412 | 2.687 |
| Disconnected 8 | B | 21.372 | 5.779 | 6.069 | 0.400 | 0.203 |
| Installed dataflow 9 | B | B | B | B | 20.351 | 3.542 |

Completed answers match independent BFS: chains produce 6/28/66 reachability
facts, diamonds 5/19, cycle 16, disconnected components 6. The actual installed
dataflow graph produces **36 reachability facts and 9 taint facts**. Its edges
come from nine installed primitive cells, not an arbitrary production program.

On chain 8, delta was approximately **9.8× faster than completed concurrent
checkpoint** and **5.2× faster than indexed full**. On installed dataflow it was
approximately **5.7× faster than indexed full**. The other dataflow modes did not
complete within the budgets, so they receive no numeric speedup claim.

### One frozen rule snapshot and occurrence delta

These numbers do **not** include recursive closure construction. Median seconds:

| Workload snapshot | Repair | Checkpoint | Concurrent CP | Concurrent + learning | Indexed full | Indexed delta |
|---|---:|---:|---:|---:|---:|---:|
| Chain 4 | 2.416 | 1.507 | 0.455 | 0.473 | 0.292 | 0.193 |
| Chain 8 | 25.924 | 10.526 | 2.870 | 3.005 | 2.000 | 0.576 |
| Chain 12 | B | B | 9.282 | 9.797 | 5.210 | 0.861 |
| Diamond, 1 stage | 2.370 | 1.497 | 0.453 | 0.471 | 0.189 | 0.095 |
| Diamond, 2 stages | 14.896 | 7.016 | 2.212 | 2.349 | 1.501 | 0.375 |
| Cycle 4 | 2.484 | 1.540 | 0.453 | 0.472 | 1.566 | 0.398 |
| Disconnected 8 | 18.665 | 10.248 | 2.879 | 3.015 | 0.187 | 0.095 |
| Installed dataflow 9 | 29.729 | 14.707 | 5.196 | 5.475 | 0.759 | 0.097 |

Repair in the last row approached the 30-second cap, but all five measured runs
completed. Do not infer a robust general scaling law from this boundary case.

### Work, allocation and fixed topology

Selected medians. Allocation is cumulative **MiB allocated**, not resident/peak
memory; the heap limit remained 2 GiB. Outer topology counts stayed fixed during
each run, and tests require no growth on duplicate updates or strategy switches.

| Workload / strategy | Aggregate activations | Predicate activations | Proof entries | Outer Net entries | Allocated MiB |
|---|---:|---:|---:|---:|---:|
| Chain 8 / concurrent | 972,210 | 3,584 | 35 | 31 | 114,645.2 |
| Chain 8 / indexed full | 141,093 | 112 | 35 | 31 | 17,593.7 |
| Chain 8 / indexed delta | 26,631 | 21 | 35 | 31 | 3,383.4 |
| Chain 12 / indexed delta | 69,533 | 55 | 77 | 39 | 8,953.0 |
| Dataflow 9 / indexed full | 267,188 | 212 | 53 | 42 | 33,342.7 |
| Dataflow 9 / indexed delta | 45,806 | 36 | 53 | 42 | 5,820.9 |

The reduction is not merely parallel wall-clock scheduling: delta reduces actual
binding/predicate and propagation work. Absolute allocation remains high in this
compiled, layered/TTMS-heavy prototype. No claim of competitiveness with an
optimized standalone Datalog engine follows from these internal comparisons.

The concurrent chain-12 and dataflow warmups exhausted aggregate work at roughly
**1,000,004 activations** (a few simultaneous in-flight checks), before 30 seconds.
Other incomplete rows exhausted elapsed time. No run hit the proof/Net-size caps.

### Acceptance decision

- **Architectural goal: demonstrated.** Rules, DB evidence, publication and the
  runtime are unchanged when the solver control cell changes. Proof content is
  identical across all six strategies, including a demanded head binding.
- **Frozen matching hypothesis: supported on these fixtures.** Delta beats both
  repair and checkpoint on four fully completed larger snapshots (7–9 nodes).
- **Strict larger end-to-end criterion: not established.** There are not two
  larger cases where repair and checkpoint both complete. Bounded completion and
  comparisons against completed concurrent/full-index modes are positive evidence,
  but do not satisfy that stronger numeric criterion.
- **Strategy is still relevant.** Concurrent checkpoint beats sequential full
  indexing on the cyclic workload; delta wins here by reducing repeated work.
  Concurrent learning adds modest overhead and does not improve these opaque,
  fully-bound membership questions.

See [RESULTS.edn](RESULTS.edn) for all samples, errors, budgets, counters and source
hashes. `SMOKE.edn`, interrupted development runs and the partial checkpoint are
not substituted for the final matrix.

## Expressiveness and limits

This demonstrates a strategy-independent rule evaluator, not full Datalog, a
globally optimal search strategy, or an LLM coordination system. V1 has a fixed
finite node domain/catalog, binary predicates, at most two body occurrences and
three variables, and pure guards. Dynamic external guard dependencies/effects,
negation, aggregation, abduction and unbounded terms are excluded.
The original Lain factories cover copy and path-step rules. The subsequent
declaration follow-up also exposes explicit bounded relation/head/body data
through opt-in `relation`, `rule` and `rule-set` bindings. It does not add a
general, unrestricted Datalog language.
Guard purity is a contract, not a security/effect sandbox.

Backward head demand restricts matches against existing facts; it neither invents
missing body facts nor retracts already established valid facts. General cross-rule
demand planning is deferred. Arithmetic refinement demonstrates the stronger
bidirectional constraint behavior separately; Horn implication is not automatically
reversible.

Current AMB comparison questions use a fully bound membership predicate plus the
supplied Lain guard. They are the existing strategy implementations, not the best
possible propagator-native relational contractor. Solver calls are synchronous
within one outer activation; concurrent modes partition the child search, not
outer rule activations or mutable shared DB state.

Remaining local costs include compiled candidate application/TTMS projection,
cumulative proof publication and routing discovery. LFTJ integration, more
factorized partial-match constraints, separating tuple changes from proof-only
changes, and per-proof publication cursors are possible next experiments, not
implemented performance claims.

No production promotion, KIROSHI mutation, commit or push is part of this task.
