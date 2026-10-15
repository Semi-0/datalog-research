# Bounded DB–rule fixed-point experiment

## Question and hypotheses

Can an ordinary propagator feedback topology derive graph/dataflow consequences
more efficiently than repeatedly guessing complete bindings, while retaining
TTMS withdrawal/provenance and a swappable strategy?

The target is a reusable coordination substrate, not a faster host interpreter
hidden behind a Lain call. Runtime semantics, the declarative question and its
search/matching strategy must remain separate.

1. Indexed relational matching should avoid AMB's Cartesian guesses.
2. Occurrence-aware delta matching should repeat less work than full joins.
3. Concurrent checkpoint and local no-good learning remain valid alternatives
   under the same question and proof-publication contracts.
4. Separate TTMS proof entries should permit alternative derivations and grounded
   cyclic withdrawal without changing global conjunctive TTMS policy.
5. Finite domains can refine backward through an actual constraint relation;
   this does not make ordinary Horn implication reversible.

This is an additive experiment. No KIROSHI mutation, production migration,
commit or push is part of this task.

## Declaration and matching contracts

The opt-in Lain factories are ordinary session-extension bindings:

```clojure
(rule-set
  (rule :edge-to-reach
    (relation :reach :x :y)
    (relation :edge :x :y))
  (rule :reach-via-reach-edge
    (relation :reach :x :z)
    (relation :reach :x :y)
    (relation :edge :y :z)))
```

Actual heads, bodies and variable connections belong to the `.lain` file. The
extension provides only generic constructors; see [LAIN_DECLARATIONS.md](LAIN_DECLARATIONS.md).

An omitted guard explicitly declares an unconditional rule. The existing
four-argument form still accepts a genuine Lain guard. See
[VALIDATION_OPTIMIZATION.md](VALIDATION_OPTIMIZATION.md) for the approved follow-up
that bypasses redundant validation and separately batches genuine guard inputs.

Their immutable representation uses the existing experimental rule convention:

```clojure
{:id :reach-via-reach-edge
 :head [:reach [:x :z]]
 :body [[:reach [:x :y]] [:edge [:y :z]]]
 :guard nil}
```

V1 has binary predicates, one or two ordered body occurrences, at most three
variables, range-restricted heads, distinct rule identities, and a fixed finite
node domain. The fixed catalog and pure guards may capture immutable constants.
Dynamic external inputs/effects in guards are outside this experiment: their
authority would need explicit declaration and proof transport, not implicit trust.

The shared strategy boundary is:

```clojure
(solvers/matching-result strategy request {:workers 4})
;; => {:bindings #{correlated-variable-maps} :probes n}
```

A request carries `:rule`, `:variables`, `:domain`, `:demand`, `:tables`, `:deltas`,
`:initial?`, immutable occurrence `:indexes`, and the compiled `:query` template.
Frozen delta comparisons also set `:require-delta? true`. Body occurrence order
is significant even when two atoms have the same predicate.

AMB repair, checkpoint, concurrent checkpoint and concurrent-learning checkpoint
reuse `solver-strategy/ConstraintSolver` and `solver-question/build`. Indexed
matchers return unconditional bindings directly and validate genuine guards
through the compiled Lain question with batched, fully bound inputs.
The common query decodes AMB's positive ordinal choices into actual node values,
tests DB membership/demand, and calls the supplied Lain guard. No compiler change
or host guard substitution is required. A guard-free unary copy is a direct
projection for **all** strategies.

## Ownership and dependency direction

| Module under `examples/lain/rule_fixedpoint/` | Owns | Does not own |
|---|---|---|
| `model` / `catalog` | Checked declarations and opt-in Lain factories | Scheduling or search |
| `query` | One compiled binding constraint, node ordinal adapter | Recursive derivation |
| `solvers` / `matching` | Swappable binding discovery, occurrence indexes | DB truth or source epochs |
| `engine` | Fixed source/DB/match/consequence feedback topology | Private fixed-point executor |
| `evidence` / `routing` | Ground support, proof identity, source-to-proof routing | New TTMS policy |
| `channels` | Existing snapshot publication for control/progress cells | Premise authority for facts |
| `domain-policy` / `contractor` | Local domain intersection and layered addition relation | Global merge changes |
| `leapfrog` | Pure existing LFTJ correctness adapter | Optimized/default strategy |
| `fixtures` / `benchmark` / `limits` | Independent oracle, measurement and bounds | Runtime answers |

```text
grounding source cells -> proof DB cells -> rule matcher -> match cells
                                ^                           |
                                +--- consequence publisher -+

rule authority + source states -> indexed premise fanout -> affected proof entries
head demand cell -----------------------> rule matcher
strategy control cell ------------------> rule matcher
```

All semantic progress, indexes, matched bindings, proof content and routing data
are immutable cell values. Closures contain immutable construction context, not
mutable inference state. Thread pools, budgets and measurement counters are
runtime-owned and stay outside Net.

Execution reuses the existing `relational-datalog.runtime/run`, continuation
constructor, task queue and trampoline, with the existing proof-relation patch
evaluator. Each activation publishes ordinary messages. There is no host loop
that recursively derives all DB consequences before returning.

## Evidence and withdrawal invariants

