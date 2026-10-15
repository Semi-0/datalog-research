# Alternative proofs, TTMS dependencies, and local contradiction

Additive, headless experiment, October 9, 2026. This extends the earlier positive
relation experiment; it does not turn it into full Datalog or change production
semantics. No KIROSHI mutation, commit, or push is part of this slice.

## Run it

From the repository root:

```sh
clojure -M -m examples.lain.relational-datalog.proof-experiment
clojure -M -m examples.lain.relational-datalog.proof-experiment-test
```

The program is [proofs.lain](../../examples/lain/relational_datalog/proofs.lain), a 10-line Lain network definition and
three applications. The primitives and publication policy are implemented in
[proof_operators.clj](../../examples/lain/relational_datalog/proof_operators.clj) and
[proof_relation.clj](../../examples/lain/relational_datalog/proof_relation.clj). The host fixture supplies explicit
source updates; it does not compute a transitive closure as an inference oracle.
The supporting Clojure implementation is not a standalone sub-100-LOC engine.

## What the Lain program expresses

```clojure
(let []
  (def-net join-path [left right] [out]
    (-> (unify-path left right) out))
  (let-cell [via-b via-d rejected]
    (join-path ab bc via-b)
    (join-path ad dc via-d)
    (join-path ab dc rejected)
    (proof-relation via-b via-d rejected)))
```

Four source cells contain supported edges:

```text
ab: [a b]      bc: [b c]
ad: [a d]      dc: [d c]
```

Each call installs a separate shared middle-variable cell and persistent layered
applications. Left and right projections assign their middle values to that
TTMS cell. Its base projection uses existing cell merge: `b` plus `d` is a
contradiction, with both input dependencies retained. There is no equality filter
deciding the match in advance. Matching here is exact scalar unification, not
general structural unification or finite-domain search.

The endpoint base procedure requires usable inputs, compatible versions, and
binding support covering both inputs. It then emits their endpoints. Its support
and premise-state procedures are the existing layers, composed through the
existing layered application. Generic message lifting publishes the resulting
datum as TTMS evidence, including supported contradiction/nothing.

The shared binding is internal to one candidate: conflicting candidates cannot
make a different candidate's binding contradictory. Candidate application names
retain their binding-cell IDs for headless inspection; names are diagnostic, not
inference state. No independent mutable dictionary or premise registry is used.

## AND inside a proof; OR between proofs

Raw relation cell content is:

```clojure
{:experiment/proof-evidence
 {proof-cell-id ttms-content}}
```

Evidence merges with `collection/merge-content` separately for each proof cell.
Support dominance never compares different proof occurrences. This matters even
when one proof's support strictly extends another's, or their tuples are equal.

The strongest relation is:

```clojure
{:experiment/live-relation
 {:facts #{[:a :c]}
  :proofs {proof-cell-id
           {:status :active ; also :pending, :contradictory, :withdrawn
            :base [:a :c]
            :support #{premise}
            :premise-state #{premise}}}}}
```

Each premise has exactly `:source`, `:timestamp`, and `:premises-status`.
`:source` is the actual injection-cell ID, resolvable against the live Net.

One proof requires **all** its premises: the existing TTMS conjunctive rule is
unchanged. The relation exposes a tuple if **any** separate proof is usable.
This alternative-proof projection is an explicit experiment-local domain rule,
not alternative-justification behavior added to TTMS itself.

Withdrawn proofs may have empty result support: they no longer justify a payload.
Their `:premise-state` still carries withdrawal information. Current-state
knowledge and the dependencies of a usable computation are not interchangeable.

## Demonstrated sequence

| Stage | Visible tuples | Live proofs | Other candidate |
|---|---|---:|---|
| No edges | Empty | 0 | Pending |
| All four edges active | `[a c]` | 2 | `ab + dc` contradictory |
| Retract `bc@2` | `[a c]` | 1 | Still contradictory |
| Retract `dc@2` | Empty | 0 | Withdrawn |
| Reactivate `bc@3` | `[a c]` | 1 | Still withdrawn |

Further checks change an endpoint, repair a previously mismatching candidate at
a newer source epoch, and withdraw a source shared by multiple live proofs.
Their visible tuples and explanations update without reinstalling topology.

## Implementation finding

