# Propagator experiment — design notes

Experimental architecture: **graph** = wiring only; **env** = runtime state and behavior per node id.

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

`primitive-propagator` is the compile-time wrapper: `(fn [cell-ids…] (construct-propagator wrapped-f inputs [out]))`.

### Primitive propagator (`p:id`, etc.)

- MIT: propagator reads neighbors, **adds** to output cells.
- Here: `f` is **pure** on snapshots; returns **diffs** `[[node CellValue] …]`; `eval-cells` merges into `env`.
- `primitive-propagator` applies `f` to **strongest** input values; skips activation if any input is `nothing` / `contradiction` (`any-unusable-values?`).
- `p:id` — identity on one input → one output (wired sync / bi-sync tests).

### Compound propagator — MIT vs ours

**MIT (correct model):** a compound propagator does **not** run a nested network at activation time. Its body **generates** cells and propagators **directly into the enclosing network** (macro / constructor expansion). Boundary arguments are the **same cell objects** as in the parent; the scheduler sees one flat graph. There is no separate “inner `env`” in the MIT story.

```
MIT compound (install time)          One global network
───────────────────────────          ──────────────────
(compound-propagator …)    →    cells + props wired in parent graph
boundary: parent cells c0, c1  =  those exact cells
```

**This repo (`compound-propagator`):** experimental **runtime enclosure**, not MIT expansion.

1. **`closure-cell`** — holds `[inner-f [inner-graph inner-env]]` in `:strongest`.
2. On activation (not at install):
   - `apply-network-closure` — call `inner-f` with boundary snapshots.
   - `run-tasks` — fixpoint on a **private** `[graph env]` (parent `env` untouched).
   - `diff-cells` — diff outer boundary snapshots before/after; emit `[[node-before msg] …]` only if `:strongest` changed.
3. Messages use **outer** boundary node ids (`node-before`) so inner topology does not leak.

```
Our compound (activation time)       Parent              Inner (simulated)
────────────────────────────         ──────              ─────────────────
closure-cell ──► run inner run-tasks  output ◄── diff    private graph/env
```

**Closer to MIT:** `compile-net` — quoted `(let … (do (p:id …)))` **installs** into one `{:graph :env}` (flat network growth). That matches “generate the network outside.”

**Deliberate divergence:** `compound-propagator` simulates lexical scope with a **stored closure + inner fixpoint + diff export**, because we split `graph`/`env` and use pure diffs instead of MIT-style in-place cell updates. Future work: a compile-time compound form that expands into the parent `graph`/`env` (MIT-aligned) rather than (or in addition to) the runtime enclosure.

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
| Compound | **Install-time expansion** into parent network; one scheduler | **Runtime:** `closure-cell` + private inner `run-tasks` + `diff-cells`. **Compile:** flat `compile-net` (MIT-like install) |
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
- `compile-net` **compound** form — MIT-style expansion into parent `graph`/`env` (not runtime inner `run-tasks`)
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

Prototype-scale timings (one JVM, median of several iters; propagation excludes build):

| Cells | Compounds | Build (approx) | Head: seed c0, run each compound | Middle: inject c⌊n/2⌋, one `run-prop` |
|------:|----------:|----------------:|-----------------------------------:|----------------------------------------:|
| 10 | 9 | ~3 ms | ~5 ms | ~2 ms |
| 100 | 99 | ~1 ms | ~52 ms | ~10 ms |
| 1000 | 999 | ~27 ms | ~84 ms | ~45 ms |
| 10000 | 9999 | ~137 ms | ~944 ms (1 iter) | ~338 ms |

Head-driven propagation runs `n-1` separate `run-prop` calls; middle inject drains the task queue once and is faster at scale. Bench auto-reduces iters for large `n` and skips head when `n > 5000` (override with `:skip-head? false`).

```bash
clj -M:propagators-bench           # default: 10, 100
clj -M:propagators-bench 1000 10000
```

**Verdict:** fine for **experiments and prototypes** — not tuned for production (variance, no dependence tracking, stub contradiction).

## Commands

```bash
clj -M:propagators-test    # network tests (sync chain, bi-sync, compound chains)
clj -M:propagators-bench  # chain propagation timings (optional lengths as args)
clj -M:test               # all suites including propagators-network-test
```

## File map (propagator stack)

```
propagators/compile.clj   — quoted DSL → {:graph :env :cells :props}
propagators/network.clj   — construct-cell, construct-propagator, primitive-propagator, compound-propagator
propagators/closure.clj   — apply-network-closure, closure-payload, compound-activate
propagators/stdlib.clj    — p:id, bi-sync, bi-sync-closure
propagators/cells/diff.clj — diff-cell, diff-cells
propagators_chain_bench.clj — chain-len propagation benchmark (-m propagators-chain-bench)
propagators/cells/snapshot.clj — pop-inputs, take-cells, snapshot-for-id
propagators/core.clj      — run-tasks, eval-propagator, eval-cells, eval-cell
as_messages.clj           — make-message, as-messages, wire-propagator-edges, cell-slot
propagators/graph.clj     — Node, link-edge
propagators/cells/        — Cell, CellValue, merge
test/propagators_network_test.clj
```
