# Persistent relational Datalog in Lain

## Recovery update — 2026-10-12

Saved definitions and callback programs now use canonical `define`/`network`
syntax. The isolated relation evaluator/merge policy is unchanged. Test with
`clojure -M:test relational-experiments`; it includes the maintained clause/proof
examples as regressions. Earlier measurements below are historical samples.

When a pending callback becomes concrete, the canonical compiler may register
late return ports. Existing callback publishers and application identities must
remain stable; late interface registration is not callback reinstallation.

An isolated positive-Datalog prototype. The 23-line `path.lain` program holds
relations in a compound fact base and installs these persistent rules:

```text
path(x,y) :- edge(x,y).
path(x,z) :- path(x,y), edge(y,z).
```

## Run

From `/Users/linpandi/cloj-leapfrog`:

```sh
clojure -M -m examples.lain.relational-datalog.experiment demo
clojure -M -m examples.lain.relational-datalog.experiment
```

The demo compiles `path.lain`, lets the empty topology settle, then publishes
`edge(1,2)` and `edge(2,3)` through the experiment's patch dispatcher. It prints:

```clojure
{:edges [[1 2] [2 3]]
 :paths ([1 2] [1 3] [2 3])
 :steps 1007
 :nodes 1409}
```

The host harness supplies input facts and assertions; it does not interpret
Datalog clauses or implement path inference. The Lain predicate compares join
coordinates. Its projection forwards coordinates into fresh `x`, `y`, and `z`
cells for each accepted candidate, then produces `(tuple x z)`.

Equality decides membership *before* installing an accepted projection. An
empty binding cell that has not yet contradicted is not proof of successful
unification: one input could simply arrive later. This prototype demonstrates
fresh binding cells but is not a general unifier or search/backtracking engine.

## Ownership and contracts

| File | Responsibility |
|---|---|
| `path.lain` | Rule predicate, projection, recursive feedback, compound fact base |
| `runtime.clj` | Relation policy, ordered patch dispatch, bounded trampoline driver |
| `operators.clj` | Generic tuple construction/access, Cartesian pairing, HOP map/filter |
| `experiment.clj` | Compile, inject facts, observe, test, report |

Relation content and strongest use the same immutable tagged representation:

```clojure
{:experiment/facts #{[1 2] [2 3]}}
```

- Tuples are alternatives/members of a relation, not competing exact values.
- Merge is set union: associative, commutative, and idempotent.
- `nothing` is the merge identity; a known empty relation is distinct from an
  unknown ordinary cell.
- Strongest keeps the tag so existing whole-datum forwarding and compound
  aliases preserve the experimental merge policy. `facts` reads its tuple set.
- Invalid updates to an established relation fail; they do not silently replace it.
- Only changed relation strongest values enqueue outgoing propagators.
- The original immutable network remains available after every transformation.

The experiment constructs the existing `network-iterator-constructor` with the
production FIFO policy and propagator evaluator, plus its own patch evaluator.
`combinator/branch` routes relation messages to the local merge/strongest policy;
other patches delegate to `runner/apply-patch` unchanged. There are no global
merge methods, `with-redefs`, private executors, mutable state inside Net, or
changes to production scheduling, compound synchronization, or TTMS.

The opt-in environment adds `tuple`, `at`, `relation`, `empty-relation`, and
`product`, and supplies relation-specific `map`/`filter` names only for this
environment. Their callbacks are one-input, one-output Lain network definitions
using the existing Compiler 2 callable/application contract, not host callbacks.

Each operation/callback/context/output/tuple combination has stable argument,
result, and publisher IDs. Previously installed applications are reused. Rule
networks remain alive; a changed relation schedules further work until quiet.
No whole-database recursive evaluator or cardinality-based stopping condition
is involved.

## Verified results — 2026-10-09

Experiment: **13 tests, 74 assertions, 0 failures, 0 errors**.

