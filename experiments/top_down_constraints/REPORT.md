# Incremental constraint-first stack experiment report

Status: semantically accepted through the runtime, scene interpreter, and
frontend model. Revision-matched interactive browser latency and visual
acceptance remain unverified, so this is not a claim of complete browser
acceptance.

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

## Verification

- The complete JVM suite passed 6,419 assertions with no failures or errors.
- The ClojureScript suite passed 78 tests and 695 assertions; the production
  browser bundle rebuilt successfully.
- `cargo test --locked` passed both Rust tests.
- A fresh r9 API smoke run reached ready state with two stack planes, one
  source-anchored expansion, preserved `:call` and `:result` node kinds, and no
  diagnostics.
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

## Remaining unverified

- Browser request-to-visible latency is not measured. The live server responds
  on port 4782, but Safari WebDriver reported that global “Allow remote
  automation” is disabled. Server or synchronous profiler time is not used as a
  substitute.
- The current r9 browser presentation has not received a revision-matched visual
  acceptance pass. The prior visual development check predates r9.
- Exact separation of initial compilation from initial propagation remains
  unavailable at the runtime boundary; cold reconstruction is reported as one
  total.
- Human/agent development time is classified separately from runtime execution,
  but no productivity conclusion is drawn from it.
