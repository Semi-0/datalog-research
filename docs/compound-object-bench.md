# Compound Object Slot Benchmark

Recorded on this repo with:

```bash
clojure -M:compound-object-bench
```

The benchmark compares:

- `baseline`: the previous slot behavior copied into the benchmark namespace.
- `optimized`: `propagators.datastructures.compound-object`.

The benchmark keeps instrumentation out of production code. It counts optimized
subnet executions with `with-redefs-fn` around the private execution helper and
counts slot messages by wrapping `core/eval-propagator` inside the benchmark run.

The important counters are:

- `subnets`: slot subnet executions.
- `skips`: optimized guard skips.
- `coll-msgs`: collection-cell messages emitted by slot propagators.
- `coll-noops`: collection messages that did not update strongest value.
- `parent-msgs`: direct accessor/parent messages emitted from slot execution.

Representative run:

| scenario | variant | median ms | subnets | skips | coll-msgs | coll-noops | parent-msgs | ok |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| redundant-100 | baseline | 20.218 | 3000 | 0 | 3000 | 3000 | 3000 | true |
| redundant-100 | optimized | 4.411 | 0 | 3000 | 0 | 0 | 0 | true |
| deep-3 | baseline | 9.553 | 192 | 0 | 192 | 200 | 216 | true |
| deep-3 | optimized | 2.831 | 48 | 160 | 96 | 40 | 56 | true |
| deep-10 | baseline | 1819.187 | 1064 | 0 | 1064 | 1280 | 1088 | true |
| deep-10 | optimized | 55.262 | 368 | 760 | 528 | 360 | 376 | true |
| wide-1 | baseline | 0.098 | 16 | 0 | 16 | 8 | 16 | true |
| wide-1 | optimized | 0.046 | 8 | 8 | 0 | 0 | 8 | true |
| wide-10 | baseline | 10.255 | 88 | 0 | 88 | 80 | 880 | true |
| wide-10 | optimized | 0.632 | 8 | 80 | 0 | 0 | 80 | true |
| wide-100 | baseline | 2225.004 | 808 | 0 | 808 | 800 | 80800 | true |
| wide-100 | optimized | 35.801 | 8 | 800 | 0 | 0 | 800 | true |
| mixed-1 | baseline | 0.303 | 90 | 0 | 90 | 90 | 90 | true |
| mixed-1 | optimized | 0.085 | 0 | 90 | 0 | 0 | 0 | true |

Larger explicit runs are available, for example:

```bash
clojure -M:compound-object-bench deep 50
clojure -M:compound-object-bench wide 1000
```

The default suite intentionally stops at `deep 10` because larger baseline deep
runs are slow enough to be inconvenient during normal development.