- Merge laws, empty identity, invalid relation values, and Cartesian pairing.
- Ordinary scalar merge and contradiction behavior remain unchanged.
- Empty quiescence, two-edge transitive closure, incremental/late facts.
- All six insertion orders for a three-edge chain produce the six expected paths.
- A three-node directed cycle produces all nine reachable pairs, including self-pairs.
- Nonmatching pairs are excluded; source edges are not changed by rule evaluation.
- Duplicate updates cause zero activations and no network/topology changes.
- Empty reruns and explicit collection-operator reactivation are idempotent.
- Late callback definition and lexical capture execute correctly.
- Unknown callback outputs remain pending; invalid callback shapes/results fail.
- Failure continuation and execution-budget handling.
- Lain source size and function-arity checks; new functions have at most four arguments.

Existing production regressions: **33 tests, 129 assertions, 0 failures/errors**:

```sh
clojure -M:test propagators.runner-test propagators.network-patch-test propagators.experimental.runner-constructor-test propagators.compound-object-network-slot-test
git diff --check
```

The full repository/compiler suite was not run for this isolated experiment.
No model mutation, commit, or push was performed.

Observed runs (not a controlled benchmark):

| Case | Activations | Nodes | Runtime |
|---|---:|---:|---:|
| Empty initialization | 386 | 464 | about 0.5 s, including compilation |
| Two-edge closure after initialization | 1,007–1,009 | 1,409 | about 0.2–0.4 s |
| Three-node cycle after initialization | 4,911–4,914 | 5,486 | about 1.3–1.7 s |
| Duplicate fact publication | 0 | unchanged | no scheduled evaluation |

JVM startup is excluded. Some test/demo processes ran concurrently. These
numbers do not establish a general speedup over the earlier whole-value
prototype: their workloads and implementation boundaries differ. However, the
previous two-edge recursive path stress scenario did not finish within its
90-second limit, whereas this persistent-rule version has now completed.

## Explicit limitations

1. This is a specialized two-rule program, not a generic rule parser/interpreter.
   The Lain rule source is under 100 lines; the entire host extension and tests
   are not. Tuple construction in this opt-in environment is currently binary.
2. Pairing recomputes a Cartesian product and collection discovery revisits
   retained tuples. Stable IDs prevent equivalent topology from growing, but
   this is **not semi-naive delta evaluation**. Memory includes applications
   for rejected pairs as well as accepted ones.
3. Closure terminates for these finite, positive, non-generative rules. An
   arbitrary callback that keeps inventing fresh tuples can grow forever; the
   driver has explicit time/activation budgets, not a universal termination proof.
4. No negation, deletion, alternative-justification tracking, TTMS integration,
   retraction, or arbitrary effectful callback sandbox is claimed.
5. **Existing callback-boundary limitation:** Compiler 2's concrete boundary
   suppresses an unusable ordinary output. A callback whose inner result is
   contradiction leaves the outer result `nothing`; the publisher cannot
   distinguish it from a pending callback through this contract. A focused test
   verifies both the retained inner contradiction and unknown publisher input.
   It is not interpreted as false or claimed to deliver an explicit failure.
   Fixing general contradictory-callback transport would need a separately
   approved runtime boundary change; it was not made here.
6. Forcing *every* production propagator to reactivate refreshes four existing
   compiler-owned immutable Net snapshot payloads in the tested two-edge case.
   Facts, topology, graph, dictionary, and relationships stay unchanged. Whole
   Net byte-for-byte equality is therefore not claimed for that forced run.
   Duplicate publication, normal quiescent reruns, and reactivation of our
   collection operators do retain the exact network.

The reviewed runner-semantic-composition constraint (2026-09-25) and persistent
bidirectional compound-slot constraint (2026-09-24) guided this boundary. They
remain unchanged: domain policy enters through evaluators and declared topology,
not through new branches in the runner constructor.

## Mutually recursive transitive clauses — 2026-10-09

`transitive.lain` adds a separate 22-line example using the same opt-in
environment and runner. It does not add host-side rule operators:

```text
reach(x,y)     :- edge(x,y).
extension(x,z) :- reach(x,y), edge(y,z).
reach(x,z)     :- extension(x,z).
```

