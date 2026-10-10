# Functional-network expressiveness assessment

## Comparison boundary

Baseline: the production Compiler 2 in this checkout at
`ab30fcbdc471665eebe3606e7edd92ac5ff1f771`, without the experiment's dispatch
rules or `apply` binding. Candidate: `experiments/functional_network.clj`.
This compares source-language capabilities, not access to arbitrary Clojure
host code. No production implementation changes are part of this assessment.

The ordinary-apply constructor was verified before the compiler entry-point
extension, without changing the implementation for that constructor. Between
those two initial snapshots, the tests demonstrated an existing capability;
they did not add expressive power. The subsequent compiler extension makes the
result-cell contract explicit and normalizes inherited raw primitive results
through a generic CPS continuation. The final experiment suites now exercise
that compiler entry point.

## Framework

Matthias Felleisen, *On the Expressive Power of Programming Languages*,
Science of Computer Programming 17 (1991), 35–75:
[published paper](https://www.cs.tufts.edu/comp/150FP/archive/matthias-felleisen/expressive-as-published.pdf).

Definitions 3.3 and 3.11 ask whether a construct can be eliminated while
preserving programs and their behavior, leaving retained constructs structurally
unchanged. Macro-expressibility additionally requires a fixed compositional
translation of each eliminated construct. Definition 3.17 compares capabilities
inside a common language universe. This is an ordering, not a numerical score.

The paper's basic observation is termination. For this experiment, the proposed
adaptation observes results, unavailable information, contradictions/errors,
exposed output ports, and effect-request/receipt traces under finite external
update schedules. Quiescence with waiting cells is not divergence. Generated
identities are compared up to consistent renaming; raw graph size is a stability
diagnostic, not an expressiveness measure. These observations are tested only
for the cases below, not quantified over all program contexts or schedules.

## Findings

| Capability | Production baseline | Functional-network experiment | Assessment |
| --- | --- | --- | --- |
| Scalar closure application | `cell-expr` | `network` | Same result in the paired example. |
| Bidirectional addition | `def-constraint` | Explicit rules with returned cell list | Both infer `[2 3 5]` in three directions. No gain in this arithmetic capability. |
| Reusable constraint constructor | Dedicated compiler form exists | Ordinary closure, definition, and `apply` composition | Verified library construction; complete elimination of the old form remains unproved. |
| Application from runtime cell-list structure | No `apply` binding in default environment | Ordinary `apply` operator | Verified new interface; absence of a baseline binding is not a non-expressibility proof. |
| Output interface | Closure output declaration; constraint has its own return convention | Returned list members register output ports, including unavailable members and late tails | More uniform composition. Merely renaming the old closure does not reproduce it. |
| Definition expression | Returns the bound cell | Returns a cell containing a binding receipt | Observable semantic change, rather than a conservative extension of shared `def` syntax. |
| External effects | Existing request/boundary machinery | Same machinery inside recursive network bodies | Existing capability composed with the new interface. No new external execution authority. |

The generic constructor is written in the experimental language:

```clojure
(define define-constraint
  (network (name definition-network)
    (define name
      (network (args)
        (apply definition-network args)
        args))))
```

It accepts a definition network as a value and returns a callable through the
name cell. It does not add a dedicated host implementation of `define-constraint`.
However, its invocation convention takes one list argument. The old syntax's
fixed positional call convention is not automatically preserved.

The new return contract is intentional: it makes every expression return a cell
and lets the expression determine that cell's information. The old last-argument
constraint convention is not a correctness requirement for the new design.
The following observations limit claims of backward-equivalent translation;
they do not identify defects in the new semantics:

1. Replacing `(def answer 42)` with experimental `(define answer 42)` changes
   the result cell's observed information from `42` to a receipt.
2. Replacing the old three-input addition constraint with a network returning
   `(list a b c)` changes the observed call result from `5` to `[2 3 5]`, even though
   the surrounding cells contain the same answers. A consumer of the call's
   scalar result cannot use that replacement unchanged.

The existing `ordinary-network-does-not-expose-returned-parameters` test gives
a third counterexample: under the baseline compiler, returning the parameter
list from an ordinary closure leaves the caller's `a` unavailable when `b=3`
and `c=5`. The experimental output registration produces `a=2`.
This rejects the simple closure-renaming translation, not every conceivable
encoding using the old language.

## Evidence and remaining proof obligations

`test/experiments/functional_network_expressiveness_test.clj` adds four paired
tests: scalar application, all three addition directions, constraint call result,
and definition receipt. All 13 assertions passed; maximum individual duration
was 1390.48525 ms. Namespace startup was reported separately.

The existing experiment suites cover recursive linked-list construction,
late values/tails, stable repeated activation, higher-order application,
generic constructor composition, and request-before-effect execution.
Those are bounded behavioral evidence, not a formal language comparison.

To claim strict greater expressiveness, define a common conservative universe
that distinguishes old value-returning definition from new receipt-returning
definition, then establish a non-expressibility result for a specific facility.
To claim the old special forms are eliminable, provide local translations that
also preserve call arity, returned values, lexical capture, late updates,
contradictions, and effect traces. No such complete proof is supplied here.

Current conclusion: the experiment demonstrates a more compositional network
interface and a working library-defined constructor. A strict expressive-power
ordering between the two languages is unresolved.
