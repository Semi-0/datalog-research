# Behavior Event Split Benchmark

Command:

```sh
clojure -M:wired/tui-bench behavior-graph 5 0
```

The benchmark builds one TUI runtime with five behavior inputs, a slider panel,
a 10-term additive behavior graph, a `be:block` watcher, and an XR trace. It
warms all five channels, then measures five widget updates.

## Baseline Before Event Split

Captured after adding the benchmark and before changing behavior arithmetic
semantics.

| variant | correct | setup ms | warmup ms | update avg ms | update p95 ms | update max ms | TUI read p95 ms | XR read p95 ms | graph |
|---------|---------|----------|-----------|---------------|---------------|---------------|-----------------|----------------|-------|
| legacy default `+` | yes | 4778.531 | 3173.406 | 1241.147 | 1721.240 | 1721.240 | 2.241 | 0.467 | 1696 nodes / 1548 edges |
| explicit `be:+` before wiring | no | 4180.310 | 2510.911 | 707.267 | 754.484 | 754.484 | 0.466 | 0.179 | 1697 nodes / 1551 edges |

The explicit variant was not semantically wired yet; it produced
`:bool4/nothing`.

## After Event Split

Captured after event facts, event-to-`be:latest` promotion, widget event facts,
and explicit behavior arithmetic were implemented.

| variant | correct | setup ms | warmup ms | update avg ms | update p95 ms | update max ms | TUI read p95 ms | XR read p95 ms | graph |
|---------|---------|----------|-----------|---------------|---------------|---------------|-----------------|----------------|-------|
| legacy default `+` | no | 2765.862 | 1981.314 | 727.681 | 1121.304 | 1121.304 | 3.648 | 0.510 | 1681 nodes / 1533 edges |
| explicit `be:+` | yes | 2258.680 | 2446.828 | 1153.988 | 1598.157 | 1598.157 | 0.874 | 0.223 | 1681 nodes / 1533 edges |

The legacy default variant is expected to be incorrect after the split because
plain `+` is current-value arithmetic, not behavior-history arithmetic.

## Result

Comparing the correct pre-refactor path, legacy default `+`, with the correct
post-refactor path, explicit `be:+`:

- setup improved from `4778.531ms` to `2258.680ms`;
- update average improved from `1241.147ms` to `1153.988ms`;
- update p95 improved from `1721.240ms` to `1598.157ms`;
- TUI read p95 improved from `2.241ms` to `0.874ms`;
- full-rebuild fallback count stayed `0`.

The full 100-update stress form is:

```sh
clojure -M:wired/tui-bench behavior-graph 100 0
```

The 5-update run is the default development check because the current graph and
trace path still cost roughly one second per measured widget update.
