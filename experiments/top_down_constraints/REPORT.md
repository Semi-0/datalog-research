# Incremental constraint-first stack experiment report

Status: accepted through the runtime, scene interpreter, frontend model, and a
revision-matched Chromium browser run. Safari-specific automation remains an
environment limitation rather than a substitute acceptance claim.

## Proposed

Retain a premise-versioned Lain network across refinements, maintain supported
constraint outcomes incrementally, compare each retained result against clean
reconstruction, and render validated occurrence stacks spatially without
mixing domain state and camera state. No speedup was assumed.

## Implemented

- `stack-spec-2026-10-20-r9`, with high-level `compound->stack` and
  `scope->stack` transformation in Lain. `expand-pending` performs a
  tail-position recursive call over explicit immutable work state.
- Frozen, observation-qualified readers; canonical one-plane graph content;
  explicit direct expansion membership; occurrence identity, sharing, cycles,
  partial information, localized contradictions, and references.
- Single-result traversal transitions. `take-pending` returns an immutable step
  with named readers; registration returns the next state directly. No public
  domain operation requires callers to allocate an output cell.
- Support-aware stack primitive dataflow. Premise state and support propagate
  through recursive traversal, so replacement withdraws a retained stack and
  restoration resumes it.
- Recursive candidate projection that removes only the current definition's
  self-support conjunct. Independent source supports remain; a claim supported
  only by itself is still rejected as feedback.
- Source-only closed-block recovery, premise-versioned editor development,
  read-only checkpoint inspection, explicit outcome/evidence invalidation,
  normalized clean comparison, a spatial scene interpreter, and a declarative
  frontend with separate model, view state, transitions, effects, and view.

## Actually measured

The canonical raw record is
[`runs/2026-10-21-stack-r9-measured-2.edn`](runs/2026-10-21-stack-r9-measured-2.edn).
It records Git revision `85010635cf887cf5e3eba1179b5181a5c31ed389`,
SHA-256 hashes for the dirty pinned sources, Java 27 on arm64 macOS, three
warmups, five measured repetitions, alternating comparison order, and a
60-second per-sample budget.

All ten measured retained/clean pairs agreed semantically and completed within
the sample budget.

| Scenario | Incremental raw ns | Incremental median | Clean raw ns | Clean median |
| --- | --- | ---: | --- | ---: |
| Unrelated edit | 125802292, 126882750, 127269083, 127648417, 137476458 | 127269083 | 22369807708, 22416147541, 22665522500, 22769754584, 22778281125 | 22665522500 |
| Reader replacement | 1885261334, 2178283750, 2526923959, 2842388709, 3197025000 | 2526923959 | 27927697708, 30077462959, 32496653375, 35363158750, 38531579875 | 32496653375 |

The separately sampled cold reconstruction took 24,717,141,208 ns and retained
40,210 entries/nodes. Repeated version replacement grows retained topology; the
measured implementation updates rose from 1.885 s to 3.197 s while remaining
well below their paired clean reconstructions. This growth is observable debt,
not hidden by the median.

Exact-name activation classification reports no stack-constraint primitive
evaluation in any measured unrelated edit. Those samples performed 404-405
propagator activations with maximum pending work 236. Reader-replacement samples
performed 3,330-3,394 activations with maximum pending work 245. The earlier
substring classifier incorrectly counted compound-slot plumbing as constraint
execution; that failed measurement method is recorded in `DECISIONS.md` and a
regression test now excludes it.

Instrumentation-overhead medians were 128,524,167 ns instrumented and
130,302,042 ns uninstrumented. The ranges overlap, so the data supports no
directional overhead claim.

The executable fixture gate checked all nine expectations: five of eight
currently required constraints were satisfied, one violated as declared, one
remained pending as declared, one was contradictory as declared, and one
fixture was inactive. Required-constraint satisfaction is therefore `5/8`;
fixture expectation agreement is `9/9`. These rates deliberately measure
different things.

The edit sequence observed pending before implementation, satisfied after 24
implementation commits, no outcome/evidence invalidation for an unrelated
edit, localized invalidation for reader replacement, pending after withdrawal,
satisfied after restoration, atomic stale-commit rejection, and supported
success after a no-op replacement with old evidence invalidated.

The separate browser record is
[`runs/2026-10-21-stack-r9-browser.edn`](runs/2026-10-21-stack-r9-browser.edn).
After three warmups, request-to-visible samples were `3326.087`, `3342.900`,
`3326.225`, `3324.360`, and `3326.929` ms: median `3326.225` ms, range
`3324.360–3342.900` ms. Timing begins at Meta-Enter dispatch and ends after the
scene is visible plus two animation frames, so it includes the browser request,
server execution, reactive update, layout, and a paint boundary.

