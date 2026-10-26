# How to Think in Lain
A Relational and Compositional Reasoning Guide for AI Coding Agents

Checked against the current Compiler-2 language on 2026-10-26. The domain illustrations in sections 1–16 are schematic where their helpers are not defined. Sections 17–24 give concrete syntax, explicit library imports, and runnable branching and recursion examples. Do not mistake an application-specific verb for a built-in operator.

## 1. Core Philosophy: Compose Relationships into Expressions
Lain is a Lisp-like, propagator-based coordination language for describing high-level interactions between components.
Its central programming abstraction is the composition of transformations and relationships. Drawing on lambda calculus and functional composition, Lain allows programmers to construct, connect, and transform propagator networks through reusable abstractions.
Compose verbs into sentences. Do not concatenate nouns into names.
A Lain program should read like a meaningful expression describing how components relate, constrain, coordinate, and transform one another.
Think of a synthesizer, an electrical circuit, or a chemical reaction. The essential structure emerges from how components interact and what relationships their interactions maintain.
Cells provide the medium through which information propagates. They support the network’s operation, while the program’s primary expression concerns the relationships between its components.
This philosophy is related to Alan Kay’s emphasis on messaging and interaction in object-oriented programming. Lain extends this perspective through compositional network transformations and multidirectional information propagation.
Fundamental principles:
* Think in transformations, connections, constraints, and coordination.
* Express complex behavior by composing smaller relationships.
* Treat network topology as part of the program’s meaning.
* Preserve the distinction between a relationship and a particular procedure for evaluating it.
* Use cells and internal machinery when their semantics require them.
* Prefer reusable relational abstractions over specialized containers.
The ideal Lain program communicates what relationships hold, how they compose, and what information can propagate through them.

⸻

## 2. Think in Verbs, Relations, and Sentences
A common programming strategy begins by identifying entities, constructing data structures, and attaching procedures to them.
Lain encourages starting from the relationships themselves.
Think in terms of:
* Relating, connecting, and constraining
* Transforming, mapping, and combining
* Unifying and synchronizing
* Observing, filtering, and propagating
* Accumulating and maintaining
* Composing and reorganizing
These verbs should correspond to meaningful operations or relationships that can participate in larger network expressions.
For example:
(-> (sender-slot-of op-a)
    (receiver-slot-of op-b))
This expression communicates the coordination directly.
The components expose meaningful endpoints, and the connection expresses how they interact.
Compose meaning rather than names
Avoid creating large abstractions whose names merely concatenate the operations they contain.
For example, a name such as:
user-input-validation-state-update-history-protocol
may conceal a large collection of cells, propagators, and fixed processing steps.
Look for an expression of the underlying relationships:
;; Conceptual composition
(-> (|> source
        normalize
        constrain
        observe)
    destination)
The composed expression exposes its structure and makes the individual transformations available for reuse.
Naming should capture a meaningful abstraction. Composition should express how abstractions relate.
A name is justified when it identifies a reusable domain concept, mathematical relationship, or network transformation.
A name is insufficient when it merely hides complexity.
Important distinction: verbs do not imply imperative execution
A sequence of verbs can still describe a procedural pipeline:
(read input)
(validate input)
(transform input)
(write output)
The goal is to express relationships with clear propagation semantics.
A composition may legitimately be directional. A constraint may be multidirectional. A network may contain several concurrently active relationships.
Choose the appropriate structure according to the semantics of the problem.
Heuristic: Before introducing a new named protocol, ask whether its meaning can be expressed through the composition of existing transformations.

⸻

## 3. Think Like a Circuit Designer
Conceive a Lain program as a network of interacting transformations.
Consider a schematic chemical relationship:
2 H₂O → H₂ + 2 OH
This is an illustrative transformation expression, not a chemically balanced reaction.
A conceptual Lain representation might be:
```clojure
;; Schematic: the molecular operations require application definitions.
(network [water hydrogen hydroxide]
  (<-> (h2-of water) hydrogen)
  (<-> (molecule:* 2 (oh-of water)) hydroxide))
```
This example is schematic and does not establish the chemical or operational semantics of the operators.
Its purpose is to demonstrate that the network exposes relationships between participants.
The same design perspective applies to electrical circuits, reactive interfaces, constraint systems, and distributed coordination.
Before implementation, identify:
1. What relationships must hold?
2. Which transformations express those relationships?
3. Which components participate?
4. How can the transformations be composed?
5. Which relationships have useful symmetries?
6. What information can be inferred from different endpoints?
Defer decisions about intermediate cells, storage, and internal plumbing until the relational structure is clear.
Heuristic: Begin by describing the network’s meaning. Derive its implementation structure from that description.

⸻

## 4. Declare Relationships, Not Execution Sequences
Lain networks describe concurrently existing relationships within activated topology. An unselected conditional body need not be expanded.
The textual order of network declarations should not be interpreted as an imperative execution sequence. Lexical scope, name binding, library loading, and explicit effect boundaries still matter; dataflow does not erase those constraints.
Consider:
```clojure
;; Schematic: f, g, and h must be defined by the application.
(define e
  (network [a b c]
    (-> (f a) b)
    (-> (g b) c)
    (-> (h c) a)
    (list a b c)))
```
These declarations describe network topology.
They do not imply that the first relationship must finish before the second begins.
Information propagates according to the dependencies and propagation semantics of the network.
Reason about topology
When reading or writing a network:
* Treat declarations as simultaneously existing relationships.
* Represent dependencies through connections.
* Allow propagation to follow available information.
* Avoid using source-code order to express control flow.
* Make ordering, buffering, and timing explicit when necessary.
* Consider feedback, convergence, and conflicting information.
A network is a persistent organization of relationships whose information state may evolve.
Anti-pattern: imperative thinking disguised as propagators
An agent may translate each step of a procedural algorithm into a separate propagator while retaining the original execution sequence.
This can produce syntactically valid Lain code with little benefit from the language’s relational capabilities.
Correction: Reconsider the problem as a network of constraints and interactions. Determine which dependencies are intrinsic to the domain and which were introduced by the procedural decomposition.

