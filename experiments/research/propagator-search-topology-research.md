# Propagator Search Topology and Strategy: Research Problem and Literature Review

Reviewed: 2026-10-12; updated with restoration literature and local evidence on 2026-10-13.
Status: research draft; the bounded checkpoint experiment below is not implementation of the overall architecture.

## 1. The question, not its future application

**Which search topology and strategy best exploit propagator refinement, incrementality, and concurrency, while keeping the declarative question separate from its solving mechanisms?**

The intended workloads are bounded knowledge reasoning and graph analysis. The future context is coordinating heterogeneous solvers and LLM agents: mechanically resolve what is determinable, expose remaining uncertainty, and ground further proposals in consistent, justified information. That context motivates the research; building an agent platform is not the immediate question.

Implementing Datalog in Lain or connecting an existing search engine is not the goal. The interesting problem is making different reasoning results cooperate: propagate partial information, assess their joint consistency, learn reusable restrictions from justified failures, and incrementally find a feasible or preferred answer.

"Optimal topology and strategy" means the best measured tradeoff for stated workloads and budgets, not a universally best architecture. Separately, an optimal *answer* requires a declared objective and sufficient evidence that no better feasible answer remains. Consistency alone establishes neither completeness nor optimality.

The question declares facts, unknowns, relations, constraints, and requested conclusions. Solver subnetworks own refinement, choices, probing, explanations, and learning. A solver should be replaceable for one unknown region without rewriting the question; different regions should also be able to cooperate using different strategies.

## 2. Variables and controls

| Variable | Alternatives to investigate |
|---|---|
| Search topology | Central choice controller, distributed local solvers, hierarchical regions, cooperative portfolios, hybrid |
| Reasoning scope | Individual constraint, connected region, specialized global deduction |
| Partial-information representation | Finite candidate sets, numeric bounds, mandatory/possible graph elements, supported facts |
| Refinement strength | Cheap bounds contraction, domain filtering, graph-global propagation, probing |
| Choice strategy | Variable selection, domain splitting, alternative ordering, failure-directed repair |
| Learning and sharing | Local explanations, context-qualified no-goods, selected shared deductions, branch summaries |
| Ownership | Which solver owns a hypothesis; which contractors may refine a shared variable |
| Incremental state | Deductions, indexes, and learned restrictions retained across updates or retractions |
| State restoration | Propagated premise withdrawal versus structurally shared immutable checkpoints; independent of learning policy |
| Execution conditions | Activation order, parallelism, delivery delays, duplicate and stale messages |

Hold the declarative question, fact versions, objective, verification oracle, and resource budget fixed within each comparison. Initially swap strategies by constructing a fresh immutable network from the same declaration; live strategy migration is a separate problem.

Distinguish the question graph, the solver communication graph, and the execution placement. Reorganizing solver communication must not silently change the question. Topology and strategy interact, so compare them jointly but also perform controlled ablations.

## 3. Semantic and architectural constraints

1. **Propagation is fundamental.** Partial domains and deductions must travel before values become concrete. An engine hidden behind one callback is an adapter baseline, not proof of propagator-native coordination.
2. **Refinement is sound.** Under fixed active assumptions, a contractor cannot remove a genuine solution. Candidate sets shrink; required graph elements may grow while possible elements shrink. Both increase information.
3. **Merge is explicit.** Restrictions combine by intersection; alternative answers do not. Supported facts, hypotheses, sound bounds, and heuristic suggestions cannot be treated as interchangeable payloads.
4. **Monotonicity is contextual.** Retracting an assumption may reopen possibilities. Learned knowledge must retain the conditions under which it is valid. TTMS freshness/support is not itself a domain contractor or a no-good inference algorithm.
5. **Concurrent refinement has algebraic obligations.** Merge must tolerate batching, reordering, and duplicates; computation must preserve the relevant information order. State convergence alone does not establish sound observations or safe choice ownership.
6. **Failure is justified.** Learn a restriction only from an explanation that establishes inconsistency. A failed heuristic or LLM attempt is not a proof that a candidate is impossible.
7. **Choice authority is explicit.** Several sound contractors may refine one variable. Independent speculative solvers cannot silently overwrite or conjoin competing choices for that variable. Portfolio branches remain distinct until a justified deduction is shared.
8. **The kernel remains generic.** Preserve immutable `Net`, runner constructor, compiler contract, generic cell evaluator, and TTMS policy. Extend using isolated data policies, layered procedures, ordinary propagators, and explicit composition; no private search executor or hidden mutable solver registry.
9. **Termination claims are scoped.** Local stability, a drained synchronous queue, distributed termination, verified satisfaction, exhaustive failure, and objective optimality are different claims. Budget exhaustion means incomplete.

