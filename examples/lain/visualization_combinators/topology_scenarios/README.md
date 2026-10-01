# Lain high-level tracer scenarios

Current status: [recursive tracer repair and quality assessment](TRACER_REPAIR_REPORT.md).
The complex projection gaps are repaired; full TTMS recursive execution remains
a separately reproduced runtime limitation.

Follow-up: [composed functions and compound sub-networks](COMPOUND_REPORT.md)
adds a more demanding program. It exposes scope and return-routing projection
gaps; the eight simpler cases below still pass. Their success is not a claim that
all compound expansions are precise.

Verified October 1, 2026 against checkpoint `841c62255756967abed988d48b4e5393f6f47be8`.
That checkpoint was committed and pushed to `origin/main` before this experiment.
The scenario files, tests, registration, and this report are subsequent local work.

## Result

Eight actual Lain programs were compiled and executed in both ordinary and opt-in
TTMS environments. The new suite passes **3 tests / 226 assertions**, with no
failures or errors. This supports precise high-level topology for these cases,
not an unrestricted correctness claim for the tracer.

| Program | What is checked | Projected nodes / directed edges |
|---|---|---|
| `chain.lain` | Two arithmetic applications and named intermediate | 6 / 6 |
| `diamond.lain` | Shared inputs, fan-out, reconvergent fan-in | 8 / 9 |
| `repeated.lain` | Two distinct occurrences of the same network definition | 5 / 4 |
| `nested.lain` | Compound containing two child applications; one outer boundary | 3 / 2 |
| `cycle.lain` | Three forward links forming a cycle | 3 / 3 |
| `bidirectional.lain` | Two persistent bidirectional links, seeded from the far end | 3 / 4 |
| `conditional.lain` | Condition and both possible value inputs to `if` | 5 / 4 |
| `late.lain` | Callable arrives after observation is installed | 0 / 0 before; 3 / 2 after |

Each expected application edge is declared explicitly in the test using Lain
binding identities. The test resolves application occurrence IDs through existing
application metadata; it does not derive expected ports or edges from the
projector under test. Equality checks compare complete path-qualified edge and
node sets, not merely labels or expected-edge subsets. Repeated `step` labels
therefore cannot accidentally merge the two occurrences.

Checks cover the graph before values arrive, after propagation, current sampled
result values, duplicate injection, no repeat topology growth, no duplicate edges,
and no leaked compound internals. In the late-callable case the graph refreshes
when the callable arrives, before writing the ordinary input. Full environment
replacement from `chain.lain` to `diamond.lain` changes exactly six edges to nine
and computes the new result; no old graph edges survive.

The initial arithmetic fixture incorrectly used `(+ a b middle)` as an explicit
output call. Variadic arithmetic actually consumes all three arguments. Corrected
fixtures use `(-> (+ a b) middle)`. Production code and expected topology were not
changed to accommodate that mistake.

## Reproduce

From the repository root:

```sh
clojure -M:test propagators.experimental.tracer-topology-test
clojure -M:test propagators.experimental.tracer-topology-test propagators.relationship-observer-test propagators.relationship-dataflow-test
```

The second command reports **283 passes, 2 failures, 0 errors**:

- New topology matrix: 226 passes.
- Existing observer tests: 21 passes.
- Existing dataflow tests: 36 passes, 2 known failures in
  `errors-merge-with-reasons-and-do-not-abort-other-work`. Legacy graph merging
  still loses the error/diagnostic after a previously published graph. Assertions
  remain unchanged; this experiment does not repair that failure.

Before the checkpoint commit, TTMS consumer and premise-transport tests passed
134 assertions and the Node browser contract checks passed. The earlier full
suite result (3,713 passes, 1 failure, 2 errors in the old compound-boundary
experiment) is documented in `../TTMS_CONSUMER_MIGRATION.md`; the full suite was
not rerun for these test-only additions.

## Scope and limits

- This is the application-level projection, not an activation log. `if` displays
  all declared potential inputs, not only the currently selected branch.
- Nested compound internals deliberately disappear from a root-level graph.
  These tests do not prove an exact recursive child graph or arbitrary nested
  network-valued owner paths. Existing child-observer tests provide separate,
  narrower evidence.
- The cycle and bidirectional cases use forwarding cells, not chained compound
  slot/accessor topology. Arbitrary recursive GUR and dynamic structural removal
  are not covered here.
- The reload test exercises the real session replacement API using two file
  contents. Filesystem watcher delivery and desktop/mobile rendering were not
  rerun here; this is headless verification, without an XR server.
- No production runtime, runner, merge policy, compound synchronization, or
  compiler contract changed. KIROSHI grounding at revision 478 retained explicit
  projections, bidirectional slots, runtime-owned effects, and TTMS dominance.
  No KIROSHI mutation occurred.
