# Composable tracer verification — 2026-11-02

Runtime checked at `e859f9d`, integrated onto main `f0fb42a` in an isolated worktree.
The original working tree and the GX10 backup checkout were preserved. No new
model trial was conducted.

## Functional acceptance

`clojure -M:test tracers` completed with **3,866 passing assertions, zero failures,
and zero errors**, across 25 namespaces. The runner rejects zero assertions.
The local invocation used the existing frontend contracts checkout through
`-Sdeps`; it did not replace production behavior.

Coverage includes generated modifier permutations and composition prefixes,
associativity, idempotence, TTMS support/state and stale revisions, recursive
proof cleanup, completeness withdrawal, Meander contributor validation, opaque
captured values, built-in empty/open selectors, shared observations, independent
view expansion, retained topology, call graphs, directional tracers, session
publication evidence, capture/history/replay, and the real harness composition.

Chrome verification used a loopback backend from the isolated checkout and the
existing editor frontend. Loading
`examples/lain/subagents/composition.lain` displayed **3 nodes and 6 edges**:
`user`, `runtime`, and `evaluation-framework`, with captured pending field
summaries. This was a browser rendering check with no submitted harness task.

## Local measurements

`clojure -M -m bench-tracer-facts` is an observation-only probe. Its snapshot
wrapper delegates every call unchanged and counts entries; it does not alter
model output or acceptance behavior. Measurements used a local OpenJDK JVM with
`-Xmx2g`. The broad repository tests were also running, so timings are not an
isolated hardware comparison.

The fixture has 24 source nodes and 120 source assertions. Each operation has
8 warmups and 40 measured iterations.

| Operation | Mean time | Allocated bytes per operation |
| --- | ---: | ---: |
| Source fact construction | 0.362 ms | 518,899 |
| View construction | 8.705 ms | 12,798,241 |
| One fact update plus view | 12.021 ms | 19,494,232 |
| Withdrawal plus view | 11.923 ms | 19,409,597 |

One configured view and two configured views each produced **one distinct
observation and two frozen-snapshot function calls**. Their one-shot preparation
times were 2,056 ms and 1,524 ms respectively. These ordered single samples have
warmup effects and do not establish a speedup.

Holding 50 constructed views changed used heap after forced GC by **4,379,544
bytes**. This is a noisy JVM heap delta, not an exact object-retention size or a
proof of improved memory use. Source-registry lifetime remains session-owned.

Earlier controlled harness runs on the unchanged backup baseline took 35.194 s
with a 344 MB used-heap sample. An intermediate migration took 65.576 s with a
365 MB sample. Those samples preceded the final capture representation fix;
they establish neither final throughput nor a retained-memory improvement.
The semantic traversal baseline used 14,823 steps; the migrated fixture used
15,298, so its explicit test guard is 20,000 steps. Budget exhaustion still fails.

No performance improvement is claimed. View allocation remains a measurable cost.

## Broader baseline checks

Both the untouched backup baseline and the final migrated XR suite returned
198 passes, 7 failures, and 2 errors, in the same tests. Existing failures concern
lazy XR server launch, automatic TUI output
blocks, slider alias labels, and rebuilding a non-empty past block. Socket tests
were run with loopback permission, separating sandbox failures from code results.
These failures are outside the tracer acceptance gate and must not be reported
as a green full-repository suite.

Deployment uses the existing GX10 pull service and GitHub `gx10/ci` and
`gx10/deploy` statuses. Passing local acceptance is not deployment evidence;
activation must be checked against the pushed commit.
