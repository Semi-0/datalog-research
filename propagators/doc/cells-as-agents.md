# Cells As Agents: Monotone Interaction In A Timeless Network

Status: theoretical proposal, 2026-09-15. This records an exploration, not an
approved architecture change or an implemented API.

## Motivation

The repository treats a network as an immutable value whose declaration and
evaluation are separate. The question is whether cells could also describe
agents: loci that accumulate observations and effectful events, receive inputs
from reality, and expose decisions to the outside world.

The intended gain is extending partial-information composition to interacting
participants themselves. Behavior descriptions, observations, intentions, and
interaction history can be incomplete, conditional, or contradictory with
provenance. Pure propagators could declare and connect agent cells, including
through higher-order propagation, while the network remains data.

The central constraint is to maintain the delusion that the propagator network
is timeless: incidental evaluation order should not determine observable actions.
Time, observations, and decision boundaries must therefore be explicit inputs.

## Grounding And Current Status

Sources inspected for the discussion:

- [Core Runtime](core-runtime.md) and `../core.clj`: explicit task queues,
  cell merge, and activation results applied to network values.
- [Boundary Effect Runtime](boundary-effect-runtime.md) and
  `../compiler_2/runtime/boundary/effects.clj`: requests in an outbox,
  runtime-owned delivery, effect identities, payload checks, and receipts.
- [Accumulating GUR](accumulating-gur.md): recursive declarations accumulate
  in an owner cell; unknown tails wait; stable identities prevent duplicate
  topology. GUR does not own general retention or GC.
- [Coordination Language](coordination-language-kernel.md): declared
  relationships, explicit projections, and activation-local execution state.
- [Bool4](../cells/bool4.clj), [cell values](../cells/value.clj), and
  [merge](../cells/merge.clj): the knowledge ordering, contradiction with
  provenance, and domain-specific merge/strongest interpretation.
- [TMS core](../datastructures/tms/core.clj),
  [TMS tests](../../test/propagators/tms_test.clj), and
  [Behavior Reactivity](behavior-reactivity.md): retained claims, premise-state
  projection, conflict provenance, and behavior-valued claims.