The browser displayed two semantic planes, one expansion edge, two cell nodes,
and two propagator nodes. The measured edge start remained within `0.19 px` of
the root compound-node center. Changing depth spacing moved the child plane from
`258 px` to `328 px` while semantic counts and kinds remained unchanged. The two
“Partial capture.” messages are expected: the controlled renderer fixture uses
capability-free legacy semantic graphs and deliberately performs no acquisition.
No page or console errors were observed.

A later presentation run added explicit depth labels, expansion/reference
counts, and accessible plane/edge descriptions without changing the stack
domain value. Its raw record is
[`runs/2026-10-21-stack-r9-browser-depth-presentation.edn`](runs/2026-10-21-stack-r9-browser-depth-presentation.edn).
On a fresh server with per-sample runtime release, its measured median was
`1320.572 ms` with range `1308.327–1322.074 ms`. This is not attributed to the
visual change: the earlier harness had retained every backend editor session,
so the two timing sets do not isolate one variable.

## Verification

- The complete JVM suite passed 6,419 assertions with no failures or errors.
- The ClojureScript suite passed 78 tests and 695 assertions; the production
  browser bundle rebuilt successfully.
- `cargo test --locked` passed both Rust tests.
- A fresh r9 API smoke run reached ready state with two stack planes, one
  source-anchored expansion and preserved `:call` and `:result` node kinds.
- Chromium browser acceptance passed three warmups and five measured samples,
  including source-anchor geometry and view-state separation checks.
- The canonical EDN artifact parses successfully and `git diff --check` reports
  no whitespace errors.

## Failures and counterexamples

- The original recursive `struct` candidate did not quiesce and was removed.
- Multi-output versioned wrappers allowed work/state values to bypass their
  premise boundary. A previously materialized stack therefore survived reader
  withdrawal. R9 replaced those APIs with single immutable results.
- The first support-aware adapter dropped valid `false` predicate results by
  testing truthiness instead of presence. A `some?` check fixed it.
- The original recursive input projection discarded a whole conjunctive claim
  when it contained self-support. That blocked the second traversal iteration;
  retaining independent supports fixed recursive refinement without permitting
  claims supported only by themselves.
- The first benchmark classifier used substring matching and falsely reported
  constraint executions for compound-object slot accessors. Exact symbol
  matching shows zero constraint primitive executions for unrelated edits.
- The initial browser harness closed browser contexts without closing their
  backend editor sessions. Repeated runs eventually produced a server heap
  exhaustion. The runner now sends the ordinary editor `close` command in a
  `finally` block; three consecutive complete 3+5 runs passed afterward. The
  original latency record remains raw evidence but is not a clean-lifecycle
  performance baseline.
- The first real compound walkthrough treated every propagator with structural
  children as another compound plane. It rendered ten planes instead of three,
  exposing primitive arithmetic, copy, and return wiring as false expansions.
  Resolving semantic applications to Lain closures, including the active body
  of a versioned definition, restores the intended boundary: primitives remain
  nodes in the owning plane.
- The full benchmark output exceeded the console display limit. The canonical
  artifact preserves all timing arrays and counters needed for the reported
  comparisons. One non-summary activation-elapsed value that was not preserved
  is explicitly `nil` and marked incomplete rather than reconstructed.

## What the measurements support

For the measured fixture and edit sequence, retained refinement preserved the
normalized semantic result of clean reconstruction, avoided constraint
primitive execution for unrelated edits, and gave substantially shorter
feedback latency than reconstruction. This is evidence for this implementation
and workload, not a general productivity or asymptotic claim.

A performance regression would not invalidate semantic acceptance. None was
observed against clean reconstruction, but retained topology and replacement
latency grew across repeated versions and should be treated as a limitation.

## Remaining limitations

- Safari WebDriver requires an administrator-authenticated “Allow remote
  automation” setting, and macOS screen capture is not authorized in this
  environment. The revision-matched browser gate therefore used isolated
  Chromium 156 with Playwright 1.64.1. No server or synchronous profiler timing
  was substituted for browser latency.
- Exact separation of initial compilation from initial propagation remains
  unavailable at the runtime boundary; cold reconstruction is reported as one
  total.
- Human/agent development time is classified separately from runtime execution,
  but no productivity conclusion is drawn from it.