The initial implementation prematurely accepted the mismatching candidate. Its
binding was temporarily concrete after the first assignment, before the second
assignment reached it. All source cells were already populated, so the early
tuple could carry complete source support despite incomplete constraint work.

Waiting merely for a concrete binding was insufficient. The corrected base
procedure requires the binding to account for both inputs, and publishes
supported contradiction when the completed binding conflicts. Procedure-owned
readiness and evidence publication fixed this within the experiment; no layered
dispatcher, compiler, TTMS policy, or scheduler change was needed. Tests also
inspect completed runner transitions to ensure this rejected candidate never
appears as a transient success in the two instrumented batch orders.

## Verification

The focused experiment passes **14 tests, 201 assertions**:

- Exact source support, source-cell lookup, and distinct equal-tuple proofs.
- Candidate-local contradiction with exact dependencies; pending is not success.
- All 24 source-arrival orders, both withdrawal orders, simultaneous updates.
- Withdrawal, recovery, changed bindings/endpoints, and delayed stale replay.
- Duplicate updates and explicit observer reruns preserve the entire Net.
- Immutable snapshot forks preserve the original branch.
- Per-proof merge laws, overlapping proofs, explicit invalid-input failures.
- No new function exceeds four arguments; all new code files are under 300 lines.

The earlier relation experiments plus the final proof suite passed
**46 tests, 684 assertions**, with zero failures/errors. Focused
TTMS/support/layered-support/runner/patch regressions passed **1,450 assertions**.
Compiler application, layered dispatcher/procedures, message lift, premise
transport, and compound-slot integrations passed **236 assertions**. Together,
the two focused runtime-regression groups contain **100 tests, 1,686 assertions**,
with zero failures/errors. Tracked diff checks and separate whitespace checks on
the new files were clean. The full repository suite is not run for this additive
slice; these results are not a claim that the entire dirty workspace is green.

## Small workload measurements

Two warmups, seven fresh-environment samples, one JVM. Timings measure runner
execution only; parsing, compilation, setup, and source-patch injection are
excluded. No other engine was benchmarked on this new retractable workload.

| Stage | Median ms | Range ms | Outer activations |
|---|---:|---:|---:|
| Initial empty-network quiescence | 131.74 | 122.46–162.23 | 516–517 |
| Four active edges | 41.61 | 38.79–46.42 | 139 |
| First withdrawal | 6.85 | 6.73–10.01 | 31 |
| Second withdrawal | 12.92 | 11.05–14.06 | 60 |
| Reactivation | 8.04 | 7.24–10.62 | 31 |

All stages and samples retain **603 persistent Net nodes**, including compiler
and layered-procedure machinery. Transient reduced-application work is included
in elapsed time, not counted as additional outer activations or persistent nodes.
This is evidence of bounded topology for this fixture, not memory-allocation
measurement or a performance win over indexed Datalog.

## Boundary and limitations

- Only example files change, plus an optional patch-evaluator argument in the
  existing experimental driver. Its default remains unchanged.
- Source publication is explicit. Derived computations preserve source IDs and
  epochs; activation does not manufacture timestamps.
- Net, production runner, cell evaluator, layered dispatcher, compiler contracts,
  compound synchronization, and existing TMS policy are unchanged.
- Uses the normal Compiler 2 extension/application APIs. No private executor.
- Proofs are enumerated by this bounded Lain program, not discovered by a general
  recursive Datalog engine. No unrestricted cyclic explanation enumeration,
  negation, labeling/search, or general unifier is implemented.
- The existing map/filter relation operators remain insertion-only. This live
  proof relation has a distinct contract and does not silently replace them.
- This demonstrates headless explainable/retractable inference. It does not
  migrate XR, claim globally atomic propagation, or prove universal glitch freedom.
- Plain contradictory callback boundaries in the earlier prototype remain as
  documented. This experiment transports explicit supported evidence instead.
- TTMS source IDs refer to the current environment; copied nested environments
  and historical source-value lookup are outside the tested scope.

Grounding reviewed the runner composition constraint (created September 25), the
TTMS dominance constraint (created September 29, still marked needs-verification
in the model), and the candidate layer-blind-dispatch rule (created October 9).
Tests are repository evidence, not automatic approval or model mutation.

The expressiveness demonstrated is one composable network containing partial
bindings, local inconsistency, source-linked explanations, and withdrawal/recovery.
It is not a claim of uniquely novel logic semantics or faster positive closure.
