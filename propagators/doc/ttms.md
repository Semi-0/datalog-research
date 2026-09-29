# TTMS — Temporary Truth Maintenance System

**TTMS** is this repository's opt-in timestamp-aware supported-evidence data
structure. It maintains a current value together with its source-cell dependencies,
supports withdrawal and recovery, and removes evidence subsumed by stronger support.
This document names the existing implementation; it does not introduce a new runtime.

The precise [constraint declaration](ttms-constraint.edn) is approved in the local
KIROSHI database as `:constraint/ttms-compacts-by-support-dominance` by `linpandi`
on 2026-09-29. It supersedes `:constraint/merge-never-silently-deletes-evidence`,
whose history remains intact. Supersession transaction: `13194139533738`;
approval transaction: `13194139533740`. The DB remains local and Git-ignored;
this declaration makes the approved policy reviewable in Git. Approval is not
proof: model evidence remains `needs-verification`, separately from test results.

“Temporary” is the agreed name, not a TTL or automatic expiration policy. Freshness
comes from explicit source timestamps, not wall-clock age. TTMS is distinct from
the existing distributed TMS, event consumers, and behavior reactivity; none are
implicitly migrated or renamed by this terminology.

## Vocabulary and implementation names

| Term | Meaning / owner |
|---|---|
| Premise | Exactly one source-cell ID, timestamp, and active/retracted status. |
| Support | A set of jointly required premises; `datastructures.support`. |
| Observation | One base value and its support, normalized independently of outer slot IDs. |
| Evidence | A set of observations, including conflicting/incomparable values. |
| TTMS content | Tagged evidence container; `datastructures.support-collection`. |
| Strongest | Explicit slot-backed `:base`/`:support` projection of current evidence. |
| Support procedure | `stdlib.support/procedure`; computes dependencies using ordinary layered application. |

All namespace names above are prefixed with `propagators.`. Keep existing public
symbols and the `:support` layer name; TTMS is the design name, not a compatibility
facade or an additional namespace. In prose, use **TTMS support**, **TTMS evidence**,
and **TTMS strongest projection** to distinguish the responsibilities.

## Data contract and specs

```clojure
;; source-cell is the actual NodeId where this information entered the network.
{:source source-cell :timestamp 1 :premises-status :active}

;; One supported observation. Both nothing and contradiction are valid bases.
{:base 10
 :support #{{:source source-cell :timestamp 1 :premises-status :active}}}

;; Raw cell content: an evidence set, even when it has only one member.
{:support/observations
 #{{:base 10
    :support #{{:source source-cell :timestamp 1 :premises-status :active}}}}}
```

Executable specs are the source of truth:

- `:propagators.datastructures.support/source`: `ids/node-id?`.
- `:propagators.datastructures.support/timestamp`: any value accepted by the
  existing `timestamp/time-rank` ordering; this is not a new clock implementation.
- `:propagators.datastructures.support/premises-status`: `:active` or `:retracted`.
- `:propagators.datastructures.support/premise`: exactly those three fields.
- `:propagators.datastructures.support/support`: a set of valid premises.
- `:propagators.datastructures.support-collection/observation`: exactly `:base`
  and `:support`; the base is unrestricted.
- `:propagators.datastructures.support-collection/evidence`: a set of observations.

`collection/content` accepts a raw two-layer map or slot-backed datum. It preserves
the base without unwrapping it, rejects extra layers, and normalizes outer slot
identity away. Equivalent observations deduplicate. The content wrapper contains
only `:support/observations`; `nothing` is also accepted as empty merge input.

Premises retain injection-cell IDs through derived computations. They do not
switch to the output cell or acquire fresh epochs merely because an activation
runs. Resolve a source against its owning live network with
`(net/network-cell-strongest network (:source premise))`. ID-shape validation alone
does not prove membership. This is neither historical-value lookup nor an address
for disambiguating repeated cells across copied nested networks or destroyed envs.

## Timestamp-aware support-set comparison

For premises with the same source, a newer timestamp covers an older timestamp
regardless of status. Equal timestamps cover only equal statuses. Different
sources cannot cover one another. Comparisons use existing `timestamp/time-rank`.

`(support/covers? new old)` holds when **every** old premise has a covering premise
in new. Extra sources are allowed. Do not replace this with ordinary `subset?`:
`A@2` covers `A@1` even though their records differ.

`(support/dominates? new old)` holds when:

1. New support is internally compatible.
2. New covers old.
3. Old does not cover new, **or** old is internally incompatible.

Compatibility requires each source to have a single timestamp/status pair. Never
normalize an incompatible computation before checking it; doing so hides mixed
input versions. The third clause permits coherent replacement of mixed support.
Support comparison does not inspect base values.

Here A/B abbreviate actual source-cell IDs; unmarked premises are active:

