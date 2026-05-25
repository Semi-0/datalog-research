# Propagator experiment — design notes

Experimental architecture: **graph** = wiring only; **env** = runtime state and behavior per node id.

TODO: COMPOUND DATA FOR CELL MERGE
COMPILER CAN BE MUCH SIMPLER
SIMPLIFY COMPOUND PROPAGATOR API

;; experiments of propagator system which decouples network declaration from network evaluation

PROBLEM CRITICAL: UNIFT THE INTERFACE OF DATASTRUCTURE
now some using record some use vector

;; 4 core function of propagators
;; 1. networked semantics DONE
;; 2. fixpoint evaluation DONE
;; 3. partial information partialy
;; 4. dependence tracking nah

## MIT-style propagator model (reference)

The classic **MIT propagator network** (Sussman & Radul, *Art of the Propagator*) has:

| Concept | MIT idea |
|---------|----------|
| **Cell** | Shared constraint; many propagators write; **merge** picks the current content |
| **Propagator** | Procedure connected to input/output cells; runs when notified |
| **Network** | Cells + propagators + scheduler; runs to quiescence (fixpoint) |
| **Compound propagator** | **Expands at construction** into the **parent** network — inner cells and propagators are wired **outside**, in the same global network; boundary ports are ordinary parent cells |
| **Generic merge / contradiction** | Lattice of partial information; contradictions are first-class |

This repo is a **Clojure reimplementation experiment**, not a port of the MIT Scheme codebase. Same *semantics in intent*, different *representation and evaluation*.

## What we built: `propagators/network.clj`

### Split: wiring vs state (vs MIT)

MIT implementations usually attach propagator objects and cell state into one runtime “network” object. Here:

| Piece | Module | Role |
|-------|--------|------|
| Topology | `graph` (`id → Node`, `:inputs` / `:outputs`) | Who connects to whom |
| Runtime | `env` (`id → Cell` or `Propagator`) | Content + `:f` per node |
| Evaluation | `propagators.core` | Immutable `run-tasks` loop |

**Benefit:** declare or compile a network into `[graph env]`, then evaluate with an explicit task queue (testable, replayable).

### Unified installer interface

Both primitive and compound propagators share one install shape:

```
(activate inputs outputs)  →  installer
installer = (fn [[graph env]]  →  [prop-id [graph env]])
```

| Constructor | Arguments | `activate` |
|-------------|-----------|------------|
| `construct-propagator` | `f`, `inputs`, `outputs` | Plain fn: `(fn [in-snaps out-snaps] → [[node msg] …])` |
| `compound-propagator` | `closure-cell`, `inputs`, `outputs` | Built by `compound-activate` (see below) |

`primitive-propagator` is the compile-time wrapper: variadic node-id installer → `construct-propagator`.

### Primitive propagator (`p:id`, etc.)

- MIT: propagator reads neighbors, **adds** to output cells.
- Here: `f` is **pure** on snapshots; returns **diffs** `[[node CellValue] …]`; `eval-cells` merges into `env`.
- **Installer API (variadic):** `((p:id c-in c-out) net)` or `(net/install-net net (apply p:id [c-in c-out]))`. All but the last node-id are **inputs**; the last is the **output**. `f` receives input strongest values as `(apply f in-vals)`.
- `compile-net` / `run-net-let` spread id vectors with `(apply inst-fn ids)` (not `(inst-fn ids)` — a single vector arg would be treated as one port).
- `primitive-propagator` skips activation if any input is `nothing` / `contradiction` (`any-unusable-values?`).
- `p:id` — identity on one input → one output (wired sync / bi-sync tests).
- `p:nothing` — topology-only boundary link: `construct-propagator` with no-op activate (`[]`). Wires avatar ↔ real; values cross via `refresh-boundaries` + `diff-cells`, not via the link propagator body. Helpers: `nothing-in-link`, `nothing-out-link` in `stdlib.clj`.

### Compound propagator — MIT vs ours

**MIT (correct model):** a compound propagator does **not** run a nested network at activation time. Its body **generates** cells and propagators **directly into the enclosing network** (macro / constructor expansion). Boundary arguments are the **same cell objects** as in the parent; the scheduler sees one flat graph. There is no separate “inner `env`” in the MIT story.

