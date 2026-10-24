# Merge-rule removal

Implementation and verification are recorded in [REPORT.md](REPORT.md).
Machine-readable counters and verification summaries are under [runs/](runs/).

The accepted endpoint is complete removal of temporal behavior and event APIs,
with supported runtime consumers migrated to explicit operations and TTMS.
No permanent legacy installer is planned. Reducer-cell and reducer-subnet remain:
compound reduction and dispatch selection use reducer-subnet.

## Protocol

1. Inventory dispatch and strongest projection, producers, consumers, and precedence.
2. Add fixtures for intermediate information, not only final rendering.
3. Migrate producers and readers, then remove the obsolete registration.
4. Compare support, withdrawal, contradictions, graph identities, and frozen history.
5. Remove compatibility implementations only after supported consumers migrate.

Run the audit only in a dedicated JVM. It temporarily wraps multimethods and
runner functions and restores them in `finally`; it must not run in the editor.

```sh
clojure -Sdeps '{:aliases {:audit {:extra-paths ["test"]}}}' -M:audit -m experiments.merge-deprecation.audit
```

Before removal of the graph method, add `--graph-default-fallback` to measure
ordinary merging while computing the original result for comparison. Once that
method is removed this mode has no graph method to substitute; use the baseline
records and semantic fixtures for comparison. Audit output contains only data,
identifiers, input key categories, counts, and test summaries, never live Net
instances, functions, or captured capabilities. Generated IDs are diagnostic,
not semantic comparison keys.

## Observed baseline

On committed source `2e2e26a6f6e5a1e4cb57ae4e69d10e569365f959`, isolated
instrumentation observed these graph-method call counts in the current session:

| Suite | Calls | Assertions |
| --- | ---: | ---: |
| Editor network replay | 256 | 56 |
| Recursive semantic views | 89 | 18 |
| Semantic gallery | 1023 | 538 |
| Semantic traversal | 4114 | 60 |

All 672 assertions passed. Ordinary fallback also passed those assertions, but
produced different, contradictory intermediate merge results in 175 history,
236 gallery, and 1229 traversal calls. These are terminal-observed baseline
summaries, not persisted raw samples or benchmark measurements.

The new producer-aware harness identified `:compiler-2/call-graph-application`
as the producer for every graph-rule event in the recursive semantic suite.
Several applications previously wrote directly to the same caller graph cell.

## Current implementation

- Realized call facts now occupy independently addressable contribution cells.
- An explicit graph-union propagator reads authoritative contributions and
  publishes the composed graph through supported information.
- Contributions carry revision signals so replacement and withdrawal wake union.
- Repeated equal publications do not advance revisions.
- Call-graph readers use `graph-union/graph-of` to read the public payload.
- Periodic trace refresh uses TTMS revision publications. Trace API readers keep
  returning graph payloads.
- Global semantic graph and epoch merge registrations have been removed.
- Runtime clock ticks and retractions use TTMS with dedicated stable NodeId
  sources; the injection cells are installed in the runtime network.
- Temporal behavior and event implementations, their compiler APIs and global
  merge/strongest methods, and the behavior-only benchmark have been removed.
- Remaining widget, bridge, display, list-text, and trace sources publish TTMS.
  Source identity includes the input port where one owner has independent inputs.
- Primitive composition preserves membership and support. A switch with mixed
  revisions of a shared source remains pending until the inputs agree.
- Cell history folds immutable snapshots explicitly instead of retaining event
  facts in the general merger. Reducer-subnet folds ordinary public source slots.
- Shadowed direct event/behavior/TMS routes and the unused installation flag are
  gone. The registry has eight methods: default, named-network, closure,
  reducer-cell, reducer-subnet, distributed-tms, support-collection, membership-collection.

Dependency, scope-source, and intensity are the three retained fallback handlers.
They have distinct partial-information semantics and tests; this change does not
claim that all remaining routes are experimental or safe to delete.

## Decisions and counterexamples

- The old graph rule performed domain composition implicitly during transport.
  Explicit composition owns graph union; TTMS owns validity and revision changes.
- Empty union is normalized explicitly with an empty graph contribution. The old
  zero-input union returned nil aliases, whereas transporting it through the old
  merge rule changed aliases to an empty map. Normalization no longer depends on
  a transport side effect.
- An inbox containing only contribution-cell IDs failed to wake on refinement.
  Revision signals fix this; authoritative data still comes from those cells.
- TTMS supports require NodeId source identities. Historical clock identifiers
  can be vectors, so clock source identity is normalized at its publication
  boundary and backed by a dedicated installed injection cell.

## Verification limitations

A detached clean checkout at `/tmp/merge-baseline.CC5FF9` reproduced the existing
trace-cycle mock arity error and the server suite's 8 failures / 3 errors.
The combined baseline run had 8 failures / 4 errors. These failures are not
accepted as regressions or repaired by weakening assertions. The migrated
supported-publication trace fixture replaces the obsolete implicit-graph/epoch
fixture while retaining its edge and node assertions.

Artifacts and implementation changes are local, uncommitted, and unpushed.
