# Propagator infrastructure — candidate System Model

Prepared on 2026-10-18 (Europe/Amsterdam) from the current checkout. This is a
reviewed 51-fact proposal, now recorded in the repository's Kiroshi database.
Normative facts remain candidates; the replaced flat-GUR decision is retained
as superseded history. The complete proposed relational model is in
[propagator-infrastructure-proposal.edn](propagator-infrastructure-proposal.edn).

Revision 2 incorporates the user's explicit architecture requirements on
2026-10-18. The original 38-fact proposal is preserved in
[propagator-infrastructure-proposal-v1.edn](propagator-infrastructure-proposal-v1.edn).

## User-directed architecture requirements — revision 2

The following eight new Constraints record the requested direction. They are
motivated by `principle/propagator-compositional-extension` and have explicit
component applicability, evidence and independent verification methods. Source
inspection and the checks below do not imply that every existing path already
conforms.

| Constraint suffix (`constraint/propagator-*`) | Requirement |
|---|---|
| `compound-access-composition` | Avoid materializing compound objects for semantic evaluation. Prefer propagator composition over unwrap chains; use slot-accessor propagators to access compound fields and retain reactive connections. Explicit host serialization stays at its boundary. |
| `cross-network-runner` | Prefer network-runner/evaluator extensions for cross-network communication, with explicit messages and boundaries. |
| `cps-extension` | Prefer the CPS combinators assembled by `cps-core` for compiler extensions; preserve fallback, continuations, stack safety and local compiler selection. |
| `merge-laws` | Cell merge must be commutative, idempotent and associative over valid domain contents. State normalization and semantic equality; do not substitute strongest for retained content. |
| `flat-gur-canonical` | Flat GUR is the canonical GUR. Other implementations require deliberate selection as alternatives or historical baselines. |
| `runtime-gc` | Decouple garbage collection through runtime extension; keep collection policy, roots and safe scheduling outside propagator semantics. |
| `session-extension` | Extend primitive propagator bindings and side-effect capabilities through immutable session-extension declarations, with bindings, fixed handlers and ledgers owned per session. |
| `immutable-structure` | Propagator structures remain immutable. Transient execution or communication state belongs in runner/runtime extensions; mutable captured state must not change a declaration's meaning. |

`decision/propagator-flat-gur-canonical-v2` proposes to supersede the earlier
observational `decision/propagator-flat-gur-candidate`, which remains in the
model and the preserved original proposal. The reviewed supersession was
subsequently recorded, as described in the recording section below.
The user has resolved the desired canonical GUR; contradictory documentation
still needs reconciliation.

Three additional Evidence facts cover runner extension, CPS compiler
composition and session capability extension. A completed GC implementation or
general runtime GC-extension API was not established; GC remains a requirement
whose implementation and in-flight-root behavior need separate verification.

Revision-2 checks were selected before execution: structural/relational and
supersession checks; the updated composition examples; existing CPS,
session-extension, generic-runner cross-network, merge and transport suites.
Results are recorded after execution below.

The proposed constitution describes a coordination language over partial
information: declare inspectable network structure, evaluate it explicitly,
and expose information through explicit projections. It covers the shared
infrastructure and its compiler/host integration boundaries. Other Datalog
engines, application behavior and an exhaustive module inventory are outside
this proposal.

## Existing model and provenance

`kiroshi status` selected exactly
`/Users/pandilyn/Documents/ChatGPT/datalog research`, with empty fact/type and
status counts. No `.kiroshi/model.edn` exists in this checkout. Documents cite
older approved facts, including TTMS decisions, but those facts are unavailable
in the local store. Their cited approval is historical evidence; this proposal
does not recreate their authority or claim to supersede them.

The installed workflow router selected `use-kiroshi-cli` followed by
`initialize-system-model`. Their inspected versions were respectively
`8327056248378dd7de17f80b63fe46c63a528b119ca9d2304cda45a6ad737b9b` and
`750d174dae33ff36ae303cdc6db76089fb4631ff6f1db0a54d72848d6966f3a0` (SHA-256).

Baseline HEAD: `73a8d2550188a7823ec0578e2262832abd51659d`. Tests ran against
the working tree based on this commit, which also contains unrelated edits and
changed during inspection. These results are not a clean-commit verification
receipt. Production files were not edited by this modeling task.

Original facts have creation time `2026-10-17T22:12:13.000Z`; the 13 additions
have creation time `2026-10-18T09:34:18.000Z`. Every fact has an explicit
module-boundary scope, lifecycle `candidate`, status `active`, and evidence
state `needs-verification`. An active candidate is not governing. There are
no currently applicable approved obligations in this empty local model.

