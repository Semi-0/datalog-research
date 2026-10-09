# Lain clauses over compound terms

Date: 2026-10-09. Additive, headless experiment; production contracts unchanged.

## Run

From the repository root:

```sh
clojure -M -m examples.lain.relational-datalog.clause-terms
clojure -M -m examples.lain.relational-datalog.clause-terms compound
clojure -M -m examples.lain.relational-datalog.clause-terms list
clojure -M -m examples.lain.relational-datalog.clause-terms-test
clojure -M -m examples.lain.relational-datalog.proof-experiment-test
```

The default demo runs both representations. Each prints pending, active,
withdrawn, and restored stages, with facts, proof statuses, source names,
activation counts, elapsed time, and outer network entry counts.

## Intention and actual scope

Use ordinary Lain network definitions as clauses, observe facts represented by
compound objects, and use private initially unknown cells for candidate binding.
No separate clause parser, compiler syntax, renderer, or imperative inference
loop is introduced.

This is a bounded two-hop experiment, not a general Datalog implementation.
The Lain program explicitly supplies three candidate pairs. The existing
`unify-path` operator still installs each private middle-variable cell; the
entire unification mechanism has not been rewritten as Lain expressions.

## Saved Lain programs

`compound_clause.lain` uses this clause:

```clojure
(def-net join-path [left right] [out]
  (-> (unify-path
        (edge-pair (from left) (to left))
        (edge-pair (from right) (to right)))
      out))
```

`linked_clause.lain` observes nested cons-shaped terms:

```clojure
(def-net join-path [left right] [out]
  (-> (unify-path
        (edge-pair (car left) (car (cdr left)))
        (edge-pair (car right) (car (cdr right))))
      out))
```

Both programs instantiate `ab/bc`, `ad/dc`, and the deliberately mismatching
`ab/dc` candidate. Each candidate has a distinct binding cell and proof cell.
The two successful candidates independently justify the same output tuple.

`from`, `to`, `car`, and `cdr` are opt-in bindings of the existing
`ttms/slot-operator`. They are pull-only readers, not replacements for ordinary
bidirectional compound accessors. `edge-pair` composes the existing concrete
vector primitive with the existing TTMS scalar application wrapper.

## Data and boundaries

Compound facts have public fields `:from` and `:to`. The list case uses two
nested ordinary compound objects with `:car` and `:cdr`; the tail sentinel is
`:nil`. This does not exercise arbitrary-length list traversal or cyclic terms.

Each fact is injected at an actual outer source cell through existing TTMS
evidence, carrying `:source`, `:timestamp`, and `:premises-status`. The base
compound's fields are separate from the outer datum's `:base`, `:support`, and
`:premise-state` layers. Source identities and timestamps are not invented by
slot readers, tuple construction, or clause execution.

The example harness owns input construction and explicit publication. Reuse a
constructed immutable term for exact replay. Semantic deduplication of newly
allocated equivalent compound networks is not an acceptance claim here.

| Boundary | Responsibility |
|---|---|
| Lain files | Clause composition and explicit candidate applications |
| `clause_terms.clj` | Opt-in environment, source fixtures, input construction, demo |
| Existing TTMS slot readers | Compound observation and support transport |
| `proof_operators.clj` | Private binding cells, readiness, supported proof publication |
| `proof_relation.clj` | Separate proof evidence and experiment-local OR projection |
| `runtime.clj` | Existing iterator composition with bounded execution |

Support is conjunctive within an individual proof; alternatives are separate
proofs in the relation. A contradictory candidate does not contradict the
entire relation. Removing the last usable proof removes the visible fact.

`nothing` in a source or a field is pending information, not a wildcard fact.
An initially unknown candidate binding can receive assignments, but a binding
must account for both sides at compatible versions before publishing success.
Conflicting concrete assignments produce a supported contradiction for that
candidate.

