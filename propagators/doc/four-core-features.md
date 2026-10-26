# Four Core Features of the Propagator System

Status: **restored from pre-refactor design notes** (`propagators/NOTES.md` before commit `484781b`, May 2026) and updated for current work on `main`.

Historical source (abbreviated):

```text
;; 4 core function of propagators
;; 1. networked semantics DONE
;; 2. fixpoint evaluation DONE
;; 3. partial information partialy
;; 4. dependence tracking nah
```

This document is the canonical place for that framing. Detailed mechanics live in linked docs; this page tracks **what the four features mean** and **how far the experiment has come**.

---

## The four features (definitions)

### 1. Networked semantics

**Meaning:** Computation is not a single function call; it is a **network** of cells (shared constraints) and propagators (local rules) wired by topology. Many writers can contribute to one cell; many propagators can read and emit along explicit edges.

**MIT reference:** Cells + propagators + scheduler on one graph; compound propagators classically **expand into** the parent network at install time.

**This repo:** Topology (`graph`) and state (`env`) are **separate** immutable values. Installers return updated networks. A network can be stored in a cell, merged, and evaluated with an explicit task queue. See [Core Runtime Model](core-runtime.md).

---

### 2. Fixpoint evaluation

**Meaning:** After inputs change, the system runs propagators until **quiescence** (no more tasks, or a controlled stop): a **fixpoint** of the local update rules under the current merge discipline.

**MIT reference:** “Run the network” until nothing pending.

**This repo:** `run-tasks` drains a FIFO queue of propagator ids (`eval-propagator` → `eval-cells` → enqueue downstream). Inner fixpoints appear inside runtime compounds (`compound-activate`) and layered apply (`p:apply-layered` runs a subnet to quiescence). Compile can defer runs (`:lazy`) or batch-enqueue at flush boundaries (`:queue` on branch `experiment/eager-install-stage-1`; see [Builder Policy](../../experiments/runtime/builder-policy-run-order-and-correctness.md)).

---

### 3. Partial information

**Meaning:** A cell’s value is not necessarily a single ground fact. It can carry **incomplete**, **conditional**, or **structured** information; **merge** combines contributions; **strongest** (or similar) picks the best readable view for propagator inputs.

**MIT reference:** Generic merge procedures; contradiction as first-class partial information.

**This repo:** `cell-merge` / `strongest-value` per domain. Plain scalars use simple merge; **named-network evidence** keeps an antichain in content and computes strongest lazily ([Named Network Evidence](named-network-evidence.md)). **Layered** objects and procedures are named-network slots ([Layered Procedure Network](layered-procedure-network.md)). **Compound** collection cells hold durable subnet state ([Compound Object Slot Sync](compound-object-slot-sync.md)). Contradiction is **not** a stub: it is the **top of the knowledge lattice** (`propagators/cells/bool4.clj`, with `nothing` as bottom and `join` / `meet` / `>=`), stored in cells, propagated, merged so that it stays absorbing while its provenance accumulates (`merge/preserve-contradiction`), marked *unusable for computation* rather than dropped (`value/unusable?`, `value/any-unusable-values?`), explained when produced by a failed merge (`propagators/cells/error_layer.clj`, [Cell Errors](cell-errors.md)), and dispatchable as policy through `merge/handle-contradiction`.

---

### 4. Dependence tracking

**Meaning:** The system can record **why** a cell’s merged content is justified — supports, retractions, and (in the full MIT stack) coordination with amb / failure — without changing how propagators are scheduled or activated.

**MIT reference:** Present in the complete propagator story, but **not inside** the propagator scheduler loop. In MIT as here, dependence is a **separate subsystem** wired at **merge** time (generic merge procedures / `cell-merge`), not in `eval-propagator` or `eval-cell`.

**Integration point in this repo:** the hook is **`propagators.cells.merge/cell-merge`** (and related `merge-cell-entry` / `strongest-value` policy), called from `eval-cell` when a message is absorbed:

```text
eval-propagator  →  messages  →  eval-cell  →  cell-merge  →  [dependence subsystem]
                     (no TMS)                    (only here)
```

`eval-propagator` and `eval-cell` stay dumb: enqueue topology, merge payload, compare strongest, wake neighbors. The subsystem records **what merge accepted** and **from which contribution**, not “which step ran” in the evaluator.

