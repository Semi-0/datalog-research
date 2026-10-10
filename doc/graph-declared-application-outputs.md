# Graph-declared application outputs

## Boundary and implementation

This change extends a propagator's graph output interface in immutable `Net`.
It does not change its activation arguments, constructor, readiness, scheduler,
cell merge, TMS, environment, or creation relationship semantics.

`network-builder/extend-propagator-outputs` validates the complete request,
ensures missing output cells, and installs reciprocal propagator/cell edges.
`network-patch/extend-propagator-outputs` exposes this operation as a declaration
effect. Only newly declared cells receive creation ownership from the emitter.
Existing cells and the target propagator retain their owners. Extension schedules
no activation and preserves existing values; duplicate additions are idempotent.

The compiler's returned-output scanner emits this declaration for each stable
member port on the owning flat-GUR application propagator. A port may exist
before its value is available. Late closures and linked-list tails extend the
same boundary. Named positions retain ordering and labels; graph connections
establish connectivity. Application inspection checks that named ports are
connected, and `outputs-of` reads graph edges without callable classification.

The graph interface includes the primary result and existing writeback edges.
Output completion remains explicit metadata; connectivity alone does not imply
that the output list is complete. Activation arity remains fixed.

## Verified behavior

Tests cover atomic invalid requests, reciprocal edges, missing and existing
cells, unchanged activation arguments and values, no scheduling, creation
ownership, duplicate extension, late list tails, late operator definitions,
empty argument cells, nested parent/child applications, stable reactivation,
generic output inspection, and compound-valued runtime neighbor projection.

Each test Var is timed independently with a 3,000 ms deadline. JVM startup is
outside that deadline. Accepted runs:

| Repository/check | Tests | Assertions | Failures/errors |
| --- | ---: | ---: | ---: |
| Infrastructure, full suite | 189 | 800 | 0/0 |
| Compiler, full suite, published infrastructure pin | 152 | 522 | 0/0 |
| Runtime, full suite, published owner pins | 159 | 578 | 0/0 |
| Maintained web integration suites | 32 | 163 | 0/0 |
| Research focused compiler/kernel suites | 193 | 699 | 0/0 |
| Research tracing suite | 8 | 29 | 0/0 |

No accepted individual test exceeded three seconds. Historical TUI tests and
unrelated performance work are outside this boundary.

## Tracer limitation

### Follow-up: redundant lowering fixed

Debugger investigation confirmed finite traversal: each of the two nodes was
expanded once. The compiler bridge emitted its complete lexical index on every
diff call, replaying 126,759 identical names. Incremental lexical export and a
single cell scan reduced this to 2,199 replays. The generated topology remains
9,769 nodes; no topology-size reduction is claimed.

The cyclic regression now completes within the individual three-second limit:
2.04 seconds against published owner pins and 2.49 seconds in the research tree.
Compiler full suite: 156 tests / 532 assertions. Runtime full suite: 160 / 585.
Research lowering/application/CPS/composition and cyclic tests: 24 / 96.
Maintained web integration suites: 32 / 163, pinned by web snapshot
`e14c1ee406d9eedd027da816e7a401fe32aff79d`.
All passed without failures or errors. See `doc/topology-effect-deltas.md`.

Follow-up owner snapshots are compiler `349c2069dd601d79b52efe98be56cc68f615724d`
and runtime `da004bf5b40954136a8f2d6feb284ca25558bf38`. GUR, lexical environment,
runner, scheduler, merge and TMS implementation remain unchanged. Research-only
topology-result metadata exports are preserved. No KIROSHI model mutation was
performed. The earlier limitation below records the pre-fix baseline.

An exploratory full Lain tracer traversal of a two-node cyclic graph exceeded
three seconds. The equivalent constructor-only graph also exceeded the deadline
with previously published dependencies. Separating bootstrap did not resolve it.
The exploratory fixture was removed; no successful two-node end-to-end tracer
result is claimed. Graph registration, the compound neighbor primitive, and the
existing one-time tracer execution/disposal case are verified independently.
General tracer performance needs a separate investigation.

Frozen snapshots expose the topology at their creation time. Periodic tracing
must take fresh snapshots to see late edges. No automatic topology subscription
was introduced.

## Delivery

Implementation branches use `codex/graph-declared-application-outputs`; main is
unchanged. Owner commits:

- Infrastructure: `12112d2bd937dd968f143bcd85f9656ed026ddb3`.
- Compiler: `1e288c27e302521c665abf8d38f5eb8310a046c8`.
- Runtime: `1885deb38d6c43d3f70ff82ffc4c1ec03b90b73d`.
- Web: `b0d1f41a617ca9394cab40a674f9c99cd71d1810`.

Compiler pins infrastructure; runtime pins infrastructure and compiler; web pins
all three. The research tree mirrors the scoped implementation and tests.
The original analyzer experiment and `.agent-memory` remain untouched.

## KIROSHI evolution evidence

Grounding at model revision 511 had no omitted facts. The existing network-state,
application-runtime, and recursive-topology-declaration component boundaries
remain unchanged. No components, parentage changes, or supersessions are proposed.

Prepared candidate identifiers:

- `:constraint/application-output-boundary-is-graph-topology`.
- `:decision/application-outputs-extend-net-graph`.

These candidates preserve delayed topology declaration, composition over
materialization, structural relationships, deterministic identities, and
pull-only compound observation. Evidence is the owner snapshots above, their
focused tests, and the verified research mirror.

Candidates remain drafts, unpersisted and unapproved. Automatic approval review
rejected the proposal-validation CLI operation as a possible database write;
it was not retried. No model mutation or supersession was performed. Older model
facts describing accumulating application remain an explicitly unresolved model
reconciliation issue outside this implementation.
