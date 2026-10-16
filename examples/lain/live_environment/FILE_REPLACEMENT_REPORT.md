# File-scoped retirement/replacement experiment

Status: **partial implementation; general callable-caller retirement blocked**.
The original working-tree experiment was verified against HEAD
`ed61cf9ee3d058d5331accc3d1b6ab278f5a8790` on 2026-10-16
(Europe/Amsterdam), before publication. It does not enable a production default
or mutate KIROSHI.

## What works

One standard runtime session can host independently loaded files. A successful
scalar-file replacement withdraws its old contributions, removes receipted private
topology, compiles a new private generation, and publishes through stable shared
bindings. Existing dependent files run again; they are not recompiled.

```clojure
(require '[propagators.compiler-2.runtime :as runtime]
         '[examples.lain.live-environment.files :as files]
         '[propagators.experimental.visualization.data :as data])

(def session (runtime/new-session))
(def producer "examples/lain/live_environment/producer.lain")
(def consumer "examples/lain/live_environment/consumer.lain")

(files/replace-file! session {:file producer})
(files/replace-file! session {:file consumer})
(data/payload (files/value-at session 'answer)) ; 22

;; This explicit source stands in for editing producer.lain to x=20.
;; Omit :source to read the actual file at each replacement.
(files/replace-file! session {:file producer :source "(define x 20)"
                              :expected-revision 1})
(data/payload (files/value-at session 'answer)) ; 42

(files/remove-file! session producer)
;; answer is unusable, with withdrawal propagated through the existing network.
```

No automatic watcher is included. A watcher/editor can call this same function;
the real-file test writes, rereads, and replaces a temporary .lain file. This is
not a whole-environment rebuild, TCP peer, process launcher, or new session type.

## Ownership and contracts

All exports are top-level `define` bindings. Duplicate exports and attempts to
overwrite another file's binding or a primitive binding fail. Forward references
receive stable public cells, so a consumer can load before its producer.
Non-export free names alias existing bindings through the existing environment
API. Private environments also receive the existing implicit-return `->` binding.
Empty/comment-only files are normalized to a private no-export `nil` program.

```clojure
;; Existing contracts used at the boundary:
(runtime/new-session)
(runtime/install-session-extension! session bundle context)
(files/replace-file! session {:file file :source source
                             :expected-revision revision})
(files/remove-file! session file)
(files/snapshot session)
(files/value-at session 'binding)
```

`source.clj` inspects ordinary parsed IR for names; it does not change compiler
lowering. `files.clj` uses the existing compiler and environment declarations.
`publication.clj` composes supported procedures and explicit premise withdrawal.
`ownership.clj` observes completed runner transitions through the existing hook,
preserving any caller transform. Its execution-local receipt atom is outside Net
and is not captured by installed activations.

Receipts associate actual installed outer nodes with file-generation lifetimes
and exact activation identities. Selected callable support can add the defining
file's generation to a call body's lifetime. These are installation receipts,
not an inference that all reachable topology belongs to the file.

`retirement.clj` validates receipts, rejects surviving propagator/value references
to removed nodes, prunes managed dictionary references and graph edges, and
projects path-qualified relationships. It reconstructs all four immutable Net
fields. Existing immutable snapshots are not rewritten. Retained inactive TTMS
evidence may still contain historical values; it is not live topology.

Candidate preparation never mutates the live session. File operations serialize
under the session lock. A compare-and-set commit also rejects an intervening update
from a non-file runtime operation, preserving that update rather than overwriting
it; callers can retry against the new state. Both replacement and removal have
an adversarial test for this case. Failed parsing, compilation, revision checks, ownership checks,
or retirement leave the identical prior state object installed. Unchanged source
is an exact no-op. Revisions survive file removal; source epochs advance globally
within this experimental session and are not reset by re-adding a file.

This is a **headless** lifecycle prototype. Declared external-effect bindings and
new boundary-effect requests are rejected rather than queued for future execution.
External-effect delivery/retirement needs a separate reviewed lifecycle contract.
Arbitrary effectful host extensions are not a sandboxed capability.

No Net shape, compiler, GUR, runner constructor, cell evaluator, compound sync,
layered dispatcher, TTMS policy or session-extension contract changed.
New handwritten functions have at most four arguments. The observer currently
compares outer environments after each transition; it is not a production-cost
installation index. Public tombstones and source/revision history are retained.

## Why general callable retirement is not finished

The earlier adapter correctly computes supported and higher-order calls. The new
file tests initially also produced the correct answers after replacing a callable,
but physical retirement exposed two ownership questions:

1. Two demanded imported-operator cells were removed under the invoking call's
   mixed lifetime, then recreated with the same IDs by the replacement body.
   They hold existing `+` and `->` operators, not obsolete arithmetic results.
   Installation timing alone does not decide their true shared lifetime. A narrow
   same-ID rule now handles this case: only exactly equal immutable Cell records
   may be classified as `:reused`; propagators and changed cells are rejected.
   The receipt's `:retired` set excludes those unchanged cells, and a subsequent
   audit rejects any active value or dictionary reference to actually retired IDs.
   Tests check equality of reused cells and absence of all obsolete private nodes.
2. Calling a closure creates caller-owned depth/scope-chain accessor registrations
   inside the **defining** environment's compound Net. Replacing the caller leaves
   that surviving environment's `:slot-index` and refined-slot index referring to
   the old caller cells. These are real external accessor references, not merely
   equal local node IDs in another nested Net.

The current compound implementation exposes accessor declaration and refinement,
but no detach contract. Blindly deleting whole surviving environments would break
other callers. Ignoring these references would leave dangling topology. Disabling
the audit or keeping all obsolete nodes would not meet physical-retirement goals.