**This repo:** the subsystem **exists**, scoped to the content domains that opt in.
`cell-merge` (`propagators/cells/merge.clj`) tries, in order: equal payloads; either
side carrying an error layer (`error-layer/merge-contradictions`); a **semantic kind**
from `compound-information/semantic-kind` (`:support-collection`,
`:membership-collection`, `:distributed-tms`); the fallback protocol in
`propagators/cells/cell_protocol.clj` (`try-cell-merge`, currently for dependency,
scope-source, and intensity content); then `built-in-cell-merge`. Any `Throwable` from
the merge becomes an explained contradiction (`propagators/cells/merge_failure.clj`),
so merging is total. Justification and retraction live in the support-carrying domains:
`propagators/datastructures/support.clj` is the support preorder, `support_collection.clj`
the observation set (union observations, drop strictly dominated ones, keep
incomparable and same-version conflicts, join the known-source frontier),
`membership_collection.clj` the membership-guarded variant, and
`propagators/datastructures/tms/core.clj` claim / premise-state monotone reducer slots
whose strongest projection is the currently active view (`tms.clj` and
`tms/distributed.clj` are compatibility surfaces over it). Layered **`:provenance`**
remains separate **domain data** merged like any other slot — that contrast still holds
and is the point of the table below.

### What implements features 3 and 4 today

| Layer | Content | Merge | Strongest / projection | Code |
|---|---|---|---|---|
| Knowledge lattice | `nothing` bottom, `true`/`false`, `contradiction` top, as `[true-evidence? false-evidence?]` | `bool4/join`, `bool4/meet` | `bool4/>=` (top absorbs, bottom is subsumed) | `propagators/cells/bool4.clj` |
| Merge dispatch | any payload | `cell-merge` (alias `generic-merge`): equality → error layer → semantic kind → protocol → built-ins; `Throwable` → explained contradiction | `strongest-value` with the same dispatch shape | `propagators/cells/merge.clj` |
| Contradiction policy | contradiction, optionally with provenance | `preserve-contradiction` keeps top absorbing and accumulates provenance; `merge-failure/contradiction` records the failed operands and exception | `value/unusable?` marks it unusable **for computation**, not for transport | `propagators/cells/merge.clj`, `merge_failure.clj`, `value.clj` |
| Justification + retraction (opt-in) | observation = base + support premise set, plus known premise states | union observations, drop strictly dominated ones, keep incomparable conflicts, join the source frontier | undominated current observations merged by a caller-supplied base merge, projected as a `:base` / `:support` / `:premise-state` layered network; `nothing` carrying the frontier once support is withdrawn | `propagators/datastructures/support.clj`, `support_collection.clj` |
| Membership domain | versioned evidence with value requirements and independently usable membership guards | retain observations; never give an old payload a newer source version | projection selects claims whose guards match current source states | `propagators/datastructures/membership_collection.clj` |
| Claims / premise states | monotone claim and premise-state reducer slots | revision-ordered merge | strongest projection = currently active view | `propagators/datastructures/tms/core.clj` |
| Option evidence | antichain of named networks | stronger incoming evidence replaces weaker; subsumed updates ignored; incomparable kept alongside | `evidence-set/strongest` | `propagators/datastructures/evidence_set.clj`, `named_network.clj`, `reducer_cell.clj` |
| Explained failure | contradiction + error layer | `error-layer/merge-contradictions` accumulates errors | `error-layer/errors-of` | `propagators/cells/error_layer.clj` |
| Scheduler hook | — | `merge/handle-contradiction` (multimethod on `[tasks env]`, default identity), called from `cell_evaluator` | — | `propagators/cells/merge.clj` |

Behaviour, checked on `main` (Oct 2026) with `propagators.cells.merge` as `merge`,
`propagators.cells.value` as `value`, `propagators.datastructures.layered-value` as
`datum`, `propagators.network` as `net`, `propagators.stdlib.source-publication` as `publication`:

```clojure
;; the lattice: 1 ⊔ 2 is top, and top absorbs
(merge/cell-merge 1 2 net/empty-net)                                     ;=> :bool4/contradiction
(merge/cell-merge (merge/cell-merge 1 2 net/empty-net) :x net/empty-net) ;=> :bool4/contradiction
(value/unusable? :bool4/contradiction)                                   ;=> true
(value/any-unusable-values? 1 :bool4/contradiction)                       ;=> true  ;; tuple stays blocked

;; justification: the strongest view carries its support
(def c (merge/cell-merge value/nothing (publication/active :source/A 1 10) net/empty-net))
(datum/layer-value (merge/strongest-value c net/empty-net) :base)         ;=> 10
(datum/support-of (merge/strongest-value c net/empty-net))
;;=> #{{:source #propagators.ids.NodeId{…} :timestamp 1 :premises-status :active}}

;; retraction: a newer retracted state for that source withdraws the value
(def c' (merge/cell-merge c (publication/retracted :source/A 2) net/empty-net))
(datum/layer-value (merge/strongest-value c' net/empty-net) :base)        ;=> :bool4/nothing
```

