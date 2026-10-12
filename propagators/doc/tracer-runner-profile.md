# Tracer runner phase profile

## Scope and implementation

`propagators.experimental.runner.profiling` decorates the existing synchronous
FIFO runner's activation and patch evaluators. It records inclusive time, exclusive
self time, call count, maximum call duration, transitions, node additions, and
peak network/queue size. It retains no network snapshots. `phase` can decorate
host functions to measure compilation, environment creation, and observation.
The runner, GUR, TTMS, compound-object kernel, and scheduler policy are unchanged.

```clojure
(let [profile (profiling/profiler)
      execution
      (with-redefs [runner/network-iterator
                    #(profiling/network-iterator profile)]
        (runner/run-network tasks network))]
  {:execution execution
   :profile (profiling/report profile)})
```

`with-redefs` is process-global: run this diagnostic in an isolated JVM, not in
the live LAN server. Timers cover synchronous calls; async callback timing is
outside this contract. Parent self time excludes nested measured spans, so
inclusive rows must not be added to their children.

## Evidence

The original eight-view `chain.lain` source was loaded with its existing
extensions. The probe replaces `tracked+` and `tracked-or` with ordinary names
in memory. No example source was changed. JVM startup and namespace loading were
completed before the measured calls. Diagnostics have a ten-second guard;
test vars retain their three-second guard.

| Operation | Cold | Warm | Warm count |
| --- | ---: | ---: | ---: |
| Complete session/example load | 6,087 ms | 4,880 ms | 1 |
| Relationship observer activation, inclusive | 2,565 ms | 2,477 ms | 151 |
| Snapshot lexical-label indexing | 1,126 ms | 1,105 ms | 6,119 |
| Snapshot construction excluding label indexing | 1,102 ms | 1,053 ms | 158 |
| Root scans, self time | 300 ms | 287 ms | 306 entries, 153 scans |
| Runner patch evaluation, self time | 1,282 ms | 866 ms | 6,482 |
| Semantic-graph projection | 878 ms | 699 ms | 18 |
| Parsing/compiling source, inclusive | 114 ms | 75 ms | 18 |
| Runtime environment declaration, self time | 141 ms | 97 ms | 18 |
| Propagation runs, inclusive | 4,894 ms | 4,038 ms | 301 |
| Repeated application settling, inclusive | 1,646 ms | 1,607 ms | 18 |

Rows are nested and overlap. Label indexing and snapshot self time are children
of observer activation; settling and patch work are inside propagation runs.
Root scan entries double-count the one-arity function's delegation, so self time
is used and the distinct scan count is 153.

The measured worker used 5,872 ms CPU cold and 4,748 ms CPU warm. Whole-JVM GC
collection time rose by 166 ms and 112 ms respectively. This is mainly CPU work,
not seconds spent blocked. Profiling adds overhead; these are diagnostic timings,
not a production benchmark or a passing three-second acceptance test.

Both loads completed with eight views, no runtime errors, 6,482 actual
transitions, peak 4,905 nodes, and peak 298 pending tasks. The earlier probe's
6,783 count included 301 terminal empty-queue iterations. There is no runaway
growth in this reproduction. This does not establish cycle freedom for every
future update.

The prior step diagnostic identified 50 observer propagators publishing to two
relationship cells: 49 activated three times, one four times. Each independently
rebuilds a selected snapshot. `dictionary-node-label` rebuilds lexical names for
every selected node. `settle-application-props` explicitly runs the application
list twice after initial propagation. Those are concrete repeated-work costs.

The first narrow repair candidate is one lexical-label index per snapshot.
Further snapshot sharing or changes to repeated settling require separate
correctness checks for late topology, live updates, and TTMS retraction.

## Verification

Profiler tests prove deterministic exclusive timing, exception identity, and
network/result parity with ordinary propagation. Existing runner and iterator
tests also passed: 16 tests, 60 assertions, zero failures/errors. Slowest test:
189 ms. No performance acceptance claim is made for the eight-view example.

Raw cold/warm reports and the isolated diagnostic harness are in
`/private/tmp/lain-functional-production/tracer-profile-{cold,warm}.edn` and
`/private/tmp/lain-functional-production/profile-eight-views.clj`.