## Proposed hierarchy

Containment describes semantic ownership, not imports or scheduling:

```text
component/propagator-infrastructure
├── component/propagator-kernel
├── component/propagator-information
├── component/propagator-structure
├── component/propagator-recursion
├── component/propagator-compiler
└── component/propagator-host
```

Each edge is its own `boundary/propagator-*` BoundaryAssignment. Each component
and boundary has supporting Evidence. The hierarchy has one root, exactly one
parent for every other component, no cycles and no orphans.

## Boundary contracts

These responsibilities describe inspected APIs and candidate ownership. There
is no proposed implementation change or transfer of ownership.

| Component | Owns | Public inputs → outputs | Exclusions and dependency direction |
|---|---|---|---|
| Kernel | Immutable `Net`, topology, message-absorption orchestration, patches, queues, completion/failure, structural ancestry | Installers, messages, patches, tasks → updated network, tasks, execution result | Consumers call kernel mechanisms. Domain policy is delegated to merge/projection functions and compound patch handlers; host IO and recursive identity are outside it. |
| Information | Merge, strongest/change policy and domain algebras; opt-in TTMS, event, behavior | Content and contribution → refined content and readable projection | Cell evaluation calls policy. Algebras use network/slot representations where needed; they do not drain tasks or choose host timing. |
| Structure | Slots, accessor declarations, avatars, bidirectional structural transport | Compound values, slot/accessor declarations, messages → network values, patches and outbound messages | Uses kernel and information contracts; does not choose support freshness, retention or lexical meaning. |
| Recursion | Stable application/frame identity and recursive declaration | Closure/application data → bounded generic declaration patches | Depends on kernel patches and declared application dispatch; scheduler does not own recursive semantics. |
| Compiler | Parsing, AST, lexical meaning, closure/application topology and CPS lowering | Forms/source/AST, environment, options → compiled network, result cell and propagator identities | Depends on recursion, structure and kernel. Compiler execution adapters are distinct from host session/IO responsibility even when both live under `runtime/`. |
| Host | Session state, serialized inputs, effect outboxes/ledgers, handlers, receipts and delivery | Source, external input, effect requests → settled session state, receipts and projections | Calls compiler/kernel APIs; clients use host contracts. Graph transports and inspection remain explicit consumers/dependencies. |

The current implementation is not a strictly layered import DAG: kernel patch
interpretation calls compound handlers, cell evaluation calls information
policies, and some information algebras use compound/network representations.
Dynamic resolution also participates in these integrations. The hierarchy
does not hide or claim to eliminate those dependencies.

The executable review examples are in
[propagator_infrastructure_contracts.clj](propagator_infrastructure_contracts.clj).
They compose inspected public APIs for message publication and task draining,
stable declaration replay, TTMS content stored in a compound slot, compiler
execution, and data-only boundary-effect construction. The specs and helper
functions in that file are proposed documentation examples, not production
interfaces. No stubbed operation or invented dependency API is required.

Existing host entrypoints include `commit-runtime-input` / `commit-runtime-input!`
and `run-runtime-cycle`; effect constructors live in the runtime boundary
module. The example only constructs an effect request. It does not demonstrate
the full asynchronous host lifecycle.

Repository extraction does not transfer semantic ownership. The inspected
flat-GUR delivery document records `lain-infrastructure` as the shared owner,
`lain-compiler` as compiler owner, `lain-runtime-clojure` as runtime owner,
`wired` as TUI owner, and `datalog-research` as graph-demo/research owner. Those
are local documentary claims; remote repository states were not checked.
No cross-repository port or boundary change is proposed here.

## Principles, constraints and rationale

The original three candidate Principles motivate four candidate Constraints;
the revision-2 compositional-extension Principle motivates eight more:

| Principle | Motivated constraints |
|---|---|
| `propagator-explicit-coordination` | `propagator-declaration-evaluation`, `propagator-idempotent-declaration` |
| `propagator-owned-policy` | `propagator-domain-policy` |
| `propagator-message-boundaries` | `propagator-host-effect-boundary` |
| `propagator-compositional-extension` | The eight revision-2 Constraints listed above |

The proposed constraints require explicit declaration/evaluation composition;
domain-owned merge and projection decisions; data-shaped host effects with
message-based publication; and stable, idempotent repeated declarations.
Each has its own scope and independent verification method in the EDN.

`decision/propagator-flat-gur-candidate` describes the public facade's current
choice of flat GUR. Its rationale uses `motivated-by`, `preserves`, `rejects`
and `supported-by` relations. It rejects the modeled failure of hidden
scheduler/domain state. It does not duplicate rationale in a `reason` field.

