# Indexed premise withdrawal for pure AMB

2026-10-19, Java 27. A dedicated experimental runner maintains an immutable
premise-source-to-cell index inside Net's dictionary. Cell constructors,
contents, cached strongest values, and public accessors retain their interfaces.
The ordinary runner and ordinary merge policy are unchanged.

## Execution

The runner initially indexes actual supported cell content, including references
in inactive evidence and transported state. Subsequent messages update only
the affected cell's index entries. This is an auxiliary index, not a replacement
for authoritative cell evidence or an append-only dependency log.

When a monitored AMB source publishes a newly accepted retraction:

1. Ordinary TTMS merging determines the source's actual current version/state.
   Stale updates cannot create a newer withdrawal; equal-version conflicts
   remain explicit.
2. The index identifies supported cells that reference that source. Each receives
   the actual state directly through ordinary cell evaluation. Cached strongest
   values are reprojected without rerunning their producing arithmetic.
3. The experimental path explicitly retires evidence whose source versions are
   strictly superseded. State knowledge is preserved. Earlier immutable network
   snapshots remain unchanged, and same-version conflicting evidence is retained.
4. Once all direct projections are updated, notifications are admitted. A known
   pure derived activation can be omitted only when its original function and
   graph interface are unchanged, its result is already nothing, its output
   contains the exact withdrawal state, and its relevant input is unusable.
5. Require, learning, strategy, and unknown activations still run normally.
   Replacement values and recovery use ordinary propagation.

The tested omissions are the builder's layered calculations and pending switch.
The runner does not skip arbitrary activations based solely on declared edges.
No speculative execution, rerun, new source epoch, or no-good logger is used.

Two named operations were added to the support-collection abstraction:
`referenced-sources` for index construction, and explicit `retire-superseded`
for retiring old versions. Ordinary merging does not call the latter.
Without retirement, old inactive evidence with a larger support can dominate a
new smaller-support derivation after one alternative is withdrawn. Tests verify
that the surviving alternative recomputes its consumer with the correct support.

## Matched measurements

One warmup and five samples per mode/domain, alternating ordinary/indexed order.
Both modes use the exact same built immutable network and predicate. Cache is
disabled. Setup of the common fixture is excluded, but index initialization and
maintenance are included. Every measured run checks the independent answer
oracle and exact equality of final choices, repair request and learned knowledge.
All runs completed; domain 4 exhausts and domains 5 and 8 return `[3 4 5]`.

| Domain | Ordinary median | Indexed median | Time reduction | Ordinary activations | Indexed activations |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1..4 | 1,191 ms | 916 ms | 23.1% | 14,239 | 9,388 |
| 1..5 | 949 ms | 722 ms | 23.9% | 12,223 | 8,065 |
| 1..8 | 2,889 ms | 2,215 ms | 23.3% | 37,087 | 24,382 |

For domain 5, 54 withdrawals cause 216 direct cell projections and avoid 162
outer derived activations. Their nested propagation also disappears, reducing
total activations by 4,158 (34.0%). Cumulative current-thread allocation falls
from 1.411 GB to 1.083 GB. This measures allocation traffic, not retained heap.

The sample ranges do not overlap within these three paired fixtures, but these
remain small single-JVM experiments. They do not establish performance for
arbitrary programs, larger domains, distributed execution or live editor loads.
The extra alternative-support handling is included in these final measurements;
earlier prototype measurements are excluded.

## Why considerable cost remains

A separately instrumented domain-5 sample takes 929 ms rather than the ordinary
benchmark's 722 ms because of diagnostic overhead. Exclusive/self times are:

| Boundary | Calls | Self time |
| --- | ---: | ---: |
| Patch application, excluding separately timed TTMS work | 7,231 | 328 ms |
| Anonymous propagator activation | 1,644 | 172 ms |
| TTMS strongest projection | 1,420 | 134 ms |
| Layered application | 219 | 85 ms |
| TTMS evidence merge | 1,424 | 38 ms |
| No-good learning | 272 | 16 ms |
| Inverse-index maintenance | 1,838 | 14 ms |
| Constraint primitive activation | 55 | 1.5 ms |

The profile subtracts measured children from parent self time; inclusive totals
overlap and must not be summed. The inverse-withdrawal operation takes 32 ms
inclusive of its direct cell updates, projections and retirement.

There are still approximately 147 activations per usable tuple, amortized across
initialization and the complete search. Replacements still run normal layered
procedures. Those procedures transport base, support and state through internal
slots and propagators, merge messages, construct projected compound networks,
and schedule work. `support-collection/project-layers`, for example, constructs
a small Net with cells for the projection layers on every strongest projection.

The measurements identify representation and propagation granularity as the next
large target. Reducing layered scalar execution overhead could help more than
optimizing the already small no-good or index operations. This experiment does
not implement that separate change or attribute all allocation cost to GC.

## Verification and limits

Focused checks passed **38 tests / 1,003 assertions**, zero failures/errors.
They cover direct chain invalidation, newer-version recovery, old snapshot
immutability, surviving alternative evidence, strictly obsolete retirement,
stale withdrawals, equal-version conflicts, valid-only AMB publications,
ordinary-runner reruns, and existing support, learning and runtime contracts.
Lint and whitespace checks passed. The full repository suite was not run.

The pilot monitors the builder's three AMB choice sources, directly stored TTMS
collections, and its audited pure derivations. It is not installed in production
or generalized to arbitrary membership collections, scoped nested cells, dynamic
cell deletion, effects or distributed source registries. The current cell API
remains intact, but globally indexed invalidation is a separate execution policy
that must be selected explicitly.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.indexed-benchmark 5 4 5 8
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.indexed-profile
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M:test propagators.experimental.runner.amb-indexed-test propagators.support-collection-test propagators.experimental.amb-learning-test propagators.experimental.amb-runtime-test propagators.runner-test
```

Run diagnostics in isolated JVMs: counters and timers redefine functions for
measurement. [INDEXED_RESULTS.edn](INDEXED_RESULTS.edn) stores final paired samples;
[INDEXED_PROFILE.edn](INDEXED_PROFILE.edn) stores the phase profile.