```
MIT compound (install time)          One global network
───────────────────────────          ──────────────────
(compound-propagator …)    →    cells + props wired in parent graph
boundary: parent cells c0, c1  =  those exact cells
```

**This repo (`compound-propagator`):** experimental **runtime enclosure**, not MIT expansion.

1. **`closure-cell`** — holds `[:closure inner-f inner-net]` in strongest (e.g. `bi-sync-closure`).
2. **Install** — `compound-propagator` takes `closure-in`, `closure-out`, `inputs`, `outputs` (real parent cells + closure ports). Wired like any propagator; boundary cells should usually appear in **both** input and output lists for bi-sync constraints.
3. **On activation** (`compound-activate` in `closure.clj`):
   - Strip closure ports: `ins` / `outs` = `boundary-nodes` (remove `closure-in-id` / `closure-out-id` from port lists).
   - **`ensure-boundaries` (Strategy A)** — read optional boundary cache from `closure-out` (`[:closure f net boundary]`). On cache hit `(f, ins, outs)` match: reuse avatar ids, `refresh-boundaries` only; skip `apply-network-closure`. On miss: **`create-boundary-outputs`** / **`create-boundary-inputs`** (clone cells, link avatar ↔ real via `p:nothing`), then wire inner `f`.
   - **`apply-network-closure`** (cache miss only) — run inner `f` (e.g. `bi-sync`) on **avatar** id lists in the shared parent `graph`/`env`.
   - **`run-tasks (pop-inputs boundary-inputs …)`** — inner fixpoint seeded from **input avatars**, not real parent cells (see scheduling note below).
   - **`diff-cells`** — avatar strongest vs real `outs` → messages on real boundaries. Update `closure-out` with `[:closure f net'' boundary]` (persist frame for next activation).
4. Messages target **real** boundary ids so inner avatar/prop ids do not leak to the parent scheduler.

```
Parent graph                         On activation (copy of graph/env grows)
────────────                         ─────────────────────────────────────
real c0, c1 ◄──► compound k          avatars c0*, c1* + boundary link + inner bi-sync
closure-in/out on k                  pop-inputs [c0*, c1*] → inner props only
                                     diff avatars → messages on real c0, c1
```

### Inner scheduling: why avatars exist

Real boundary cells remain wired as **inputs** to the compound propagator in the parent `graph`. If inner `run-tasks` used `pop-inputs` on those real ids, the task queue would include the **compound itself** → `compound-activate` re-entered `run-tasks` without bound (hang / `StackOverflowError`). **Fix:** seed inner work from `boundary-inputs` (avatars only linked via `p:nothing` + inner closure). Proof tests: `test/propagators_compound_diagnosis_test.clj` (`clj -M:test propagators-compound-diagnosis-test`).

| `pop-inputs` on | Schedules compound? | Use for inner `run-tasks`? |
|-----------------|---------------------|----------------------------|
| `boundary-inputs` (avatars) | No | **Yes** |
| `ins` (real cells) | Yes (parent edge) | **No** — causes re-entry loop |

**Closer to MIT:** `compile-net` — quoted `(let … (do (p:id …)))` **installs** into one `{:graph :env}` (flat network growth). That matches “generate the network outside.”

**Deliberate divergence:** runtime `compound-propagator` uses **stored closure + avatar frame + inner fixpoint + diff export** on a shared `graph`/`env`, not MIT install-time expansion. See **Two-stage compound strategy** below for the intended split: runtime tier for hot reload / inspection, compile tier for fast steady state.

## Two-stage compound strategy (runtime inspect + MIT compile)

Long-running **self-reflective** systems need both: **mutable closure-as-data** (`closure-in` hot reload) and **fast steady propagation** without paying avatar + inner `run-tasks` on every hop. Pure MIT expansion alone is a poor fit for hot reload (expanded inner props are baked into the parent graph; changing `closure-in` requires patch/uninstall). Pure runtime avatars alone are correct but ~2×+ slower than snapshot-era inner-net on long chains.

**Plan: keep both tiers**, same propagator language, two lowerings.

### Stage 1 — Runtime compound (avatar frame) — *current default*