⸻

## 5. Search for Symmetry and Multidirectionality
One of the central opportunities in propagator-oriented programming is multidirectional computation.
A conventional procedure often assumes:
inputs → computation → result
A relation may support inference from several combinations of known information.
For example:
a + b = c
This relationship supports:
a + b → c
c - b → a
c - a → b
A conceptual Lain implementation:
```clojure
;; Also provided by combinator.lain. All three ports are explicit.
(define constraint:+
  (network [a b c]
    (-> (+ a b) c)
    (-> (- c b) a)
    (-> (- c a) b)
    (list a b c)))
```
The three propagators jointly express a multidirectional arithmetic constraint.
When appropriate information is available at two endpoints, the network can derive information about the third, assuming the operators support the necessary partial-information behavior.
The relationship carries more potential information than a single forward procedure.
Generalize through composition
A higher-order abstraction can construct arithmetic constraints from reusable components:
(define make-composable-constraint-arithmetic
  (network [arith]
    (network [a b]
      (let-cell [c]
        (apply arith (list a b c))
        c))))
`apply` takes a callable and one linked argument list, not a separate operand for every argument. This wrapper returns the scalar c; do not assume it exports every reverse connection of a relation that explicitly returns all three ports. The precise behavior depends on arith and its declared interface.
The essential idea is to express a family of relationships through composition.
Heuristics:
* Search for algebraic and structural symmetries.
* Identify what can be inferred from different combinations of available information.
* Prefer reusable multidirectional constraints where meaningful.
* Compose constraints from small propagators.
* Preserve the ability to refine information over time.
* Never assume that an arbitrary transformation is reversible.
Multidirectionality must be justified by the relationship’s semantics.

⸻

