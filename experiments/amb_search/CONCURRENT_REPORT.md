# Concurrent p:amb without reevaluation

2026-10-19, Java 27, builder-level Pythagorean search on domain `1..5`.
This experiment runs workers on one shared logical network. It does not partition
the domain into separate searches or replace AMB with a checkpoint solver.

## Admission and correctness

The implementation selects a FIFO prefix of activations with independent,
explicit cell read/write footprints. No selected activation's writes may
overlap another's reads or writes. Workers evaluate that batch against the
same immutable network; the coordinator applies their patches once, in FIFO
order, through the existing evaluator. Pending tasks remain queued until commit,
so ordinary task deduplication is preserved.

There is no speculative execution, invalid-result discard, retry, or additional
activation calculation. A dependent task waits until its predecessor's writes
have committed. Unknown activations run exclusively on the coordinator, avoiding
an unnecessary thread handoff.

The experiment audits the existing builder's `p:amb` and `p:require` activations:

* Choice reads include domain, repair, and both output cells, because publication
  reads the previous control/output state. Only vector/unusable domains qualify;
  arbitrary compound-slot reads are not assumed to be described by graph edges.
* Require reads include its predicate, output, and every source referenced by
  the predicate's support, as required by `current-support?`.
* The original activation function and graph interface must still match the
  fixture. Replacements do not inherit a trusted footprint.

Layered calculations and declaration-producing learning/backtracking activations
run exclusively. Their current API receives the entire network and does not
declare a complete read/write set. Treating just their input edges as that
contract would be unsound. The runner accepts a complete access plan explicitly;
it does not infer safety for arbitrary pure functions or support effects.

## Measurements

One warmup followed by five samples per mode, alternating order, cache disabled.
The pools and fixture are constructed outside measurement. Counters include
nested ordinary propagation inside activations. Every sample checks exact final
choice records, repair request, learned knowledge and `[3 4 5]` against FIFO.

| Mode | Median ms | Range ms | Total activations | Parallel batches | Largest batch |
| --- | ---: | --- | ---: | ---: | ---: |
| Ordinary FIFO | 989.8 | 929.9–1,017.4 | 12,223 | 0 | 1 |
| Direct coordinator control | 989.5 | 936.6–1,015.8 | 12,223 | 0 | 1 |
| Two workers | 979.8 | 948.0–1,082.5 | 12,223 | 110 | 2 |
| Four workers | 995.5 | 940.6–1,076.0 | 12,223 | 110 | 3 |

All modes commit the same 1,323 outer activations. The remaining 10,900 activations
occur in nested propagation. Worker admission creates no extra evaluations.
Actual simultaneous worker execution is tested with a two-worker latch, rather
than inferred merely from a thread pool's existence.

**No clear speedup is demonstrated.** The two-worker median is about 1% lower,
within the broad overlapping sample ranges. Four workers expose only three
independent choices at a time and provide no median improvement. These are small
single-JVM samples, not a statistical significance claim.

The current footprint contract exposes only small independent activations.
Patch application remains serialized, and the expensive layered calculations
remain exclusive. Earlier profiling measured only about 8 ms of choice
activation self time across the whole search, compared with hundreds of
milliseconds of patch evaluation and layered work. Scheduling small operations
on workers cannot remove that larger cost.

This does not show that all concurrent propagator designs are slow. A stronger
experiment would first give expensive pure computations complete ownership and
read/write contracts, then schedule their independent work without reevaluation.
Reducing propagation granularity is another distinct experiment. Neither is
implemented here, and this experiment is not installed in the editor or runner.

## Verification and reproduction

Focused runner tests: **17 tests, 81 assertions, zero failures/errors**. They
cover actual overlap, read-after-write ordering, exactly-once dependent
calculation, FIFO deduplication, error identity, domains 4 (exhaustion) and 5
(solution), exact activation-count parity including nested execution, learned
knowledge, choice/retraction state and quiescent reruns. Lint and whitespace
checks passed. No production file was changed.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.concurrent-benchmark 5 5
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:test propagators.experimental.runner.amb-concurrent-test propagators.runner-test propagators.experimental.runner-constructor-test
```

The benchmark's process-global activation counter must run in an isolated JVM.
All initial speculative implementation paths were replaced; their measurements
are excluded. [CONCURRENT_RESULTS.edn](CONCURRENT_RESULTS.edn) contains the final
no-reevaluation samples. A raw diagnostic metric label was normalized to
`activation-dispatches`, which includes coordinator execution as well as workers.
The raw measured report remains at `/tmp/amb-concurrent-no-repeat.edn`.
