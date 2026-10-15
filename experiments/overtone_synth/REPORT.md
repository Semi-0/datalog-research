# Functional-network synth case study — 2026-10-06

## Recovery update — 2026-10-12

The case study now uses the canonical production file loader and session-extension
API, not `experiments.functional-network.*`. Child observation uses the maintained
`relationship-dataflow/child-dataflow-graph`; XR publication is `xr:io`.
The live graph observes the public pulse/tick/frequency/note/receipt boundaries
explicitly. This does not claim that pulse-only tracing covers identity-return
aliases. Audio remains a runtime-owned effect outside Net.

Current synth checks: 10 tests / 53 assertions, all passing, including real
external-file replacement, last-good-state preservation, stale generation
rejection, withdrawal/recovery, safe notes, and resource cleanup. The backend
was mocked or silent; physical SuperCollider audio was not verified in this run.
The older measurements and prototype descriptions below are historical, not
newly reproduced results. See `experiments/history/missing-slices-recovery.md` for delivery gates.

## Ownership and implementation

Grounded with the repository's reviewed KIROSHI grounding and architecture-evolution
skills. The implementation keeps the approved fixed contracts: Net shape, runner
kernel, TTMS policy, normal cell evaluation, production higher-order application,
and bidirectional compound synchronization. No KIROSHI mutation, default compiler
migration, commit, or push is part of this work.

The isolated functional-network compiler was ported from the inspected experimental
implementation (`755a305`, contract follow-up `f4d1d67`) in the compiler-cleanup
worktree. Its primitive definitions reuse Compiler 2's existing application handlers
and flat GUR. Direct compiler-only operators stay in compiler dispatch; they are not
misrepresented as runtime callables. Forwarding reuses existing TTMS-aware messages.

Responsibilities:

| Module | Owns |
| --- | --- |
| `experiments.functional-network.*` | Opt-in compilation, session extension, declared application interfaces |
| `experiments.synth.audio` | Finite note validation and declarative audio capability |
| `experiments.synth.overtone` | Owned DSP group and actual runtime audio effect |
| `experiments.synth.runtime` | Explicit source epochs, clock lifecycle, reload generation, bounded history publication |
| `experiments.synth.observation` | Child projection and composed runner instrumentation |
| XR visualizer | Full-context encoding, window presentation, inspector and timeline drawing |

The instrumentation extends the existing runner transform hook; the constructor
has no synth, observation, or history branch. It records selected application
occurrences, not every internal primitive or a DSP sample. History publication is
outside recorder scope, avoiding self-observation feedback. Labels come from the
existing dictionary, with a cache invalidated by binding-table identity.

Functional member-output cells come from the experiment's declared dictionary
metadata, not node degree. Shared dataflow projection accepts an interface inspector
and additional explicit boundary routes; the default remains unchanged. Ordinary
definition forwarding needs these routes so named output roots remain connected.

## Verification

Focused checks cover functional scalar/list returns, runtime apply, lexical capture,
late definitions, equivalent activation, positional output interfaces, repeated-call
ambiguity rejection, effect replay, TTMS withdrawal/recovery, functional child views,
finite audio limits, rejected eager audio, failed audio receipts, startup cleanup,
generation rejection, and real file-watcher replacement after an external edit.

The external-edit test changes melody frequency, confirms the next note reflects
it, then writes malformed source and proves the last working environment remains.
No real audio is needed for these checks; the controlled backend records requests.

Live verification exposed two full-context transport problems: recursive equality
of nested Net values in the codec cache, then recursive traversal after longer runs.
XR encoding now uses identity discovery, an explicit work queue, and shallow encoded
shape deduplication. A 3,000-level context test and actual server publication test
cover this path. The visualizer inlet detects reused immutable components without
deep Net equality. TTMS remains the observation transport, not a new stream type.

Desktop and 390×844 viewport checks displayed all four monochrome windows. Node
selection opened a live inspector. The mobile viewport was restored afterward.
LAN HTTP access was verified. These are browser checks, not a physical-phone or
WebXR-headset test. The Overtone adapter loaded against the actual dependency;
audible playback was not verified because `scsynth` is absent.

Checks actually run:

| Check | Result |
| --- | --- |
| `clojure -M:test functional-network graph.xr-inspection-test` | 279 passing assertions, no failures/errors |
| `node --test test/graph/*test.mjs` | 26 passing tests |
| `clojure -M:test` | 4425 passing assertions, 6 failures, 4 errors |
| Original projection/visualizer overlay: compound tracer + TTMS compound boundary | Same 1 failure and 4 errors reproduced |
| Original codec/inlet/projection overlay: websocket selection | Same 5 failures reproduced |
| `git diff --check` | Passed |

The full run used a stable source snapshot; the final additional deep-set ordering
guard was subsequently verified by the 279-assertion focused run. No assertions
were weakened or removed. The full suite is **not green**. Its remaining problems:

- `ttms-recursion-without-tracer-control` and `recursive-occurrences` exceed the
  existing 30,002-step verification budget (two errors).
- `domain-base-and-support-slots-are-not-outer-layers` and
  `domain-support-slot-is-not-an-outer-layer` reject domain structural slots as
  unsupported TTMS layers (two errors).
- `slot-retraction-is-visible-to-downstream-readiness` fails its existing assertion.
- `complete-chain-selection-clear-and-reselection-reach-websocket` fails five
  selection assertions in the existing eight-view example. The same failures occur
  with the original transport/projection; the cause remains unresolved.

Separate earlier checks of `propagators.relationship-dataflow-test` still had two
error-reason assertions failing. This case study does not fix those diagnostics.
Broader XR-runtime checks also exposed legacy trace/TUI failures; they are not
covered by the passing case-study result. The existing failures were neither
disabled nor repaired by changing the fixed TTMS/compound contracts.

## Measured performance

Final isolated comparison, no test processes or case-study server running, 50
measured ticks per mode after one warm-up tick:

| Recording | Total ms | ms/tick | Installed entries before/after | Retained samples | Omitted samples | Projected view characters |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| Off | 13074.82 | 261.50 | 1190 / 1190 | 0 | 0 | 17410 |
| On | 13441.87 | 268.84 | 1190 / 1190 | 2218 | 0 | 874611 |

Recording measured 2.8% higher per-tick cost in this single off-then-on comparison.
There are no repetitions/confidence intervals; JVM warm-up/order effects and other
machine activity are not controlled. This is useful local evidence, not a general
performance guarantee. Projected characters include history rendering data but
exclude full-context transport, browser drawing, and DSP/audio latency.

Two 200-tick runs, one warm-up tick each, silent backend:

| Recording | Total ms | ms/tick | Installed entries before/after | Retained samples | Omitted samples | Projected view characters |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| Off | 111233.48 | 556.17 | 1190 / 1190 | 0 | 0 | 15290 |
| On | 180036.02 | 900.18 | 1190 / 1190 | 920 | 7763 | 375689 |

These runs overlapped regression tests and live visualization on the same machine.
They establish no topology growth and bounded history, **not isolated overhead**.
The measured difference cannot be attributed solely to instrumentation. View-size
figures precede the final supported-value display improvement. The benchmark excludes
full-context encoding/network transport and browser rendering. Do not extrapolate
it to audio-rate performance. The previous scalar primitive bug was fixed before
these runs: domain arithmetic now uses existing TTMS scalar/layered procedure
composition rather than an observation wrapper intended for declarative views.

## Limits and remaining work

- Install/verify SuperCollider separately before an audible test; no installation
  or system audio settings were changed here.
- Existing recursive/compound TTMS regression failures remain outside the case-study
  approval. No fixed runtime change was made to suppress them.
- Full network context is lossless and can grow as the runtime retains data; the
  history limit is not a bound on the entire Net or transport size. The codec is
  stack-safe, not a promise of constant memory/latency.
- History marks inspect the **current** network, not an archived Net at that tick.
  Durations measure completed outer transitions, not hardware audio timing.
- Clock/TMS source changes are explicit. Equivalent source values do not create
  new epochs merely through rerunning an activation.
- A repeated same-operator/same-arguments occurrence selector remains ambiguous and
  fails explicitly; the example uses distinct applications.
- The production website extension console is not migrated to the experimental
  compiler. The whole-file watcher is the supported functional case-study path.

No new architectural component, hidden provenance dictionary, private recursive
executor, behavior stream, or support-specific compiler application contract was
introduced. Existing unrelated visualizer work in the dirty checkout was preserved.