| Piece | Location | Role |
|-------|----------|------|
| Install | `network.clj` / `compound-propagator` | Wired like any propagator; `closure-in` / `closure-out` ports |
| Activate | `closure.clj` / `compound-activate` | Avatars, Strategy A cache on `closure-out`, inner `run-tasks`, `diff-cells` |
| Closure payload | `closure-in` strongest | `[:closure f net]` or `[:closure f net boundary]` — **source of truth** for hot reload |

**Use when:**

- REPL, experiments, meta-level reflection (walk `graph`/`env`, swap closure body).
- Closure body or inner `net` changes often.
- Inspecting explicit boundaries (`boundary` map: `:ins`, `:outs`, `:in-avatars`, `:out-avatars`).

**Costs:** extra cells/links per boundary; `refresh-boundaries` on cache hit; inner fixpoint on parent graph (isolated only by avatar `pop-inputs` seed).

**Not lexical scope:** parent `c0` ≠ avatar `c0*`; frame + indirection, not “same binding as caller.”

### Stage 2 — MIT expansion (compile) — *planned*

| Piece | Location | Role |
|-------|----------|------|
| Source | Same quoted body as stage 1 (e.g. `bi-sync` via `net-let`) | One IR, two lowerings |
| Lowering | `compile-net` **`compound` form** (not implemented yet) | Expand into parent `graph`/`env` at **commit** time |
| Boundaries | Parent cell ids from install site | **Same cells** as MIT — lexical wiring |

**Use when:**

- Reflective layer has **committed** a stable compound (promoted).
- Long chains / hot paths where avatar overhead dominates.
- Steady-state production after equivalence is tested.

**Hot reload:** do **not** silently re-expand on every activation. On `closure-in` change: **bump generation**, run stage 1 (or demote), then **re-promote** when stable. Expanded subgraph must be removed or marked stale so the scheduler does not run dead propagators.

### Promotion workflow (intended)

```
edit closure-in → runtime activate (stage 1) → inspect / test
       ↓
  stable? ─no→ keep stage 1
       ↓ yes
  expand into parent graph (stage 2) + tag :gen N
       ↓
closure-in changes → invalidate gen / demote → stage 1 until re-promoted
```

**Requirements before promotion is safe:**

1. **Semantic equivalence tests** — same boundary cells, same inputs → same strongest values (runtime vs expanded).
2. **Generation / version** on `closure-in` and expanded nodes (`:gen N`).
3. **Demotion / GC** — retire inner prop ids when spec changes; avoid unbounded accretion in `graph`/`env`.
4. **Hybrid chains** — some compounds compiled, some runtime; no duplicate wiring on the same real boundaries.

### Historical snapshot era (reference only)

Commit `37d3a6f`: `compound-activate` used **snapshots** and inner fixpoint on **`inner-net`** from `[:closure f inner-net]` — not the parent graph. That gave cheap isolation (compound not in inner `pop-inputs` graph) but not hot-reload-friendly unified inspection on one net. Moving `bi-sync` to mutate the **parent** `network` forced avatars for scheduling correctness.

### Lexical / scope comparison (summary)

| Model | Lexical identity | Hot `closure-in` | Long-run perf |
|-------|------------------|------------------|---------------|
| MIT expansion | **Best** (same cells) | Needs patch layer | **Best** when stable |
| Runtime + avatars (stage 1) | Frame indirection | **Best** (data on cell) | Moderate; Strategy A helps |
| Snapshot `inner-net` | Split graphs, same ids | Good | **Fastest** runtime compound in bench; different API |

**Verdict for this repo:** implement stage 2 as an explicit **promote** from stage 1; keep stage 1 as the reflective default. See `test/propagators_compound_diagnosis_test.clj` for scheduling invariants stage 1 must preserve.

### Helpers in `network.clj`

| Fn | Purpose |
|----|---------|
| `construct-cell` | Install empty cell (`nothing`) in `env`, node in `graph` |
| `propagators.cells.diff` | `diff-cell` / `diff-cells` — emit `CellValue` only if strongest changed |

Comments in source note open questions (diff policy, bi-directional binding) — still experimental.

## What we built: `propagators/compile.clj`

MIT code is usually written with macros (`compound`, `p:…`) in Scheme. We use a small **quoted DSL** + `compile-net` (r2-style `compile*` + context).

