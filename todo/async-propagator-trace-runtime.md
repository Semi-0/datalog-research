# Async Propagator Runtime For Trace

## Problem

The current compiler-2 TUI/XR trace path is working, but it is fragile. Most
recent runtime bugs came from the same boundary:

- tracing is too expensive to run inside the main runtime/session transaction;
- `trace` still looks like an ordinary compiler-2 operator;
- the actual trace calculation now runs outside the propagator network;
- the trace result is written back into the program net and expected to wake
  ordinary consumers such as `io:xr`, `xr-io`, `be:block`, and `be:block-at`.

That gives us a hybrid model: trace is declared inside the language, computed
outside the propagator runtime, then manually re-entered as a behavior value.
Every re-entry point has to recreate scheduling, epoch, stale-result, wakeup,
and boundary-effect behavior that the propagator runtime should own.

## Current Architecture

The current path is:

```text
(trace out g)
  -> compiler-2 trace operator emits :xr/trace-subscribe boundary effect
  -> runtime records a trace subscription
  -> trace worker snapshots the semantic graph and runtime epoch
  -> worker computes semantic-trace/trace-graph outside the session lock
  -> worker writes a latest behavior value into g
  -> runtime settles retained application props
  -> io:xr / xr-io consume g and emit XR launch effects
  -> be:block / be:block-at consume g and emit TUI display effects
  -> boundary delivery records XR/TUI effects
```

Important implementation points:

- `propagators.compiler-2.runtime.operators.trace` emits a trace-subscription
  boundary effect rather than a trace graph.
- `propagators.compiler-2.runtime.inspection.trace.subscriptions` computes traces from immutable
  snapshots and writes latest behavior values back to target cells.
- `propagators.compiler-2.runtime.operators.xr` accepts either a raw semantic trace
  graph or a behavior-wrapped trace graph.
- TUI auto-output treats trace-containing forms specially so the display block
  watches the behavior result instead of copying it once.
- Boundary effect coalescing has special cases for XR graph richness and usable
  TUI display payloads.

## Design Smells

### Trace Is Half In The Program And Half Outside

`trace` is source-level syntax and a compiler operator, but its real work is a
runtime subscription. This means it does not naturally participate in normal
propagator dependency tracking.

### Re-Entry Is Manual

When a trace worker finishes, it has to:

- write the result into the program net;
- settle retained applications;
- deliver boundary effects;
- preserve XR trace registry state;
- suppress stale results;
- wake TUI/XR consumers.

Each of these is a place where a future change can miss one consumer or reorder
events incorrectly.

### Timing Semantics Differ By Entry Point

`handle-command!` schedules trace refresh asynchronously. Direct facade calls
such as `append-tui-block!` and `submit-tui-block!` force a synchronous refresh
to preserve immediate-read test semantics. That is a compatibility bridge, not
a principled runtime model.

### Boundary Coalescing Encodes Too Much Policy

Generic boundary delivery now knows that:

- XR trace launches should prefer richer graphs for the same delivery key;
- display writes should prefer usable values over unusable values;
- text writes must still allow contradiction in some cases.

These policies belong to typed effect reducers or to the propagator scheduler,
not to a generic outbox collapse helper.

### TUI Auto-Output Knows About Trace

Expression display should not need syntax-specific trace logic. The fact that
trace forms need `be:block-at` while other expressions use `block-at` means
display behavior is coupled to a specific operator implementation detail.

## Root Cause

The deeper issue is that the propagator runtime does not have a cohesive async
worker model.

Tracing is a long-running derived computation. We moved it off the main thread
to avoid blocking TUI/XR/runtime commands, but the runtime has no first-class
way to say:

```text
this propagator declares async work;
the worker reads snapshot N;
the result returns as a behavior event for epoch N;
normal propagation resumes from that event.
```

So trace implemented that protocol by hand as a side channel.

## Proposed Direction

Add first-class async worker propagators to the runtime, then reimplement trace
on top of that primitive.

The primitive should provide:

- immutable snapshot capture for worker input;
- runtime/topology epoch tagging;
- stale result suppression through normal behavior/latest merge;
- coalesced scheduling so obsolete queued work can be dropped;
- result commit through the same event/cell path as ordinary propagation;
- downstream wakeup through the normal scheduler;
- typed result/effect reducers rather than generic outbox heuristics.

Then `trace` can become declarative again:

```text
trace-request(out, graph, epoch)
  -- async worker -->
latest-behavior(g, trace-graph, epoch)
```

`io:xr`, `xr-io`, `be:block`, and `be:block-at` should consume `g` without
knowing whether it was produced synchronously or asynchronously.

## Target Architecture

```text
compiler-2 source
  -> propagator graph declares async trace request cell
  -> runtime async scheduler observes request cell
  -> worker computes from immutable graph/env/net snapshot
  -> worker commits result event to trace result cell
  -> normal propagator scheduler wakes consumers
  -> typed boundary reducers deliver XR/TUI effects
```

## Migration Plan

1. Define a small async-work cell protocol.
   - Request identity.
   - Snapshot epoch.
   - Input snapshot value.
   - Output target cell.
   - Coalescing key.

2. Add a runtime async scheduler.
   - Maintain pending/running/latest request state.
   - Drop obsolete queued work by coalescing key.
   - Commit only if result epoch is not stale.

3. Make worker result commit use the normal cell event path.
   - Avoid direct ad hoc `assoc-net-cell` re-entry where possible.
   - Ensure downstream props wake through the same mechanism used by normal
     cell messages.

4. Rebuild trace on the async primitive.
   - `trace` creates a trace request.
   - The trace worker computes `semantic-trace/trace-graph`.
   - The result cell is a latest-retaining behavior.

5. Remove compatibility special cases.
   - Remove trace-specific TUI auto-output branching.
   - Remove direct facade `refresh-now!` as a semantic requirement.
   - Move XR/TUI delivery policy into typed reducers.

6. Keep current external trace path as a fallback during migration.
   - Gate it behind a feature flag or internal runtime option.
   - Use benchmarks to compare old side-channel tracing and async propagator
     tracing.

## Acceptance Criteria

- `(trace out g)` updates `g` when upstream topology grows.
- `(io:xr g)` updates whether installed before or after the first trace result.
- TUI trace display updates without trace-specific auto-output logic.
- Widget behavior updates do not corrupt trace or display cells.
- Multiple traces can refresh without blocking TUI append/edit/read.
- Obsolete trace jobs are dropped or ignored deterministically.
- Direct runtime calls and server command calls have the same semantic timing.
- Existing compiler-2 behavior tests continue to pass.
- Benchmarks show trace refresh work no longer dominates append latency.

## Relevant Current Tests

- `graph.xr-runtime-test`
- `graph.vijual.compiler-2-runtime-server-test`
- `propagators.compile-2-test`
- `clojure -M:wired/tui-bench trace-subscriptions`

