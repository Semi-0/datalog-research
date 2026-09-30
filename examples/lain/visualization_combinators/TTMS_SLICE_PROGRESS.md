# TTMS continuation checkpoint — 2026-09-30

## Published and verified

`32b91e2` was committed and pushed to `origin/main` in
`Semi-0/datalog-research`. It adds supported-map field publication through actual
bidirectional compound participants. Promotion means Git main, not replacing the
default accessor or enabling TTMS throughout Compiler 2.

The exact source candidate was tested in an isolated export of `f986aca`, without
the working checkout's unrelated tracer/compiler edits:

- Structural-field experiment: 235 passing assertions.
- Selected existing regressions: 1,738 passing assertions.
- Full default suite: 3,524 passes, 11 failures, 2 errors, matching the preexisting
  checkpoint. The experimental suite is explicitly invoked, not auto-registered.

No runtime policy was changed and no failing acceptance assertion was weakened.
Unrelated edits, older experimental probes, and the local deprecated-test change
were not included in the promotion.

## Continued publication slice

Added `propagators.structural-publication-test`, composing existing
`ttms-publication/p:observe`, source stamping, message lift, structural field
publication, and real bidirectional accessors. No publication implementation
change was necessary.

**38 new assertions pass**: complete map sampling, changed/unchanged epochs,
withdrawal/recovery through both participants, source identity, compaction,
topology stability, and reverse conflict without rewriting the struct source.
Together with structural-field and existing publication tests: **295 passes**.
The same 295 assertions passed in the isolated export without unrelated edits.
This is headless publication integration, not XR integration.

```sh
clojure -M:test propagators.structural-publication-test propagators.structural-field-test propagators.ttms-publication-test
clojure -M -m examples.lain.visualization-combinators.ttms-forwarding-benchmark 10 100 1000
```

## Forwarding benchmark

The benchmark uses existing identity propagators and the real runner. It separates
setup, publication timing, and activation-instrumented runs. A small warmup is
performed; each table entry is still a single sample, not a statistical estimate.
Plain immutable values have no replacement/retraction contract, so the plain
control measures initial propagation only. TTMS measures its actual lifecycle.

Uninstrumented publication times (milliseconds):

| Stages | Plain initial | TTMS initial | TTMS update | TTMS withdrawal | TTMS recovery |
|---|---:|---:|---:|---:|---:|
| 10 | 1.09 | 3.89 | 4.57 | 3.13 | 3.60 |
| 100 | 7.17 | 17.64 | 17.66 | 14.19 | 13.24 |
| 1,000 | 21.60 | 62.71 | 77.05 | 67.15 | 64.13 |

Instrumented runs counted exactly N activations for N stages in every tested
phase. All runs added zero runtime nodes; TTMS retained one output observation
after recovery. Terminal base assertions passed. Timing differences between
instrumented and uninstrumented runs are subject to JIT/GC/order effects.
This is **not** the outstanding compiled-arithmetic/branching benchmark and says
nothing about XR rendering or browser throughput.

## Architecture evolution review and stop boundary

Live KIROSHI retrieval selected cell semantics, compound objects, and truth
maintenance at model revision 469. The approved September 24 bidirectional-slot
constraint and September 29 TTMS compaction constraint remain current. TTMS
evidence is still `needs-verification`; no model fact was mutated or approved.

Classification for completed work: implementation and verification within
existing boundaries, not component reparenting. Cells continue to own merge and
strongest; compound objects own persistent slot topology; source publication owns
epochs; observational consumers own explicit projections. Existing identities and
BoundaryAssignments remain unchanged.

The consumer migration is **not** a namespace substitution:

- `visualization.data/evidence-value` unwraps distributed TMS, not TTMS. A live
  probe on a TTMS-supported `{:x 10}` returned
  `{:consumer-unwraps-ttms? false :consumer-returns-network? true}`.
- `visualization.data/supported` creates distributed-TMS result claims.
- Collection `source-reader`, `gate-reader`, and result-retention paths can emit
  no update for unusable inputs. TTMS migration must deliberately transport
  invalidation, not simply unwrap a base and suppress withdrawal.
- `visualization.data/decision` still uses reducer-cell for selection controls.
  Reachability is nonzero; reducer-cell removal is not permitted.

The reviewed structural-field plan expressly excluded compiler/consumer
migration and fixed existing synchronization. The requested architecture-evolution
skill requires a reviewed code-level boundary transition before changing the
collection support/publication contract. No such transition was implemented while
the user was away. Whole-compound snapshot/wrapper failures also remain separate.

Required next review: opt-in TTMS collection input/output contracts preserving
source references, predicate support, excluded/pending candidate invalidation,
and retraction/reactivation through actual higher-order callbacks. Keep the
existing distributed-TMS consumer path unchanged until parity is proven. Do not
introduce latest-wins graph merging, hidden payload history, or a one-way accessor
as a workaround.

## Remaining slices, reconciled with recent history

Source history: `f986aca` and `dee4cf7`, plus the chronological
`SUPPORT_RETRACTION_REPORT.md`. Later statuses supersede earlier historical claims.

| Slice | Current state | Completion gate |
|---|---|---|
| TTMS collection/message lift | Existing implementation | Preserve current laws/regressions |
| Scalar primitives and branching | Existing opt-in implementation | Broader compiled chain performance still pending |
| Structural transport | Map-to-persistent-slot experiment verified | No claim of whole-compound snapshot/wrapper migration |
| Tracer/source publication | Headless sampler-to-real-slot composition verified | Integrate selected consumer contract after review |
| Map/filter/dataflow/view consumers | Paused at reviewed-boundary requirement | Support, invalidation, diagnostics, and provenance parity |
| Live XR/reload | Pending consumer migration | Status clearing, desktop/mobile, external-process full reload |
| Benchmarks | 10/100/1,000 forwarding measured | Compiled arithmetic/branching chain family still pending |
| Reducer cleanup | Retain; active selection caller found | Zero maintained reachability before removal |

`when` topology withdrawal remains explicitly deferred. Clock/TUI/event migrations
and cross-repository ports were not performed. No background server or monitor
was started. Unfinished local work remains untouched.
