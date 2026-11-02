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

## CI regressions found after the initial gate

The initial focused gate was incomplete. GX10 CI for main `e8489a1` reported
9,446 passing assertions, **217 failures and 8 errors**, and did not activate that
revision. All seven assertion-failing namespaces pass on the exact prior main
`f0fb42a`: 49 tests and 1,278 assertions, with zero failures or errors. These were
migration regressions, not pre-existing failures.

| Affected namespace | Assertion failures |
| --- | ---: |
| `propagators.experimental.ttms-view-test` | 4 |
| `propagators.experimental.tracer-topology-test` | 122 |
| `propagators.experimental.compound-tracer-test` | 23 |
| `propagators.experimental.compound-levels-demo-test` | 9 |
| `propagators.experimental.view-xr-test` | 8 |
| `experiments.synth-test` | 3 |
| `graph.lain-editor.inspection.semantic-gallery-test` | 48 |

The declarative visualizer still passed capture wrappers to a raw graph renderer.
Legacy test adapters also read raw graph keys on those wrappers. The repair uses
public capture/fact accessors and preserves the existing topology/value oracles.
Editor inspection now preserves an already acquired capture's identity.

The error/recovery test exposed another production gap: relationship diagnostics
were dropped as unusable publication bases. They now retain their input revision
support, so a corrected graph supersedes the diagnostic. An unresolved dataflow
input remains pending rather than producing a type error.

Seven harness errors reported an absent selected `user` boundary. The eighth
was `Socket publication timed out` in the real loopback user-socket case;
classifying all eight as boundary errors was incorrect. Capture creation had
conjoined every field's support into scope availability. A snapshot
containing independent fields at different revisions of one source could therefore
hide every topology fact. Scope availability now uses its own support, while each
value assertion retains its field support. A focused regression verifies current
topology, field evidence, and explicit scope withdrawal. The actual harness
assignment/withdrawal lifecycle passes all 19 assertions after this repair.

The repaired seven-suite run with loopback permission passes **1,286 assertions,
zero failures, and zero errors**. The snapshot regression namespace passes all
35 assertions. Chrome renders the actual composition as three actors and six
edges; a retained unary gallery example renders five nodes and four edges with
`flag = false` and `result = true`.

The first repair at `0d12ee8` completed 33 namespaces with 5,187 passing assertions
before its slow local harness run was interrupted for the integration below.

## Scope availability, acquisition, and socket integration

A second regression withdraws an entire scope while independent selection
declarations remain active. Previously derivation raised `boundary absent`.
Derivation and display now consult the supported scope-availability assertion.
An unavailable scope retains its assertions/declarations and displays an empty
view; missing or ambiguous selectors in an available scope still raise errors.
A new available observation restores the selected components.

Thread samples identified repeated whole-metadata scans during capture. Frozen
observations now reuse application indexes by owner path, and each snapshot
reuses its lexical-name/operator indexes across selected nodes. The indexes use
one immutable source; new observations/samples acquire fresh metadata. Captured
values retain their original observation and are never read from a newer source.
Focused regressions verify reuse, fresh names/values, and observation isolation.

The socket framing receipt preceded synchronous Lain publication. The test now
reports a publication-error frame directly rather than hiding it behind a generic
timeout. With the acquisition indexes, receipt-to-publication was **18,696.7 ms**,
within the unchanged **30-second deadline**. The server also closed an idle client
while the harness was computing feedback. It now continues waiting when a read
times out before any frame bytes arrive; a timeout in a partial frame still
closes the client. Real socket regressions verify both behaviors.

Integrated socket validation passes **103 assertions**, zero failures/errors:
8 harness/error-frame assertions and 95 transport assertions. The 35 other tracer
namespaces pass **235 tests / 5,296 assertions**, zero failures/errors. After the
scope-availability guard, the high-level/TTMS/capture/editor follow-up passes
**44 tests / 1,953 assertions**, zero failures/errors. A fresh actual harness
withdrawal run also passes all 19 assertions in 203.1 seconds, covering the final
guard in production composition.

The consolidated three-harness run at `-Xmx2g` exited **nonzero** after 2,209.2 s:
111 passes, zero assertion failures, and one `OutOfMemoryError` in the final
Datalog ownership query. The preceding two namespaces passed 102 assertions.
Every original boundary/socket case has passed across the completed and fresh
runs, but the consolidated 2 GB gate is not green. A 4 GB rerun, matching GX10 CI,
is pending; it measures requested-GC heap between namespaces and keeps the
one-hour overall timeout and 30-second socket deadline. This is a resource
limitation, not evidence of a proven caching leak or successful deployment.

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

These microbenchmarks do not establish an end-to-end speedup. View allocation
remains a measurable cost; the socket deadline measurements above are separate
integration results.

## Broader baseline checks

Both the untouched backup baseline and the final migrated XR suite returned
198 passes, 7 failures, and 2 errors, in the same tests. Existing failures concern
lazy XR server launch, automatic TUI output
blocks, slider alias labels, and rebuilding a non-empty past block. Socket tests
were run with loopback permission, separating sandbox failures from code results.
These failures are outside the tracer acceptance gate and must not be reported
as a green full-repository suite.

The previously unregistered `propagators.relationship-observer-test` also has
two unchanged legacy failures: reading refreshed values from unversioned observer
publication. Both prior main `f0fb42a` and the integration return the same failures
(the baseline has 8 tests, 19 passes, 2 failures). This migration leaves that
suite unchanged; the new snapshot metadata/freshness regression is registered
separately as `propagators.relationship-snapshot-test`.

Deployment uses the existing GX10 pull service and GitHub `gx10/ci` and
`gx10/deploy` statuses. Passing local acceptance is not deployment evidence;
activation must be checked against the pushed commit.
