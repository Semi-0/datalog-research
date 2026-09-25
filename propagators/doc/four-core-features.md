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

**This repo:** `run-tasks` drains a FIFO queue of propagator ids (`eval-propagator` → `eval-cells` → enqueue downstream). Inner fixpoints appear inside runtime compounds (`compound-activate`) and layered apply (`p:apply-layered` runs a subnet to quiescence). Compile can defer runs (`:lazy`) or batch-enqueue at flush boundaries (`:queue` on branch `experiment/eager-install-stage-1`; see [Builder Policy](builder-policy-run-order-and-correctness.md)).

---

### 3. Partial information

**Meaning:** A cell’s value is not necessarily a single ground fact. It can carry **incomplete**, **conditional**, or **structured** information; **merge** combines contributions; **strongest** (or similar) picks the best readable view for propagator inputs.

**MIT reference:** Generic merge procedures; contradiction as first-class partial information.

**This repo:** `cell-merge` / `strongest-value` per domain. Plain scalars use simple merge; **named-network evidence** keeps an antichain in content and computes strongest lazily ([Named Network Evidence](named-network-evidence.md)). **Layered** objects and procedures are named-network slots ([Layered Procedure Network](layered-procedure-network.md)). **Compound** collection cells hold durable subnet state ([Compound Object Slot Sync](compound-object-slot-sync.md)). Contradiction handling is still largely a **stub** ([Core Runtime Model](core-runtime.md)).

---

### 4. Dependence tracking

**Meaning:** The system can record **why** a cell’s merged content is justified — supports, retractions, and (in the full MIT stack) coordination with amb / failure — without changing how propagators are scheduled or activated.

**MIT reference:** Present in the complete propagator story, but **not inside** the propagator scheduler loop. In MIT as here, dependence is a **separate subsystem** wired at **merge** time (generic merge procedures / `cell-merge`), not in `eval-propagator` or `eval-cell`.

**Integration point in this repo:** the hook is **`propagators.cells.merge/cell-merge`** (and related `merge-cell-entry` / `strongest-value` policy), called from `eval-cell` when a message is absorbed:

```text
eval-propagator  →  messages  →  eval-cell  →  cell-merge  →  [dependence subsystem]
                     (no TMS)                    (only here)
```

`eval-propagator` and `eval-cell` stay dumb: enqueue topology, merge payload, compare strongest, wake neighbors. Any future TMS records **what merge accepted** and **from which contribution**, not “which step ran” in the evaluator.

**This repo:** **Not implemented** as a generic subsystem. `cell-merge` today
only combines partial **values** (default, named-network evidence, compound
updates, reducer-cell slot evidence). Layered **`:provenance`** is still
separate **domain data** merged like any other slot — not generic dependence.
`propagators.datastructures.tms` is a reducer-cell experiment that projects TMS
views from monotone claim/premise slots; it is not yet a repo-wide merge-time TMS.

See [Provenance vs dependence](#provenance-vs-dependence-layered) below and [Compound Object Slot Sync](compound-object-slot-sync.md) § future dependence tracking.

---

## Provenance vs dependence (layered)

| | Layered `:provenance` | Dependence tracking (feature 4) |
|--|------------------------|----------------------------------|
| **What** | Application layer on a compound object (e.g. `#{:a :b}`) | Generic justification for **cell content** at merge |
| **Where wired** | Layered procedure closures + slot propagators | **`cell-merge` only** (MIT-shaped); not `eval-cell` / `eval-propagator` |
| **Who builds it** | You install `provenance/+` (etc.) on `proc` | Merge/TMS when absorbing messages |
| **Retraction** | No TMS; change inputs/proc and re-run | Remove support → content/strongest recomputed from remaining justifications |
| **Still missing** | N/A (works as domain layer) | Entire subsystem: support sets, retract, merge-time hooks, bi-sync that preserves justifications |

---

## Progress matrix (historical → now)

| # | Feature | Historical (`NOTES.md`) | Current (`main`, Jun 2026) |
|---|---------|-------------------------|----------------------------|
| 1 | **Networked semantics** | **Done** — `graph`, installers | **Done** — immutable `Net` (`graph` + `env` + `dict` + parent/child `relationship`), variadic installers, `compile` DSL, runtime compounds, layered/compound-object subnets as network-shaped values |
| 2 | **Fixpoint evaluation** | **Done** — `run-tasks` | **Done** — continuation-constructed trampoline drains the task queue to quiescence; inner fixpoints in compounds and `p:apply-layered`; explicit `nb/run-propagators` / `install-propagator!` for caller-controlled drains |
| 3 | **Partial information** | **Partial** — `CellValue` kinds; strongest often identity | **Stronger partial** — named-network evidence merge, layered slots, compound subnet state, arithmetic **provenance layer** on outputs; still not full generic merge lattice; contradiction stub |
| 4 | **Dependence tracking** | **No** — merge-time subsystem | **Still no generic subsystem** — reducer-cell TMS can project claim/premise slots, but `cell-merge` has no repo-wide TMS; layered `:provenance` is domain data, not merge justifications |

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
| **Builder policy** | Stage-1 `:queue` + flush experiment on `experiment/eager-install-stage-1`; results on `main` in [Builder Policy](builder-policy-run-order-and-correctness.md) and [Eager Install](eager-install-and-arithmetic-procedure.md) |
| **Tests** | Default layered tests use full bootstrap; reactive extension is explicit special-case tests only |

---

## MIT alignment (short)

| Topic | MIT-style | This repo today |
|-------|-----------|-----------------|
| Cell merge | Generic merge procedures | Domain-specific `cell-merge` + sentinels (`nothing`, `contradiction`) |
| Scheduling | Alert on change | Explicit propagator task queue |
| Propagator body | Often mutates neighbors | Pure `f` → messages → `eval-cells` |
| Network | Monolithic object | Immutable `graph` + `env` + `dict` |
| Compound | Install-time expansion | Runtime compound + avatars; compile flat install for primitives; compiled compound **planned** |
| Dependence / backtrack | Merge-time subsystem (not in scheduler) | **Not yet** — hook = `cell-merge` |
| Contradiction | `contradict` + handlers | Stub |

---

## Open work (tied to the four features)

1. **Partial information** — richer contradiction policy; less ad hoc strongest; unify compound-data dispatch away from `c:linked-list` only.
2. **Fixpoint / construction** — stage-2 `install-arithmetic-procedure`; optional default `:queue` only with flush-aware helpers.
3. **Networked semantics** — compiled compound lowering; port-order discipline for set-backed graph ports.
4. **Dependence tracking** — merge-time subsystem on `cell-merge` (MIT-shaped); do not wire into `eval-cell` / `eval-propagator`; not the same as layered **:provenance**.

Commands and file map: [Experiments And Commands](experiments-and-commands.md).

---

## Related documents

- [Core Runtime Model](core-runtime.md) — graph/env, scheduler, limits
- [Named Network Evidence](named-network-evidence.md) — partial information discipline
- [Compound Runtime](compound-runtime.md) — inner fixpoints, avatars
- [Layered Procedure Network](layered-procedure-network.md) — procedures as partial network values
- [README](README.md) — documentation map