The compound fact base has `:edge`, `:reach`, and `:extension` slots. Each slot
holds a tagged set of ground tuples. It is not a set of independently allocated
compound objects, nor a finite-domain variable. Callback networks join on the
shared coordinate, project endpoints, and feed facts back through union.

```sh
clojure -M -m examples.lain.relational-datalog.transitive demo
clojure -M -m examples.lain.relational-datalog.transitive
```

The test run passed **4 tests, 58 assertions, zero failures/errors**. An
independent host-side closure specification checks results but never participates
in inference. Coverage includes empty relations, a three-edge chain, a diamond
with duplicate derivations, a directed cycle, disconnected components, a late
bridge, all six arrival orders, and zero-activation duplicate/quiescent reruns.

| Input | Derived reach facts | Activations | Nodes | Observed execution |
|---|---:|---:|---:|---:|
| Three-edge chain | 6 | 2,762 | 2,974 | 980 ms |
| Four-edge diamond | 5 | 2,574 | 3,033 | 686 ms |
| Three-node cycle | 9 | 4,780 | 4,804 | 1,293 ms |
| Two disconnected edges | 2 | 531 | 1,026 | 85 ms |

These are single-run observations after initialization, not controlled benchmark
results. The same limitations above still apply: whole Cartesian pairing, no
general unification or search, and no retraction. This demonstrates positive
recursive Datalog-style inference, **not CLP(FD)**. Union accumulates relation
members; finite-domain restrictions would need their own narrowing semantics.

Only the example and verification files were extended. The KIROSHI read was
nonpersistent; no model mutation, commit, push, or production-runtime edit was
made for this extension.

## Semi-naive strategy — 2026-10-09

The new `semi_naive.clj` supplies opt-in differential `product`, `map`, and
`filter` operators. The Lain rule programs remain unchanged. The existing
strategy remains available, and ordinary production bindings are untouched.

```clojure
(require '[examples.lain.relational-datalog.experiment :as experiment]
         '[examples.lain.relational-datalog.semi-naive :as semi]
         '[examples.lain.relational-datalog.transitive :as transitive])

(def prepared
  (experiment/initialize transitive/source
                        {:bindings (semi/bindings :semi-naive)}))
(def solved (experiment/add-edges prepared [[:a :b] [:b :c] [:c :d]]))
(transitive/tuples solved :reach)
;; #{[:a :b] [:a :c] [:a :d] [:b :c] [:b :d] [:c :d]}
```

Each product owns two immutable progress cells. Given previous sets `L0/R0`
and current sets `L/R`, it computes:

```text
delta-L = L - L0
delta-R = R - R0
new pairs = (delta-L × R) union (L0 × delta-R)
```

This equals `(L × R) - (L0 × R0)` for monotonically growing inputs, including
simultaneous growth. Shrinking inputs are rejected rather than silently losing
inference. Progress is normal tagged relation content, advanced by ordinary
messages in the same completed iteration as result publication. No atom/cache
is captured in these operator activations. Restoring a network snapshot restores
its progress, as demonstrated by independent branches from one earlier state.

Each map/filter owns one progress cell and only declares applications for newly
seen candidates. Validation and callback wiring are shared with the original
operators. Progress does not mean a callback has completed: installed callbacks
remain live, and a pending predicate can become true later without reinstallation.
The operator boundary includes its progress cells as declared outputs.

### Measurements

```sh
clojure -M -m examples.lain.relational-datalog.semi-naive-test
clojure -M -m examples.lain.relational-datalog.benchmark
```

Both strategies ran the same `transitive.lain`, inputs, FIFO driver, and set-union
merge. The comparison uses a full-product operator with the same operator contract
as the differential product, rather than comparing unlike host primitive wrappers.
Compilation/initialization are excluded; input publication, execution, and observer
cost are included. Two warmup runs precede seven measured samples per strategy
per case. Order alternates, and each sample starts from its immutable initialized
network. The corrected benchmark ran without another test process in parallel.