For a finite candidate domain `D` and contractor `C`, the initial experiment should check:

```text
contracting:       C(D) is a subset of D
solution-safe:     solutions(D) are contained in C(D)
monotonic:        D1 subset D2 implies C(D1) subset C(D2)
restriction merge: associative, commutative, idempotent intersection
```

The full initial universe means unknown; an empty candidate domain means inconsistency under its supporting context, not ordinary `nothing`. A singleton still needs the question's constraints verified. Arbitrary real intervals need a declared precision/termination policy; finite-domain convergence must not be generalized to them automatically.

## 4. Evaluation criteria

| Criterion | Evidence required |
|---|---|
| Correctness | Oracle agreement, sound restrictions, justified failures, honest completion claims |
| Separation | Unchanged question when replacing a local strategy; no strategy branches in constraints |
| Composability | Cross-region deductions and failure explanations remain meaningful to another strategy |
| Propagation leverage | Candidates eliminated and deductions established before guesses; search avoided |
| Efficiency | Setup/runtime, activations, allocations, retained evidence, communications, topology growth |
| Incrementality | Affected work versus full recomputation; withdrawal and restoration correctness |
| Concurrency robustness | Equivalent fixed-context closure under valid reordered/duplicate updates; safe speculative ownership |
| Generality and readability | New primitives/adapters, code size, ordinary Lain expression, boundaries understandable locally |
| Future suitability | Proposal producers can participate without changing coordination semantics or receiving unchecked authority |

Correctness and question/strategy separation are gates, not scores to trade away for speed. Compare Pareto tradeoffs rather than hide them in an arbitrary weighted average. Measure first feasible answer, proven optimum, and deductive closure separately.

## 5. Literature review: recent related research

### Review method and limits

This is a focused narrative review, not a systematic or exhaustive survey. Sources were checked on the review date, prioritizing primary papers and author/publisher pages from 2024-2026. Older work is identified separately where it supplies indispensable foundations. Publication years come from the papers or venue metadata, not search-engine crawl dates. Abstracts and selected relevant sections were inspected; external artifacts were not built or benchmarked.

Each entry separates a reported contribution from its proposed relevance here. Published speedups are not Lain measurements. No paper below proves that TTMS, ordinary compound objects, or this runner already implements its algorithm.

### R1. Native constraint-level conflict learning — CP 2026

Marijnissen, Flippo, and Demirovic, *From Literals to Atomic Constraints: Generalising Conflict-Driven Clause Learning for Constraint Programming*. Published 2026-07-13, peer-reviewed conference paper.