- Alexey Radul, *Propagation Networks: A Flexible and Expressive Substrate for
  Computation*, thesis sections 2.1-2.3, pp. 17-20; 4.3, pp. 63-65;
  6.5-6.7, pp. 134-136; and 7.3, pp. 144-146.
  Local source: `/Users/linpandi/Dropbox/Programs/propagator/doc/phd-thesis.pdf`.
  [Public thesis record](https://dspace.mit.edu/entities/publication/f7147c79-449d-469a-8790-d7abe02947c1).

KIROSHI was queried for `/Users/linpandi/cloj-leapfrog`. Current constraints
created and approved on 2026-09-09 include
`:constraint/net-is-kernel-state`, `:constraint/propagators-emit-messages`,
`:constraint/external-effects-cross-runtime-boundary`,
`:constraint/scheduler-excludes-domain-policy`, and
`:constraint/merge-never-silently-deletes-evidence`. The last explicitly requires
retaining claims and provenance while premise state controls strongest selection.
Current boundary assignments
place cell semantics and scheduling under the coordination kernel and effect
execution under Compiler 2. Convergence and stable redeclaration identities
were marked as needing verification; this discussion does not establish them.

The current system already expresses histories, dynamic topology, and effects.
The proposed benefit is a common composition model for those capabilities,
not a claim that they are currently impossible or that computational power grows.
The one-time-network approach described in the discussion remains useful for
bounded tracing and effectful logging. No GC failure was reproduced here.

Some older docs describe TMS as unimplemented or contradiction handling as a
stub. The generic contradiction handler remains a no-op, but current Bool4,
distributed TMS, and layered code implement the semantics described below.
This note relies on that code and its focused tests rather than treating every
historical status summary as current or claiming universal provenance coverage.

## Relationship To The Original Propagator Model

Radul assigns autonomous, asynchronous, stateless computation to propagators and
accumulated memory to cells. A stateful machine can be represented by propagators
with a private state cell. The original model therefore already permits compound
agents; it does not require every effect propagator to be a terminal sink.

This repository additionally treats the network itself as pure data. Network
description and network execution are separate levels. An agent-cell declaration
does not make an immutable value literally run or interact with reality.
Autonomy belongs to its execution; composability belongs to its description.

Agent descriptions might expand into ordinary propagator topology, or a runtime
might interpret an agent-cell description as a new primitive. Both are candidate
designs. The latter changes the execution model; neither follows automatically
from storing a description in a cell. Pure representation alone is insufficient:
the evaluator must preserve the explicit boundary for external observations and IO.

## Partial Information Includes Explained Contradiction

For Boolean propositions, the implementation distinguishes four knowledge states:

| State | Evidence |
|---|---|
| `nothing` | Neither true nor false is supported. |
| true | Positive evidence only. |
| false | Negative evidence only. |
| contradiction | Both positive and negative evidence. |

Arbitrary cell contents are richer than these four Boolean values. Bool4 supplies
the Boolean knowledge domain and bottom/top conventions; compound information
and TMS supply further structure. Contradiction is valid information, not a host
exception or an instruction to select whichever contribution arrived last.

For example, two active claims about one exact-valued collection period can say:

```text
period = 5 seconds, supported by A
period = 10 seconds, supported by B
join -> contradiction with provenance identifying the claims and premises
```

This is a meaningful composition result. Further propagation may explain the
conflict, seek evidence, or contribute premise updates. It need not immediately
choose a schedule. An exact period, a maximum period, and an independent
subscription still need distinct domain meanings; once the meaning is specified,
incompatible contributions can be represented without inventing a winner.

The distinction between content and strongest is essential. Distributed TMS
retains claims while premise state selects active conclusions. A later premise
update can change the projection from contradiction to one justified behavior,
and reactivation can restore the same conflict. Indexed TMS canonicalizes
superseded premise states to the latest epoch while retaining claims.

By contrast, bare contradiction merged into an ordinary scalar cell is absorbing.
An agent must preserve supported information where future premise changes matter;
flattening a TMS projection into ordinary content can lose that capability.
Conflict provenance is implemented in specific paths, not automatically attached
to every scalar merge. The current TMS test covers claim/premise provenance;
layered tests cover propagation of input and scoped-operator provenance.

## Proposed Meaning Of An Agent Cell

An agent cell is a persistent locus where descriptions of behavior, observations,
intentions, and interaction history accumulate as partial information, including
contradiction and provenance. It has identity, explicit interaction ports, and a
lifetime policy. It need not be a thread or an independently mutable object.
Ordinary information-only cells remain useful.

| Concern | Proposed owner | Input and output |
|---|---|---|
| Information | Cell content and merge | Contributions become accumulated facts. |
| Inference | Pure propagators | Facts produce messages and declarations. |
| Decision | Explicit policy in declared topology | Evidence and a decision boundary produce a committed request. |
| Interaction | Runtime interpreter | Requests produce external actions; observations and receipts return as facts. |
| Lifetime | Declared scope interpreted by runtime | Completion, cancellation, and consumer progress permit release. |

Domain policy stays visible in topology or cell semantics. The scheduler supplies
execution opportunities without deciding what a sensor, UI, or workflow means.
Sockets, callbacks, worker handles, and execution cursors stay outside durable
network values. Reading, merging, or copying a declaration performs no host IO.

Autonomy means the runtime can service a declared participant when its local
conditions permit. It does not mean a cell reads reality during pure evaluation.
A propagator spawning an agent declares an instance; runtime activation is a
separate operation. Equivalent redeclarations must preserve instance identity.

## Motivating Notation: Composing Participants

The user's sketch makes the intended composition concrete:

```clojure
(Time-> cell)

(-> logger-behaviour logger)
(Agent-description-> logger terminal)
(-> cell logger)

(-> llm-agent-behaviour agent-description-a agentA)
(-> llm-agent-behaviour agent-description-b agentB)
(Solve problem-input agentA agentB)

(-> information-collector (every 5s clock) collector)
(Pull collector cellB)
```

This is proposed notation, not executable repository syntax. In particular,
`->` denotes a proposed relationship here, not the Clojure threading macro.

The logger's behavior, source information, and terminal relationship contribute
independently to the same participant. Another source or observer could connect
to `logger` without rebuilding its execution procedure. The precise meaning of
`Agent-description->` remains open: describing a terminal relationship and
activating its runtime resource are distinct operations.

Similarly, `agentA` and `agentB` have continuing identities configured by behavior
and individual descriptions. `Solve` could declare relationships among their
proposals, critiques, evidence, and decisions. It does not yet specify a turn
protocol, termination rule, or authority to choose a final answer. Those policies
must be explicit before the sketch becomes an executable contract.

Contributing `llm-agent-behaviour` describes participation; reevaluating that
contribution must not call the LLM again. An invocation requires a new identified
obligation, such as a problem or received proposal. Responses return as facts.

For the collector, each clock tick can identify a collection opportunity. The
collector requests an observation and accumulates its result. Each observation
can be complete while the collector remains open. `Pull` still needs to choose
among three meanings: subscribe to observations, read a snapshot at an explicit
boundary, or demand a fresh collection. Demand needs an identity so repeated
evaluation does not repeatedly initiate the same collection. Periodic scheduling
also needs an explicit policy for delayed or missed ticks.

## Comparison With A Mono Input Effect Operator

A mono input effect operator such as `(log-effect cell)` usually expresses one
action on supplied information. Used only for output, it becomes a terminal
propagator; external input needs a source propagator. The continuing participant
that relates input, output, configuration, and lifetime then needs another home.

| Effect operator as invocation | Cell as agent |
|---|---|
| Represents an action to perform. | Represents a participant in relationships. |
| Composition connects action inputs and outputs. | Composition also refines the same participant through independent contributions. |
| Continuing identity and interaction state need another home. | The agent cell provides their semantic home. |

The limitation is not strictly the number of inputs. One input could carry a
compound description of behavior, configuration, messages, and requests. If it
has stable identity, accepts independent contributions, and exposes observations
and receipts, it effectively reconstructs the proposed agent abstraction.

An effect propagator may return receipts and therefore need not terminate the
graph. Receipts connect actions, but do not by themselves provide the shared
participant around which the sketch composes relationships. The current system
has ingredients for this; the proposed gain is a cohesive semantic home.

The refined proposal is therefore: **an agent cell is a locus of accumulated
information whose participation is defined by composable behavior, description,
and connection constraints.** This adds composition through contributions to an
agent's identity and behavior alongside composition through information flow.

Behavior composition can yield an explained contradiction; that does not make
the composition undefined. Choosing whether compatible periodic and explicit
demands share an in-flight request is still an execution-policy question. Keeping
both demands as supported information separates that decision from their merge.

## Agents Reasoning About Agents

The strongest opportunity is that configuring an agent can itself be a
partial-information computation. An agent's behavior can remain incomplete,
conditional, or contested while its identity and other relationships are known.
The existing behavior-valued TMS claims provide a concrete foundation, although
they do not yet implement the proposed agent abstraction.

For `(Solve problem-input agentA agentB)`, a possible composition is:

1. Agent A contributes a supported claim about a proposition.
2. Agent B contributes an incompatible supported claim about that proposition.
3. Their combination exposes contradiction and its provenance.
4. Another relationship uses that conflict to request evidence or a focused critique.
5. New observations or premise updates change the active conclusions.

This describes collaboration through shared supported knowledge. A diagnostic
participant can observe conflicts, a collector can seek missing evidence, and a
logger can record decision support using the same information model. Provenance
identifies sources; it does not establish that an LLM's claim is true. The scheme
also requires claims to address comparable propositions, not arbitrary text whose
semantic disagreement the merge operation would somehow infer automatically.

## Four Distinctions To Preserve

1. **Refinement:** more information becomes known.
2. **Decision commitment:** a particular action is chosen under an explicit policy.
3. **Execution:** that action interacts with the world and may produce a receipt.
4. **Lifetime completion:** a region no longer needs to remain available.

An append-only event history is monotone, but its current-value projection may
change. Later timestamps do not make a decision policy monotone. Choosing the
first answer observed can still depend on scheduler order unless the observation
boundary and selection policy are explicit.

Radul's decider separates partial reasoning from committed action. An effect
requires complete inputs for that action, not complete information everywhere.
Independent actions still require causal ordering where their external effects
interact. A receipt can provide a dependency for the next action.

In the knowledge order, `nothing -> true -> contradiction` is increasing
information. An effect fired on the intermediate true value may already have
occurred when contradictory evidence arrives. Thus being currently justified is
different from being committed for external execution. Absence of a known
contradiction is not proof that no further contradiction can be discovered.

An agent may act on stable historical evidence, an explicit decision for a fixed
input boundary, or a provisional projection whose contract permits correction.
Withdrawing a premise changes future inference; it cannot erase an external
action. Commitment policy belongs explicitly in the model rather than being an
accident of when the interpreter happens to inspect strongest.

## Network Composition Examples

The [companion note](agent-network-examples.md) compares six structures with
current mechanisms: open-list pipelines, feedback loops, shared observers,
dynamic agent groups, causal workflows, and replicated peers.

## What Makes These Structures Composable?

A shared event envelope alone is insufficient. Each participant needs explicit:

- **Ports and meanings:** observations, requests, and receipts are distinguishable.
- **Identity and causality:** equivalent declarations and repeated delivery agree
  on identity; decision inputs and action dependencies remain inspectable.
- **Readiness policy:** stable evidence, a committed decision, or a provisional
  update permits action without assuming global completion.
- **Lifetime and demand:** scopes define cancellation, finite observations,
  consumer progress, and what happens when producers outrun consumers.
- **Interpreter boundary:** declaration stays pure; execution supplies reality.

A compound agent should preserve these public contracts when internal topology
is substituted. That is the useful sense in which a network of agents could
itself act as an agent. It requires no claim that every cell is an OS process.

## Equilibrium, GC, And The Main Tradeoff

Equilibrium means no currently enabled propagation adds information. Completion
means enough is known for a particular question. Collectability means retaining
state cannot affect any permitted future computation or observation.

An open tail can coexist with equilibrium. A cell containing `nothing` is not
itself a GC root. Retained network values, callbacks, subscriptions, pending
requests, accessors, and consumers determine which regions remain reachable or
semantically necessary. The existing linked-list test separately seeds an empty
terminal accessor network in `../../test/propagators/compiler_2_gur_linked_list_test.clj`.

Agent cells could support local effects between bounded propagation batches.
They need not require concurrently mutating the network, but they would relax
the global quiescence barrier described in the current boundary-effect design.
Fairness and backpressure become necessary for long-running interaction.

Monotone histories can grow without bound. Bounded memory requires closed scopes,
consumer frontiers, equivalent summaries, external archival, or explicitly
limited history contracts. Acknowledgment alone does not authorize forgetting
facts that retained accessors or other consumers may still need. Physical
compaction preserves logical monotonicity only relative to a stated observation
contract; arbitrary historical inspection and bounded in-memory retention conflict.

The smallest promising direction is to retain pure propagators and runtime-owned
IO while exploring agent declarations, local decision boundaries, and explicit
lifetimes. One-time execution remains a useful bounded case. General agent
semantics should earn their complexity through a concrete composition example.

## Assessment

The proposal is coherent as an extension of partial-information semantics to the
description of interacting participants. Its strongest benefits are independent
contributions to one agent, supported alternatives in behavior, conflict-driven
collaboration, and higher-order declaration of whole groups as network data.

The main question is how an agent interprets supported, possibly contradictory
information into explicit commitments while retaining enough information to
explain and revise future behavior. Contradiction representation is already a
foundation; action policy, resource lifetime, execution identity, and fair
scheduling remain obligations. Copying a network value must distinguish a new
instance from a snapshot or replay of an existing external participant.

## Questions For A Future Experiment

1. Can an open-list mapper deliver one known element while preserving an unknown tail?
2. Do reordered fair activations yield the same committed requests for fixed inputs?
3. Does redeclaration reuse an agent, while explicit instantiation creates a new one?
4. Can a receipt advance a dependent action without repeating its source action?
5. Can two consumers progress independently and safely release an agreed prefix?
6. Can a bounded observer detach without retaining its source network?
7. Can simulation and live execution interpret the same declarations?
8. Can conflicting behavior claims expose provenance and recover through premise
   updates without reconstructing the agent or discarding the claims?
9. Can conflict-driven collaboration request evidence without confusing a
   provisional conclusion with a committed external action?

These are proposed verification criteria for agents, not implemented behavior.
Existing semantic foundations were checked on 2026-09-15 with:

```sh
clojure -M:test propagators.bool4-test propagators.tms-test propagators.layered-procedure-test
```

Result: 35 tests, 126 assertions, zero failures or errors (Bool4: 35 assertions;
TMS: 46; layered procedures: 45). This verifies the selected existing semantics,
not agent execution, effect commitment, convergence, or GC. No runtime code or
KIROSHI facts were changed for this proposal.
