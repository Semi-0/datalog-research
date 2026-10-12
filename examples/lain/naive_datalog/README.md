# Naive Datalog in Lain

## Recovery update — 2026-10-12

The harness is now safely requireable; importing it neither runs tests nor exits
the process. Run the seven non-stress smoke tests with
`clojure -M -m examples.lain.naive-datalog.experiment`. They passed 10 assertions
against current main. Previously documented stress limits remain; those cases
are not part of this recovery's passing claim.

An isolated, partial experiment: **84 lines of Lain for the engine**, with no
compiler, primitive-environment, runner, cell-merge, or TMS changes. `query.lain`
adds a five-line verified query. `path.lain` is a nine-line recursive stress
example, not a verified working example.

## Representation and contracts

A fact base is a compound object with a `:facts` slot holding compound lists:

```clojure
(database (list (list :edge 1 2) (list :edge 2 3)))
```

A rule is a two-element compound list containing a head and a list of body atoms:

```clojure
(list (list :path :x :z)
      (list (list :edge :x :y) (list :edge :y :z)))
```

- Relation labels and variables are keywords. In argument positions, keywords
  denote variables; numbers, strings, and Booleans denote constants.
- Only finite, ground fact lists and positive, safe rules are in scope. Every
  head variable must occur in the body. These restrictions are assumptions, not
  a complete input validator. Keyword constants, nested terms, negation,
  aggregates, retraction, and live source updates are unsupported.
- `map`, `filter`, and `flat-map` are defined in Lain and receive ordinary
  `network` definitions. No host callback evaluates rules or matches facts.
- `matches` selects relations by label and arity and builds body bindings.
  `compatible?` checks constants and repeated variables before head construction.
- `bind-term` merges contributions into fresh empty cells. Source fact cells
  are read through one-way forwarding, never used as writable binding cells.
  This is not a backtracking unifier: ordinary equality checks decide candidate
  membership. Absence of a contradiction is not treated as proof of a match.
- `unique` compares tuple values, not compound-object identity. Each `step`
  unions old and derived facts and constructs a new compound base. `datalog`
  repeats until cardinality is stable; this criterion relies on positive,
  deduplicated, append-only facts.
- All declared Lain networks have at most three arguments. The smoke suite also
  checks the requested four-argument limit and the engine's under-100-line size.

## Execution

This requires Compiler 2's ordinary-function `network` syntax. Verification used
the existing integration checkout at commit
`c9413d090df36120e3c066629788f4034d569c44`, not the older compiler in the canonical
working checkout. The experiment files remain in the canonical checkout.

Run from a checkout with that compiler:

```sh
cd /Users/linpandi/.codex/worktrees/layered-dispatch-publish/cloj-leapfrog
clojure -M /Users/linpandi/cloj-leapfrog/examples/lain/naive_datalog/experiment.clj
```

The Clojure script only compiles Lain, runs the existing scheduler, decodes
results, counts activations/nodes, and asserts expected results. Its measurement
atom stays outside `Net`. It introduces no private executor or Datalog runtime.
Default limits are 90 seconds and 100,000 activations **per execution**; limits
are checked at iterator boundaries, not by preempting an activation. Measurements
include compilation and quiescence, but exclude JVM startup.

An individual test can be selected with `LAIN_DATALOG_TEST`. For example:

```sh
LAIN_DATALOG_TEST=one-rule-derivation clojure -M /Users/linpandi/cloj-leapfrog/examples/lain/naive_datalog/experiment.clj
```

The verified query derives `[[:path 1 2]]`. The default suite runs basic checks
only. These unresolved stress tests remain explicitly runnable with their
original semantic assertions:

```sh
LAIN_DATALOG_TEST=two-atom-join clojure -M /Users/linpandi/cloj-leapfrog/examples/lain/naive_datalog/experiment.clj
LAIN_DATALOG_TEST=mutual-recursion-fixed-point clojure -M /Users/linpandi/cloj-leapfrog/examples/lain/naive_datalog/experiment.clj
LAIN_DATALOG_TEST=path-fixed-point clojure -M /Users/linpandi/cloj-leapfrog/examples/lain/naive_datalog/experiment.clj
```

`LAIN_DATALOG_SECONDS` and `LAIN_DATALOG_STEPS` override the bounds. Probe mode
accepts a Lain expression in `LAIN_DATALOG_PROBE`; `LAIN_DATALOG_FORMAT=rows` or
`values` decodes a result list instead of printing its raw strongest value.

## Results

The final basic suite passed **7 tests, 10 assertions, 0 failures, 0 errors**:
network callbacks with lexical capture; constant/repeated-variable matching;
equal-value cell binding; contradiction confined to a fresh local cell;
duplicate elimination; the full single-rule tuple; the empty fixed point; and
source-size/arity checks.

