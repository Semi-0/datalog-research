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
  expansion (`stack-spec-2026-10-20-r1`).
- Declared fixture outcomes for distinct calls, sharing, cycles, deep expansion,
  partial tails, conflicts, ambiguous roots, withdrawal, and late refinement.
- Source-only closed-block archives with a capacity of 64, exact editable pane
  descriptors, fresh Not-run reopen, persistence validation, palette commands,
  and acknowledged API delivery with idempotent redelivery.
- A read-only checkpoint constructor with stable outcome IDs, revision checking,
  required and inactive counts, required-constraint satisfaction, fixture
  agreement, retained-node counts, commit history, and portable capability-free
  summaries.

## Actually measured

Only focused correctness checks and the failed `struct` quiescence experiment
have been run. No warmup/measured incremental-versus-clean benchmark and no
browser request-to-visible latency samples have been collected.

The full Clojure suite passed 6,419 assertions with zero failures or errors,
including the loopback HTTP tests run with localhost access. The ClojureScript
suite passed 73 tests / 671 assertions, the production browser bundle rebuilt,
and `cargo test --locked` passed two Rust tests. These counts are verification
evidence, not performance samples.

## Failures and counterexamples

The first recursive `struct` candidate loaded but failed to quiesce when applied
to a concrete stack. It was removed and remains recorded in `DECISIONS.md`.

This implementation session completed recovery code before it revised and pinned
the tail-recursive Gate B source. Gate A's recovery constraints already existed,
but this order deviates from the experiment's stricter global sequence. The
development checkpoint is therefore not a valid methodology-performance baseline;
comparative measurement must begin from a fresh, revision-pinned replay.

Sandboxed loopback attempts failed with `java.net.SocketException: Operation not
permitted`; the same focused boundary suite passed outside the socket sandbox.

## Supported conclusions

The current evidence supports that recovery/checkpoint transitions are locally
testable and that the pinned high-level composition is executable while its
lower-level obligations remain explicit. It does not support claims of localized
constraint recomputation, incremental/clean semantic equivalence, performance
improvement, complete stack transformation, or correct spatial rendering.

## Remaining work

Implement and verify frozen public scope readers, traversal state, recursive
constraint maintenance, clean replay comparison, and renderer/browser behavior.
Then run the prescribed alternating five-sample benchmark and update this report
from raw artifacts. Any semantic mismatch blocks acceptance.
