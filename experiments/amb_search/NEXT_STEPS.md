# AMB performance findings and follow-up experiments

These are research directions, not implemented guarantees or a production
migration plan. Preserve the ordinary FIFO runner and global merge semantics
while testing each idea independently. Do not infer branch pruning from smaller
stored no-good sets or infer a general speedup from these small fixtures.

## Findings to preserve

- [Pure search](PURE_ANALYSIS.md): precise failure support determines useful
  branch elimination. The opaque three-input predicate learns no exclusions of
  previously untested tuples in the measured domain. Learning itself is a small
  cost relative to propagation and layered value transport.
- [Workers](CONCURRENT_REPORT.md): conflict-free batches avoid reevaluation but
  do not demonstrate a speedup for these small activations. More worker threads
  do not remove redundant topology and synchronization work.
- [Indexed withdrawal](INDEXED_REPORT.md): directly reprojecting affected cells
  removes downstream invalidation activations. Index actual evidence and state,
  preserve alternative support and equal-version conflicts, and retire strictly
  obsolete evidence explicitly. Do not invent source versions.
- [Larger graphs](LARGER_CONSTRAINT_REPORT.md): tested gains increase with more
  downstream constraints, but the five-queen ordinary run is budget-limited and
  has no completed parity comparison.
- [Cell references](SHARED_SLOT_REPORT.md): returning existing cells saves slot
  synchronization in audited procedure/result boundaries. A raw leaf reference
  fails for a premised parent and for compound replacement. The regression
  records both failures and the correct existing guarded-slot behavior.

## First candidate: cache a slot in its owning context

Resolve `(owner, slot)` to a stable context-bearing cell or view. Repeated access
can return that same identity, allowing ordinary consumer construction to attach
directly to it. Cache the address relationship, with explicit rules for parent
replacement and declaration changes; do not cache a projected payload forever.

For a compound supported by `p` containing a field supported by `q`, an exposed
field must retain the appropriate `p` and `q` requirements. Membership guards
and transported premise-state knowledge remain separate from positive support.
Alternative observations, missing fields and equal-version conflicts must retain
the existing selection semantics. Blindly unioning all parent/child supports
would incorrectly conflate alternative evidence.

Two small alternatives deserve measurement before choosing a design:

1. **Shared guarded cell:** keep one existing membership-aware projection per
   context and reuse its cell ID for all readers. This can remove duplicate
   accessors without first redesigning premise transport. Measure whether the
   existing slot cache already covers a call site before adding another cache.
2. **Indexed contextual view:** store authoritative leaf evidence and context
   references, derive eligibility from their current states, and index consumers
   by the dependencies of both. This might avoid forwarding withdrawal messages
   through aliases. It still has to schedule affected consumers and keep cached
   strongest values consistent; aliasing does not eliminate those dependencies.

Use separate contextual identities when the same raw leaf appears under
independent parents. Never let retracting one context invalidate another valid
context. A changing parent may point to a different physical leaf; a stable
logical slot must then resolve the replacement without rewriting old snapshots.
Initially preserve the existing write-through behavior or explicitly scope the
new view to reads; arbitrary bidirectional aliasing requires its own conflict
and rebinding semantics.

Required checks before claiming general premised-compound support:

- Parent-only, child-only and combined dependencies; independent parents sharing
  a raw leaf; multiple alternative supports.
- Retraction, recovery, stale updates and equal-version conflicts, including
  support-only or membership-only changes with unchanged scalar values.
- Slot absence, replacement with a new leaf identity, nested parent contexts,
  aliases reached through multiple paths, and cyclic/shared compound structures.
- Exactly-once subscriber notification for one semantic update where the
  ordinary queue deduplicates it, plus unchanged quiescent reruns.
- Immutable earlier network/value snapshots and ordinary slot behavior parity.

Do not replace the general compound-slot implementation until these contracts
are established. The current direct-slot benchmark does not prove them.

## Other focused ideas

**Reuse application topology.** The remaining layered path constructs temporary
graphs on activation. A reusable plan for audited pure scalar applications may
reduce this cost. Keep the plan distinct from changing cell values, and define
invalidation when procedure layers, declarations or arity change. Start with a
single primitive and compare allocation and exact constraint-call traces. Avoid
an initialization-time snapshot of mutable procedure declarations.

**Expose smaller failure reasons.** Observe independently refutable constraints
before a completion gate adds unrelated premises, while retaining the success
gate. The transitive probe demonstrates why this can reduce search work. Test
the exact learned support and excluded untested assignments, rather than only
elapsed time or the number of stored no-goods.

**Measure the remaining allocation.** Reprofile the combined indexed/direct-slot
variant before choosing another optimization. Distinguish graph construction,
TTMS evidence merging/projection, scheduling and GC; allocation volume alone
does not establish time spent collecting garbage. Consider avoiding equivalent
projection reconstruction only with an explicit evidence/state dependency key.

**Revisit concurrency after reducing overhead.** Use expensive independent pure
operations with audited read/write contracts. Preserve conflict-free admission
and exactly-once evaluation; speculative reruns are outside the intended
experiment. Partition ownership or notifications for a distributed runner is a
separate experiment requiring explicit source-version and ordering semantics.

## Measurement discipline

Use one isolated benchmark JVM at a time. Keep fixture construction outside
the timer and index/plan initialization inside when it is required per run.
Alternate modes after warmup, retain raw samples, report allocation alongside
time and nested activation counts, and state the search stopping condition.
Check exact final solution, choices, knowledge and repair state; compare
constraint-call traces when changing evaluation structure. Report budget-limited
runs separately and do not compute completed-run medians or parity for them.

## Pre-commit verification, 2026-10-19

The four AMB experiment suites plus support collection, membership collection,
AMB learning/runtime and runner/runner-constructor suites passed together:
**75 tests, 1,429 assertions, zero failures/errors**. Clj-kondo checked 26 files
across the experiment directory, its four test namespaces and the two changed
support-collection files with zero warnings/errors. Whitespace checks passed.
The full repository and frontend suites were not rerun for these headless
experiments. The two added support-collection accessors are the only production
code changes; ordinary merge and runner policy remain unchanged.