## 6. Use Coordination Operators as Relational Grammar
Two coordination operators used in this guide are shown below. `->` is core wiring; `<->` is installed by the compositional extension. Load its actual implementation before using the extension forms:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
```

Their forms are:
(-> source destination)
and:
(<-> endpoint-a endpoint-b)
Conceptually:
* -> expresses directed propagation or coordination.
* <-> expresses a symmetric or bidirectional relationship between compatible endpoints.
These operators form a concise vocabulary for expressing network structure.
Prefer direct coordination
A lower-level implementation might manually access component internals, create intermediate structures, and install propagators.
When the intention is to connect two endpoints, express that directly:
(-> (sender-slot-of op-a)
    (receiver-slot-of op-b))
Expose meaningful symmetry
When two endpoints support a symmetric relationship:
(<-> (shared-value-of op-a)
     (shared-value-of op-b))
The operator makes the symmetry explicit in the network expression.
Its exact behavior depends on the definitions of the endpoints and the implementation.
Use directed propagation for relationships that are inherently directional or lossy unless meaningful reverse semantics are explicitly defined.
Heuristic: Let coordination operators express the relationships. Keep connection mechanics behind appropriate abstractions.

⸻

## 7. Make Network Transformation a Compositional Abstraction
In Lain, network transformations can be expressed through function transformations.
This allows higher-order functional composition to organize and transform network structure.
A programmer should therefore reason about networks as composable expressions.
Complex coordination should emerge from applying and composing small transformations.
Compose small transformations
After loading combinator.lain, the implemented pipe operator can express:
(|> input
    transformation-a
    transformation-b
    transformation-c)
The pipe represents a composition of transformations.
For example:
(-> (|> op-a
        compound-slot-accessor
        (slot :need-slot)
        (switch interested-information?)
        transform-function)
    (|> op-b
        receiver-slot-accessor))
The expression communicates how information is accessed, selected, transformed, and connected.
`|>` is an implemented library operator, not new parser syntax. It passes an endpoint through unary stages from left to right; `compose` returns a unary stage and applies its stages right to left. The domain-specific stage names in these schematic examples still require application definitions.
Compose compatible endpoints
For example:
(<-> (|> op-a
         (slot :position))
     (|> op-b
         (slot :position)))
This describes a relationship between composed views of two components.
A composition mechanism may internally construct cells, propagators, or network fragments. Those implementation details should remain encapsulated.
Preserve higher-order composition
When designing abstractions:
* Prefer functions that produce reusable network transformations.
* Make transformations composable with other transformations.
* Avoid unnecessary specialization.
* Preserve the semantic properties of composed endpoints.
* Keep intermediate structures hidden unless they are meaningful to the caller.
Heuristic: When a network expression becomes complicated, search for a reusable transformation that simplifies the composition without concealing its essential relationships.

⸻

## 8. Introduce Cells for Meaningful State, Not Procedural Plumbing
Cells are fundamental to information propagation.
However, an agent should not begin implementation by allocating a large collection of cells and subsequently inventing a protocol to contain them.
This is a common failure mode.
Cell-first design
A problematic approach:
1. Create many intermediate cells.
2. Assign a procedural stage to each cell.
3. Connect the cells into a fixed pipeline.
4. Wrap the entire structure in a large named protocol.
This reproduces a conventional data-processing architecture using propagator machinery.
Relationship-first design
A preferable approach:
1. Identify the domain relationships.
2. Find existing transformations that express them.
3. Compose those transformations.
4. Introduce cells where the network requires independently meaningful information state.
5. Encapsulate internal plumbing behind reusable abstractions.
A dedicated cell is appropriate when it represents:
* Independently retained information
* Shared knowledge among multiple propagators
* A meaningful network endpoint
* Feedback state
* Explicit temporal state
* Information that must be accumulated or refined
Heuristic: Every explicit cell should have a clear semantic role.
If a cell exists only to carry a temporary value between two composable transformations, consider eliminating it from the high-level expression.

⸻

## 9. Make Time, History, and State Explicit
Concurrent relationships require explicit treatment of temporal behavior.
A propagator network may evolve as information becomes available, changes, or is refined.
Temporal operations should be expressed as transformations with clear semantics.
For example:
(-> (|> op-a
        slot-accessor
        (history 5))
    slot-full-history)
`|>` and `(history n)` are implemented by combinator.lain. Here `(history 5)` means at most five incoming publications per application, not a five-second time window. The domain-specific source/accessor names remain schematic.
Potential temporal transformations include:
* Sampling and debouncing
* Delaying and buffering
* Accumulating and windowing
* Deduplicating and batching
* Synchronizing and retaining history
These transformations can become reusable coordination operators.
Heuristics:
* Use explicit stateful transformations when information must persist.
* Define event ordering and retention behavior.
* Avoid relying on incidental scheduling order.
* Represent meaningful temporal state through appropriate cells or network abstractions.
* Consider unbounded history and memory growth.
* Distinguish information refinement from temporal replacement where relevant.
Whenever timing affects correctness, make it part of the declared network semantics.

⸻

## 10. Separate Domain Relationships from Crosscutting Capabilities
A Lain program should keep its primary domain relationships legible.
Observability, tracing, logging, debugging, history, and similar capabilities can be introduced through additional network relationships where appropriate.
For example:
(network [source destination audit]
  (-> (|> source
          normalize
          validate)
      destination)

  (-> (|> source
          observe)
      audit))
This example uses the implemented combinator library together with application-defined normalize, validate, and observe transformations.
The domain relationship remains visible while obsconervability is composed into the network.
Potential crosscutting capabilities include:
* Observability and tracing
* Debugging and introspection
* Error detection
* Provenance and explanation
* Performance measurement
* Persistence and history
* Visualization
* Conflict reporting
These capabilities should be designed as reusable transformations whenever their semantics permit.
Heuristic: Express the smallest clear domain network first. Compose supporting capabilities around it.

⸻

## 11. Preserve Abstraction Boundaries
A high-level Lain program should expose the intention of a network while hiding incidental machinery.
Avoid manipulating internal data representations when suitable constructors, accessors, or network transformations exist.
Prefer:
(-> (output-of component-a)
    (input-of component-b))
over manually extracting storage, allocating intermediate structures, and installing connections.
Data abstraction rules
* Construct structured values through explicit constructors.
* Access structured values through explicit accessors.
* Never directly construct internal data structures in the middle of unrelated network logic.
* Keep representation details behind public abstractions.
* Encapsulate internal network topology when callers do not need to know it.
* Use reusable combinators for recurring coordination patterns.
A high-level network should remain understandable even if the internal representation of its cells or propagators changes.
Heuristic: Abstractions should preserve the meaning of relationships while allowing their implementation to evolve.

⸻

## 12. Build a Vocabulary of Small, Reusable Transformations
Treat network transformations as composable building blocks.
A useful abstraction should ideally be:
* Composable: It participates in larger network expressions.
* Declarative: Its meaning can be understood independently of execution order.
* Encapsulated: Its implementation details can remain hidden.
* Reusable: It captures a general coordination pattern.
* Inspectable: Its contribution to the network can be observed.
* Directionally explicit: Its propagation and symmetry guarantees are clear.
Prefer a small vocabulary of general transformations over a growing collection of specialized protocols.
However, naming remains useful when it captures a genuine concept.
For example:
constraint:+
history
normalize
synchronize
These names identify meaningful relationships or transformations.
The distinction is between naming a reusable concept and naming a large collection of implementation steps.
Heuristic: Introduce abstractions that enrich the language’s compositional vocabulary.
Avoid abstractions that merely relocate complexity into a larger container.

⸻

## 13. Practical Reasoning Procedure for AI Agents
When implementing a feature in Lain, follow this reasoning process.
Step 1 — Express the problem as relationships
Describe what the system must maintain, relate, constrain, communicate, or transform.
Begin with a compact conceptual network.
Do not start by designing data structures or protocols.
Step 2 — Identify the verbs
Find the smallest meaningful transformations and coordination operations.
Search for existing functions, propagators, and network combinators that express them.
Step 3 — Compose a sentence
Try to express the intended behavior through the composition of those operations.
Ask:
Can the implementation read as a meaningful relational expression?
Prefer a short, compositional network expression over a large named protocol.
Step 4 — Search for symmetry
Determine whether relationships support inference in multiple directions.
Identify the conditions under which each direction is valid.
Preserve multidirectionality when it is semantically meaningful.
Step 5 — Construct the network
Use network, ->, <->, and appropriate existing combinators to express the coordination.
Treat declarations as concurrently existing relationships.
Use only operators supported by the actual implementation.
Step 6 — Introduce necessary state
Add cells for meaningful information state, shared knowledge, feedback, or retention.
Avoid intermediate cells that merely reproduce a procedural pipeline.
Step 7 — Verify propagation semantics
Check for:
* Accidental dependencies on evaluation order
* Cyclic propagation
* Convergence assumptions
* Conflicting information
* Unsupported inverse operations
* Unbounded state or history
* Ambiguous timing behavior
Do not assume scheduling, conflict-resolution, or truth-maintenance mechanisms that have not been implemented.
Step 8 — Add supporting capabilities
Compose tracing, observability, validation, and other crosscutting behavior where appropriate.
Keep the primary domain relationships visible.
Step 9 — Simplify the expression
Look for redundant plumbing, unnecessary cells, repeated coordination patterns, and overly specialized protocols.
Ask whether existing transformations can be composed more directly.
Step 10 — Review the conceptual model
Before finalizing, ask:
Am I describing a network of relationships, or have I merely translated an imperative algorithm into propagator syntax?
If the latter, reconsider the decomposition.

⸻

## 14. Common Anti-Patterns
A. Imperative pipelines disguised as networks
Symptom: Propagators are arranged as a fixed sequence of processing stages, with each stage treated as a command that must execute after the previous one.
Problem: The implementation reproduces imperative control flow using propagator syntax. The network’s relational semantics become secondary to a predetermined execution path.
Correction: Identify the relationships and dependencies that genuinely exist in the domain. Express them as network connections rather than procedural steps.
A directional pipeline is appropriate when the domain is inherently directional. The anti-pattern is assuming that every computation must be organized this way.
Heuristic: Ask whether the network describes relationships or merely schedules operations.
B. Monolithic protocols that hide collections of cells
Symptom: A large protocol contains numerous cells, propagators, accessors, and internal connections, often accompanied by an excessively long descriptive name.
Problem: The abstraction hides the network’s composition instead of expressing it. The programmer must understand the protocol’s implementation to discover the relationships it represents.
Correction: Decompose the protocol into small, reusable transformations. Compose these transformations directly to express the intended behavior.
Introduce a named protocol only when it represents a coherent, reusable abstraction with meaningful semantics.
Heuristic: If a protocol’s name describes a sequence of operations rather than a concept, consider replacing it with a compositional expression.
C. Concatenating nouns instead of composing verbs
Symptom: Complex behavior is represented by increasingly elaborate identifiers that combine entity names, processing stages, and implementation details.
For example:
user-input-validation-state-update-history-protocol
Problem: The name compresses a network into an opaque identity. Its internal grammar is lost at the call site.
Correction: Express the behavior through smaller transformations and explicit relationships.
Conceptually:
(-> (|> user-input
        normalize
        validate)
    state)
The pipe is implemented by combinator.lain; normalize and validate remain application-defined.
Heuristic: Compose meaning through expressions. Use names to identify reusable concepts, not to substitute for composition.
D. Cell-first design
Symptom: Implementation begins by allocating numerous cells before determining what relationships the network should express.
Problem: The resulting architecture is organized around storage locations rather than information relationships. Intermediate cells may reflect procedural stages instead of meaningful state.
Correction: Identify the domain relationships first. Compose transformations, then introduce cells wherever independently meaningful information must be retained, shared, or refined.
Heuristic: Every explicitly introduced cell should have a clear semantic purpose.
E. Manual plumbing instead of generic coordination
Symptom: The code repeatedly manipulates internal slots, allocates intermediate structures, or installs low-level connections to express simple relationships.
Problem: Implementation details obscure the network’s meaning and increase coupling to internal representations.
Correction: Use existing accessors, constructors, and generic coordination operators.
Prefer:
(-> (output-of component-a)
    (input-of component-b))
over manually reproducing the mechanics of the connection.
Heuristic: Expose meaningful endpoints and compose their relationships.
F. Unnecessary one-way computation
Symptom: A relationship with useful algebraic or structural symmetry is implemented only as a forward calculation.
Problem: The implementation discards opportunities to infer information from other combinations of known values.
Correction: Examine the underlying relationship for meaningful multidirectional inference. Where justified, express the relationship through cooperating propagators or reusable constraints.
Heuristic: Ask what can be inferred when different endpoints become known.
G. Unjustified bidirectionality
Symptom: A transformation is connected bidirectionally merely because symmetric syntax is available.
Problem: Not every transformation has a meaningful inverse. Filtering, aggregation, and lossy transformations may destroy information required for reverse inference.
Correction: Distinguish genuine relational symmetry from directed information transformation.
Use <-> only when the participating endpoints and relationship support its intended semantics.
Heuristic: Bidirectionality is a semantic property, not a stylistic preference.
H. Hidden execution-order assumptions
Symptom: Correctness depends on the order in which propagators happen to execute or on the textual order of network declarations.
Problem: The program relies on scheduling behavior that may not be guaranteed by the network semantics.
Correction: Represent causal dependencies through explicit relationships. Model sequencing, synchronization, buffering, and temporal behavior when they are necessary.
Heuristic: A network declaration describes topology, not an execution script.
I. Accidental feedback and unsupported convergence assumptions
Symptom: Cyclic networks are introduced without examining how information propagates through feedback paths.
Problem: Feedback may cause repeated updates, nontermination, conflicting information, or unexpected state growth, depending on the propagators and information domains.
Correction: Examine cycles explicitly. Determine whether the transformations are monotonic, whether convergence is expected, and how conflicting information is handled.
Do not assume that every cyclic network converges or that the runtime automatically resolves inconsistencies.
Heuristic: Every feedback relationship should have an understood propagation and convergence model.
J. Hidden temporal state
Symptom: Propagators implicitly depend on previous values, event ordering, or accumulated history without making those dependencies visible.
Problem: Temporal semantics become embedded in implementation details, making the network difficult to reason about and reuse.
Correction: Express history, accumulation, delay, sampling, and synchronization through explicit stateful transformations.
Heuristic: If time affects correctness, time belongs in the network’s semantics.
K. Mixing domain logic with infrastructure
Symptom: Logging, tracing, debugging, persistence, and bookkeeping are embedded directly into domain transformations.
Problem: The central relationships become difficult to read, and infrastructure concerns are tightly coupled to domain behavior.
Correction: Compose supporting capabilities through separate relationships where the required semantics permit.
Heuristic: Keep domain relationships visible. Attach orthogonal capabilities compositionally.
L. Premature specialization
Symptom: A new application-specific protocol or wiring operator is introduced for every coordination problem.
Problem: The system accumulates overlapping abstractions instead of developing a small, reusable vocabulary.
Correction: Search for existing transformations and combinators before introducing a specialized abstraction.
When a new abstraction is necessary, prefer one that generalizes a recurring relational pattern.
Heuristic: Extend the compositional vocabulary rather than multiplying specialized containers.
M. Abstraction without composability
Symptom: A component hides its implementation but cannot participate naturally in larger network expressions.
Problem: Encapsulation alone does not guarantee useful abstraction. A component may become an isolated object requiring specialized coordination code.
Correction: Design abstractions with meaningful endpoints, explicit propagation semantics, and compatibility with general network transformations.
Heuristic: A good abstraction should be easy to compose, not merely easy to name.
N. Over-abstraction that obscures relationships
Symptom: The program introduces numerous layers of higher-order transformations, wrappers, or generic combinators without improving the clarity of the network.
Problem: Composition becomes an end in itself. The resulting expression may be harder to understand than the relationships it was intended to describe.
Correction: Keep abstractions proportional to their semantic value. Prefer direct relational expressions when they communicate the intention more clearly.
Heuristic: Composition should reveal meaning, not conceal it.
O. Assuming proposed syntax or runtime capabilities exist
Symptom: An implementation uses conceptual operators, hypothetical propagators, or assumed runtime behavior without verifying their availability.
Problem: A conceptually elegant network may not be executable or may rely on semantics that the implementation does not provide.
Correction: Distinguish existing Lain features from proposed extensions and illustrative pseudocode.
Verify operator availability, endpoint compatibility, propagation semantics, and runtime guarantees before implementation.
Heuristic: Design conceptually, but implement against the actual language.

⸻

## 15. Evaluation Checklist
Before finalizing a Lain implementation, an AI coding agent should verify:
* ☐ Can the program be explained as a composition of relationships?
* ☐ Are the important domain relationships immediately visible?
* ☐ Does the code read like a meaningful relational expression rather than a collection of named containers?
* ☐ Have I avoided translating an imperative pipeline directly into propagator syntax?
* ☐ Have I searched for useful algebraic and structural symmetries?
* ☐ Are -> and <-> used according to their intended semantics?
* ☐ Can repeated wiring be replaced with reusable composition?
* ☐ Does every explicit cell have a meaningful semantic role?
* ☐ Have I avoided large protocols that merely hide collections of cells?
* ☐ Have I avoided assumptions about execution order?
* ☐ Are temporal behavior and retained state explicit?
* ☐ Have I considered cycles, convergence, and conflicting information?
* ☐ Are implementation details hidden behind appropriate abstractions?
* ☐ Can supporting capabilities be added compositionally?
* ☐ Are all operators and runtime capabilities used actually supported?
* ☐ Is this the clearest expression of the network’s intended relationships?
## 16. The Guiding Principle
Lain is a language for composing transformations into networks of coordinated relationships.
Its programming style should encourage thinking in terms of circuits, constraints, synthesis, and information propagation.
Compose verbs into sentences, rather than concatenate nouns into names.
A program should express how relationships are established, maintained, constrained, and transformed.
The quality of a Lain program should be judged by how directly its structure communicates those relationships.
Prefer meaningful transformations, justified symmetry, generic coordination operators, and declarative composition.
Use cells, protocols, storage, and lower-level machinery in service of the network’s semantics.
The ideal Lain program is not a taxonomy of computational objects or a disguised sequence of commands. It is a compositional expression of the relationships that constitute a system.



## 17. Write Against the Current Language

The following examples use the production Compiler-2 source/session loader. Run each complete example in a fresh session from the repository root; follow-up snippets explicitly extend that same session. Inspect the named result bindings after propagation. At the raw compiler boundary, `define` produces a binding receipt, so a final `answer` expression makes the intended value explicit.

### Core syntax and optional libraries

| Intention | Current form | Important distinction |
| --- | --- | --- |
| Declare a reusable network | `(define f (network [x] (+ x 1)))` | A name belongs in `define`, not between `network` and its parameters. |
| Return a result | The final body expression | There is no separate output vector after the parameters. Return `(list ...)` when those cell connections form the intended interface. |
| Introduce unknown endpoints | `(define input)`, `(def-cells a b)`, `(let-cell [local] ...)` | An unresolved cell is information not yet available, not an empty list or an assignment variable. |
| Select a result | `(if condition then-expression else-expression)` | Exactly three operands; use a boolean-valued predicate. |
| Select among ordered cases | `(cond [test expression test expression else expression])` | One vector of pairs; the default is bare `else`, not Clojure's `:else`. |
| Install guarded topology | `(when guard body ...)` | It waits for usable information, not specifically boolean true. |
| Apply a callable to cells | `(apply f argument-list)` | Exactly one linked argument list; the cells remain connected. |
| Declare rest parameters | `(network [head & tail] ...)` | The rest parameter is a linked list, not a host vector. |
| Construct a complete list | `(list)`, `(list 1 2)` | The constructor supplies a known empty terminal tail. |
| Construct an open prefix | `(cons head unresolved-tail)` | Unknown tail information must not be interpreted as a final length. |

Lain uses an EDN-backed reader; do not assume every Clojure special form exists. In particular, the removed `def`, `def-net`, `def-constraint`, `cell-expr`, `compound`, and `do` forms are not substitutes for current syntax. Use keywords for the slot keys shown here rather than assuming a host quote form.

`|>`, `compose`, `struct`, `slot`, `<->`, unary predicate `switch`, bounded `history`, and `constraint:+` belong to the compositional library/extension:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
(define increment (network [x] (+ x 1)))
(define double (network [x] (* x 2)))
(define answer (|> 3 increment double))
answer
;; 8: pipe stages apply left to right.
```