The experimental runtime therefore rejects unsafe caller replacement before commit.
Replacing a callable definition updates two surviving callers without recompiling
them. Repeated definition replacement has constant live node counts. Replacing a
file containing a bidirectional cycle and a private compound-list computation now
also passes under the unchanged-cell reuse rule.
The desired callable and returned-network acceptance tests remain in the suite,
with `:retirement-gap` metadata; they are **not** replaced with weaker expectations.

Next boundary review must define exact removal of caller-owned accessor
registrations in a surviving
compound environment, preserving other callers, canonical information and cycles.
No compound synchronization or GUR repair was made below the approved boundary.

## Verification

Required checks: successful lifecycle semantics, physical topology removal,
unchanged dependent activations, atomic failure, TTMS withdrawal/recovery,
callable/higher-order lifecycle, cycles, existing runtime regressions and static
scope/arity/whitespace checks. Verified on 2026-10-16 (Europe/Amsterdam) against the HEAD
above plus the files that were uncommitted at verification time.

| Check | Tests | Assertions | Outcome |
|---|---:|---:|---|
| File environment acceptance | 9 | 65 | 63 pass, 2 errors: callable-caller retirement |
| Retirement robustness/safety | 18 | 76 | All pass |
| Previous supported/higher-order callable feasibility | 8 | 56 | All pass |
| **Complete experiment** | **35** | **197** | **195 pass, 0 failures, 2 errors** |
| Maintained focused regressions, evaluator enabled | 160 | 1,526 | All pass after authorized socket retry |
| Full repository suite | — | — | Not run |
| Handwritten-function arity | 9 Clojure files | — | No function above four arguments |
| Tracked/new-file whitespace and conflict markers | — | — | No diagnostics |

The first sandboxed regression run passed 1,514 assertions and hit three
`SocketException: Operation not permitted` errors in existing server/file-watcher
tests. The authorized retry passed all 1,526 assertions. These errors are separate
from the two genuine, still-unmet file-retirement acceptance cases.

The focused regression run binds `runner/*advance-transform*` to the experimental
application transform and calls `clojure.test/run-tests` on these maintained roots:

```clojure
'[propagators.compiler-2-functional-network-test
  propagators.compiler-2-functional-network-gur-test
  propagators.compiler-2-functional-network-contract-test
  propagators.compiler-2-rest-parameters-test
  propagators.compiler-2-closure-frame-test
  propagators.compiler-2.runtime.session-extension-test
  propagators.compiler-2.runtime.session.file-loader-test
  propagators.compound-object-network-slot-test
  propagators.support-collection-test
  propagators.support-transport-test
  propagators.premise-transport-test
  propagators.layered-support-test
  propagators.event-test]
```

The complete experiment command deliberately includes unresolved acceptance tests:

```sh
clojure -M:test propagators.experimental.file-environment-test \
  propagators.experimental.file-retirement-safety-test \
  propagators.experimental.live-environment-test
```

Scenarios include scalar chains, late producers, export removal/restoration,
stable IDs, old snapshot immutability, unchanged caller activations, repeated
replacement topology counts, multiple sessions, conflicting owners, foreign
writes, stale and competing revisions, canonical path aliases, actual file edits,
invalid/empty sources, foreign propagator references, changed activation receipts,
path-sensitive relationship pruning, preservation of caller instrumentation and
safe rejection of unsupported callable retirement.

The documented producer/consumer example was also run from the actual example
files: `answer=22`, replacement gives `answer=42`, with 30 private nodes retired.

## Checkpoint compatibility pass (2026-10-16)

The later combined changed-test selection ran 309 tests: 2,982 assertions passed,
zero assertion failures, and three errors. One error was a sandbox-denied HTTP
socket bind; the authorized isolated editor retry passed all 49 assertions.
The other two errors remain the callable-caller and returned-network retirement
acceptance cases documented above. They are not suppressed or reclassified as
successful tests. The separate parser suite passed all 339 assertions.

This pass fixed pending layered callback application by loading the existing
supported-callable handler in the explicit layered environment. Snapshot contents
now preserve the same graph opacity as sampled values, preventing nested observer
feedback. Experimental peer port declarations emit the existing transactional
name-binding effects instead of relying only on recording-view dictionary writes.
Stale dataflow, compiler naming, parser and error-message expectations were
updated; the raw-application negative control explicitly isolates raw dispatch
from optional handlers loaded by other suites.

These compatibility fixes do not implement shared-environment accessor detachment.
Unsafe caller retirement still leaves the live session unchanged. The complete
repository suite is not claimed green.

## Grounding and remaining slices

The read-only KIROSHI Question Model reported revision 616, `:grounded`, with 11
omitted facts. Missing evidence was disclosed for compiler materialization,
premature materialization, process-runtime creep and lexical-fast-path authority.
The bounded model is not exhaustive verification. Reviewed constraints preserve
immutable Net state, explicit execution crossings, runtime-owned effects and
semantic composition outside the runner constructor. No model sync is proposed.

1. Review and specify compound accessor detachment and shared-lifetime ownership
   for **caller** retirement. Preserve other callers and bidirectional semantics.
2. Implement only after that boundary is approved; make both remaining acceptance
   tests pass, including higher-order returned callables.
3. Add automatic source watching/terminal commands using this transaction API.
4. Review external-effect delivery/retirement separately; do not silently drain
   requests from failed or retired generations.
5. Measure activation/latency costs and expand regression coverage. Live topology
   growth is checked, but this report makes no performance improvement claim.

The broader repository suite is not claimed green; this slice runs focused
regressions and preserves visible failing acceptance cases. No verification
receipt was submitted to KIROSHI at verification time; the new artifacts were then uncommitted.