The retained source data is never refined through a read accessor. No mutable
registry, hidden premise cache, private executor, or permanent `with-redefs`
is used. Net, runner, compiler, layered dispatcher, compound synchronization,
and TTMS merge/strongest policy remain unchanged.

## Verification

The initial temporary composition probes passed 20 assertions per representation.
Two earlier probe attempts failed: a raw helper received named-network evidence
instead of a projected compound, and an explicit output was passed as an input
to an expression-style operator. The saved implementation reuses existing TTMS
slot readers and the tested `->` output-forwarding syntax instead.

Saved focused tests: **6 tests, 90 assertions, 0 failures, 0 errors**.

- Both representations: pending facts, two valid proofs, isolated contradiction.
- Withdrawal of both paths and recovery of one path at a fresh timestamp.
- Exact injected source identities and timestamps on proof supports.
- Three distinct private binding cells; exact support for each successful proof.
- Read-only observations preserve original source fields.
- Unknown fields and missing list tails remain pending; a fresh update repairs
  an incomplete term.
- Exact replay and stale replay cause zero activations and preserve the Net.
- Outer topology remains fixed across the checked updates.
- Invalid modes and term arities fail explicitly.
- Saved files remain under 300 lines; new functions have at most four arguments.

The demo completed for both representations. Observed outer entries stayed at
852 for compound terms and 914 for linked terms through all four stages. These
are topology observations, not controlled performance benchmark results or
memory-byte measurements.

Nearby production regressions: **30 tests, 119 assertions, 0 failures/errors**:

```sh
clojure -M:test propagators.runner-test propagators.network-patch-test propagators.compound-object-network-slot-test propagators.layered-support-test
git diff --check
```

The publication package includes only the two term examples, their harness and
tests, this report, and the required earlier proof/runtime helpers. Those helpers
were previously untracked. The proof harness now uses `runtime/completed` and
its own directory constant rather than importing the unrelated positive-relation
experiment and its benchmark-dependent test namespace. Existing proof
assertions are unchanged.

A first clean-copy test exposed that unnecessary test dependency. It was a
packaging error, not an inference failure. After removing that dependency, the
narrowed package passed against committed runtime `74fa9f2`, without any local
runtime/XR/compiler changes: **20 tests, 291 assertions, 0 failures, 0 errors**
(14 existing proof tests / 201 assertions plus 6 term tests / 90 assertions).

The clean check loaded and ran just these two test namespaces with
`clojure.test/run-tests`, and exited nonzero if either reported failures/errors.
The working-copy term tests were rerun after the cleanup and passed again.
Full repository tests are not implied by these focused checks.

Publication note: remote `main` at `2b3efe2` has newer Compiler 2/XR work and
diverges from the verified local base. This report does not claim compatibility
with that newer compiler, automatic migration, or a completed merge. Publishing
to a separate branch preserves the tested base; integration with updated main
requires a separate checked migration.

## Grounding and remaining work

The reviewed runner semantic-composition constraint (created 2026-09-25)
keeps domain logic outside the constructor. The reviewed TTMS dominance
constraint (created 2026-09-29, evidence still needs verification in the model)
does not authorize compiler or compound synchronization changes. The
layer-blind-dispatch rule (created 2026-10-09) is a candidate, not an approved
normative constraint. This experiment performs no KIROSHI model mutation.

Not implemented or established:

- A clause whose explicit shared Lain cells fully replace `unify-path`.
- Automatic enumeration of candidate facts or a strategy-selecting solver.
- General structural unification, occurs checks, recursive term generation,
  full Datalog, negation, or CLP search.
- Lowering arbitrary Lain networks into an indexed Datalog backend.
- New slot-level source epochs, arbitrary nested supported slots, or general
  bidirectional source retraction.
- Universal glitch freedom, unrestricted convergence, or a speed advantage.

Any future solver must preserve candidate isolation and declare its supported
relational fragment explicitly. Recursive term construction can make the domain
unbounded, unlike ordinary finite positive Datalog.