| Old support | New support | Strictly dominates? |
|---|---|---|
| `{A@1}` | `{A@2}` | Yes: freshness. |
| `{B@7}` | `{A@1,B@7}` | Yes: strict extension. |
| `{A@1,B@7}` | `{A@2,B@7}` | Yes: A advances. |
| `{A@1,B@7}` | `{A@2}` | No: B is missing. |
| `{A@2,B@7}` | `{A@1,B@8}` | No: A regresses. |
| `{A@1}` | `{A@1}` | No: equal support. |
| `{A@1 active}` | `{A@1 retracted}` | No: same-version conflict. |
| `{A@1 active}` | `{A@2 retracted}` | Yes: explicit newer withdrawal. |
| `{A@1,A@2}` | `{A@2}` | Yes: coherent replacement. |

## Merge, projection, and retraction

`merge-content` unions evidence, then removes every dominated observation. The
source-free bottom observation `{:base nothing :support #{}}` is discarded when
nonbottom evidence exists. Raw content therefore is **not an append-only history**.
Incomparable observations and equal-support unequal-value observations remain.

`strongest-value` compacts supplied content, computes current source states with
`support/join`, and excludes payloads whose per-source latest timestamp is stale.
It merges eligible bases using the injected existing cell merge function, then
combines the current source states with eligible observations' full support.
Mixed versions in a still-current observation remain visible. Empty content yields
`nothing`; nonempty content yields a layered datum even when it is unusable.

- `x/{A@1}` plus distinct `y/{B@7}` yields a contradictory base with `{A@1,B@7}`.
- Adding `z/{A@1,B@7}` or `z/{A@2,B@7}` subsumes both singleton observations.
- Different bases with equal support remain contradictory.
- Equal bases from independent sources combine their dependencies in strongest.
- Retracting A at a newer epoch removes A's old payload but preserves its
  retracted premise. A remaining B payload may no longer be contradictory, yet
  the result stays unusable until all conjunctively required sources are active.
- A mixed-version computation can have a noncontradictory base and still be
  unusable. Usability and base contradiction are distinct checks.

This is **conjunctive**, not alternative-justification TMS: even if A and B both
assert the same value independently, retracting either invalidates the combined
support. Contradiction/nothing must not erase dependency information.

## Laws and verification scope

The intended algebra and current regression checks distinguish three operations:

| Operation | Laws / invariant |
|---|---|
| `support/combine` | Set union: associative, commutative, idempotent; empty identity. Keeps mixed versions. |
| `support/join` | Current-state normalization: associative and commutative; normalization is idempotent. Self-join equals the input only for already normalized support. |
| `support/covers?` | Reflexive, transitive preorder; mutual coverage need not imply identical raw support sets. |
| `support/dominates?` | Strict comparison: irreflexive, asymmetric, transitive; incompatible support cannot dominate. |
| `collection/merge-content` | Associative, commutative, idempotent on normalized evidence; union followed by dominance compaction. Empty input normalizes arbitrary content. |
| Projection | Deterministic slot identity; never relabel an old computation with a newer premise to make it current. |

“Normalized evidence” contains no observation dominated by another. Therefore,
merging an arbitrary unnormalized set with itself may compact it rather than
return it byte-for-byte. Delayed evidence already dominated by retained evidence
cannot restore an old value. Use a consistent timestamp representation per source;
comparison uses timestamp ranks while compatibility checks actual timestamp/status
values.

Regression coverage includes active/retracted support sets, finite order checks,
merge laws, exact conflict dependencies, every tested three-observation arrival
order, source-cell lookup through two computations, and eight diamond schedules.
Finite enumeration is not a proof of unrestricted convergence or glitch freedom.

## Runnable example and composition boundary

```sh
clojure -M -m examples.lain.visualization-combinators.support-retraction-demo
clojure -M:test propagators.support-test propagators.support-collection-test propagators.support-glitch-test propagators.layered-support-test propagators.message-lift-test
```

The example wires `source -> (+20) -> (+5)` through ordinary layered applications.
Source updates explicitly provide timestamps; the support procedure combines input
support. Publication uses generic message lifting:

```clojure
(prop/compose-activation
 activation
 (message/lift-message collection/content))
```

Message lifting only packages values; it does not stamp sources, decide readiness,
or own merge policy. Concrete consumers decide usability. No scheduler changes,
hidden source registry, special compiler path, or TTMS-specific executor is needed.

## Current status and limits

The implementation report records **1,336 focused** and **3,054 default-suite**
passing assertions. The isolated staged TTMS tree also passes **72 additional**
event/visualization/dataflow assertions. Separate unfinished tracer work has two
known error-reporting failures and is excluded from that tree. See
[the report](../../examples/lain/visualization_combinators/SUPPORT_RETRACTION_REPORT.md)
for development history and remaining integration slices.

TTMS remains opt-in. General transport audit, automatic tracer source/epoch
stamping, consumer migration, and XR/reload end-to-end verification remain separate
work. Pairwise evidence compaction is quadratic in observation count (plus support
comparison cost); incomparable evidence may grow without bound. It is not a
security boundary, a full audit log, or a replacement for existing distributed TMS.
