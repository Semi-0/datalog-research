# Propagator experiment — design notes

Experimental architecture: **graph** = wiring only; **env** = runtime state and behavior per node id.

;; experiments of propagator system which decouples network declaration from network evaluation

;; 4 core function of propagators
;; 1. networked semantics DONE
;; 2. fixpoint evaluation DONE
;; 3. partial information partialy
;; 4. dependence tracking nah



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
- **Task queue** — immutable FIFO via `propagators.task-queue` (dedupe by node `:id`, deterministic order). `run-tasks` accepts a queue, set, or seq.
- **Bootstrap** — no built-in initial task queue or default cells; caller supplies `env`, seeds tasks, injects first cell messages.
- **Tests** — none yet under `propagators/`.

## Not in scope (yet)

- Dependence tracking
- Real `cell-strongest` / contradiction policies beyond stub