`((compose increment double) 3)` applies the rightmost stage first and produces `7`. Names such as `normalize`, `observe`, and `sender-slot-of` in the earlier domain illustrations still need application definitions.

## 18. Branching Selects Information; `when` Installs Topology

### Binary selection

```clojure
(define absolute
  (network [x]
    (if (< x 0) (- 0 x) x)))
(define answer (absolute -7))
answer
;; 7
```

An unresolved condition leaves the selected result unresolved. The unselected branch body is not eagerly expanded. A branch activated earlier can retain its topology for reuse; selecting another branch is not a promise to deallocate the old graph. Supported selections preserve availability, support, and premise state rather than flattening those into an ordinary scalar.

### Ordered cases

```clojure
(define classify
  (network [x]
    (cond [(< x 0) :negative
           (= x 0) :zero
           else :positive])))
(define below (classify -3))
(define answer (classify 0))
(define above (classify 8))
answer
;; below = :negative, answer = :zero, above = :positive
```

`cond` lowers to nested `if` selections. It does not skip an unresolved earlier condition to guess a later branch. An explicit final `else` makes the intended coverage clear; without one, an unmatched final condition leaves the result unavailable.

### Information availability is different from truth

This is an intentional distinction from ordinary Lisp `when`:

```clojure
(define answer)
(when false (-> 42 answer))
answer
;; 42: false is usable information, so the body can be installed.
```

