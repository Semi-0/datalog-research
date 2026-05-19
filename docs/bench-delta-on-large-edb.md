# Benchmark: one-edge delta on a large static EDB

Recorded **2026-05-19**. Code: `bench_compare.clj` (`one-edge-delta-*`, `print-delta-sweep!`).

## Scenario (the experiment you asked for)

1. **Pre-build** a directed chain: edges `1→2, 2→3, …, base-n→(base-n+1)` and run path closure once (`build-chain-facts`). **Not timed.**
2. **One transaction:** add a single edge `(base-n+1)→(base-n+2)`.
3. **Time only step 2:**
   - **full** — `semi-naive` on the whole EDB (all edges + paths recomputed from scratch)
   - **incr** — existing facts + `semi-naive-step` fixpoint from `{:edge #{new-edge}}`

This isolates **small delta on a large existing DB**, unlike the [growing-chain bench](bench-incremental-path-expansion.md) where the DB is rebuilt from scratch every txn.

## Fact counts

Base EDB after `base-n` edges (before the new edge):

```
path-tuples(base-n) = base-n * (base-n + 1) / 2
edges(base-n)       = base-n
```

After the txn, path tuples = `path-tuples(base-n + 1)`.

## Recorded results (iters = 3)

| base-n | path tuples (base) | full ms | incr ms | winner | full/incr |
|--------|-------------------|---------|---------|--------|-----------|
| 5 | 15 | 7.25 | 4.73 | **incr** | 1.5× |
| 10 | 55 | 27.11 | 18.14 | **incr** | 1.5× |
| 20 | 210 | 33.36 | 3.90 | **incr** | 8.6× |
| 100 | 5,050 | 605.31 | 89.16 | **incr** | 6.8× |
| 300 | 45,150 | 15,879 | 1,809 | **incr** | 8.8× |
| 500 | 125,250 | 62,640 | 6,591 | **incr** | 9.5× |

Full fine sweep (same day): **incr wins for every base-n tested from 5 through 500** — no upper crossover in that range.

## Crossover answer

| Question | Result |
|----------|--------|
| **At what EDB size does incr beat full?** | From the **smallest tested base-n = 5** (**15** path tuples, **5** edges). We did **not** find a lower bound below 5. |
| **Does incr eventually lose as EDB grows?** | **Not through 500 edges / 125k path tuples** — incr lead **widens** (≈6–10× full/incr). |
| **Contrast with growing-chain bench** | There, **full wins from ~n ≥ 20** because each txn **re-runs full semi-naive without reusing** the previous closure as the starting fact base. |

So the crossover is not “how big must the DB be?” but **which workload**:

- **Reuse accumulated facts + delta** → incremental wins (even small DB).
- **Recompute from scratch every txn** → full wins (chain growth bench).

## Why chain extension favors incremental

New edge at the end of a chain: only paths that **end at the new tip** are derived; work is **O(base-n)** new tuples, while full `semi-naive` revisits the entire edge set and path closure.

## Commands

```bash
clj -M:bench delta-sweep          # default sweep 10..500
clj -M:bench verify               # includes recorded-delta-sweep checks
```

## dleap

Not included in the recorded sweep (per-txn `transact` still has ~200 ms async drain overhead; correctness on large graphs requires full drain). Compare leapfrog strategies first; fix `drain-versioned-output` before fair dleap numbers.