### Context

```clojure
{:cells {sym → node-id}
 :installers {sym → installer}   ;; e.g. 'p:id
 :graph {}
 :env {}
 :props []}                      ;; propagator ids in install order
```

### Supported forms

| Form | Behavior |
|------|----------|
| `(let [x (cell) …] body)` | Bind each `x` to new cell id (`construct-cell`) |
| `(do e1 e2 …)` | Sequential compile |
| `(p:id c0 c1)` | Lookup installer, resolve cell syms → ids, install propagator |

Output: same `{:graph :env :cells :props}` you would build by hand — **evaluation unchanged** (`run-tasks` in `core`).

### Installers table

`default-installers` maps `'p:id` → `(primitive-propagator fn)`. Extend by passing a custom map to `(compile-net expr installers)`.

### Refs after compile

- `cell-ref` — symbol → cell id  
- `prop-ref` — index in `:props` (for tests: run propagator `n` manually)

**MIT alignment:** declarative network description; each form **adds** to one `graph`/`env` (like MIT install-time expansion). **Gap:** no quoted `compound` macro yet — only `let` / `do` / propagator apply. Runtime `compound-propagator` in `network.clj` is a separate experiment, not the MIT compound model.

## MIT vs this repo (summary)

| Topic | MIT-style | This repo |
|-------|-----------|-----------|
| Cell merge | Generic merge procedures | `cell-merge` on plain payloads + `[:nothing]` / `[:contradiction]` sentinels |
| Scheduling | Alert propagators when cells change | FIFO task queue of **propagator** `Node`s (`run-tasks`) |
| Propagator body | Often mutates neighbor cells | Pure `f` → diffs → `eval-cells` |
| Network storage | Monolithic network object | `graph` + `env` |
| Compound | **Install-time expansion** into parent network; one scheduler | **Two-stage (planned):** runtime `compound-propagator` + avatars (inspect / hot reload); MIT `compile-net` expansion (fast commit). Today: stage 1 only + flat `compile-net` for primitives |
| Syntax | Scheme macros | `compile-net` on quoted s-exprs |
| IDs | Symbols / objects | UUID v7 (`propagators.ids`) |
| Dependence / backtrack | Supported in full MIT stack | **Not yet** (see below) |
| Contradiction | `contradict` + handlers | Stub `handle-contradiction` |

## Four core functions (status)

| # | Function | Status | Notes |
|---|----------|--------|-------|
| 1 | Networked semantics | **Done** | `graph`, `link-edge`, `wire-propagator-edges`, installers |
| 2 | Fixpoint evaluation | **Done** | `run-tasks` / `eval-propagator` / `eval-cells` |
| 3 | Partial information | **Partial** | `CellValue` kinds + merge; strongest selection still identity |
| 4 | Dependence tracking | **No** | Planned via eval-based core / backtrack later |

## Roles

| Store | Holds |
|-------|--------|
| `graph` | `id → Node` (`:inputs` / `:outputs` as id sets) |
| `env` | `id → Cell` or `id → Propagator` (`:f`) |

**Ids:** `java.util.UUID` v7 via `propagators.ids` (`new-node-id` / `new-node-id-secure`). Sortable, unique without a coordinator. `construct-cell` / `construct-propagator` allocate ids when omitted.

Evaluator: `propagators.core` (`run-tasks` → `eval-propagator` → `eval-cells` → `eval-cell`).

## Assumptions

1. **Task queue items are propagator `Node`s** — `eval-propagator` reads topology from `graph` and `f` from `env[(:id current)]`.
2. **Wake enqueue uses cell `Node`s** — `eval-cell` schedules `(node-outputs graph node)` where `node` comes from an `f` diff; successors must be propagator nodes.
3. **Propagator ports are cell ids** — `cell-snapshot` does `(get env (:id node))`; ports must resolve to `Cell` entries, not `Propagator`.
4. **`f` diffs target cell nodes only** — `eval-cell` destructures `{:keys [content strongest]}`; diffs at propagator ids fail (intentional if `f` only writes cells).
5. **`f` returns `[[node message] …]`** — each `message` is a `CellValue` to merge.
6. **`env` is fully pre-seeded** — missing keys crash on read/write (intentional).

