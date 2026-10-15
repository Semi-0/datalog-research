# Complete Lain declarations through an opt-in session extension

## Current dataflow program

`examples/lain/rule_fixedpoint/dataflow.lain` now declares every predicate,
variable, head and body occurrence explicitly:

```clojure
(rule-set
  (rule :flow-to-reach
    (relation :reach :x :y)
    (relation :flow :x :y))

  (rule :reach-via-reach-flow
    (relation :reach :x :z)
    (relation :reach :x :y)
    (relation :flow :y :z))

  (rule :taint-via-taint-flow
    (relation :taint :x :z)
    (relation :taint :x :y)
    (relation :flow :y :z)))
```

Keywords here name logical variables. No Lain arithmetic guard or constant-true
guard is needed. The reachability-only `reach.lain` likewise declares its full
two-rule program with explicit heads and bodies.

The generic session bindings are:

```clojure
(relation predicate left-variable right-variable)
(rule rule-id head-relation body-relation ...)
(rule-set rule ...)
(guarded-rule declaration pure-lain-network)
```

The existing finite binary schema is unchanged: one or two body occurrences,
at most three variables, range-restricted heads and distinct rule IDs. These
are ordinary opt-in operators, not new compiler forms. The older `copy-rule`
and `step-rule` convenience constructors remain for low-level tests and genuine
guard unit tests, but neither is used to declare the actual dataflow or reach
program. The declaration test disables those bindings entirely.

The redundant `x < z` chain control has been removed from the active validation
benchmark. The genuinely selective arithmetic control lives in
`selective_guard.lain`, with its complete head/body and captured ceiling declared
there. Clojure benchmark code now loads its catalog rather than embedding Lain
program strings. The older benchmark matrices, including the removed control,
remain historical artifacts.

## Ownership

| Part | Responsibility |
|---|---|
| `.lain` file | Complete experiment-specific rules and variable connections |
| `catalog/session-extension` | Generic declaration constructors, installed through the standard API |
| `catalog/initialize` | Fresh session + existing TTMS extension + experiment extension |
| Clojure fixtures | Source graph facts and an independent expected-answer oracle, not inference rules |
| Solver/engine | Matching strategy, generic proof publication and fixed-point propagation |

New names are absent in an ordinary fresh session before and after the
experiment. Installing the same extension twice is idempotent. No global Lain
bindings, multimethods or effect handlers are added. The extension declares no
effects. Production compiler, basis, runner, cell evaluator and TTMS policy are
unchanged.

Source facts and solver selection are still host inputs. This is not a claim
that the entire solver wiring has been rewritten in Lain or that a new
`(solve solver rules facts ...)` interface has been implemented.

## No invented guard in the compiled AMB question

Indexed unconditional rules already bypass compiled candidate validation. AMB
and checkpoint still need their actual membership/demand constraint. The
generated question now calls that constraint directly when the rule has no
guard; it no longer defines or invokes `(network (x y z) true)`. Genuine user
guards retain their compiled evaluation and batching behavior.

Earlier benchmark matrices are preserved as historical measurements, not
relabelled as performance evidence for this changed question template. This
follow-up verifies semantics and extension isolation; it does not rerun the
complete benchmark matrix.

## Running the dataflow example

```sh
clojure -M -m examples.lain.rule-fixedpoint.demo indexed-delta dataflow
```

The demo loads `dataflow.lain`, reads a real installed nine-cell chain as source
facts, derives reachability and taint, withdraws one source edge, then restores
it at a newer epoch. It reports all requested fact sets and topology metrics.
The original diamond invocation remains available:

```sh
clojure -M -m examples.lain.rule-fixedpoint.demo indexed-delta
```

## Verification

The new catalog namespace has eight tests / 56 assertions. It verifies exact
parsed heads/bodies with only generic extension bindings; no guards; local and
idempotent extension installation; default-session isolation; rejection of
unsupported declarations; no invented compiled guard; an edited Lain body
changing inference without a Clojure change; actual dataflow answers (36 reach,
9 taint); source withdrawal/recovery; stable topology; and identical proof
content across all six strategies on a smaller dataflow question. The meaningful
guard control is loaded from its Lain file and rejects the expected candidate.

The final focused command passes **74 tests / 646 assertions**, zero failures
or errors, including existing rule/proof and session-extension suites. The
earlier broader run passed **331 tests / 3,838 assertions**, zero failures or
errors, before the last guard-control declaration addition. These runs overlap
and must not be added together. The full default suite was not rerun; its known
legacy TUI/environment-I/O baseline is not claimed green.

The real dataflow demo completed all stages with 42 outer Net entries: active
36 reach/9 taint, selected-edge withdrawal 28 reach/1 taint, restored 36 reach/9
taint. Which edge receives `:flow-0` follows fixture set iteration; exact
withdrawal counts can vary with that choice. All three selective-guard benchmark
variants completed a smoke check with one answer, four proofs and 18 Net entries.
No performance comparison is claimed from these smoke checks.

No commit, push, KIROSHI mutation or unrelated-work migration is included.
