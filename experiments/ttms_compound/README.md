# TTMS compound and reducer experiment

This is an opt-in composition of existing TTMS evidence and reducer-cell protocols.
It does not change default Lain bindings, named-network subsumption, TTMS merge
policy, or the compiler. No new whole-snapshot publication rule is installed.

```sh
clojure -M -m experiments.ttms-compound.demo
clojure -M:test ttms-compound
```

## Bidirectional supported slots

`propagators.experimental.ttms-compound/p:slot` constrains a compound cell and a
slot cell in both directions. Inputs carry ordinary TTMS content, with actual
source-cell IDs, explicit source timestamps, and premise state. A compound base
is an existing named network. Constants and plain maps are not silently stamped.

```clojure
(compound/p:slot :x x-id object-id)
```

Every observation supports both its slots' existence and their values. A newer
version from that source makes the older structural contribution stale. An
independent source's still-current membership remains. The same rule applies to
a supported update entering through a slot: the accessor never creates a source
or advances a timestamp. Whole-object source versions invalidate all older
contributions from that source, so per-slot versioning requires per-slot sources.

Forward access maps supported object observations to supported slot observations.
Reverse access embeds new slot observations as singleton compound contributions.
A reflected observation already derivable from the parent is not embedded again.
Singleton topology is created once at installation; activation adds no runtime
cells, propagators, accessor declarations, or source versions. Missing slots still
forward source-state knowledge so delayed child feedback cannot revive stale data.

Nested access preserves outer membership support. Same-version incompatible slot
values remain contradictory; compatible partial compound contributions can join
under the existing named-network rules. A projected compound base can be a native
named-network evidence set; inspection resolves it with `evidence/strongest`.

## Recorded history on reducer-cell

`propagators.experimental.ttms-reducer/p:collect` composes three ordinary stages:

```clojure
(history/p:collect input-id reducer-id archive-source-id output-id)
```

1. Record the input's TTMS observations and source-state frontier in reducer-cell
   content. Set union deduplicates records; source-state join advances the frontier.
2. Run a pure strongest network producing historical records, current records, and
   their provenance. `:reduced/dependence` is current-record support;
   `:reduced/epoch` is a frontier identity, not a newly minted source timestamp.
3. Record that state at an explicitly supplied, exclusively owned TTMS injection
   cell using the existing `ttms-publication/p:observe`. Forward it to the output.

The recording source advances only when its recorded value changes. Ordinary
reruns and repeated evidence do not advance it. The output's support refers to this
actual archive cell: it asserts what is recorded now. Original input timestamps
remain inside each historical record and are never rewritten as current premises.
Withdrawing the input removes its current contribution while the archive remains
readable. This is an explicit recording boundary, not a transparent identity.

The output base contains:

- `:records`: original `:base` and `:support`, plus a derived `:current?` flag.
- `:history`: all observed payloads, including recorded contradictions/withdrawals.
- `:current`: the payloads whose original premises are currently active.
- `:support`: the current records' original support.
- `:frontier`: latest known states per original source.

Records sort by source identity, then that source's timestamp; independent sources
have no invented global arrival order. A contradiction stored as a record remains
data about an observation, with its provenance. Collection is not arithmetic over
those payloads and does not declare their contents computationally usable.

The same collector retains semantic graph maps and immutable network values. Old
structure remains intentionally reachable through history, while current views can
shrink. The collector records only evidence it observes after installation; it is
not a log of every queued message or unobserved scheduler activation.

## A counterexample to general transparent reduction

The demo also runs `dependency-shrink-counterexample`. An old result `[7 8]`
has support `{A@1, B@1}`. The next result `[8]` depends only on `{B@1}`, with
`A@2-retracted` in the source-state frontier. Existing TTMS support dominance lets
the old joint observation subsume the incoming B-only observation. Projection
then rejects the old observation as stale, leaving `the-nothing` instead of `[8]`.

The suite records this as an explicit limitation of current native TTMS, not as
an acceptance test for a repair. It is why this history collector uses a real,
explicit recording source rather than publishing an arbitrary reduced history
with relabelled input support. A general transparent compound/reducer algebra
still needs a rule for changing dependency sets, plus merge-law verification.
No TTMS dominance rule is changed by this experiment.

## Verification boundaries

The focused suites exercise parent/child updates, missing and removed slots,
independent sources, same-version conflicts, nested access, withdrawal/recovery,
stale feedback, duplicate evidence, fixed-point reruns, retained original support,
graph/network payloads, and history merge associativity/commutativity/idempotence.
The default suite separately covers contradiction forwarding through `->`, `<->`,
identity boundaries, and ordinary slot peers.

This prototype is not a proof of a new recursive TTMS compound algebra. It reuses
existing temporal dominance, including its conjunctive-support policy. Arbitrary
independently layered child TTMS datums, executable-network retirement, distributed
source addressing, and archive garbage collection are not established here.
History grows intentionally; no bounded-memory claim is made. No language binding
or frontend control is installed by loading the experiment.
