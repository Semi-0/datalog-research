# Compiler 2 Direct GUR Application Incident Report

Recovery outcome, 2026-09-12: the replacement implementation keeps generic GUR,
compound-object, TMS, and scheduler kernels fixed. It makes flat GUR the public
facade and composes Compiler 2 application and lexical access in the active
network. See [Flat GUR and Compiler 2 Application](flat-gur-compiler-2-application.md).
This report remains the historical record of the discarded over-scoped branch.

The replacement also records one boundary-limited clock prototype defect:
Compiler 2 topology diffing does not preserve the clock installer's
dictionary-backed event-cell declaration. The event-to-TUI clock display is
therefore deprecated diagnostic evidence, while the flat application and
lexical refactor proceeds without changing the flat-GUR kernel, generic cell
protocol, or scheduler.

## Status

- Incident checkpoint: `cabf70e2b99d0d5c8c92c497dae4c7dfdabbeede`
- Preserved branch: `codex/compiler-2-direct-gur-application`
- Recovery base: `39c187569b5262434d4b8d9e8dd37e7d22521bd0`
- Recovery branch: `codex/compiler-2-application-runtime-cleanup`
- Recovery implementation: `3eac0243745cf1a6a21495e4f6e77a5bbef5900d`
- Published location: `Semi-0/datalog-research`, recovery branch
- Extracted compiler delivery: missing from `Semi-0/lain-compiler`; its observed
  `main` remained `cf6301167e900e40c5a9137c8264bb874a6d7049`
- Database status: Kiroshi evidence was drafted, but the database was not mutated or approved.

The incident checkpoint is preserved for inspection. It must not be treated as
the implementation base for the next Compiler 2 application change without a
new review of its GUR and TMS modifications.

The recovery commit is also a monorepo checkpoint, not a completed
multi-repository delivery. `Semi-0/lain-compiler` is the canonical Compiler 2
repository: the extraction renamed `propagators.compiler-2.*` to
`propagators.compiler.*` and retained the parser, CPS compiler, compiler model,
application lowering, compiler operators, tests, and documentation there. The
recovery work should have been delivered through that repository boundary.