To require a boolean true before installation, gate that information explicitly. The two-argument `switch` takes the value first and the condition second:

```clojure
(define enabled)
(define answer)
(when (switch true enabled)
  (-> 42 answer))
answer
;; Unresolved: enabled has no information yet.
```

Extend that session with:

```clojure
(-> true enabled)
;; answer becomes 42.
```

In a separate fresh session, defining `enabled` as `false` leaves this guarded answer unresolved. `when` is not a replacement for `if`, nor a network-disposal mechanism. Use `if`/`cond` to select changing results and `when` to express the intended topology-installation guard.

`->` declares a relationship; it is not destructive assignment. Sending two incompatible plain values into the same cell may produce a contradiction. Use the runtime's versioned source-publication/TMS interface for replacement, retraction, and repeated selector changes; do not model them as a sequence of plain assignments.

## 19. Compound Objects Are Live Connected Interfaces

Partial information is not the same as delayed construction. A struct can have addressable fields whose values are still unknown. Conversely, an unselected branch can defer constructing its body. Neither fact means the runtime must recursively complete an object graph before a field can be used.

### Select an owner, then observe its current field

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
(def-cells enabled left-reading right-reading)
(define left (struct :inner (struct :reading left-reading)))
(define right (struct :inner (struct :reading right-reading)))
(define selected (if enabled left right))
(define answer (|> selected (slot :inner) (slot :reading)))
(define direct (p:slot :reading (p:slot :inner selected)))
answer
;; Both answer and direct are initially unresolved.
```

First extend the same session:

```clojure
(-> true enabled)
(-> 99 right-reading)
;; Still unresolved: the selected left field has no value.
```

Then extend it again:

```clojure
(-> 10 left-reading)
;; answer = 10, direct = 10.
```

The inactive right value does not leak into the selected result. The selected owner carries immutable accessor references to live field cells; reads follow the current owner and its support/state. Independent unknown fields do not require whole-object materialization. Supported source updates can later change selection or withdraw information without allowing an old branch watcher to copy stale data.

### A field view can preserve writeback

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
(define incoming)
(define object (struct :nested (struct :value incoming)))
(-> 42 (|> object (slot :nested) (slot :value)))
(define answer incoming)
answer
;; 42: the view connects to the original field cell.
```