Run: `clojure -M:test propagators.support-test propagators.support-collection-test propagators.support-glitch-test propagators.layered-support-test propagators.support-transport-test propagators.supported-slot-test propagators.tms-test propagators.ttms-primitives-test propagators.ttms-publication-test propagators.ttms-branching-test propagators.bool4-test propagators.cell-protocol-test propagators.error-layer-test propagators.sync-contradiction-test` → **2060 assertions, 0 failures, 0 errors**.

**What is still missing** (the honest version): plain payloads carry **no** justification
at all — `1 ⊔ 2` is a bare contradiction with no record of who produced it; TTMS
supports are conjunctive with version domination, not assumption-based alternative
justifications, and the `undominated` filter is quadratic in the observation count; legacy
ordinary TMS output cells are not retracted when their inputs are withdrawn and dead
evidence is never collected (`propagators/datastructures/tms.clj`); and contradiction
triggers no worldview search inside the runtime — `merge/handle-contradiction` is the
hook, and backtracking is expressed in-network
([Dependency-Directed Backtracking](dependency-directed-backtracking.md)).

See [Provenance vs dependence](#provenance-vs-dependence-layered) below and [Compound Object Slot Sync](compound-object-slot-sync.md) § future dependence tracking.

---

## Provenance vs dependence (layered)

| | Layered `:provenance` | Dependence tracking (feature 4) |
|--|------------------------|----------------------------------|
| **What** | Application layer on a compound object (e.g. `#{:a :b}`) | Generic justification for **cell content** at merge |
| **Where wired** | Layered procedure closures + slot propagators | **`cell-merge` only** (MIT-shaped); not `eval-cell` / `eval-propagator` |
| **Who builds it** | You install `provenance/+` (etc.) on `proc` | Merge/TMS when absorbing messages |
| **Retraction** | No TMS; change inputs/proc and re-run | Remove support → content/strongest recomputed from remaining justifications |
| **Still missing** | N/A (works as domain layer) | Justification for domains that opt out, assumption-based alternative justifications, retraction of legacy TMS outputs, collection of dead evidence |

---

## Progress matrix (historical → now)

| # | Feature | Historical (`NOTES.md`) | Current (`main`, Oct 2026) |
|---|---------|-------------------------|----------------------------|
| 1 | **Networked semantics** | **Done** — `graph`, installers | **Done** — immutable `Net` (`graph` + `env` + `dict` + parent/child `relationship`), variadic installers, `compile` DSL, runtime compounds, layered/compound-object subnets as network-shaped values |
| 2 | **Fixpoint evaluation** | **Done** — `run-tasks` | **Done** — continuation-constructed trampoline drains the task queue to quiescence; inner fixpoints in compounds and `p:apply-layered`; explicit `nb/run-propagators` / `install-propagator!` for caller-controlled drains |
| 3 | **Partial information** | **Partial** — `CellValue` kinds; strongest often identity | **Done as a lattice, specialised per domain** — `bool4` bottom/top with `join` / `meet` / `>=`, kind-dispatched `cell-merge` / `strongest-value` plus the `cell_protocol` fallback, named-network evidence merge, layered slots, compound subnet state, arithmetic **provenance layer** on outputs, contradiction carried with provenance and an `:error` layer on merge failure |
| 4 | **Dependence tracking** | **No** — merge-time subsystem | **Done at the merge boundary, opt-in per domain** — support sets with version domination and retraction (`support.clj`, `support_collection.clj`), membership guards (`membership_collection.clj`), claim / premise-state TMS (`tms/core.clj`), evidence-carrying named networks (`evidence_set.clj`) and reducer cells; `eval-cell` stays dependence-free by design. Plain scalars and legacy TMS outputs remain unjustified |

---

## What changed since the doc split (`484781b`)

The long single-file `propagators/NOTES.md` was split into `propagators/doc/*`. The **four-core checklist** was not copied into the new index; only fragments remained (e.g. “Cells hold partial information” in [README](README.md), “dependence tracking is not implemented” in [core-runtime](core-runtime.md)).

**Restored here** so the experiment keeps a stable vocabulary across refactors.

### Milestones since the split (selected)

| Area | Progress |
|------|----------|
| **Compound data** | Linked-list spike documented as fragile; **compound-object slot sync** (`p:car` / `p:cdr`) is the replacement direction |
| **Layered procedures** | `p:layered-procedure`, `p:apply-layered`, `layered/+` … `layered//`, `install-layered-procedure!`, **`provenance-arithmetic`** (`+` `-` `*` `/` with base + provenance bootstrap) |
| **Stdlib** | Leaf namespaces under `propagators.stdlib.*`; deprecated barrel |
| **Builder policy** | Stage-1 `:queue` + flush experiment on `experiment/eager-install-stage-1`; results on `main` in [Builder Policy](../../experiments/runtime/builder-policy-run-order-and-correctness.md) and [Eager Install](../../experiments/runtime/eager-install-and-arithmetic-procedure.md) |
| **Tests** | Default layered tests use full bootstrap; reactive extension is explicit special-case tests only |
| **Merge lattice & TMS (Oct 2026)** | Corrected stale feature-3 / feature-4 claims: `cells/bool4.clj` is the knowledge lattice, `cells/merge.clj` the merge / strongest dispatch (semantic kind → `cells/cell_protocol.clj` fallback → built-ins) with `preserve-contradiction` and `handle-contradiction`, `cells/merge_failure.clj` + `cells/error_layer.clj` explain failed merges, `datastructures/support.clj` + `support_collection.clj` + `membership_collection.clj` carry justifications and retract them by source version, `datastructures/tms/core.clj` carries claims and premise states. Added “What implements features 3 and 4 today” |

---

## MIT alignment (short)

| Topic | MIT-style | This repo today |
|-------|-----------|-----------------|
| Cell merge | Generic merge procedures (`merge` / `equivalent?` / `contradictory?` scmutils generics, overridable per cell) | `cell-merge` — dispatch on semantic kind, then the `cells/cell_protocol.clj` fallback (`try-cell-merge` / `try-cell-strongest`), then built-ins; `bool4` supplies bottom and top |
| Scheduling | Alert on change | Explicit propagator task queue |
| Propagator body | Often mutates neighbors | Pure `f` → messages → `eval-cells` |
| Network | Monolithic object | Immutable `graph` + `env` + `dict` |
| Compound | Install-time expansion | Runtime compound + avatars; compile flat install for primitives; compiled compound **planned** |
| Dependence / backtrack | Merge-time subsystem (not in scheduler): `v&s` value/support pairs, global `*premise-outness*` + worldview numbering, nogoods, `search.scm` DDB | Merge-time subsystem, opt-in per domain: support sets with version domination, membership guards, claim / premise-state TMS, evidence networks; backtracking expressed in-network. No global worldview |
| Contradiction | `contradict` + handlers; **ordinary cells raise** “Ack! Inconsistency!” rather than storing it; TMS cells emit a `nogood-effect` | First-class top of the lattice, stored, propagated, provenance- and error-layer-annotated; `handle-contradiction` is the policy hook; no worldview search inside the runtime |

---

## Open work (tied to the four features)

1. **Partial information** — richer contradiction *policy* (user-replaceable resolution
   or search on contradiction, MIT’s `contradict` + handler stack); less ad hoc
   strongest; unify compound-data dispatch away from `c:linked-list` only.
2. **Fixpoint / construction** — stage-2 `install-arithmetic-procedure`; optional default `:queue` only with flush-aware helpers.
3. **Networked semantics** — compiled compound lowering; port-order discipline for set-backed graph ports.
4. **Dependence tracking** — extend the merge-time subsystem to domains that still
   opt out (plain payloads merge to an unjustified top), assumption-based alternative
   justifications, retraction of legacy TMS outputs, collection of dead evidence.
   Keep it on `cell-merge` (MIT-shaped); do not wire into `eval-cell` /
   `eval-propagator`; not the same as layered **:provenance**.

Commands and file map: [Experiments And Commands](../../experiments/runtime/experiments-and-commands.md).

---

## Related documents

- [Core Runtime Model](core-runtime.md) — graph/env, scheduler, limits
- [Named Network Evidence](named-network-evidence.md) — partial information discipline
- [Compound Runtime](compound-runtime.md) — inner fixpoints, avatars
- [Layered Procedure Network](layered-procedure-network.md) — procedures as partial network values
- [README](README.md) — documentation map