## Intentional (for now)

- **`cell-snapshot`** — `[node full-cell]` (not `:content` alone).
- **`cell-strongest`** — identity; real strongest selection later.
- **`cell-updated?`** — compares new strongest to old `:strongest`; content can change without waking downstream.
- **Contradiction** — still enqueues outputs and runs `handle-contradiction` (stub dispatches on `env`, always `:default`).

## Minor implementation notes

- **Port order** — `node-inputs` / `node-outputs` return sets; snapshot order is undefined. Use commutative `f` or fix port ordering later.
- **Task queue** — immutable FIFO via `propagators.helpers.task-queue` (dedupe by node `:id`, deterministic order). `run-tasks` accepts a queue, set, or seq.
- **Bootstrap** — no built-in initial task queue or default cells; caller supplies `env`, seeds tasks, injects first cell messages.
- **Layout** — top: `compile`, `core`, `network`, `propagator`, `graph`, `ids`; `cells/` (cell, value, merge); `helpers/` (task-queue, network wiring).
- **Network compile** — `propagators.compile/compile-net` lowers quoted `let` / `do` / `(p:id in out)` into `{:graph :env :cells :props}`; evaluation unchanged (`run-tasks`).
- **Tests** — flat under `test/`; all suites via `clj -M:test`, or `clj -M:propagators-test` for one suite.

## Not in scope (yet)

- Eval based core allow us to backtrack with the evaluator and also contradiction handling, but we will expand on that later
- Dependence tracking
- Real `cell-strongest` / contradiction policies beyond stub
- `compile-net` **compound** form + promote/demote/generation (stage 2 of two-stage strategy; see above)
- Port of full MIT primitive library (`c:add`, `switch`, etc.)

## Bi-sync compounds: symmetric boundaries (constraint wiring)

`stdlib/bi-sync-closure` runs inner `bi-sync` (`p:id` both ways). The closure comment says **input and output should be the same** — treat the compound as a **constraint** on a set of cells, not a directed pipe.

### Correct install shape

```clojure
;; constraint: a and b stay in sync
(install-compound n k [a b] [a b])

;; wrong for bi-sync: b is only an output of k — k is NOT scheduled when b updates
(install-compound n k [a] [b])
```

`wire-propagator-edges` links each input cell → propagator and propagator → each output cell. When a cell is in **both** sets, an update to that cell enqueues the propagator via `node-outputs` (`eval-cell` in `core.clj`).

### Scheduling asymmetry (why inject-at-middle failed with directional compounds)

Chain `a <-> b <-> c` with compounds `k0` on `[a,b]` and `k1` on `[b,c]`:

| Event | What runs |
|-------|-----------|
| Seed / inject updates `b` | `k1` (b is input **and** output of k1) |
| Same event, directional `k0: [a]→[b]` | **k0 does not run** — b is only an output of k0 |

So `e -p:id-> b` updated `c` downstream but left `a` at `nothing` until we switched to `[a b] / [a b]`. Test `compound-bi-sync-chain-inject-e-to-b` encodes the expectation that **all** chain cells update after one inject + `run-prop`.

### Chain tests (`test/propagators_network_test.clj`)

| Test | What it checks |
|------|----------------|
| `stdlib-bi-sync-closure-compound-single` | One compound, seed head |
| `stdlib-bi-sync-closure-compound-chain` | 3 cells, run compounds in order from head |
| `stdlib-bi-sync-closure-compound-chain-4` / `-10` | Parameterized head-driven chain |
| `compound-bi-sync-chain-inject-e-to-b` | 3-cell chain, inject middle, one `run-prop` |
| `compound-bi-sync-chain-10-inject-middle` | 10 cells, inject `c5`, all cells get value |

Builders: `build-stdlib-compound-chain-n`, `build-stdlib-compound-chain-n-with-inject`. No `boundary-inject` test fake — failures expose real scheduling / closure behavior.

### Open issue: snapshot order in `bi-sync`

`stdlib/bi-sync` uses `(first input-snapshots)` and `(second output-snapshots)`. Port ids on `graph` nodes are **sets** — order is undefined. Symmetric wiring fixed scheduling; pairing by position may still bite on larger nets. Prefer matching snapshots **by cell id** later.

