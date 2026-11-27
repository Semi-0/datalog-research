# Prototype-2: reactive cells, TMS project, and DDB nogoods

Two jobs share TTMS *supports* but must not be conflated.

## 1. Cell content = reactive spine + lattice frontier

A cell (see `rx-cell`, L-2 observation lists) does **not** overwrite content.
Each `add-content` / `extend` conses a newer observation tip onto a spine.

Aggregate state is an **undominated frontier** (set of observations). Merge is a
semilattice join — suitable as an incremental dataflow reducer and for async
partial aggregates:

| Operation | Role |
|---|---|
| `front-add` | Assimilate one observation into a frontier (drop dominated / dups). |
| `tms-join` / `frontier-join` | Join two frontiers (C / A / I). |
| `tms-step` | Incremental step: `front-add` frontier ← tip. |
| `tms-front-reduce` | Fold a stream → frontier (full pull). |
| `tms-project` | Tip *view*: `car` of the frontier (fine when supports totally order). |

Incomparable observations are **both kept** (no order-biased “left wins”).
That is what makes join commutative under async / out-of-order arrival.
Full `reproject` rebuilds the frontier from the spine (worldview-style refresh).

Alerting neighbors uses `Network.listeners` (`cell-id → #{prop-id}`), so
`write-cell` is O(#listeners), not a scan of every propagator.

## 2. DDB nogoods ≠ TMS project

Dependence-directed amb (`amb`, L-2 `ddb-pyth`) builds the nogood set from
**failure certificates**, not from projecting the attempts spine:

```
probe fails
  → require-bool false support3     ; support3 = combine of choice stamps
  → learn → certificate->nogood     ; literals [source,index] + context
  → conj onto knowledge
  → backtrack / decide → repair-choices
```

The `attempts` observation list is a reactive **log** of tried assignments.
Search control is `knowledge` + repair. `tms-project` on that log is optional
instrumentation; it is not how nogoods are learned.

## 3. Why this is fast enough for near-DDB

- Incremental lattice `tms-step` / `front-add` (no per-write full reduce).
- Nogoods from certificate supports (no TMS assimilate / kick-out loop).
- Listeners index for write alerts (dominant network cost before the index).

See also: [ttms.md](ttms.md) for the shared support/dominance vocabulary;
`prototype_2/amb.clj`, `rx_cell.clj`, `support_tag.clj`, and
`compiler/l2-reactive-prelude`.