The authors replace SAT literals with atomic constraints for conflict analysis, no-good learning, and propagation. Their Pumpkin implementation reports competitive performance and reduced failures, especially for domain-hole reasoning. **Relevance:** strategies might exchange restrictions in the vocabulary of the question rather than only concrete failed assignments. **Limit:** this needs an explanation/learning protocol; a set of TTMS premises is not sufficient by itself. [Paper and abstract](https://drops.dagstuhl.de/entities/document/10.4230/LIPIcs.CP.2026.42).

### R2. Global transitive propagation — 2026 preprint

Kletzander, Dekker, Schutt, and Stuckey, *Global Difference Constraint Propagation for Constraint Programming*. Submitted 2026-07-22; the paper notes an earlier, shorter PPDP 2008 version.

It groups constraints `x - y <= d` into a global bounds propagator, exploiting shortest-path structure and explaining deductions for lazy clause generation. Experiments report advantages over separate local propagation. **Relevance:** test specialized graph-global deductions against many small contractors, including cycles and long transitive chains. **Limit:** difference logic is a particular fragment, not arbitrary graph reasoning or nonlinear arithmetic; this is not an entirely new 2026 foundational idea. [Full text](https://arxiv.org/html/2607.20022v1).

### R3. Learning vocabulary and its costs — 2026 commentary

Ohrimenko, Stuckey, and Codish, *Lazy clause generation in retrospect*. Published 2026-07-09; publisher classifies it as a Comment.

The retrospective explains how a richer vocabulary of intermediate constraints can make learned restrictions reusable, and why adding learning does not automatically improve every model. **Relevance:** benchmark the usefulness and transport cost of shared explanations, not just their count. **Limit:** a commentary and model-specific examples are not a controlled comparison of our proposed topologies. [Article](https://link.springer.com/article/10.1007/s10601-026-09390-9).

### R4. Cooperative search-space partitioning — CPAIOR 2025

Davies, Didier, Perron, and Stuckey, *Parallelising Lazy Clause Generation with Trail Sharing*.

The study combines subtree reassignment, trail-prefix sharing, and buffered work stealing in CP-SAT. It identifies redundant work and communication as obstacles to splitting search in learning solvers, and reports improvements alongside portfolio/clause sharing. **Relevance:** compare isolated workers, shared no-goods, and transferred branch summaries; topology must account for what useful knowledge crosses its edges. **Limit:** these workers share a solver representation, not arbitrary heterogeneous propagators. [Author publication page and abstract](https://research.google/pubs/parallelising-lazy-clause-generation-with-trail-sharing/).

### R5. Sharing across heterogeneous strategies — LPAR 2024

Tammet, *Waste Reduction: Experiments in Sharing Clauses between Runs of a Portfolio of Strategies (Experimental Paper)*. Published 2024-05-26.

The paper investigates reuse of generated clauses across theorem-prover strategy runs and evaluates their usefulness or lack thereof. **Relevance:** sharing a common declarative question may enable reuse without requiring identical strategies. **Limit:** a clause-sharing interface is specific to theorem proving; arbitrary partial results cannot be transferred as if they were equivalent clauses. [Publication and abstract](https://easychair.org/publications/paper/wbJ2).

### R6. Rewriting topology without rewriting meaning — SIGMOD 2024

Chu et al., *Optimizing Distributed Protocols with Query Rewrites*.

The work uses order-insensitivity and dependency analysis to derive preconditions for correct decoupling and partitioning of distributed protocols. **Relevance:** treat solver topology as an execution/coordination choice whose semantic equivalence needs proof, not as the question itself. **Limit:** experiments concern protocols such as 2PC and Paxos, not constraint search; neither their throughput nor their rewrite rules transfer automatically. [Author-hosted paper](https://hydro.run/papers/david-sigmod-2024.pdf).

### R7. Certainty and completion — ICDT 2025

Power, Koutris, and Hellerstein, *The Free Termination Property of Queries over Time*. Published 2025-03-21.

It studies when a node can conclude a query answer despite possible future updates, complementing soundness/convergence questions with completeness. **Relevance:** a solver attachment needs explicit criteria for stable deductions, closed input contexts, and final results; local inactivity cannot stand in for global search completion. **Limit:** free termination is query- and update-model-dependent, not a generic termination detector. [Paper and abstract](https://drops.dagstuhl.de/entities/document/10.4230/LIPIcs.ICDT.2025.32).

### R8. Separating coordination from computation — 2025 preprint

Li and Lee, *A Preliminary Model of Coordination-free Consistency*. Submitted 2025-04-01.

It formalizes confluence, monotonicity, consistency, and coordination-free computation, giving conditions for strong eventual consistency and a distributed-computation account of CALM. **Relevance:** establish the order and delivery assumptions under which independently produced refinements can converge. **Limit:** a preliminary theoretical model is not proof of correct choice arbitration, hypothesis retraction, or optimization in this infrastructure. [Preprint abstract](https://arxiv.org/abs/2504.01141).

### R9. Incremental computation with eventual consistency — PaPoC 2024

Power, Achalla, Cottone, Macasaet, and Hellerstein, *Wrapping Rings in Lattices: An Algebraic Symbiosis of Incremental View Maintenance and Eventual Consistency*.

The work reconciles group/ring-based incremental computations with lattice-based replication. **Relevance:** distinguish monotone retained transport from an application projection that can change through deletion; not every useful incremental computation is itself monotone set growth. **Limit:** the construction's algebraic and delivery assumptions must be checked before adaptation; it is not an instruction to replace TTMS with signed counts. [Author research summary](https://hydro.run/research/#wrapping-rings-in-lattices-an-algebraic-symbiosis-of-incremental-view-maintenance-and-eventual-consistency-papoc-2024); [2025 author dissertation, Chapter 5](https://www2.eecs.berkeley.edu/Pubs/TechRpts/2025/Archive/EECS-2025-103.pdf).

### R10. Dynamic reasoning circuits — Datalog 2024

Alves de Lima, Kramer, Apinis, and Micinski, *Incremental Evaluation of Dynamic Datalog Programs as a Higher-order DBSP Program*.

The authors model positive Datalog evaluation as a circuit where facts and rules can be added and retracted. Their evaluation reports workload-specific update advantages over DDLog and Souffle. **Relevance:** investigate reusable deductions and explicit change propagation across reasoning regions. **Limit:** positive least-fixed-point evaluation is not speculative constraint search, and cyclic deletion handling cannot be reduced to naive proof counting. [Workshop paper](https://ceur-ws.org/Vol-3801/paper1.pdf).

### R11. Structural planning and information passing — PVLDB 2025

Zhao et al., *FlowLog: Efficient and Extensible Datalog via Incrementality*. PVLDB 19(3), 361-374, 2025.

FlowLog separates per-rule relational plans from recursive control, uses structural planning and sideways information passing, and executes through Differential Dataflow. **Relevance:** graph shape, intermediate-result size, and early filtering belong in the topology comparison; robust composition need not use an opaque all-purpose engine. **Limit:** SQL-style semijoins and differential operators have different contracts from finite-domain narrowing and TTMS. The paper's year is 2025 even though the associated VLDB conference cycle is 2026. [Paper](https://www.vldb.org/pvldb/vol19/p361-zhao.pdf).

### R12. Cooperation between search and propagation — 2026 preprint

Marijnissen, Beck, Demirovic, and Kuroiwa, *Domain-Independent Dynamic Programming with Constraint Propagation*. Submitted 2026-03-17, revised 2026-04-28.

The authors integrate CP into a domain-independent DP framework to prune states and transitions. Their experiments show fewer expansions, with runtime benefit depending on constraint strength and propagation overhead. **Relevance:** compare useful pruning against the cost of coordinating different solving mechanisms. **Limit:** this couples specific model representations; it does not demonstrate arbitrary local solver substitution in an unchanged network. [Full text](https://arxiv.org/html/2603.16648v2).

### R13. Efficient state restoration — ICFP 2024

Allain, Clement, Moine, and Scherer, *Snapshottable Stores*. Published 2024-08-15.

The paper presents efficient capture/restoration of a store of mutable references, with persistent snapshots and a mechanized core proof, for backtracking in type-checking and theorem proving. **Relevance:** state restoration deserves its own comparison alongside topology, strategy, and learning. **Limit:** the implementation is an imperative store, not a claim about Clojure persistent-map costs, TTMS withdrawal, or the reversibility of external effects. [Author-hosted paper](https://cs.nyu.edu/~am15509/publications/allain-clement-moine-scherer-24.pdf).

## 6. Foundations that remain necessary

- **Radul/Sussman propagators:** partial information, extensible merge, and hypothetical premises supply the starting model, not a new search contribution. [Revised Report](https://groups.csail.mit.edu/mac/users/gjs/propagators/).
- **Finite-domain propagation:** contracting/monotone operators, fixpoints, and staged cheap/strong propagation ground correctness and scheduling. [Schulte/Stuckey, 2008](https://www.gecode.org/papers/SchulteStuckey_TOPLAS_2008.pdf).
- **Contractor composition and grouped reasoning:** forward/backward contraction, shaving, and constructive disjunction are directly relevant mechanisms. [IBEX documentation](https://ibex-team.github.io/ibex-lib/contractor.html); [Lagerkvist/Schulte, Propagator Groups, 2009](https://chschulte.github.io/papers/lagerkvistschulte-cp-2009.pdf).
- **Distributed constraint optimization:** constraint graphs, pseudo-trees, and inference/search tradeoffs are established prior art for topology comparison. [Fioretto/Pontelli/Yeoh survey, JAIR 2018; preprint first submitted 2016](https://arxiv.org/abs/1602.06347).
- **Restoration techniques:** copying, trailing, and recomputation have long been compared in constraint programming. TTMS withdrawal is not ordinary mutation trailing; their costs must be measured separately. [Schulte, 1999](https://www.ps.uni-saarland.de/Publications/documents/Schulte_99a.pdf).
- **Structured graph domains:** mandatory/possible graph bounds allow reasoning about unknown topology rather than only accumulating known edges. [Choco variable documentation](https://choco-solver.org/docs/modeling/variables/).

## 7. Synthesis and five testable hypotheses

The following are inferences from the review, not published findings about Lain. No source establishes a universally best topology. Useful explanation vocabularies, pruning strength, communication cost, and completion boundaries must be evaluated together.

| Hypothesis | Proposed composition | Predicted advantage | Main falsification risk |
|---|---|---|---|
| H1: Local refinement first | Small bidirectional contractors over shared domains; explicit AMB ownership for unresolved choices | Cheap, legible baseline with minimal extension | Weak pruning makes activation/search overhead dominate |
| H2: Hybrid graph-global reasoning | H1 plus a specialized contractor for selected transitive/difference-constraint regions | Fewer repeated deductions across chains and cycles | Global bookkeeping costs more than it saves; fragment too narrow |
| H3: Hierarchical region cooperation | Region-local solving with boundary restrictions and context-qualified explanations | Contain local work and reuse it when distant regions change | Separators become large, incomplete, or expensive to summarize |
| H4: Cooperative strategy portfolio | Distinct speculative branches; share only justified restrictions, bounds, and selected explanations | Different strategies escape different weaknesses without duplicating everything | Contaminated contexts, sharing overhead, or redundant search |
| H5: Incremental demand-directed cooperation | Activate relevant reasoning regions; reuse versioned deductions and selectively invalidate affected work | Low latency for selective queries and small updates | Index/evidence maintenance dominates; cycles or retractions break reuse |

These are not five unrelated engines. Build H1 first, then compare additions and combinations against the same question. Test bounds filtering versus stronger candidate filtering as an orthogonal variable. H3 must preserve cross-region constraints; graph partitioning does not make connected regions logically independent.

The first decisive composition should have one region eliminate possibilities for another, a failure depending on choices from both, a learned restriction useful on a later attempt, and an update that leaves unrelated deductions reusable. AMB is part of a solver subnetwork, not a strategy-specific runner loop.

## 8. Experiment direction and acceptance gates

Use bounded graph/knowledge questions as the primary workload:

- Transitive reachability and constrained paths through chains, diamonds, cycles, and disconnected regions.
- Mandatory/possible edges with endpoint, connectivity, and finite path-cost restrictions.
- Small entity-candidate matching questions with shared relational constraints and conflicting proposals.
- Difference-constraint chains and inconsistent cycles to exercise H2's specialized fragment.
- Insert, withdraw, and restore a supported fact or hypothesis; repeat under reordered and duplicate deliveries.

Keep Pythagorean triples as a mechanism check, not the architecture's defining benchmark. Separate positive entailment, bounded completion search, and objective optimization: all-pairs closure, one witness, and minimum-cost witness are not equivalent workloads. An absent fact is unknown unless the question explicitly establishes a closed-world boundary.

For every applicable hypothesis, verify oracle agreement, unchanged question declarations, sound cross-region deductions, explanation scope, idempotence, and honest incomplete outcomes before timing. Compare at least two local strategy attachments and one heterogeneous composition. Fresh installations may have different solver topology; their question topology and semantics must remain fixed.

For deterministic fixed-context refinement, compare final closure across fair activation orders. For speculative search, compare validity/completeness and objective quality, not necessarily the same first witness. A synchronous empty queue with delayed producers is not global completion. Retraction tests must distinguish monotone transport from widening projected domains.

Measure cold setup, warm inference, first witness, proof of exhaustion/optimality, update latency, pruning before the first guess, activations, allocations, retained evidence, topology growth, and communication/sharing costs. Use identical input versions and budgets, warmups and repeated runs, and report distributions/timeouts. Include no-learning/no-sharing/full-recompute ablations. Report provenance overhead with equivalent explanation requirements, not an unannotated baseline alone.

LLM integration is deferred. Initially use controlled proposal producers to test the coordination boundary. A later model may suggest candidates or priorities, but cannot assert a mechanically unverified restriction or no-good as a fact. The value of actual agent participation should be measured separately from the network mechanism.

Stop and revise if a composition requires domain/search branches in the constructor, hidden mutable registries, changing global TTMS policy, or redefining question semantics to fit a strategy. No production migration or external solver installation is authorized by this research draft.

## 9. Current repository grounding and next evidence

Inspected checkout: `5ab235970c67000d7efa691947053e941b125cf5` (2026-10-12 review). These are current code/document observations, not freshly executed tests:

- [`network-iterator-constructor`](../../propagators/runner_constructor.clj) remains generic CPS activation/patch/task plumbing; no search-specific branch is needed or proposed.
- [`AMB primitives`](../../propagators/experimental/amb/primitives.clj) implement ordinary choice, requirement, learning, and repair propagators. `choice-activation` explicitly rejects a changing domain. [`The builder`](../../examples/lain/amb/builder.clj) assembles the problem and shared repair network together; independently swappable attachments remain to be demonstrated.
- [`AMB documentation`](../amb/README.md) distinguishes logical no-goods from TTMS dominance and records static-domain limitations. It does not establish a dynamic domain contractor or a heterogeneous solver protocol.
- [`Relational runtime`](../../examples/lain/relational_datalog/runtime.clj) supplies experiment-local tuple union through composed patch evaluation. [`Its report`](../relational_datalog/README.md) explicitly distinguishes this from finite-domain narrowing. This demonstrates an isolation pattern, not completion of this research.
- [`Cell merge`](../../propagators/cells/merge.clj) treats unequal default plain values as contradiction. Candidate restrictions cannot simply use that default as intersection. The experiment needs an isolated refinement representation/policy while retaining production semantics.

Next implementation evidence: prove domain laws and ordinary network refinement; separate a fixed graph question from two solver attachments; demonstrate cross-attachment explanation/repair; then compare the five hypotheses incrementally. How stronger probing detects completion and how explanations cross AMB ownership regions are open design questions, not solved contracts.

This document changes no runtime, compiler, solver, KIROSHI facts, or benchmark results. It records the agreed research problem, a sourced literature review, and hypotheses for later experiments.

## 10. Subsequent local evidence — 2026-10-13

[Matched checkpoint/withdrawal DFS](../amb/CHECKPOINT_REPORT.md) used the same supported Pythagorean constraint network and no-learning DFS ordering. Across bounds 4, 5, and 8, checkpoints were 1.90-1.94x faster with identical search work and oracle-correct answers, using roughly half the activations. These are small single-thread builder cases, not completion of H1-H5. The separate learning-enabled AMB reference uses a different policy/topology; its timing is not a restoration-only comparison. Combined learning, range refinement, graph workloads, concurrent search, and BFS remain unverified.