Semantic facts have explicit `applies-to` edges. Neither containment nor a tag
match establishes constraint applicability. Evidence supports every component,
boundary and principle; principles explicitly motivate every constraint.

## Evidence map

Only Evidence facts carry `implemented-in` repository paths. The full file
lists, including representative tests, are in the EDN.

| Evidence | Coherent boundary | Representative tracked sources |
|---|---|---|
| `propagator-infrastructure` | Overall composition | `coordination-language-kernel.md`, documentation index |
| `propagator-kernel` | Network/evaluation | `network.clj`, `runner.clj`, `cell_evaluator.clj`, `network_patch.clj` |
| `propagator-information` | Information policy | `cells/merge.clj`, `datastructures/support_collection.clj`, TTMS documentation |
| `propagator-structure` | Structural transport | Compound-object facade, `boundary.clj`, slot-sync documentation |
| `propagator-recursion` | Recursive declaration | `gur.clj`, `gur/flat.clj`, flat-GUR/application documentation |
| `propagator-compiler` | Language/compiler | Compiler facade, `cps_core.clj`, compiler layout documentation |
| `propagator-host` | Host execution/IO | Session input, boundary effects, boundary-effect documentation |
| `propagator-runner-extension` | Kernel runner extension | Runner constructor, runner, propagator declaration, generic-iterator cross-network tests |
| `propagator-compiler-composition` | Compiler extension | `cps_core.clj`, compiler-common CPS combinators, custom-form and deep-recursion tests |
| `propagator-session-extension` | Session capabilities | Session-extension implementation, boundary effects, live-environment documentation and session-extension tests |

These sources support an interpretation of existing responsibilities; they do
not establish human consensus on normative facts or exhaustive correctness.

## Tags and retained uncertainty

There are three canonical tags: `tag/propagation`, `tag/declaration`, and
`tag/boundary-effects`. Few facts receive direct tags. Under Kiroshi's one-hop
policy, the tagged explicit-coordination Principle propagates declaration
context through `motivates` to the two relevant Constraints. The policy's
reverse `preserves` direction makes a directly tagged Constraint provide
related tag context to the Decision preserving it. Related tags are derived,
not duplicated as stored direct tags, and cannot confer authority.

Three OpenQuestions remain explicit:

1. Recover and reconcile the prior canonical model before persistence. No
   unavailable historical identity is claimed as superseded. The revision-2
   candidate Decision links to its retained local predecessor with `supersedes`;
   this proposes a transition without executing it. Existing tracked
   declarations are retained.
2. Reconcile documentation drift. The index and the flat implementation's own
   namespace docstring still call accumulating GUR canonical; the public GUR
   facade and newer architecture note select flat GUR. Older core-feature
   descriptions of missing dependence handling coexist with newer opt-in TTMS
   and distributed-TMS implementations. None proves a universal TMS.
3. Audit host phase completeness across trace, TUI, XR, receipts and file IO.
   Existing adapters and ledgers do not by themselves prove universal
   next-round publication, at-most-once delivery or oscillation detection.

The sole Assumption states the modeling scope. No unsupported documentation
claim was promoted to a governing fact.

## Initial verification performed (38-fact proposal)

Required checks were selected before execution: fact shape, semantic relations,
hierarchy, tracked evidence paths, candidate overlap, representative
compositions and focused regression tests.

| Check | Observed result |
|---|---|
| Installed validator and relational checks | All 38 facts passed; references and target types resolve, metadata/support/applicability/motivations present. |
| Hierarchy and evidence provenance | Seven components, six boundaries; no cycles/orphans; all 27 referenced evidence files tracked. Baseline blob identities recorded by the check script. |
| Non-persisting proposal checks | CLI `kiroshi propose --data` accepted the root; the same installed `model/propose` operation classified all 38 facts as distinct candidates. Fact counts stayed empty before/after. This does not compare the unavailable historical model. |
| Clojure composition examples | Five checks passed: copy, stable declaration replay, slot content preservation, compiler result 42, boundary declaration. |
| Focused regressions | 67 tests, 1,029 assertions passed; zero failures/errors across seven namespaces. |
| Whitespace | `git diff --check` passed; new proposal files were also checked separately. |

Structural/overlap checks ran `2026-10-17T22:13:55.238Z` through
`22:13:57.120Z`; the composition and regression commands completed during the
same review, by `22:14:25Z`. Their exact process start/finish instants were not
recorded. Known unrelated edits remained in the working tree. The seven suites
were runner, network-patch, support-collection, compound-object-network-slot,
TTMS compound boundary, compiler functional-network contract and runtime boundary.