Benchmark-only counter atoms sit in evaluator observers **outside Net**. They
never decide propagation, supply values, or cache inference. Pair counts measure
emitted result pairs, excluding cursor updates; declaration counts measure attempted
callback publisher declarations, including repeated attempts in the naive strategy.
An initial pair-counter bug was fixed and independently tested before these results.

| Case | Pair generation, naive → semi | Callback declarations, naive → semi | Median ms, naive → semi | Retained nodes, naive → semi |
|---|---:|---:|---:|---:|
| Three-edge chain | 42 → 18 | 55 → 27 | 501.9 → 499.5 | 2,974 → 2,980 |
| Four-edge diamond | 36 → 20 | 43 → 27 | 503.3 → 498.9 | 3,033 → 3,039 |
| Three-node cycle | 54 → 27 | 93 → 48 | 1,126.3 → 1,121.6 | 4,804 → 4,810 |
| Incremental late bridge | 67 → 18 | 85 → 27 | 487.8 → 484.8 | 2,974 → 2,980 |

All seven samples per case/strategy produced the expected fact count, topology
count, and work counts. Activation counts were effectively unchanged: 2,763 for
the chain, 2,575 versus 2,574 for the diamond, 4,780 for the cycle, and 3,075 for
the late bridge. These small timing differences do **not** establish a meaningful
speedup. The robust result is reduced repeated pair/declaration work, not lower
retained topology or dramatically less scheduler work.

### One-time topology: what this does and does not achieve

Rule networks are installed once. Each stable tuple's callback application is
also installed once; revisiting known facts does not reinstall it. However, each
new candidate still needs its own callback binding/result cells and live Lain
application. Rejected candidates also retain predicate topology. Semi-naive
processing changes the amount of replayed work, not the number of distinct
candidates considered. The six extra nodes above are explicit progress cells.

One reusable network for an entire batch would require a separate relation-wide
callback/lifting design. Reusing scalar binding cells for successive tuples is
not equivalent: ordinary cells refine/merge values rather than resetting them
between function calls. No overwrite/reset policy or new callback contract was
introduced here. Indexing joins to avoid nonmatching candidates and relation-wide
execution remain future experiments, not claimed optimizations.