Likewise, compatible partial components can share endpoints:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
(define position-of (slot :position))
(def-cells left right)
(<-> (position-of left) (position-of right))
(-> 7 (position-of left))
(define answer (position-of right))
answer
;; 7
```

These are specific connected-accessor guarantees. They do not imply that arbitrary transformations, filtering, or selected compound graphs have unrestricted inverse semantics.

For a mathematical relation, make its justified directions explicit:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
(def-cells a b c)
(constraint:+ a b c)
(-> 3 b)
(-> 5 c)
(define answer a)
answer
;; 2, inferred through the declared reverse arithmetic relationship.
```

## 20. Distinguish Complete Lists from Open Prefixes

A list has separate structural and element information. `(cons unknown-head tail)` can expose a cons node before its head value arrives. `(list)` is known emptiness; it is not bottom, false, or an unresolved endpoint.

```clojure
(define xs (list 1 2))
(define end (cdr (cdr xs)))
(define answer (empty-list? end))
answer
;; true
```

For an unresolved cell, `empty-list?` remains unresolved. It does not report true merely because no cons is currently visible. A known cons reports false independently of its head value. A nonempty scalar tail is not an empty-list marker; do not silently treat malformed list structure as a valid end.

### Compose the existing recursive list networks

```clojure
(load-lain "propagators/compiler_2/runtime/lain/list.lain")
(define doubled (map-list (network [x] (* x 2)) (list 1 2 3)))
(define kept (filter-list (network [x] (>= x 4)) doubled))
(define paired-sums
  (zip-with (network [x y] (+ x y)) kept (list 10 20)))
(define answer (car (cdr paired-sums)))
(define complete? (empty-list? (cdr (cdr paired-sums))))
answer
;; doubled = (2 4 6), kept = (4 6), paired-sums = (14 26)
;; answer = 26, complete? = true.
```

