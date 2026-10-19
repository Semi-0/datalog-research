# AMB with slots that return existing cells

The small experiment supports the idea. Resolving slot paths directly to
existing cells removes forwarding work while retaining ordinary cell updates
and scheduling. Combining direct procedure-layer access and result-cell slots
cuts median time by 51% on the opaque AMB question and 62% on its decomposed
constraint graph. Combining these changes with indexed withdrawal saves more.

## What was changed

`cell_slots.clj` keeps an immutable tree in the experimental network dictionary:
`owner -> slot -> canonical cell ID`. Multiple paths can name the same cell.
`resolve-slot` returns `[cell-id unchanged-network]`, with no extra cell,
forwarding propagator or message. Consumers subscribe to the returned ID through
the existing propagator constructor; writes use the existing cell evaluator.
Repeated paths therefore share both value and scheduling identity.

Two application boundaries use these references:

1. **Procedure preparation:** an unambiguous declared procedure layer points to
   a closure cell that already exists. Read it directly instead of copying it
   into a temporary object and running legacy slot synchronization to recover
   that same value.
2. **Result slots:** each base/support/state procedure already has a result
   cell. Bind its result-bank slot to that cell instead of installing the
   bidirectional slot/avatar synchronization network. The assembler subscribes
   directly to the referenced result cells.

The branch/reducer execution stages, base calculation, support/state
procedures, TTMS publication, AMB controls and runner remain in place. The
assembler publishes an ordinary immutable compound snapshot, so historical
values do not acquire live references into a later network.

The experiment marks only caller-audited `from-primitive` closures. Fast result
slots also require the existing support/state procedures and no other layer
kinds. Unmarked/custom applications use the original dispatcher; ambiguous
procedure declarations use the original materializer. Unknown slots and
incompatible rebinding fail explicitly. There is no new general protocol for
rebinding, cyclic aliases or arbitrary aliases between existing scalar node IDs.
This tests multiple slot paths sharing a canonical cell within an application.

All changes are in experimental namespaces and their tests. Temporary function
redefinitions run only in an isolated benchmark/test JVM, never in the editor.
No production runner or cell-store behavior is changed.

### Premised compound boundary

A raw leaf cell is insufficient when the containing compound carries premises
that are absent from the leaf. For example, `{x: 10}` supported by `p` must not
expose an unguarded `10` after `p` retracts. Replacing the compound can also
replace its physical leaf cell, making a permanent alias to the original leaf
stale even after recovery.

A regression now compares raw-leaf access with an alias to the existing
membership-aware slot projection. The context-bearing slot returns `10` under
the original premise, becomes unavailable on parent retraction, and returns
`20` under the replacement premise. The raw alias stays `10` throughout. The
earlier published snapshot stays `10` as required.

Thus a general direct-slot implementation must return a cell or view for the
slot **in its owning context**, preserving value support, membership guards,
transported premise states, and replacement identity. Paths may share such a
cell when these semantics coincide. Reusing one raw leaf across different
premised contexts does not establish that equivalence. The current experiment
does not replace the existing membership-aware compound-slot transport, and
its speedups do not establish correctness for eliminating that transport.
The guarded regression deliberately retains the existing slot propagator;
it verifies the boundary rather than implementing a propagation-free context view.

## Measurements

2026-10-19, Java 27, one warmup per mode, three measured samples, forward/reverse
mode order on alternate samples, network cache disabled. Each mode starts with
the same marked immutable fixture. Index initialization is included; fixture
construction, namespace loading and exhaustive oracle generation are excluded.
Activation counting and cooperative budget checks are identical in each mode.
No other timed benchmark JVM ran concurrently.

Both fixtures have three choices of domain size five (125 possible tuples) and
return `[3 4 5]`. These are first-answer searches, not full enumeration.

| Mode | Opaque median | Opaque activations | Decomposed median | Decomposed activations |
| --- | ---: | ---: | ---: | ---: |
| Ordinary | 1,037 ms | 12,223 | 2,306 ms | 31,686 |
| Direct result-cell slots only | 919 ms | 10,261 | 1,961 ms | 25,672 |
| Direct procedure-layer slots only | 634 ms | 7,318 | 1,205 ms | 16,566 |
| Both slot changes | 511 ms | 5,356 | 866 ms | 10,552 |
| Indexed withdrawal only | 745 ms | 8,065 | 1,568 ms | 19,780 |
| Indexed withdrawal + both slot changes | 430 ms | 3,466 | 701 ms | 7,172 |

Direct procedure-layer access saves more than result-slot forwarding alone.
Both slot changes reduce activations by 56% and 67%, respectively. Combined
with indexed withdrawal, time falls 58% and 70% versus ordinary, or 42% and 55%
versus indexed withdrawal alone. The indexed variants still perform the same
54 withdrawals in each question.

Median cumulative allocation falls from 1.479 to 0.761 GB for opaque AMB and
from 3.463 to 1.261 GB for the decomposed question with both slot changes.
Adding the index lowers these to 0.647 and 1.033 GB. These are cumulative
allocations, not live heap size; substantial work remains.

These timings establish benefit for these audited fixtures, not a general
speedup guarantee for arbitrary slotful procedures. This experiment removes
synchronization needed only to reconstruct already-known cell relationships;
it does not establish that every compound-object synchronization can be removed.

## Correctness and reproduction

Every completed warmup and sample matches the ordinary runner's exact final
solution, choice records, learned knowledge and repair state. Each solution is
also checked against an independent exhaustive oracle. Small unsatisfiable AMB
tests compare the exact constraint-call sequence and multiplicity as well as
final state, guarding against hidden reruns or changed search behavior.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.shared-slot-benchmark 3 opaque-5 decomposed-5
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:test propagators.experimental.runner.amb-shared-slot-test
```

Raw samples: [SHARED_SLOT_RESULTS.edn](SHARED_SLOT_RESULTS.edn).
Tests cover shared identity, ordinary subscriber scheduling, duplicate evidence,
withdrawal/recovery, equal-version conflicts, immutable snapshots, missing slots,
incompatible rebinding, public layer views, AMB exhaustion, unchanged constraint
evaluation, and the unmarked-application fallback. The larger-constraint fixture
tests also passed (two tests, 14 assertions). The full repository suite was not
rerun for this isolated experiment.

The shared-slot suite now passes seven tests with 44 assertions, including the
premised-compound boundary. The membership collection suite also passed 14
tests with 299 assertions: 21 tests and 343 assertions total, zero failures/errors.
Clj-kondo reported zero warnings/errors for all four new code
and test namespaces, and `git diff --check` passed.