Rerun structural checks from the neighboring Kiroshi repository:

```sh
clojure -M '/Users/pandilyn/Documents/ChatGPT/datalog research/system-model/check_propagator_proposal.clj'
```

Rerun compositions from this repository (the documentation directory is loaded
explicitly because its hyphenated name is not a Clojure namespace path):

```sh
clojure -M -e '(load-file "system-model/propagator_infrastructure_contracts.clj") (prn (system-model.propagator-infrastructure-contracts/check-contracts)) (shutdown-agents)'
```

The full suite, universal host-phase audit, remote extraction verification and
historical-model reconciliation were not run. No verification receipt was
submitted, and new proposal artifacts are uncommitted. The composition and
focused checks support specific observed behavior only. All candidate facts
retain `needs-verification` pending agreement and claim-specific evidence.

## Review and recording

### Revision-2 verification

All six structural checks passed for 51 facts: shape, relations, hierarchy,
tracked evidence paths, proposed supersession target and candidate overlap.
There are 12 Constraints, four Principles, ten Evidence facts and two Decisions;
the seven-component/six-boundary hierarchy is unchanged. All 35 evidence paths
are tracked. All 51 proposals were distinct relative to the empty local store,
and fact counts stayed empty before and after. The supersession check confirms
the retained predecessor exists with the same fact kind; it does not execute
the transition.

Structural checks ran `2026-10-18T09:36:43.342Z`–`09:36:46.332Z` against the
same HEAD recorded above. Eight composition checks passed, including a slot
accessor that reacts to a later compound message without materializing it,
activation composition preserving the original declaration, a custom CPS rule
with canonical fallback, and a session bundle declaring primitive and effect
capabilities together. The session example constructs the declaration; it does
not perform IO.

The six selected suites passed 68 tests and 1,010 assertions, with zero failures
or errors: CPS compiler, session extension, generic runner constructor,
support collection, TTMS compound boundary and message lifting. All commands
completed by `2026-10-18T09:37:02Z`. The test and composition process start/end
instants were not captured individually. These are working-tree results;
unrelated edits remain outside this task.

Merge checks cover finite TTMS evidence examples and their laws, rather than
proving commutativity/idempotence/associativity for every domain. Existing
runner tests exercise cross-network composition; existing session tests cover
fixed handlers, immutable declarations and session installation behavior. No
general audit of mutable activation captures, collector implementation or
universal materialization avoidance was performed. Requirements remain
explicit even where complete implementation conformance is unverified.

The full suite was not repeated for these model/example edits. No model facts
or verification receipts were persisted. The original proposal is retained;
the updated EDN and review examples remain uncommitted.

### Consensus and persistence at proposal time

Review the complete EDN, boundary contracts, hierarchy, principles and
constraints together. The initialization skill's step 11 states:
“Stop and obtain explicit consensus; silence is not approval.”

The proposal stopped at that gate. Agreement must cover the displayed ownership and
contracts. Historical-model reconciliation remains necessary before durable
recording. Named-human approval, persistence and any later supersession are
separate operations; no `remember`, `approve`, `supersede`, `verify` or `stale`
command had been run at proposal time.

### Authorized recording

The user subsequently authorized recording the reviewed proposal, committing
the repository backup, and opening the Kiroshi web interface. The local store
was rechecked and remained empty, with no portable model to restore. Recording
therefore creates the reviewed local model while preserving the OpenQuestion
about the unavailable historical model.

All 51 identities were validated and recorded with explicit session, task and
source provenance. Every durable command confirmed `.kiroshi/model.edn`; its
contents were parsed and checked after each mutation. The old flat-GUR
Decision was superseded by the reviewed canonical flat-GUR Decision, retaining
the old fact and transaction history. The final backup contains all 51 reviewed
identities and no verification receipts.

No `approve` command was run. Normative facts remain candidates, and behavioral
support was not upgraded from test summaries. The runtime's canonical-tag
handling leaves one tag current/supported; aggregate lifecycle counts are 49
candidates, one superseded fact and one current tag. Fifty facts retain
`needs-verification`.

`kiroshi human` generated the searchable local projection at
`.agent-memory/system-model.html`. The live observer serves the repository at
`http://127.0.0.1:7331`. Only the portable `.kiroshi/model.edn` backup and the
related model/review artifacts are included in the dedicated repository
commit; `.agent-memory/` and unrelated working-tree changes are excluded.
The commit is local; pushing was not requested.