The delta strategy follows the insertion-only principle in
[FLIX section 3.7](https://inst.eecs.berkeley.edu/~cs294-260/sp24/papers/datalog-to-flix.pdf).
This is an asynchronous worklist-style differential operator strategy, not a
generic Datalog compiler that rewrites arbitrary rules into synchronized rounds.
It still scans input sets to find differences; there is no join index, negation,
deletion, TTMS retraction, or new contradictory-callback transport.

### Verification

- Combined experiments: **27 tests, 378 assertions, zero failures/errors**.
- Production runner/patch/compound regressions: **33 tests, 129 assertions,
  zero failures/errors**.
- Exhaustive small-set delta comparisons, simultaneous growth, all six fact
  arrival orders, cycles, disconnected components, late bridge and duplicate proofs.
- Exact network idempotence on duplicate updates and operator reactivation;
  restored progress across immutable snapshot forks.
- Lexical capture, late callback definition, pending predicate completion without
  topology growth, invalid declarations, instrumentation separation, and maximum
  four-argument function checks.
- `git diff --check` and direct whitespace checks passed. The full repository
  suite was not run. No production file or System Model fact was changed, and
  no commit or push was performed.

## Existing-engine comparison — 2026-10-09

`compare_engines.clj` compares the two Lain strategies with the existing mutable
and pure Clojure Leapfrog engines and differential Leapfrog. `compare_rust.rs`
uses the unchanged local Rust Leapfrog library. No engine or compiler was patched.

All use the same three `reach/extension` clauses, integer-valued input tuples,
batch boundaries, and independent expected closure. Every included sample checks
the complete `edge`, `reach`, and `extension` sets before accepting its timing.
Incremental Leapfrog uses the existing public `semi-naive-step` to retain facts
across batches; tests also compare that adapter against full `semi-naive` evaluation.

Median milliseconds per complete workload, excluding initialization:

| Engine | Chain | Diamond | Cycle | Three incremental batches |
|---|---:|---:|---:|---:|
| Lain naive | 901.734 | 1,601.098 | 1,186.035 | 1,813.715 |
| Lain semi-naive | 816.168 | 1,453.067 | 1,204.943 | 1,567.719 |
| Clojure Leapfrog | 0.121 | 0.0515 | 0.111 | 0.116 |
| Pure Clojure Leapfrog | 0.227 | 0.0722 | 0.188 | 0.167 |
| Differential Leapfrog | 211.144 | 241.981 | excluded | 668.335 |
| Rust Leapfrog, optimized | 0.0362 | 0.0265 | 0.0600 | 0.0508 |

Methods: two warmup samples and seven measured samples; the JVM alternates engine
order. Each timed sample averages 100 independent executions for Clojure Leapfrog,
1,000 for Rust, and one for Lain/differential. Every execution starts from the
same empty initialized state. Rust runs separately with optimization level 3,
not interleaved with the JVM. Loading libraries, JVM startup, Rust compilation,
result decoding, and correctness assertions are outside inference timing.

Warmed initialization was separately measured over seven samples: approximately
130.1 ms for Lain naive and 123.0 ms for Lain semi-naive. Other Clojure engines
only create empty database state at this boundary (under 0.002 ms), with their
rule data already prepared; they have no corresponding Lain compilation stage.
Raw initialization/timing ranges and fact counts are in `COMPARISON_RESULTS.edn`.

Lain timing variation was substantial: the semi-naive chain ranged from 579 to
1,450 ms, and the cycle from 1,143 to 4,728 ms. Therefore this run does not establish
a reliable naive-versus-semi-naive speedup or replace the earlier within-strategy
measurement. These are tiny fixture results, not system-isolated throughput or
scaling claims. The large gap to dedicated indexed query execution is nevertheless
visible across the measurements. Lain retains thousands of inspectable callback
nodes; Leapfrog evaluates relations with trie joins instead of a Lain application
per candidate. No profile was taken to attribute the timing gap to a specific
compiler/runner function. Memory bytes were not measured.

Differential Leapfrog retains derivation weights, unlike Boolean set membership.
Its cyclic weighted derivations are not compared as a Boolean cyclic fixed point.
Its existing `drain-versioned-output` also waits up to 200 ms per transaction;
that public-API overhead is included, not patched away. Acyclic visible support
matches all expected facts, including the diamond's alternative derivations.

The older `naive_datalog` interpreter was probed with a five-second/10,000-step
bound. It failed at parsing before inference: `network outputs must be a vector`.
Its ordinary-function `network` syntax requires the other compiler checkout
documented in that example. It is unavailable for a same-checkout timing comparison;
no historical timeout is presented as a fresh timing result.

Run the JVM comparison and its focused checks:

```sh
clojure -M -m examples.lain.relational-datalog.compare-engines
clojure -M -m examples.lain.relational-datalog.compare-engines-test
```

For Rust:

```sh
datalog_compare_dir=$(mktemp -d /private/tmp/lain-datalog-compare.XXXXXX)
rustc --edition=2021 --crate-type=lib --crate-name leapfrog -C opt-level=3 leapfrog-rs/src/lib.rs -o "$datalog_compare_dir/libleapfrog.rlib"
rustc --edition=2021 -C opt-level=3 examples/lain/relational_datalog/compare_rust.rs --extern leapfrog="$datalog_compare_dir/libleapfrog.rlib" -o "$datalog_compare_dir/compare-rust"
"$datalog_compare_dir/compare-rust"
```

This avoids downloading Criterion or changing existing benchmark targets. All
new functions have at most four arguments. No model mutation, commit, or push
is included.

Verification: **32 tests, 483 assertions passed**; existing Clojure Leapfrog
checks: **16 tests, 34 assertions passed**; Rust library: **2 tests passed**.
The older interpreter's parser error is separate. Full repository suite not run.