The displayed list notation describes connected cons cells, not a request to copy them into a host collection. See [the list-network contract](../compiler_2/runtime/lain/list.md) for current map/filter/zip behavior.

### An available prefix need not wait for its end

```clojure
(load-lain "propagators/compiler_2/runtime/lain/list.lain")
(define tail)
(define xs (cons 1 tail))
(define doubled (map-list (network [x] (* x 2)) xs))
(define first-value (car doubled))
(define second-value (car (cdr doubled)))
(define third-value (car (cdr (cdr doubled))))
(define complete? (empty-list? (cdr (cdr (cdr doubled)))))
first-value
;; first-value = 2; second-value, third-value, and complete? are unresolved.
```

Extend that session with:

```clojure
(-> (list 2 3) tail)
;; first-value = 2, second-value = 4, third-value = 6, complete? = true.
```

This is information arriving at a previously unresolved tail, not an initialization-time snapshot being replaced. Keeping the same cell references is what lets the mapped output grow.

### `apply` connects an argument list

```clojure
(define add-two (network [x y] (+ x y)))
(define closed-sum (apply add-two (list 2 3)))
(define tail)
(define answer (apply add-two (cons 2 (cons 3 tail))))
answer
;; closed-sum = 5, answer = 5, even though tail is unresolved.
```

A declared fixed interface can connect once its required argument cells are addressable; their values need not all be known first. A known extra or malformed tail still has an inspectable arity/interface failure. Primitive operators without a declared argument interface require a known empty end rather than guessing that the current prefix is final.

A variadic network receives the live remaining list:

```clojure
(define rest-of (network [head & tail] tail))
(define later)
(define arguments (cons 9 later))
(define remaining (apply rest-of arguments))
(define answer (car remaining))
(define next-value (car (cdr remaining)))
answer
;; answer and next-value are unresolved.
```

Extend it with:

```clojure
(-> (list 4 5) later)
;; answer = 4, next-value = 5; the rest list was not frozen as an empty prefix.
```

Calls preserve the availability/support/state of the consumed list structure even when the callee ignores argument values. Unspecified-arity primitives also depend on their completion marker. Do not replace these dependencies with a materialized vector of current payloads.

## 21. Recursion Builds Relationships with a Lifetime

GUR is the runtime for recursive network application, not a `GUR` source special form. Recursive definitions use ordinary network calls. No separate `recur` syntax is needed to express the examples below.

### Scalar recursion with a branch

```clojure
(define sum-to
  (network [n acc]
    (if (<= n 0)
      acc
      (sum-to (- n 1) (+ acc n)))))
(define answer (sum-to 5 0))
answer
;; 15
```

The recursive call is inside the nonterminal branch. If n is unresolved, the comparison and selected result wait; the runtime need not expand an infinite recursive body in advance. Pulling the recursive call into an unconditional outer `let` can destroy that guard and cause unwanted expansion.

### Structural recursion over a list

```clojure
(define map-values
  (network [f xs]
    (if (empty-list? xs)
      (list)
      (cons (f (car xs))
            (map-values f (cdr xs))))))
(define mapped (map-values (network [x] (+ x 1)) (list 1 2 3)))
(define answer (car (cdr (cdr mapped))))
(define complete? (empty-list? (cdr (cdr (cdr mapped)))))
answer
;; answer = 4, complete? = true.
```

This is not tail-position recursion: `cons` uses the recursive result as its tail. It is nevertheless the natural expression of a live list transformation. A cons relates its head and tail cells, allowing an available head to propagate while a later tail is unresolved. Use the existing `map-list` library when no different policy is needed.

### Tail-position recursion is not a memory guarantee

```clojure
(define sum-list
  (network [xs acc]
    (if (empty-list? xs)
      acc
      (sum-list (cdr xs) (+ acc (car xs))))))
(define answer (sum-list (list 2 3 4) 0))
answer
;; 9
```

The final sum waits for a known end and the required element information. An open list is not assumed complete. Accumulator style changes the relationship being expressed; it does not prove constant retained network space.

A recursive invocation may add lexical frames, cells, propagators, and connections that remain available for later propagation or replay. Equivalent reactivation should reuse established identities/topology, but different recursive paths can construct more structure. Tail position describes the control-flow expression; bounded graph growth depends on construction, retention, and lifecycle policy.

Before introducing recursion:

1. Identify the decreasing quantity or structural end that makes the current computation finite.
2. Keep the recursive call inside the appropriate conditional branch.
3. State what an unknown input or open tail should leave unresolved.
4. Identify which relationships must remain live and which are temporary observations.
5. Inspect topology size and replay/reactivation when updates can explore new paths.
6. Use explicit bounds or a reusable finite network when the input can grow without limit.

Do not assume the runtime can reclaim every previous recursive frame merely because the call is in tail position. An anonymous immediate network call is still an ordinary retained invocation, not automatically a disposable one-time network.

## 22. One-Time Networks Are an Existing Observation Boundary

The optional `run-flat-gur-once` operator creates private flat-GUR topology over an immutable snapshot, runs the supplied closure to quiescence, projects the result, and returns that projected value. Temporary trace cells/propagators are not installed into the active caller graph.

