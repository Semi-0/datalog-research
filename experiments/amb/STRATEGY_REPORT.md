# Three Network-Owned Solver Experiments

Date: 2026-10-13. Status: isolated builder-level prototypes; no production migration.

## Outcome

All three experiments returned the same answers as the previous examples and
independent exhaustive enumeration. The strongest architecture result is that
checkpoint search can be driven by **three ordinary propagators**, using an
explicit frontier in cells, without an external DFS loop or a scheduler change.

- **Indexed no-good resolution:** approximately halves resolution calls, but
  measured runtime improves only 0.9–2.6%. It does not establish a significant
  end-to-end speed improvement on this workload.
- **Domain refinement:** solves the measured cases in 1.30–1.73 ms, with six
  activations and no guesses. This uses an extensional relation contractor and
  a simpler plain-domain representation, not the original TTMS scalar topology.
  It is evidence for batching/refinement, not a fair isolated TTMS optimization.
- **Network-owned checkpoints:** 1.67–1.72 times faster than matched withdrawal
  DFS, while 13–14% slower than external checkpoint DFS. The extra overhead buys
  explicit, composable solver control topology.

## Fixed question and ownership

Find positive integers `a`, `b`, and `c` in `1..n` satisfying
`a <= b <= c` and `a*a + b*b = c*c`. The same base predicate is used in all modes.
The task is first solution or finite exhaustion, not global optimization.

Preserved: production Net shape, runner constructor, runner, cell evaluator,
compiler, layered dispatcher, TTMS merge/support rules, and compound behavior.
All implementation additions are under `examples/lain/amb/`, with one isolated
test namespace. New named functions have at most four explicit arguments.
Earlier documentation moves and checkpoint additions were preserved separately.
No KIROSHI model update, commit, or push is included.

| Module | Owns | Does not own |
|---|---|---|
| [incremental_learning.clj](../../examples/lain/amb/incremental_learning.clj) | Immutable no-good index/worklist and learner activation | New resolution semantics, repair policy, scheduler |
| [domain_refinement.clj](../../examples/lain/amb/domain_refinement.clj) | Finite-domain intersection, relation projection, isolated declaration-compatible patch evaluation | Production merge changes, live TTMS domain transport |
| [network_search.clj](../../examples/lain/amb/network_search.clj) | Frontier, one-job execution, result collection | Question constraints, generic scheduling, cross-branch learning |
| [strategy_benchmark.clj](../../examples/lain/amb/strategy_benchmark.clj) | Observation, oracle checks, timings and allocation | Solver state or production execution |

### 1. Indexed learning inside existing AMB

Reuse the existing `resolve-choice`, no-good subsumption, certificate validation,
context conditions, choices, and repair propagators. The experimental builder
extends the existing learner's declared outputs with one ordinary storage cell
and replaces only that constructed instance's activation. It does not patch the
existing learner implementation.

The cell stores `:knowledge`, `:by-source`, `:agenda`, `:queued`, `:resolutions`,
and static domains as immutable data. Only sources affected by an inserted or
removed no-good enter the worklist. Equivalent/subsumed insertions do no work.
Resolution still reaches the original finite-domain closure; this is not
watched-literal propagation or a new unit-propagation solver.

### 2. Refine finite domains before choosing

Each question cell holds `{:experiment/domain #{...}}`. Updates intersect sets;
an empty set means inconsistent, not unknown. An ordinary bidirectional
`p:relation` projects satisfying tuples back onto its input/output domain cells.
Several such contractors can form chains and cycles, reaching a fixed point.

The custom patch branch is local to this example and composes with the unchanged
iterator constructor. The ordinary production patch evaluator is the fallback.
There is no precomputed solution table hidden in setup: Cartesian enumeration
runs inside the contractor and is included in timing and predicate counters.

This is an **extensional** contractor: initially it checks `n^3` tuples. It
materializes the Cartesian product locally, so it is not a scalable symbolic
Pythagorean contractor. Unary projections can retain incompatible combinations;
they are sound domain restrictions, not proof that every remaining combination
works. Further contraction/choice is still necessary in general.

Domain 12 has two valid triples; after initial refinement, network-owned search
makes one choice and returns `[3 4 5]`. This is tested, but not part of the timed
domains. Chained arithmetic relations and an inconsistent cycle are also tested.

### 3. Checkpoint frontier as ordinary network data

The solver network has three control cells (`frontier`, `job`, `branch-result`)
and three ordinary propagators:

```text
frontier --select--> job --evaluate one branch--> branch-result --collect--> frontier
```

Frontier jobs hold immutable `{network tasks}` checkpoints and a chosen source,
value, depth, and unique job ID. Selecting/collecting jobs implements ascending
DFS. Duplicate or stale job results cannot advance the frontier. Each evaluation
performs one choice, then runs the child constraint network to quiescence using
the appropriate existing or isolated domain runner. There is no whole-search
loop inside the worker activation.

Control publication uses existing source snapshots and message lifting. Logical
state is in cells, not closure-local atoms. Accepted branch state remains
inspectable in `:frontier/:accepted`. Atoms in the benchmark count work only.

Inner quiescence is atomic at this boundary. This is not micro-step suspension,
safe execution of arbitrary effectful closures, or a universal deep-immutability
proof. Search uses pure, fixed questions. Child evidence is retained in the
child Net; it is not automatically translated into live outer TTMS dependencies.

## Reproduction

Run from the repository root:

```sh
clojure -M -m examples.lain.amb.strategy-benchmark 5
clojure -M:test propagators.experimental.amb-strategy-test propagators.experimental.amb-checkpoint-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.experimental.amb-search-test propagators.experimental.amb-lain-test
```

