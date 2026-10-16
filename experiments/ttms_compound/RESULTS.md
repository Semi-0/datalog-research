# Experiment results — 2026-10-16

## Implemented

- Ordinary one-way sync (`->`), bidirectional sync (`<->`), application identities,
  and compound slot routes now transport contradictions with their provenance.
- TTMS conflict transport retains the current supporting observations instead of
  introducing a synthetic joint conflict claim that can block later recovery.
- Opt-in bidirectional supported-compound slots compose ordinary TTMS evidence
  merges and preserve original source timestamps and membership support.
- An opt-in reducer-cell collector retains original observations and projects
  history, current records, current support, and a per-source state frontier.
  Its explicitly owned archive cell uses the existing TTMS recording mechanism.

## Observed behavior

The runnable demo begins with `{:x 10 :y 20}`. A fresh version `{:x 7}` removes
`:y` from the current compound. Delayed old feedback through the `:y` accessor
does not restore it. Retraction removes both current slot values; recovery with
`{:x 99}` restores only `:x`. All four original source versions remain in history.

Repeated execution reaches a fixed point. The demo's outer runtime node count is
11 before and after all four versions. This is a topology check for this fixture,
not a bounded-memory claim: intentionally retained history continues to grow.

Tests also cover nested access, independent membership, unchanged payloads with
newer support, compatible same-version refinement, conflicting same-version
values, provenanced contradictions, semantic graph values, and network values.
History merge is checked for associativity, commutativity, and idempotence over
the finite tested set. These checks are not a proof of a general compound algebra.

## Counterexample

A transparent reducer result changes from `[7 8]/{A@1,B@1}` to `[8]/{B@1}` while
its state frontier advances to `{A@2-retracted,B@1}`. Native TTMS drops the new
B-only observation under the older joint support, then projects the old payload
as stale. The observed result is `the-nothing`, not `[8]`.

This is deliberately exposed by the demo and a named counterexample test. The
prototype does not claim to repair native TTMS dominance. The archive-source
boundary is explicit and preserves original provenance; it is not a generic fix
for transparent reductions whose dependency sets shrink.

## Validation

- `clojure -M:test propagators.sync-contradiction-test propagators.support-transport-test propagators.observation-transport-test ttms-compound`: 584 assertions passed, zero failures/errors.
- `clojure -M -m experiments.ttms-compound.demo`: completed; stale feedback was
  rejected, reruns reached a fixed point, and runtime node counts were `[11 11]`.
- Changed-source/test lint: zero errors, zero warnings.
- `git diff --check`: passed.
- `clojure -M:test propagators ttms-compound`: 4,205 assertions passed, 10 failed,
  zero errors. All 10 failures reproduce with the pre-change sync files: one
  definition-receipt parser assertion, five compound-levels graph/call-label
  assertions, and four XR executable-chain assertions. These earlier parser and
  tracer changes were not altered as part of this work.

The pre-change sync overlay lives only under `/tmp/lain-sync-before`. The baseline
runner printed its loaded resource paths to verify that it used those old sync
files. The user's working tree was not reset or stashed for baseline checks.
Changes remain local; nothing was committed or pushed.