```clojure
(load-primitive-environment
  "propagators/compiler_2/runtime/one_time_network.clj"
  :propagators.compiler-2.runtime.one-time-network/one-time-environment
  0)
(define answer
  (run-flat-gur-once true
    (network [snapshot n] (+ n 1))
    41))
answer
;; 42
```

The supplied closure receives the frozen snapshot as its first argument, followed by the optional user arguments. The trigger, closure, and supplied argument values must be usable before this operator runs. The default compound projection can turn a closed temporary list into a host vector at this observation boundary: `(list)` projects to `[]`, and `(list 1 2)` projects to `[1 2]`. That deliberate snapshot projection is different from materializing live domain graphs to implement normal computation.

This boundary does not make arbitrary recursion terminate, provide tail-call optimization, or guarantee immediate heap reclamation. The low-level host runner returns diagnostic temporary-network information too; a host caller retaining it still holds references. The Lain operator returns only the projection, making its private temporary topology eligible to be discarded when no longer referenced.

Use this for finite snapshot observations. Keep relationships that must react to future source changes in the live network. For repeated snapshots, use a boundary that creates fresh one-time invocations/result cells; repeatedly feeding unrelated plain snapshots into one retained result cell can create a contradiction rather than temporal replacement.

See [the one-time implementation](../compiler_2/runtime/one_time_network.clj) and [its tracing notes](compiler-2-one-time-network-tracing.md).

## 23. Observation, Bounded History, and Tracers

These capabilities answer different questions:

| Capability | Question | Boundary |
| --- | --- | --- |
| `(history n)` | Which n recent publications reached this endpoint? | Session-local bounded recording supplied by the combinator library. |
| `trace:history` | What inspection history does this runtime expose? | Runtime tracing/inspection capability; do not assume the same interface or retention policy as `history`. |
| `trace-network-once` | Which nodes and edges are connected in a frozen topology snapshot? | Optional one-time traversal library and observation primitives. |

### Observe incoming values without changing the domain relation

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
(define incoming 7)
(define answer (|> incoming (history 2)))
answer
;; [7]
```

Each applied history stage owns bounded recording state. Reevaluating the same publication does not create another sample; a new supported source revision can be a new observation even when its payload is equal. This is publication history, not a request to log every helper or every propagation step.

### Load a topology tracer explicitly

```clojure
(load-primitive-environment
  "propagators/compiler_2/runtime/one_time_network.clj"
  :propagators.compiler-2.runtime.one-time-network/trace-environment
  0)
(load-lain "propagators/compiler_2/runtime/lain/network_trace.lain" 0)
(define node-to-inspect)
(define answer (trace-network-once node-to-inspect :upstream))
answer
;; Pending until node-to-inspect receives a valid node identity.
```

Supply a real node identity from the inspection boundary, not the node's ordinary payload value. Directions are `:upstream`, `:downstream`, and `:both`. `neighbors` and `node-info` provide the observation primitives; the loaded Lain networks implement traversal/visited-set policy and return trace fields such as `:trace/root`, `:trace/nodes`, and `:trace/edges`.

The setup above verifies loading and pending-input behavior, not a completed traversal or a performance bound. The tracer is a snapshot observation, not an automatically updating subscription. Recursive traversal can still create substantial temporary topology. Choose physical topology tracing or the separate semantic tracing surfaces deliberately; do not assume their node identities and graph projections are interchangeable.

See the [semantic tracing gallery](../../examples/lain/semantic_tracing/README.md) for semantic observation examples. Attach observation through its declared boundaries rather than changing the propagation scheduler or reconstructing domain objects in a debugger.

## 24. Verification and Source References

The runnable examples and explicit follow-up extensions were checked through the production source/session loader without behavior-changing overrides. Verification on 2026-10-26 covers 25 program cases and 46 expected outputs, including pending results, late fields, live list growth, branches, recursion, and one-time list projection. The pending tracer setup only establishes that its environment loads and its unresolved input remains pending.

The language contracts used here are grounded in:

- [The current parser](../compiler_2/language/parser.clj): `network`, `define`, `if`, vector-clause `cond`, and `when` syntax.
- [Core operators and predicates](../compiler_2/compiler/basis.clj): list construction, accessors, and `empty-list?`.
- [The compositional library](../compiler_2/runtime/lain/combinator.lain) and [its contract tests](../../test/propagators/compiler_2_combinator_test.clj): pipes, structs, connected slots, switching, and bounded history.
- [The list networks](../compiler_2/runtime/lain/list.lain), [GUR list tests](../../test/propagators/compiler_2_functional_network_gur_test.clj), and [lazy conditional tests](../../test/propagators/compiler_2_lazy_conditional_test.clj): recursive composition and branch readiness.
- [Supported compound selection](../../test/propagators/compiler_2_supported_compound_if_test.clj) and [live-list apply tests](../../test/propagators/compiler_2_open_apply_test.clj): owner/field availability, support, withdrawal, and retained connections.
- [The one-time runner](../compiler_2/runtime/one_time_network.clj) and [topology tracer](../compiler_2/runtime/lain/network_trace.lain): explicit disposable observation boundaries.

For ongoing language-contract checks, run the existing focused suites from the repository root:

```sh
clojure -M:test \
  propagators.compiler-2-conditional-syntax-test \
  propagators.compiler-2-lazy-conditional-test \
  propagators.compiler-2-functional-network-gur-test \
  propagators.compiler-2-open-apply-test \
  propagators.compiler-2-combinator-test
```

Keep a distinction between a successful concrete example, a tested runtime contract, and a proposed extension. When the implementation changes, update the examples and their stated availability/lifetime semantics together.
