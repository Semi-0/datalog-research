# Compound Object Slot Benchmark

Recorded on this repo with:

```bash
clojure -M:compound-object-bench
```

The benchmark compares:

- `baseline`: the previous slot behavior copied into the benchmark namespace.
- `optimized`: the current `propagators.datastructures.compound-object` facade,
  which defaults public slots to demand-driven network-slot topology.

The benchmark keeps instrumentation out of production code. It counts optimized
subnet executions with `with-redefs-fn` around the private network-slot execution
helper and counts slot messages by wrapping `core/eval-propagator` inside the
benchmark run.

The important counters are:

- `subnets`: slot subnet executions.
- `skips`: optimized guard skips.
- `coll-msgs`: collection-cell messages emitted by slot propagators.
- `coll-noops`: collection messages that did not update strongest value.
- `parent-msgs`: direct accessor/parent messages emitted from slot execution.

The optimized correctness check follows the network-slot contract: ordinary
slot values remain in outer accessor cells, while the collection cell carries
structural accessor topology. The baseline correctness check still expects
durable slot values inside the collection network.

Representative run after plain named-network preservation, recorded
`2026-06-12`:

| scenario | variant | median ms | subnets | skips | coll-msgs | coll-noops | parent-msgs | ok |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| redundant-100 | baseline | 17.529 | 3000 | 0 | 3000 | 3000 | 3000 | true |
| redundant-100 | optimized | 4.336 | 0 | 3000 | 0 | 0 | 0 | true |
| deep-3 | baseline | 7.416 | 208 | 0 | 208 | 224 | 232 | true |
| deep-3 | optimized | 0.846 | 56 | 112 | 56 | 0 | 8 | true |
| deep-10 | baseline | 736.531 | 1104 | 0 | 1104 | 1352 | 1128 | true |
| deep-10 | optimized | 1.761 | 168 | 384 | 168 | 0 | 8 | true |
| wide-1 | baseline | 0.112 | 16 | 0 | 16 | 8 | 16 | true |
| wide-1 | optimized | 0.090 | 8 | 8 | 8 | 0 | 8 | true |
| wide-10 | baseline | 3.452 | 88 | 0 | 88 | 80 | 880 | true |
| wide-10 | optimized | 0.962 | 8 | 80 | 8 | 0 | 80 | true |
| wide-100 | baseline | 467.428 | 808 | 0 | 808 | 800 | 80800 | true |
| wide-100 | optimized | 54.974 | 8 | 800 | 8 | 0 | 800 | true |
| mixed-1 | baseline | 0.371 | 90 | 0 | 90 | 90 | 90 | true |
| mixed-1 | optimized | 0.097 | 0 | 90 | 0 | 0 | 0 | true |

Additional independent-slot microbenchmark, recorded `2026-06-12`, using `200`
accessors spread across `200` distinct slots and then updating one accessor:

| shape | strategy | setup ns | update ns | collection changed on update |
|---|---|---:|---:|---|
| 200 accessors / 200 slots | `p:legacy-slot` | 298,543,583 | 150,788,917 | yes |
| 200 accessors / 200 slots | `p:network-slot` / `p:slot` | 143,394,791 | 316,500 | no |

Compared with the earlier `2026-06-10` network-slot record
(`155,213,739 ns` setup, `366,062 ns` update), this run is about `1.08x` faster
for setup and about `1.16x` faster for the single accessor update. Treat the
exact ratio as a local microbenchmark signal, not a stable machine-independent
number.

Larger explicit runs are available, for example:

```bash
clojure -M:compound-object-bench deep 50
clojure -M:compound-object-bench wide 1000
```

The default suite intentionally stops at `deep 10` because larger baseline deep
runs are slow enough to be inconvenient during normal development.