Headless composition:

```clojure
(require '[examples.lain.amb.checkpoint-question :as question]
         '[examples.lain.amb.domain-refinement :as domain]
         '[examples.lain.amb.network-search :as search]
         '[propagators.runner :as runner])

(defn solve [question]
  (let [built (search/build question)
        result (runner/run-network (:tasks built) (:network built))]
    (case (:status result)
      :completed (search/answer built (:network result))
      :failed (throw (:error result))
      (throw (ex-info "Unknown execution outcome" result)))))

(solve (question/build 8)) ; existing TTMS-supported question
(solve (domain/build 12))  ; finite-domain contractor followed by one choice
```

These new strategies are builder-level examples, not new Lain bindings. The
existing compiled Lain AMB path is regression coverage, not proof that the new
solver modes were compiled from Lain.

## Benchmark protocol and results

Java `25.0.2`, synchronous single thread, one warmup per mode/domain, then five
samples. Six modes alternate forward/reverse order. All 90 measured answers and
statuses match the oracle; activation, predicate, and resolution counters are
identical across repeats. Dispatch caches are disabled. Setup is measured once
separately, not included in the medians below; its first measurements include
cold initialization and are not warmed setup medians.

Timings include activation/entry counters, predicate counts, and resolution-call
instrumentation. Resolution observation temporarily redefines a public function
only in the isolated benchmark JVM. Allocation is cumulative current-thread
allocation, not retained heap or total checkpoint memory. `:peak-env` measures
the largest single Net, not the combined live frontier. This is one exploratory
JVM run; overlapping sample ranges do not establish statistical significance.

Previous examples were rerun alongside the new strategies. Do not compute
speedups using historical timings from a different benchmark run.

| Mode | `1..4` median ms | `1..5` median ms | `1..8` median ms |
|---|---:|---:|---:|
| Previous external checkpoint DFS | 511.07 | 488.94 | 995.95 |
| Previous TTMS withdrawal DFS | 994.16 | 922.44 | 1919.77 |
| Previous learning-enabled AMB | 1180.94 | 949.93 | 2843.71 |
| Indexed-learning AMB | 1169.89 | 925.28 | 2816.44 |
| Network-owned checkpoint DFS | 578.22 | 552.81 | 1132.80 |
| Domain refinement + network search | 1.30 | 1.36 | 1.73 |

Domain 4 exhausts; domains 5 and 8 return `[3 4 5]` in every mode.

Work/allocation breakdown for domain 8:

| Mode | Activations | Predicate checks | Resolution calls | Allocated MB |
|---|---:|---:|---:|---:|
| External checkpoint DFS | 14293 | 157 | 0 | 1663.72 |
| TTMS withdrawal DFS | 28276 | 157 | 0 | 3152.62 |
| Original AMB | 37087 | 166 | 1098 | 4751.76 |
| Indexed-learning AMB | 36756 | 166 | 569 | 4743.69 |
| Network-owned checkpoints | 14837 | 157 | 0 | 1905.20 |
| Domain refinement + network search | 6 | 514 | 0 | 2.14 |

MB is decimal. Domain predicate counts are 64, 127, and 514 respectively: the
initial Cartesian checks plus subsequent contraction and singleton validation.
Fewer activations therefore **do not mean fewer predicate checks**. Domain mode
also has four child Net entries rather than the TTMS question's 33-entry peak,
and no live domain provenance. The comparison changes representation, layering,
granularity, and strategy together; attributing its entire gain to pruning would
be incorrect.

The controlled checkpoint-versus-withdrawal modes have the same decisions and
leaves. Network-owned checkpoint DFS matches that path too: 84/64, 87/70, and
180/157 decisions/leaves. Its peak pending jobs are 10, 13, and 22; several jobs
share one immutable parent snapshot. Total retained frontier memory was not
measured. The learning modes have a different repair policy, so their comparison
with checkpoint modes cannot isolate restoration cost.

All samples, ranges, counters, and setup measurements are in
[STRATEGY_RESULTS.edn](STRATEGY_RESULTS.edn). The earlier matched restoration
experiment remains in [CHECKPOINT_REPORT.md](CHECKPOINT_REPORT.md).

## Verification and limits

The focused strategy namespace passes **10 tests / 276 assertions**. It covers
exact resolution-closure parity for all 27 rejection tuples in three insertion
orders and every prefix; idempotence; stale certificates and context; original
AMB integration; intersection laws; preservation of all bounded solutions;
contractor chains/cycles; network DFS parity; unchanged outer topology;
quiescent reruns; stale job rejection; nontrivial domain search; and errors not
being reclassified as unsatisfiability.

The combined six AMB namespaces pass **33 tests / 429 assertions, zero failures
and errors**, including the existing compiled Lain example. Static verification
also passed: clj-kondo reported zero errors/warnings; tracked and new-file
whitespace checks were clean; local documentation links and the four-argument
maximum were verified. No production Clojure file under `propagators/` differs
from HEAD. The full repository suite was not run. Existing known whole-compound
limitations remain unchanged.

The best next hypothesis is to compose **cheap local domain contractors** with
the network-owned frontier, rather than relying on one exponential global table.
This prototype does not prove performance on transitive knowledge/graph workloads,
live context changes, parallel search, or general optimizing solvers. Retracting
assumptions/domain widening, retained learning across checkpoint branches, and
branch-qualified publication need separate contracts and are not implemented.
No new solver was made the default.

Follow-up: [concurrent range workers](PARALLEL_REPORT.md) compares these question
networks unsplit and partitioned, with matching sequential and threaded controls.
