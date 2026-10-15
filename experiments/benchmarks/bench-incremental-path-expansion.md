# Incremental path expansion benchmark

Recorded **2026-05-19** on this repo (`bench_compare.clj`). Numbers below are **frozen** in `bench-compare/recorded-growth-sweep`; re-run with `clj -M:bench sweep` to refresh.

## Scenario

Rules (same in leapfrog and differential-leapfrog):

- `path(x,y) :- edge(x,y)`
- `path(x,z) :- path(x,y), edge(y,z)`

Workload: insert chain edges `1→2, 2→3, …, n→(n+1)` **one at a time** (n transactions).

Three implementations:

| Key | Function | Meaning |
|-----|----------|---------|
| `full` | `expand-path-leapfrog-full` | After each edge, full `semi-naive` on entire EDB |
| `incr-step` | `expand-path-leapfrog-incremental` | After each edge, `semi-naive-step` fixpoint |
| `dleap` | `expand-path-dleap` | After each edge, `differential-leapfrog.core/transact` |

Correctness: all three agree on `:path` support for checked n (see `verify-incremental-paths-agree`).

## Fact counts (chain of n edges)

Nodes: `n + 1`. Directed forward `:path` closure (pairs `i < j` reachable on the chain; no reflexive `[i i]`):

```
path-tuples(n) = n * (n + 1) / 2    ;; = C(n+1, 2)
edges(n)       = n
```

| n (edges) | nodes | path tuples | notes |
|-----------|-------|-------------|--------|
| 10 | 11 | 55 | noisy tie zone (incr vs full) |
| 20 | 21 | 210 | full clearly faster than incr-step |
| 100 | 101 | 5,050 | |
| 300 | 301 | 45,150 | |
| 400 | 401 | 80,200 | largest timed run |

## Recorded timings (ms, single JVM run)

| n | path tuples | full | incr-step | dleap (n txns) | ~205×n |
|---|-------------|------|-----------|----------------|--------|
| 20 | 231 | 14.0 | 17.3 | 4,112.6 | 4,100 |
| 50 | 1,326 | 18.7 | 41.3 | 11,047.7 | 10,250 |
| 100 | 5,151 | 126.8 | 826.8 | 24,142.2 | 20,500 |
| 200 | 20,301 | 305.5 | 2,877.0 | 54,917.2 | 41,000 |
| 300 | 45,451 | 1,364.8 | 12,772.4 | 91,077.6 | 61,500 |
| 400 | 80,601 | 2,497.5 | 23,074.6 | 113,325.3 | 82,000 |

Single-edge `transact` into an empty system (`dleap-1`): **~204–266 ms** at every n — dominated by a **~200 ms** wait in `drain-versioned-output` (`core.async` timeout), not by derivation size.

One batch `transact` with 30 edges (separate spot run): **~286 ms** total (~9.5 ms/edge amortized) vs **~4.5 ms** for one-shot leapfrog `semi-naive`.

## Crossover: when does incremental win?

### 1. `incr-step` vs `full` (leapfrog, per-edge txn)

- **No sustained crossover** through **n = 400** (**80,200** path tuples).
- Earlier spot checks showed **unstable ties around n ≈ 6–10** (≤ **55** path tuples at n=10); from **n ≥ 20** (**≥ 210** path tuples), **full recompute wins** and the gap **widens** (e.g. ~9× at n = 400).
- **Conclusion:** On this chain + path rules, keeping facts and running `semi-naive-step` per edge is **slower** than restarting `semi-naive` each time.

### 2. `dleap` vs `full` (per-edge `transact`)

- **No crossover observed** through **n = 400**.
- Cost is **`≈ 205 ms × n`** (matches `dleap` column vs `~205×n`), while `full` stays orders of magnitude below that line (2.5 s vs 113 s at n = 400).
- Fixing the drain timeout would remove the artificial **`F ≈ 200 ms`** per txn, but this benchmark still favors `full` until **DB + rule work per txn** is large compared with engine overhead.
- **Rough crossover inequality** (if per-txn cost were truly `F + w(n)` vs `Σ g(i)`): would need `Σ g(i) > nF`. With **F = 205 ms**, `full` at n = 400 is **2.5 s** vs **82 s** for `nF` — not close. Extrapolating `full` as superlinear still does not reach **`n × 200 ms`** within practical n on this workload.

### 3. `dleap` vs `incr-step`

- `dleap` is slower by the **~200 ms/transact** tax through at least n = 400.

## When incremental *would* be expected to win (not shown here)

- Small **delta** vs large **static** EDB (selective joins, good indexes/tries).
- **Retractions**, weighted multiplicities, **`:history`** deltas — features `transact` provides and bare `semi-naive` does not.
- **Low per-txn fixed cost** (no async spin-up / drain timeout per call).

## Commands

```bash
# Timed run (default n=50)
clj -M:bench
clj -M:bench 100 3

# Correctness + checks against recorded table (no timing bench)
clj -M:bench verify

# Re-print / re-time growth sweep (optional refresh)
clj -M:bench sweep
```

## Related: small delta on large EDB

The opposite result holds when the EDB is **pre-built** and only **one edge** is added — incremental wins from **base-n ≥ 5**. See **[bench-delta-on-large-edb.md](bench-delta-on-large-edb.md)**.

## Related code

- `bench_compare.clj` — scenarios, `recorded-growth-sweep`, `recorded-delta-sweep`, `run-verification!`
- `test/bench_compare_test.clj` — CI tests (correctness + recorded conclusions)
- `differential_leapfrog/core.clj` — `transact`, `drain-versioned-output`
