# Hot Path Dispatch And Compound Boundary

## Current finding

The slider/event hot path became faster mostly because it avoids the
network-local generic procedure dispatcher for standard cell protocol operations.
It does not mean the runtime no longer uses compound objects or layered datum
representations.

The current direct protocol helpers in `propagators.cells.cell-protocol` are:

- `direct-event-merge` / `direct-event-strongest`
- `direct-behavior-merge` / `direct-behavior-strongest`
- `direct-tms-merge` / `direct-tms-strongest`
- `direct-dependency-merge` / `direct-dependency-strongest`
- `direct-scope-source-merge` / `direct-scope-source-strongest`
- `direct-intensity-merge` / `direct-intensity-strongest`

`direct-standard-merge` and `direct-standard-strongest` try those direct
Clojure functions first. Runtime networks mark `:cell/direct-standard-protocols?`
so ordinary standard protocol operations skip generic-procedure fallback.
Benchmark code can remove that marker to compare against the generic path.

## What is still compound/layered

TMS, behavior summaries, scope-source values, dependency values, intensity
values, and event facts/projections are still represented with compound-object
or named-network shaped values.

So the optimization boundary is precise:

- avoided: generic procedure materialization/application for standard protocol
  merge/strongest on the hot path;
- reduced: event strongest/history scanning by storing latest-by-source indexes
  in event content;
- not avoided: compound-object/layered representation costs when values are
  projected, annotated, displayed, or used by TMS/behavior/scope layers.

This is why plain event arithmetic improved much more than trace/XR paths.
Trace/XR still needs label-aware graph projection and richer annotation values.

## Evidence

Latest local benchmark command:

```sh
clojure -M:wired/tui-bench slider-cache-profile
```

Observed run:

| Variant | 60 updates total |
| --- | ---: |
| direct standard protocol, plain slider arithmetic | 279 ms |
| generic rebuild protocol, plain slider arithmetic | 1927 ms |
| retained generic frame protocol, plain slider arithmetic | 1681 ms |
| direct standard protocol with trace/XR/block | 1030 ms |

The retained generic experiment reuses generic application frames and was correct,
but it only improved the forced generic protocol path modestly. It did not catch
up with direct protocol handlers.

Existing dispatch benchmark:

```sh
clojure -M:dispatch-bench 50 51
```

Observed run:

| Variant | Median |
| --- | ---: |
| generic 50 handlers / 51 dispatches | 3054 ms |
| layered base+provenance / 51 dispatches | 123 ms |

This indicates that retained generic application is not enough by itself for
reactive event hot paths. The bigger win is keeping event current-value updates
out of generic/layered interpretation unless that expressiveness is needed.

## Architecture direction

Keep three levels:

1. Direct standard protocol functions for event/behavior/TMS/scope/dependency
   merge and strongest.
2. A future network-local Clojure handler registry for extensibility without
   building a temporary propagator network per merge/strongest.
3. Generic procedure fallback for cases that really need propagator-native
   handler topology.

Longer term, event current-value reactivity should use compact event records or
indexes in the hot path. Compound/layered values should be introduced at explicit
boundaries: behavior promotion, TMS annotation, debug projection, trace display,
and user-facing inspectors.

## Open work

- Replace the ad hoc direct helper list with a small handler registry API.
- Decide whether the registry should be host-global multimethods or network-local
  handler entries in the net dict.
- Build a separate retained-frame experiment for installed `p:apply-layered`;
  the current retained experiment covers `apply-generic-value`, not layered
  procedure application.
- Add a benchmark target for trace/XR projection coalescing, because trace/XR
  remains much slower than plain event arithmetic.