## Benchmarks (`propagators_chain_bench.clj`)

Prototype-scale timings (one JVM; **propagation excludes network build**; median of several iters after warmup). Each `chain-len` runs **two** boundary modes (see below). Scenarios: **head** = seed `c0`, run each compound propagator once; **middle** = inject `e -p:id-> c⌊n/2⌋`, single `run-prop` on `e→mid`.

### Historical baselines (different `compound-activate` — not apples-to-apples)

| Era | Git (approx) | Head @ 1000 (median) | Notes |
|-----|----------------|----------------------|-------|
| Snapshot compound | `37d3a6f` | ~43 ms | `input-snapshots` / `output-snapshots`; no avatars |
| Network, no avatars | `9e9bdc7^` | ~421 ms | `pop-inputs` on real boundary cells; can re-enter compound |
| Old NOTES table (~84 ms) | `37d3a6f` | ~84 ms (recorded) | Same snapshot era as first row — **not** “pre-avatar network” |

Avatar fix adds ~2× over network-no-avatar on the same bench harness (~421 ms → ~850 ms), not exponential. The ~10× gap vs the old NOTES row is mostly **snapshot → network**, not avatars alone.

### Strategy A boundary cache + topology-only `p:nothing` (current bench)

`clj -M:propagators-bench` reports **head** cold vs warm (first vs last iter on same chain): cold = create avatars + `bi-sync` wire; warm = Strategy A cache hit on `closure-out` (refresh + skip re-wire). **Middle** = inject at `c⌊n/2⌋`, one `run-prop`, full iters median.

Example head cold → warm (2026-05, one JVM):

| chain-len | cold (first iter) | warm (last iter) |
|----------:|------------------:|-----------------:|
| 10 | ~2.3 ms | ~1.2 ms |
| 100 | ~16 ms | ~12 ms |
| 1000 | ~791 ms | ~741–796 ms (noise) |

Strategy A helps most on short chains; at 1000 cells inner `run-tasks` + `bi-sync` dominate. `p:nothing` is the noop `construct-propagator` link (formerly documented as `p:nothing-b`).

Head-driven propagation runs `n-1` separate `run-prop` calls; middle inject drains the outer task queue once. Bench auto-reduces iters for large `n`.

```bash
clj -M:propagators-bench           # default: 10, 100
clj -M:propagators-bench 1000 10000
```

**Verdict:** fine for **experiments and prototypes** — not tuned for production (variance, no dependence tracking, stub contradiction).

## Commands

```bash
clj -M:propagators-test                      # network tests (sync, bi-sync, compound chains)
clj -M:test propagators-compound-diagnosis-test  # compound inner-scheduling proof tests only
clj -M:propagators-bench [10 100 1000 ...]    # chain propagation timings
clj -M:test                                   # all suites
```

## File map (propagator stack)

```
propagators/compile.clj   — quoted DSL → {:graph :env :cells :props}
propagators/network.clj   — construct-cell, construct-propagator, primitive-propagator, compound-propagator
propagators/closure.clj   — compound-activate, create-boundary-inputs/outputs, apply-network-closure
propagators/stdlib.clj    — p:id, p:nothing, nothing-in/out-link, bi-sync, bi-sync-closure
propagators/datastructures/compound_data.clj — experimental p:cons / p:car / p:cdr (WIP)
propagators/cells/diff.clj — diff-cell, diff-cells
propagators_chain_bench.clj — chain-len propagation benchmark (-m propagators-chain-bench)
propagators/cells/snapshot.clj — pop-inputs, take-cells, snapshot-for-id
propagators/core.clj      — run-tasks, eval-propagator, eval-cells, eval-cell
as_messages.clj           — make-message, as-messages, wire-propagator-edges, cell-slot
propagators/graph.clj     — Node, link-edge
propagators/cells/        — **deprecated** barrel (`cells.clj`); use `cells/cell`, `cells/merge`, `cells/value`, …
test/propagators_network_test.clj — integration tests (compile + compound chains)
test/propagators_compound_diagnosis_test.clj — avatar vs real `pop-inputs` scheduling proofs
debug_compound_stuck.clj  — optional REPL script to trace inner `run-tasks` (not in CI)
```