Single-rule derivation used **25,430 activations, 12,874 nodes, 9.82–10.53 s**
in two final basic-suite runs.
These are fixture observations, not controlled benchmark or complexity claims.
They already show substantial overhead for one fact.

| Stress case | Required result | Observed result |
| --- | --- | --- |
| Two-fact, two-relation join | `[:path 1 3]` | 90 s budget reached; 56,557 activations, 22,621 nodes |
| Mutual recursion, `seed → ready → seed` | `{[:seed], [:ready]}` | 90 s budget reached; 52,216 activations, 24,574 nodes |
| Two-edge recursive reachability | Five total facts, including `[:path 1 3]` | 90 s budget reached; 45,944 activations, 23,407 nodes |

Timeouts are reported as errors, not accepted semantic results. The correct join
and recursion assertions have not been removed or relaxed. A timeout does not
prove nontermination or an incorrect result; these cases have **not demonstrated
quiescence within the limits**. No full Datalog correctness claim is justified.
The final join and reachability runs were concurrent; their wall times and
activation counts at the time limit are not controlled performance comparisons.

## Findings and boundaries

1. An initial implementation counted derived rows without checking their
   fields. Inspecting the tuple revealed `nothing` fields; that count was not
   completion evidence. The current assertion checks the actual tuple values.
2. A literal-key slot-write probe returned `1`, while the equivalent computed-key
   probe remained `nothing`. The experiment avoids computed-key writes and uses
   fresh binding cells instead; the compiler was not patched. That observation
   alone does not establish the compiler's root cause.
3. Constructing a compound base while its rows input was pending left the empty
   `step` result pending. `database` now waits for a concrete list length before
   installing the base. The empty fixed-point test then passed. This static
   workaround is not evidence of incremental compound-source update behavior.
4. Readability/conciseness is plausible for the basic cases; practical general
   joins and recursion remain unresolved. Large topology growth needs profiling
   before attributing it to the language, compiler, compound representation, or
   the deliberately naive algorithm. Production changes are outside this
   experiment.

No KIROSHI facts were mutated, and nothing was committed or pushed. Unrelated
working changes were preserved. The repository-wide suite was not rerun: only
new example files were changed, and the report makes no production-health claim.

## Research follow-up: persistent inference, not an evaluator loop

The first prototype expresses Datalog's evaluator as recursive functions over
whole database values. That is one possible encoding, but it does not establish
an efficient propagation-native design. Timeouts alone do not isolate the cause.

There is direct research support for a different execution shape:

- [Radul's thesis, §5.4](https://www.cs.tufts.edu/~nr/cs257/archive/alexey-radul/phd-thesis.pdf)
  distinguishes unification from clause search. Mergeable partial argument lists
  support multidirectional deduction; search over alternatives remains a
  separate problem. Empty cells are therefore a useful binding mechanism, not
  by themselves a complete logic-programming evaluator.
- [BloomL, §§3–4](https://dsf.berkeley.edu/papers/socc12-blooml.pdf) implements
  lattice-based logic programming, including set-union accumulation and
  semi-naive evaluation. New deductions grow persistent relations rather than
  replace snapshots. Its delta optimization has algebraic requirements; an
  arbitrary monotone operation cannot simply be applied only to deltas.
- [From Datalog to Flix, §3.7](https://inst.eecs.berkeley.edu/~cs294-260/sp24/papers/datalog-to-flix.pdf)
  describes reacting to a newly inferred fact by evaluating relevant rule
  instances. Its lattice generalization also shows why rule evaluation must
  respect the information order, not merely process arbitrary snapshots.

**Working hypothesis, not a verified replacement:** retain the compound fact
base, install persistent rule networks, and publish newly established facts into
an accumulating relation. Give each candidate match its own variable cells;
incompatible candidates must not contaminate facts or other candidates. Positive
recursive rules would form feedback edges. The existing runner's quiescence,
rather than a database-length comparison inside Lain, would detect completion
for the current finite inputs.

Two candidate representations deserve a small test: a tuple-set with union
semantics, or compound entries containing per-tuple presence cells that move
from `nothing` to `true`. These are relational membership, not competing exact
scalar assignments or TTMS evidence. Multiple independent derivations of one
fact would also need their own justification treatment if retraction is added;
no change to the agreed conjunctive TTMS semantics is implied.

The repository already has a relevant implementation pattern in
`propagators.experimental.visualization.collections`: `stable-id`, `list-step`,
`entry-patches`, and `collection-operator` install per-element callback topology
through declaration patches. It is worth examining for reuse, but has not been
verified here as a Datalog relation store or with this experiment's compiler.

Changed-cell scheduling alone is **not** proof of semi-naive joins: a reactivated
rule can still recompute every old tuple pair. A future test must check stable
candidate identities and duplicate activation/topology behavior, not only the
final fact set. First verify one persistent rule and one feedback cycle before
trying a general interpreter. No such replacement was implemented in this slice.
