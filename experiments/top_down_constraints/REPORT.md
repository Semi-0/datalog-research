# Incremental constraint-first stack experiment report

Status: in progress. This report separates implemented work from proposed and
unverified work; it does not report final success.

## Proposed

Retain a premise-versioned network across refinements, update supported
constraint outcomes incrementally, compare against clean reconstruction, and
render validated occurrence stacks in the browser without collapsing semantic
node kinds or view state into the domain value.

## Implemented in this checkpoint

- A pinned high-level Lain composition with explicit work state and tail-position
  expansion (`stack-spec-2026-10-20-r4`).
- Declared fixture outcomes for distinct calls, sharing, cycles, deep expansion,
  partial tails, conflicts, ambiguous roots, withdrawal, and late refinement.
- Source-only closed-block archives with a capacity of 64, exact editable pane
  descriptors, fresh Not-run reopen, persistence validation, palette commands,
  and acknowledged API delivery with idempotent redelivery.
- A read-only checkpoint constructor with stable outcome IDs, revision checking,
  required and inactive counts, required-constraint satisfaction, fixture
  agreement, retained-node counts, commit history, and portable capability-free
  summaries.
- A clean revision-pinned semantic baseline created before the editor development
  implementation began. It records exact source hashes, controlled replay input,
  complete initial obligation membership, retained-network counts, and explicit
  timing limitations.
- An opt-in premise-versioned editor path with portable replay state, a separate
  live-session registry, generation-qualified commits, and a feature-local
  Javelin transition/effect/view boundary. Ordinary pane execution remains the
  default.
- Frozen capture readers, immutable traversal mechanics, support-aware stack
  fields, a normalized clean-reconstruction comparator, and a spatial stack
  scene/view with independent camera state.
- Explicit incremental outcome/evidence deltas with automatic invalidation when
  a depended-on premise is replaced.

## Actually measured

Focused correctness checks, the failed `struct` quiescence experiment, one cold
pinned semantic-baseline replay, and an r4 integrated replay have been run. No
warmup/measured incremental-versus-clean benchmark and no browser
request-to-visible latency samples have been collected.

The latest focused JVM checks passed for primitive readers/traversal (6 tests / 29
assertions), the recorded integrated mismatch (1 / 6), high-level design (1 / 38),
scene interpretation (2 / 10), constraint indexing (2 / 11), development runtime
(4 / 26), and clean comparison (1 / 3). The full JVM suite passed 6,419
assertions. The ClojureScript suite passed 78 tests / 693 assertions, the
production browser bundle rebuilt, and `cargo test --locked` passed two Rust
tests. A direct loopback API smoke test produced a ready one-plane `stackScene`
from a materialized stack. Browser visual acceptance was not available in the
current automation environment. These counts and observations are correctness
evidence, not performance samples.

## Failures and counterexamples

The r4 one-form-per-slot replay now admits and replaces all 21 implementation
definitions and constructs the frozen capture. Its final recursive stack result
remains pending because support is still lost/nested across the versioned wrapper
composition. This is a correctness mismatch and blocks stack acceptance. Direct
host traversal and supported direct-call traversal pass; they do not substitute
for the failing integrated path.

The first recursive `struct` candidate loaded but failed to quiesce when applied
to a concrete stack. It was removed and remains recorded in `DECISIONS.md`.

The earlier implementation session completed recovery code before it revised and
pinned the tail-recursive Gate B source. The new baseline starts from the clean
`f808b7a` revision after the specification was pinned, so it is the controlled
semantic starting point for subsequent edits. Its compilation and propagation
time were not separated and it is not a methodology-performance comparison.

Sandboxed loopback attempts failed with `java.net.SocketException: Operation not
permitted`; the same focused boundary suite passed outside the socket sandbox.

## Supported conclusions

The current evidence supports that recovery/checkpoint transitions are locally
testable, the pinned high-level composition is executable while its lower-level
obligations remain explicit, and a directly materialized stack can be interpreted
and served as a spatial scene. It does not support claims of integrated
incremental/clean semantic equivalence, performance improvement, complete stack
transformation, or visually accepted browser rendering.

## Remaining work

Resolve the supported-value/closure-boundary mismatch in the versioned recursive
composition, then verify incremental/clean agreement on the required edit
sequence. After that correctness gate, perform visual browser acceptance and the
prescribed alternating five-sample benchmark, and update this report from raw
artifacts. Any semantic mismatch blocks acceptance.
