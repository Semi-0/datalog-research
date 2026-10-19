# Lain top-down programming and bounded agent orchestration

Status: discussion draft, updated 2026-10-19. This document proposes an architecture;
it does not authorize implementation, agent execution, or knowledge-base writes.

## Intent

Use the Lain editor as a shared interface for humans and agents to construct,
inspect, and refine executable high-level architectures. Express a goal as a
constrained network, evaluate the parts already defined, and expose unresolved
obligations as explicit work rather than burying them in conversations.

The intended benefits are better task completion, less human context
fragmentation, and bounded concurrent work across repositories and machines.
Agent count and speed are secondary to preserving a coherent, inspectable goal.

The central hypothesis is that an incomplete program can be a useful executable
specification: known portions propagate information while unresolved portions
remain visible. Agents help refine that program without silently changing its
accepted constraints.

## Established foundations and proposed extensions

Repository foundations relevant to the proposal:

- [Partial information and fixpoint semantics](../propagators/doc/four-core-features.md)
  distinguish retained information, merge, and readable conclusions.
- [Higher-order construction](syntax-design.md#higher-order-construction)
  composes network declarations and ordinary application. Conditional topology
  and recursion have their own evaluation rules; not every syntactically present
  branch represents active work.
- [Call inspection](../graph/lain_editor/call_inspection.clj) presents waiting
  and contradictory values and follows actual application topology.
- [Structural inspection](../graph/lain_editor/structural_value.clj) distinguishes
  pending list tails, completed empty tails, unavailable observations, and
  contradictions.
- [The multi-repository workspace](../multi-repo/README.md) dispatches commands
  across independent Git repositories.

These provide starting points, not evidence that an orchestration system already
exists. Missing-work classification, task contracts, scheduling, remote workers,
permission enforcement, and approval workflows are proposed additions.

Here, HOP provisionally means **higher-order propagator**: a mechanism able to
construct or connect network behavior. Confirm this interpretation before
choosing a concrete API. Merely being higher-order does not imply that an
operator can observe unavailable inputs or discover all missing work.

## 1. Goals as constrained networks

Humans and agents use the same network to describe:

- Desired outputs and their domain meaning.
- Premises and the evidence supporting them.
- Hard constraints and softer preferences, kept distinct.
- Architectural boundaries and allowed interfaces.
- Acceptance checks and outstanding questions.

Constraint observations distinguish satisfied, violated, and unknown. Unknown is
not success; a contradiction is not merely an unfilled field. A goal is complete
only when its required acceptance evidence is valid for the intended revisions.

Premises determine whether a candidate fits the current goal and constraints.
Changing a premise must reveal affected conclusions and evidence. A conversation
may propose a change, but should not become a second, hidden source of authority.

### Lain-first transformation and orchestration specifications

We can and should directly define high-level function transformations and
orchestration in `.lain` files. The high-level plan is actual network composition
over explicit data structures, inputs, outputs, constraints and implementation
obligations, not only a prose description or a diagram reconstructed afterward.
These files are the shared medium for human-agent discussion and the
versioned specification against which later implementations are integrated.

Separate high-level domains into focused `.lain` files when they own distinct
contracts, and express their composition through import expressions in a Lain
entry point. For the current runtime, `(define import load-lain)` gives that
spelling without adding compiler syntax. Paths are project-root-relative;
imports share the current program environment, so declare dependency order
explicitly and do not assume namespaces or cached, idempotent imports.
`examples/lain/top_down/stack-design.lain` demonstrates structure, constraints,
hierarchy transformation and example domains, with orchestration in the entry.
The same accepted source files, not a second prose-only workflow, must be used
when later filling obligations and checking integration.

Define the high-level transformations before filling in their lower-level
operations. Express orchestration decisions in the same way: what information
is required, which work depends on it, what candidate action is proposed, and
which checks permit integration. Missing functions remain declared obligations;
they must not be replaced with successful stubs to make the plan appear complete.

For example, this is a Lain composition for review, not an implemented agent
executor. Its three lower-level operations are explicit implementation holes:

```clojure
(def-cells required-obligations eligible-work dispatch-requests)

(define plan-step
  (network [goal specification implementations evidence policy]
    (let [obligations
            (required-obligations goal specification implementations evidence)
          proposals
            (eligible-work specification obligations policy)
          requests
            (dispatch-requests specification proposals policy)]
      (list
        (list :obligations obligations)
        (list :proposals proposals)
        (list :dispatch-requests requests)))))

(def-cells goal accepted-specification implementations evidence policy)
(define proposed-step
  (plan-step goal accepted-specification implementations evidence policy))
```

`dispatch-requests` declares proposed external work as data; evaluating this
network does not itself launch a subagent. Unknown eligibility or authorization
does not authorize a dispatch. The host executor interprets only requests that
have passed the explicit authorization boundary.

The development sequence is therefore: write the high-level Lain specification,
evaluate its known portions, discuss its data and constraints, accept a named
revision, then supply compatible lower-level implementations. Extend the
visualizer or other host adapters afterward to interpret the agreed data, not to
invent or silently change its semantics. For a stack view, the structure and
compound-network-to-stack transformation belong in this first discussion;
plane placement and camera controls belong to the later rendering work.

## 2. Executable incompleteness

"Evaluate before completion" means evaluating a well-formed network with partial
information or unresolved components. It does not mean that arbitrary incomplete
source text is parseable, or that a network is correct because it reaches
quiescence.

An architectural component can expose a declared interface, input information,
constraints, and acceptance examples before its implementation is available.
Independent parts may evaluate; dependent parts retain unresolved obligations.
Supplying a compatible implementation can then enable further propagation.

Illustrative flow, not executable Lain syntax:

1. Declare a goal requiring a local and remote execution adapter.
2. Supply the local adapter, worker budget, and repository boundaries.
3. Evaluate what is currently available; inspect blocked outputs.
4. Identify the remote adapter implementation as one unresolved obligation.
5. Propose and authorize a bounded task to supply that implementation.
6. Evaluate the candidate refinement and run its acceptance checks.
7. Integrate it only if evidence and architectural constraints agree.

An interface alone does not validate an implementation. Runtime outputs, contract
checks, and integration tests remain necessary. A stub or simulated result must
be marked as such and cannot satisfy production acceptance evidence.

## 3. Measuring empty cells and unresolved obligations

An empty cell is an observation, not automatically an agent task. At minimum,
distinguish these cases:

| Observation | Meaning | Possible response |
| --- | --- | --- |
| No current information | An input has not arrived | Wait or request input |
| Missing implementation | A required declared component is unimplemented | Propose a development task |
| Insufficient partial information | Some structure is known, but a required result is not | Trace dependencies and request refinement |
| Unavailable inspection | The observer cannot read the relevant state | Repair or extend observation |
| Contradiction | Available contributions disagree under the domain policy | Investigate premises or implementation |
| Inactive branch | Current selection does not require this component | Do not dispatch work |
| Budget-limited evaluation | Evaluation stopped before resolution | Report the limit, not a missing implementation |

The distinction requires declarations, dependency information, and evaluation
status; it cannot be recovered reliably from a rendered blank node alone.

Start with a bounded, read-only observation of the required goal slice at a named
revision and evaluation checkpoint. Record which declared obligations are
unresolved, their blockers, and whether evaluation is quiescent or interrupted.
Do not claim to enumerate future topology that has not yet been constructed.

A HOP could subsequently use an authorized refinement to connect a candidate
implementation. Keep inspection, task proposal, and topology mutation separate.
Ordinary value-dependent propagators may not activate when inputs are absent;
verify the observation mechanism before implementing an in-network monitor.

Absence can disappear when information arrives. Therefore "this cell is empty"
must be a checkpoint observation with invalidation or support semantics, not a
permanent monotone fact. An explicit unresolved-obligation declaration is often
safer than deriving work solely from absence.

Measure unresolved required obligations and their blocking relationships before
inventing a completion percentage. Raw empty-cell counts are misleading: adding
internal cells or an inactive branch must not make a task appear less complete.
Reduction in holes is useful feedback, never sufficient acceptance evidence.

## 4. Accepted architecture and candidate refinements

Maintain an accepted specification revision and candidate refinements against it.
The accepted revision owns goals, constraints, component interfaces, and scope.
Candidates contain experiments, proposed changes, patches, and their evidence.

An agent may refine within its contract, but cannot weaken acceptance criteria,
expand permissions, or remove a constraint to make its result pass. A necessary
architectural change becomes an explicit amendment with rationale and impact.
Human approval initially governs amendments and integration.

Experiments have bounded duration and attempts. A failed experiment can still
produce useful evidence that the current combination of premises and constraints
does not admit the proposed approach. It must not be disguised as completion.

### Lock integration to the same specification code

Use the accepted high-level `.lain` network as the common contract for planning,
dependency-based orchestration and candidate integration. Do not maintain a
separate, independently evolving workflow that merely resembles that network.
Human-readable plans and task queues are projections of the specification and
its current supported observations, not alternative sources of architectural
authority.

Each worker task identifies the specification revision, the particular binding
or declared obligation it may refine, its permitted interfaces and write scope,
and its required acceptance checks. A candidate supplies an implementation or
an explicit extension at that boundary. It does not quietly replace the accepted
high-level transformation, rewire its consumers or weaken its constraints.
The integration gate evaluates the same accepted composition against the
candidate implementations and revision-matched evidence. A changed specification
requires an explicit amendment and renewed agreement; earlier evidence remains
tied to the earlier revision.

This is the intended architecture-drift lock: pinned specification code, bounded
refinement boundaries, dependency validity and an integration gate. It is not a
claim that a specification file alone prevents arbitrary code changes or proves
behavioral equivalence. Permission isolation, inspection of specification and
interface diffs, and independent contract/integration checks enforce the parts
that are mechanically enforceable; unresolved architectural judgments remain
human decisions.

### Reuse TTMS and the existing dependence substrate

Build on Lain's existing support, premise-state, and membership machinery rather
than introducing a parallel validity system. The governing principle is:

> Lain's existing support and membership substrate maintains what is currently
> warranted; orchestration proposes and executes bounded actions in response.

The existing mechanisms have distinct responsibilities:

- [Support collections](../propagators/datastructures/support_collection.clj)
  retain supported observations and project current results.
- [Premise state](../propagators/stdlib/premise_state.clj) carries knowledge of
  source versions and withdrawals without making every watched source a result
  dependency.
- [Membership collections](../propagators/datastructures/membership_collection.clj)
  distinguish value requirements from guards determining whether an observation
  belongs in the current result, including explicit retracted-state conditions.
- [Supported procedures](../propagators/stdlib/supported_procedure.clj) compose
  value computation with support and state propagation.

Proposed domain mapping:

| Orchestration meaning | Existing substrate to compose |
| --- | --- |
| Accepted architecture or supplied requirement | Explicit source premise |
| Candidate conclusion | Supported observation |
| Test result | Evidence tied to the tested artifact, contract, and environment |
| Architecture or implementation change | Advance the corresponding source version |
| Previously warranted acceptance | Freshness and withdrawal propagation |
| Candidate selected for experimentation | Membership guard |
| Remaining work | Projection of declared obligations and current evidence |

For example, "this component satisfies its contract" depends on architecture
revision A, implementation revision B, and verification result C. When B changes,
C must not be relabelled as evidence for the new implementation. This follows the
existing rule that an old payload must never acquire a new source version merely
to appear current. Acceptance still requires a policy interpreting the evidence;
dependency validity alone does not establish behavioral correctness.

Boundary adapters must deliberately map Git revisions, verification runs, and
approvals to premise identities and epochs. TTMS cannot infer external changes
or authenticate a worker's claims by itself.

An empty-cell observation should likewise be supported: "at evaluation checkpoint
E, required obligation X has no usable implementation." Its validity depends on
the declaration, evaluation state, and selection conditions. Membership guards
may help represent it, but explicitly established absence is not equivalent to
not having received information. An inspector must establish that distinction.

The new toolbox is therefore limited to domain declarations, boundary adapters,
editor projections, and a bounded effect executor. Persistence stores declarations,
execution records, and evidence; it should not independently redefine validity.
Skills guide agents in using these tools, but do not enforce permissions.

Two limits remain explicit: legacy TTMS support is conjunctive, so an acceptance
rule such as "either reviewer is sufficient" needs deliberate domain policy;
and compacted evidence is not a complete audit history. Do not assume a generic
repo-wide dependence system or automatic transport integration where the
existing mechanisms remain opt-in.

## 5. Orchestration and enforcement

Separate five responsibilities, without requiring five services:

1. Editor: graph editing, focused conversations, approvals, and evidence inspection.
2. Lain evaluation: derive constraints, blockers, and eligible task proposals.
3. Scheduler: authorize proposals under budgets, dependencies, and scope ownership.
4. Worker adapter: execute a task through a local or remote agent environment.
5. Integration gate: validate artifacts and evidence against the accepted revision.

### Dependency-based runner and bounded subagent refinement

The intended orchestration runner follows the dependencies declared by the Lain
specification: required, selected and currently warranted obligations determine
eligible work. It can coordinate authorized subagents or bounded implementation
extensions that fill those obligations, then feed their artifacts and evidence
back through ordinary boundary messages for reevaluation of the same spec code.
Independent eligible work may run concurrently within the approved budget;
blocked, inactive, withdrawn or unauthorized work must not be launched simply
because a callable is unresolved.

Compose this coordination around the existing support/membership substrate and
host effect executor. If an orchestration runner extension is needed, its
contract and authority require a separate review. It must not move domain
validity, agent permissions or integration policy into hidden scheduler state,
or change ordinary propagation task policy to encode an agent workflow.
Existing dependence mechanisms do not by themselves provide a subagent executor
or an architecture-drift enforcement gate; those remain proposed capabilities.

Each dispatched task needs a stable identity, goal, architecture revision,
dependency context, allowed repositories and paths, allowed effects, resource
budget, acceptance checks, and expected artifacts. A focused context package
includes the relevant graph slice and links to its supporting evidence rather
than requiring a transcript of every other agent.

Initially, agents propose child tasks; only the scheduler launches them. Global
concurrency, delegation depth, attempts, and total resource budgets are bounded.
Children consume allocated parent budgets rather than creating new budgets.
Budget accounting is centralized; a worker cannot renew its own authority.

The graph declares permissions; worker isolation enforces them. Use isolated
workspaces, restricted credentials and tools, explicit effect approvals, and
exclusive ownership of overlapping write scopes. Cancellation revokes further
authority but cannot undo effects already performed.

Pi and Codex are candidate adapters, not alternative task databases. Their exact
launch, resume, cancellation, and remote-access capabilities require separate
verification. Remote workers must report artifacts and evidence with task,
attempt, base-revision, and environment identities.

Persist dispatch identity and authorization before launch. Retries must reconcile
existing attempts rather than blindly repeating effects. Pure derivation can be
idempotent while process creation, publication, and other external effects are not.

## 6. Repository boundaries

Google repo enumerates projects and dispatches shared commands; it does not
provide a sandbox, resolve ownership, or create atomic cross-repository commits.

Give concurrent tasks isolated worktrees or checkouts for their explicitly
allowed repositories. Do not share an actively edited working directory. One
task may span several repositories when that scope is intentional and budgeted.

Artifacts identify exact base and result revisions. Integration checks consumers
against the intended dependency revisions; sibling working trees do not replace
pinned dependencies automatically. Commit and push permissions remain separate.

## 7. Semantic principles

- Networked semantics: topology, declarations, and relationships are inspectable.
- Partial information: useful work can proceed without pretending unknowns are known.
- Fixpoint evaluation: compatible monotone derivations refine information toward
  quiescence. Associative, commutative, idempotent merge supports order-independent
  information combination; fair scheduling and convergence conditions still matter.
- Dependence tracking: conclusions and evidence retain their supporting premises
  and revision identities, with explicit invalidation or retraction on change.

These principles govern reasoning, not a claim that arbitrary agent actions
commute or that every recursive network terminates. Architectural changes and
empty-cell observations require explicit versioning and freshness rules.

## 8. Human interface

Reuse editor capabilities where suitable, with three focused projections of the
same authoritative model: intent, execution, and evidence. Avoid an obligatory
single giant graph. Selecting a node reveals its contract, relevant conversation,
current information, blockers, ownership, and evidence.

Conversation produces proposed structured changes that can be inspected before
acceptance. Execution events update derived views, not the accepted goal itself.
The interface should answer: what is wanted, what is happening, why it is blocked,
and which decision needs a human.

## 9. First experiment and acceptance

Begin locally with one partially specified goal and one worker. Demonstrate that
known portions evaluate, a declared missing implementation stays visible, and
providing it advances the network without changing its acceptance constraints.
Then add a second isolated task and one remote worker to test bounded concurrency.

Required demonstrations:

- Missing input, missing implementation, contradiction, and inactive topology
  lead to distinct observations and do not cause indiscriminate dispatch.
- Supplying information invalidates an earlier empty-cell observation.
- Repeated evaluation does not launch duplicate attempts.
- Workers cannot exceed their allocated write scopes or concurrency budget.
- A premise or source revision change makes affected evidence visibly stale.
- Tests and artifacts identify the revisions and environments they validate.
- A human can understand progress without reading every agent transcript.
- No candidate can satisfy a goal by weakening its accepted constraints.
- The same accepted Lain specification drives work proposals and integration
  checks; an unapproved change to its composition or interfaces blocks integration.
- Filling a declared implementation hole advances that specification without
  replacing its high-level plan with a separate agent-owned workflow.

## Open decisions

- Does HOP mean higher-order propagator here, and should it construct candidate
  topology, observe obligations, or compose separate mechanisms for both?
- What is the smallest explicit representation of a required implementation hole?
- Which observations can existing Lain evaluation expose without a new operator?
- Which constraints are mechanically enforceable, and which require human review?
- What evidence is sufficient to accept a candidate refinement?
- Should the first experiment use a code-generation task or a pure dataflow task?

The proposed first scope excludes autonomous acceptance-criteria changes,
unrestricted recursive delegation, automatic publishing, and a general-purpose
distributed execution platform.