- Each support premise has the actual grounding **cell ID**, timestamp and status.
- A fact source and a rule authority are explicit physical cells.
- A derived proof conjoins the parent supports and an active rule authority.
- Distinct proof entries are alternatives; sources within an entry are joint
  requirements. This domain-level OR does not change TTMS's conjunctive policy.
- Proof identity is a stable ID of rule identity, full binding and flattened
  grounding-source IDs. Timestamps/statuses belong to retained TTMS evidence.
  Bindings use declared variable order and the fixed domain's representatives;
  source sets use canonical UUID insertion order before the shared ID helper.
  Equal maps/sets must not get different IDs because of their printed order.
- Guess cells, checkpoint frontiers and match/progress snapshot cells do not
  become authority for published DB facts.
- The reverse index routes premise states even through nothing/contradiction.
- Same-epoch conflicting source payloads invalidate dependent proofs as supported
  contradictions. Routing also observes actual source bases, not only clock/status
  changes. It preserves the original proof premises; it never invents a retraction
  or a fresh epoch to hide a conflict. A newer source version can repair the proof.
- A new proof for an existing tuple is a delta, not a no-op.
- A cycle cannot survive loss of its last grounding proof. No derived cell is
  promoted into an independent premise.
- Duplicate updates and quiescent reruns do not reinstall topology or create
  epochs. External updates provide explicit versions.
- Rule authority changes force a new evaluation, including indexed delta mode.

`engine/restrict` updates one rule's head-demand control cell. It restricts future
matches against the existing DB; it does not invent missing body facts or retract
previously established, still-valid facts. Cross-rule demand planning is deferred.

`engine/select-strategy` changes one ordinary control cell and reruns the prepared
strategy against the same DB, declarations and query templates. Tests require all
six strategies to execute in one evaluator while retaining the same physical
grounding cells and topology. This is not merely rebuilding six similar programs.

## Genuine backward refinement

`contractor/prepare` installs a fixed three-port `x+y=z` network. Full finite
bounds and narrower assumptions have separate justifications. The ordinary
layered procedure computes base projections and combines support/state layers.
An activation transform retains computation justifications before publication;
separate propagators publish domains and route premise states.

With `z={5}` and `y={2}`, `x` becomes `{3}`. Withdrawing `y={2}` restores the full
bound and then refines both x and y to `0..5`; reasserting `y={1}` gives `x={4}`.
An empty domain is a local contradiction and a newer assumption can repair it.
Retraction must not require changing TTMS compaction or overwriting cell content.

## Verification and measurement

Workloads: 4/8/12-node chains; one/two-stage diamonds; a four-node grounded cycle;
disconnected components; and reachability plus recursive taint over the actual
cell-to-cell graph of nine installed dataflow cells. Independent BFS verifies
closure, but never supplies runtime answers.

Two separate measurements:

1. **End-to-end:** build evaluator/index/proof state, inject facts, derive to
   quiescence and verify complete answers.
2. **Frozen rule:** one fixed snapshot and occurrence-delta window; all six
   strategies must return the same delta-relevant bindings. The snapshot is not
   counted as end-to-end recursive work.

Compilation/template setup and pool creation are reported separately. All other
per-sample initialization, query instantiation, index work and proof work are in
timing. One warmup and five measured runs; measured order alternates; caches are
disabled; the same four-worker pool is reused. Never repeat a failed warmup or
treat incomplete enumeration as empty answers.

Bounds per sample: 30 seconds, one million aggregate outer/inner activations,
10,000 proof entries, 20,000 entries in an active Net, JVM heap at 2 GiB. Bounds
are cooperative, checked at activation boundaries. Timing includes checks.
Record predicate activations (including repeated checks), tuple probes, proof
count, topology size, activation count, wall time, and cumulative allocation on
the main and completed worker threads. Allocation is **not** peak retained heap.

Support a speed hypothesis only when indexed-delta's median beats both repair
and checkpoint on at least two larger **fully completed comparable** cases.
Report concurrent comparisons separately. A timeout is not a numeric speedup.

Focused tests also cover retraction/restoration, delayed stale updates,
same-tuple alternative proofs, authority withdrawal, duplicate publication,
head-demand changes, exact grounding, cycles, self-joins, stable topology,
arithmetic narrowing, local contradiction and LFTJ parity.

## Stops and exclusions

Stop and revise if correctness requires changing Net, the runner constructor,
compiler/application semantics, cell evaluator, layered dispatcher, compound
synchronization or global TTMS policy; hidden mutable inference state; or a
separate recursive fixed-point executor. A negative performance result is valid
experimental evidence, not permission to expand these boundaries.

Defer negation, aggregation, abduction, unbounded terms, arbitrary arity,
dynamic rule replacement, external/LLM effects, cross-worker shared knowledge,
optimized LFTJ execution and production integration. Canonical flattened support
sets bound cycles but may still have exponential proof cost; budgets are not a
proof of practical scalability.

## Grounding

The approved design follows propagator relations, supported information and
autonomous choice from [Radul and Sussman, *The Art of the Propagator*](https://groups.csail.mit.edu/mac/users/gjs/6.945/readings/art.pdf).
The [Flix lattice-Datalog paper](https://flix.dev/paper/pldi2016.pdf) motivates
separating lattice information and semi-naive work. Neither paper proves this
prototype's TTMS withdrawal behavior or performance; those require the tests
and matched measurements above.