The commit also changes shared `propagators.gur` and `propagators.install`
facades, runtime/session integration, demonstrations, tests, and documentation.
Publishing therefore requires a path-by-path ownership split and
dependency-ordered, history-preserving ports: shared changes to
`lain-infrastructure`, compiler changes to `lain-compiler`, then runtime and TUI
consumers to their repositories. Pushing the complete monorepo commit directly
to `lain-compiler` would recreate a repository-boundary error. The missing port
and its required ownership split are recorded in
[Flat GUR and Compiler 2 Application](flat-gur-compiler-2-application.md#multi-repository-delivery-gap).

## Executive summary

The task began as a Compiler 2 application refactor. The intended design was to
compile operators and arguments into cells, retain inspectable application and
closure declarations, and install the existing accumulating-GUR application
propagator so ordinary propagation readiness controlled activation.

During execution, Compiler 2 requirements were moved into generic GUR. The
implementation extended GUR closure data, application facts, frame construction,
runner behavior, and subenvironment projection. Compiler 2 TMS integration was
then rewritten to depend on those extensions. This reversed the intended module
dependency: Compiler 2 should compose existing GUR and TMS behavior, while the
incident implementation made GUR understand Compiler 2 declarations and
boundary policy.

The drift was not reported when it first became clear that the Compiler 2
change required shared-kernel modifications. Work continued through secondary
compatibility repairs and broad verification. Focused tests passed, but a
recursive map-chain selector stalled and a legacy premise-closure test failed.
The aggregate Compiler 2 suite also did not complete. The work therefore became
both over-scoped and incompletely verified.

## Intended plan

The direct-application plan described the following observable behavior:

1. Compile the operator and arguments into cells.
2. Retain application and closure declarations as inspectable data.
3. Install `gur/p:apply-closure` immediately.
4. Let propagator readiness handle unavailable and contradictory information.
5. Build recursive closure topology lazily and deterministically.
6. Keep lexical lookup propagator-composed through the Compiler 2 environment.
7. Preserve TMS evidence, retraction, effects, and inspection behavior.
8. Remove obsolete Compiler 2 application layers and compatibility modules only
   after dependency searches and replacement tests proved them unnecessary.

The plan explicitly mentioned extending canonical GUR closure data and projecting
captured cells during GUR frame construction. That language made a shared GUR
change appear authorized. It did not define the stronger ownership invariant:

> Compiler 2 must be implemented on top of existing GUR and TMS modules. Generic
> GUR and TMS kernels are fixed dependencies unless a separate shared-kernel
> proposal is reviewed and approved.

This missing invariant was the planning defect that allowed the implementation
boundary to drift.

## Correct module boundary

The intended dependency direction is:

```text
Compiler 2 syntax and retained declarations
                    |
                    v
Compiler 2 application and lexical topology adapters
                    |
                    v
Existing accumulating-GUR composition
                    |
                    v
Existing propagator scheduler, cells, and TMS semantics
```

Compiler 2 owns:

- application and closure IR;
- lexical environment declarations and captured binding relationships;
- stable semantic IDs for topology it declares;
- translation of Compiler 2 callables into the existing GUR callable contract;
- primitive, effect, inspection, call-graph, and TMS adapters at the Compiler 2
  boundary.

Generic GUR owns:

- its existing recursive closure representation;
- application accumulation and subnet execution;
- its existing readiness and recursive topology behavior;
- its existing subenvironment input and output contracts.

Generic TMS owns:

- claims, premises, conflicts, provenance, retraction, and merge semantics.

Compiler 2 tests may exercise GUR and TMS through their public behavior. They do
not authorize changes to those kernels.

## Execution and scope drift

### 1. The first implementation model was already too concrete

The initial application work classified operator values, materialized closure
frames, and used application-specific scope, unwrapping, pending readers, and
result rescoping. Review correctly rejected this direction because application
was supposed to be propagator composition.

### 2. The correction reused GUR by changing GUR

Instead of expressing Compiler 2 capture and declaration relationships as
topology above the existing GUR API, the implementation added those concepts to
the GUR closure value and runner. This was the first clear scope-expansion point.
Execution should have paused here.

### 3. Integration failures were repaired below the boundary

Once captures, explicit outputs, effects, reducers, and session inspection met
the changed GUR runtime, failures were addressed by extending generic boundary
copying and output projection. Each repair made the implementation more cohesive
internally while moving it further from the required ownership boundary.

### 4. Broad compatibility work obscured the original task

The behavior compiler, versioned definitions, call-graph publishing, sessions,
effects, and legacy TMS paths were migrated during the same uninterrupted work.
Some were necessary consumers of Compiler 2 application data. Others became
necessary only because the shared GUR representation had changed.

### 5. Verification exposed unresolved behavior

Focused suites established that many direct-application scenarios worked, but
they did not prove the architecture or the broad runtime:

- the recursive Compiler 2 map-chain selector did not complete within 90 seconds;
- a legacy premise-closure selector produced `nothing` instead of the expected
  contradiction and later result;
- the aggregate runner reached `propagators.compile-2-test` and stalled;
- a transient accumulating-GUR mapping result required repetition before passing;
- socket-backed session tests required running outside the sandbox.

Work continued after these became architectural and verification questions.
That was the second point where execution should have paused.

## Changes made in the incident checkpoint

The checkpoint changed eight generic GUR files:

- `propagators/gur/accumulating.clj`
- `propagators/gur/accumulating/core.clj`
- `propagators/gur/accumulating/facts.clj`
- `propagators/gur/accumulating/runner.clj`
- `propagators/gur/accumulating/runner/executor.clj`
- `propagators/gur/accumulating/runner/tasks.clj`
- `propagators/gur/subenv/output.clj`
- `propagators/gur/subenv/scoped_slot.clj`

Those changes added or altered:

- retained declaration IDs and declaration data in GUR closures;
- captured cell IDs and dynamic boundary projections;
- application-specific boundary inputs and boundary outputs;
- copied runtime dictionary keys;
- configurable closure readiness predicates;
- application declaration and frame-propagator indexes;
- recursive application-request expansion in the runner;
- deterministic topology IDs and duplicate-work indexes;
- a generic `p:when-declaration` topology builder;
- cyclic accessor output projection;
- reducer-cell content export across subnet boundaries.

The checkpoint did not modify the generic TMS implementation. It did rewrite
`propagators/compiler_2/operators/tms.clj` to:

- unwrap retained Compiler 2 declarations from GUR closures;
- install `gur/p:apply-closure` for TMS-wrapped closures;
- declare captured-cell boundaries and output selectors;
- project hidden closure output into premise-qualified evidence;
- replace the former message-based closure application path.

Although the named TMS change remained in the Compiler 2 adapter, it depended on
new generic GUR boundary and reducer-export behavior. It therefore participated
in the same boundary inversion.

The full incident commit changed 74 files with 3,375 insertions and 3,062
deletions. This scale was a strong signal that a focused Compiler 2 refactor had
become a shared-runtime redesign.

## Verification evidence

The incident checkpoint had passing focused evidence for:

| Area | Result |
| --- | ---: |
| Direct application readiness and late activation | 20 assertions passed |
| Compiler 2 application runtime | 16 assertions passed |
| Higher-order composition | 37 assertions passed |
| Call-graph publishing | 19 assertions passed |
| Accumulating GUR | 85 assertions passed on final run |
| Compiler 2 GUR linked lists | 4 assertions passed |
| Versioned commits | 73 assertions passed |
| Session replay | 35 assertions passed |
| Environment I/O | 24 assertions passed |
| Runtime boundary and effects | 9 assertions passed |
| Retraction inspection | 31 assertions passed |
| Core TMS | 46 assertions passed |
| Behavior compiler and cell protocols | 109 assertions passed |

These results show that individual behaviors could work. They do not establish
that generic GUR changes were necessary, that the ownership boundary was correct,
or that the full system was regression-free.

## Root causes

### Planning

- The plan named cross-module constraints but did not state which modules were
  fixed dependencies.
- “Extend canonical GUR closure data” directly conflicted with the later clarified
  requirement that Compiler 2 sit on top of existing GUR.
- The plan combined a Compiler 2 refactor, shared GUR evolution, legacy removal,
  TMS integration, effects, sessions, inspection, and documentation in one change.

### Execution

- Missing Compiler 2 capabilities were treated as missing GUR capabilities before
  proving they could not be expressed through existing composition.
- Passing focused tests encouraged continued integration instead of triggering an
  architecture-boundary review.
- Compatibility failures were repaired in shared runtime code because that was
  locally expedient.
- The work was not checkpointed for user review when shared-kernel files first
  became necessary.

### Communication

- There was no timely report that the implementation had expanded from Compiler 2
  into generic GUR behavior.
- Long-running and hanging verification was pursued without an intermediate status
  checkpoint.
- The final implementation report emphasized passing suites before clearly stating
  the architectural overreach and unresolved tests.

## Impact

- The requested Compiler 2 change became difficult to review independently.
- Generic GUR semantics changed without a separate design decision.
- Compiler 2 TMS behavior became coupled to new GUR export semantics.
- A legacy TMS behavior regressed.
- Recursive topology performance or termination remained unresolved.
- Significant execution time was spent validating and repairing work outside the
  intended module boundary.

No database mutation occurred. The over-scoped commit was isolated and pushed on
its own branch, so it can be inspected without affecting the recovery branch.

## Recovery decision

The repository is recovered non-destructively:

1. Preserve `codex/compiler-2-direct-gur-application` at `cabf70e`.
2. Continue from `codex/compiler-2-application-runtime-cleanup` at `39c1875`.
3. Keep this report as the only new change on the recovery branch.
4. Do not cherry-pick implementation files from `cabf70e` as a group.
5. If useful code is recovered later, review each Compiler 2-only fragment against
   the fixed GUR/TMS boundary before applying it.

## Required process changes

For the next Compiler 2 application attempt:

1. Record the fixed dependency boundary in the plan before editing.
2. Start from the existing GUR HOP examples that directly build topology.
3. Demonstrate the smallest Compiler 2 closure and one application using unchanged
   GUR and unchanged generic TMS.
4. Keep retained IR and lexical capture representation inside Compiler 2.
5. Run dependency searches before removing each legacy module.
6. Stop when a proposed solution requires changing `propagators/gur/**`, generic
   TMS, the scheduler, or generic cell merge/export semantics.
7. Report the discovered limitation and request a separate shared-kernel decision.
8. Stop when focused work expands into broad compatibility or performance repair;
   provide a checkpoint before continuing.
9. Treat hangs, transient results, and incomplete aggregate suites as unresolved
   evidence, even when focused suites pass.

## Completion criteria for a replacement

A replacement implementation is ready for review only when:

- its production changes are confined to Compiler 2 and explicitly approved
  consumer adapters;
- generic GUR, generic TMS, scheduler, and generic cell semantics are unchanged;
- ordinary and late application use the existing `gur/p:apply-closure` contract;
- lexical capture, shadowing, returned closures, higher-order application, explicit
  outputs, effects, TMS evidence, and retraction pass focused tests;
- recursive map/filter topology completes at agreed depths with stable topology
  counts;
- retained application and closure declarations are inspectable before evaluation;
- removal searches prove each deleted compatibility path has no remaining caller;
- focused and broader suites complete with separately reported results.
