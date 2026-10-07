# Functional-network production migration

## Ownership and fixed contracts

`lain-compiler` owns the source parser, named CPS handlers, declaration receipts,
canonical callables, linked-list apply, returned-member ports, and versioned
definition projections. `lain-runtime-clojure` owns source loading, export,
block compilation, and effect integration. `wired` owns syntax-sensitive TUI
fixtures, generators, and read-only rendering adapters.

Generic GUR, compound-object topology, cell merge, TMS semantics, scheduling,
the live environment kernel, immutable extension API, and external effect
execution remain fixed. This migration creates no new architecture components
or parent assignments.

## Replacement contracts

```clojure
(define increment (network (x) (+ x 1)))
(define answer (apply increment (list 41)))
answer
```

The shared reader/parser and named CPS dispatch compile these forms directly.
Every expression returns a cell. `define` reserves/refines a lexical target and
returns a receipt; an omitted source waits, while an explicit `nil` is a value.
The body is compiled when flat GUR applies its callable in the active `Net`.
Returned live linked-list members become stable positional ports. A late tail
adds only the missing ports; scalar and empty returns have no member ports.

Returned input members project back to caller cells. Versioned calls use the
same compiler with premise-supported projections at those returned crossings.
Candidate input projections exclude their own output feedback while preserving
outer evidence and unrelated caller support. This prevents a retired candidate
from pinning its successor's result.

## Verification state

Implementation was verified on `codex/lain-functional-production`. Delivery is
ordered compiler, runtime, wired, then the research mirror; downstream revisions
are pinned to the corresponding published owner commits.

After behavior deprecation, the compiler suite passed 147 tests / 464 assertions and the shared runtime suite
passed 158 tests / 572 assertions. Focused web routing and graph label checks
passed 6 tests / 72 assertions. Shared-session replacement and watch regressions
now pass after keeping the rebuilt root network and its environment together.
The runner measures every test var separately and rejects durations
over 3,000 milliseconds; namespace/JVM startup is reported separately.

The unmodified compiler baseline passed 67 tests/238 assertions. The runtime
baseline passed 147 tests/527 assertions. The downstream TUI baseline stopped at
its three-second threshold in `web-clients-route-through-coordinator-model`;
that limitation is separate from syntax migration correctness.

The previous demo blocker is resolved by the separately authorized temporal
behavior deprecation. Active session roots now use TMS-only bindings and the
demo joins ordinary cells without a behavior reducer. Historical behavior APIs
remain deprecated, explicitly opt-in paths; see [deprecation details](behavior-deprecation.md).
Terminal TUI repair remains excluded. Browser verification subsequently succeeded
through the localhost web endpoint: canonical network compilation, visible graph
labels, and a slider event acknowledged by the runtime. The maintained web suite
passed 32 tests / 163 assertions, browser-side tests passed 14/14, and mirrored
server/assembly checks passed 13 tests / 53 assertions. The broader mirrored
compiler run passed 187 tests / 622 assertions. Every Clojure test Var finished
below three seconds. See `web-functional-network-corrections.md`
in the wired owner repository for the complete correction evidence.

## KIROSHI reconciliation

Read-only grounding consulted the architecture-evolution skill and current
compiler/declaration/application boundaries. At model revision 396,
`:constraint/merge-never-silently-deletes-evidence` remained active/current and
supported. Its requirement is preserved at outer cell merge and retraction;
candidate projections never replace outer content.

Prepared, unpersisted candidates:

```clojure
#{:decision/lain-functional-network-syntax-is-canonical
  :constraint/compiler-expressions-return-cells
  :constraint/compiler-definitions-refine-named-cells
  :constraint/compiler-network-outputs-follow-body-return}
```

Replacement declaration, application, and syntax evidence must identify verified
Git snapshots after delivery. No candidate has been proposed, approved, or used
to supersede a current model fact.
