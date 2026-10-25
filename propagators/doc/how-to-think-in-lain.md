# How to Think in Lain

Programming heuristics for AI agents.

Lain is a Lisp-like propagator language for composing transformations and relationships. Cells carry partial information; a program describes how components constrain, transform and exchange that information. Its quality depends on how directly its topology reveals the domain relationship.

Think in transformations, connections and coordination. A circuit, synthesizer or chemical reaction is a better starting analogy than a script of commands. This recalls Alan Kay's emphasis on interactions between components: the connection matters more than the incidental storage behind an endpoint.

This guide uses the current `(network [inputs] ... result)` syntax. Code marked **schematic** describes a design and requires domain primitives. The combinator reference below distinguishes implemented operators from proposed temporal capabilities.

## 1. Start with relationships

Before choosing storage, identify:

- The components that participate.
- The transformations or constraints that relate them.
- Which combinations of available information permit an inference.
- Where information is shared, transformed or retained independently.
- Which relationships have meaningful symmetry.

For example, a schematic chemical relationship can connect quantity views:

```clojure
;; Schematic: domain quantities and chemistry constraints need definitions.
(network [water hydrogen hydroxyl]
  (<-> (hydrogen-of water) hydrogen)
  (<-> (* 2 (hydroxyl-of water)) hydroxyl)
  (list water hydrogen hydroxyl))
```

This exposes relationships without claiming to model complete chemistry. A one-way multiplication inside a symmetric connection does not establish an inverse multiplication rule. Define that inverse constraint explicitly when it is required.

Defer intermediate cells and slot storage until the relationship is clear.

## 2. Prefer verbs and meaningful endpoints

Look first for transforming, connecting, filtering, mapping, combining, constraining, observing and accumulating. Represent an interaction directly:

```clojure
;; Schematic: components expose these domain accessors.
(-> (output-of sender) (input-of receiver))
```

When object construction, callback installation or intermediate allocation dominates a network, ask whether two existing transformations can express the same relationship. A named domain abstraction is useful when its name explains the interaction; it does not need to expose its storage.

## 3. Declare concurrent relationships

Network expressions coexist. Their textual order does not schedule an imperative workflow:

```clojure
;; Schematic: f, g and h are separately supplied transformations.
(network [a b c]
  (-> (f a) b)
  (-> (g b) c)
  (-> (h c) a)
  (list a b c))
```

The final expression is the network's ordinary value. `(list a b c)` constructs a collection; it does not declare three output ports. A constructor for one actor returns the actor itself, not `(list actor)`. Connect endpoints through explicit relationships instead of assigning special output syntax to an ordinary list expression.

Information propagates when the relevant inputs are available. A pipe introduces transformation dependencies, not a sequence of external commands. Represent causal dependencies with connections; represent ordering, buffering and history explicitly when they affect correctness.

For feedback, specify convergence, duplicate facts and conflicting information. Do not assume a scheduler, error policy or truth-maintenance behavior that the installed session does not provide.

## 4. Search for justified symmetry

An arithmetic relationship `a + b = c` admits three inferences:

- Known `a` and `b` constrain `c`.
- Known `c` and `b` constrain `a`.
- Known `c` and `a` constrain `b`.

Construct a multidirectional relationship from small propagators when those inferences are valid. The library exposes an explicit three-endpoint constraint:

```clojure
;; After importing combinator.lain.
(def-cells a b c)
(constraint:+ a b c)
(-> 2 a)
(-> 3 b)
(display c)
;; 5
```

A lossy filter, aggregation or arbitrary transformation does not acquire an inverse merely because it participates in a network.

`->` propagates information in one direction. `<->` connects compatible endpoints in both directions. Both rely on the participating endpoints and merge semantics; neither invents a domain inverse.

## 5. Compose transformations instead of plumbing

Import the shared combinator library once in a session:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")
```

The library installs its primitive environment through the existing primitive-extension boundary. It does not install a separate workflow interpreter. The extension entry is `propagators.compiler-2.runtime.operators.combinator/primitive-bindings`; `combinator.lain` performs the boundary declaration below, so callers should import the library rather than repeat it:

```clojure
(load-primitive-environment
  "propagators/compiler_2/runtime/operators/combinator.clj"
  :propagators.compiler-2.runtime.operators.combinator/primitive-bindings
  0)
```

### Combinator reference

| Form | Relationship |
| --- | --- |
| `(-> source destination)` | Existing directed propagation. |
| `(<-> left right)` | Existing bidirectional connection between compatible endpoints. |
| `(|> input stage-a stage-b)` | Apply unary stages left to right and expose the final endpoint. With no stages, expose the input endpoint. |
| `(compose outer inner)` | Produce a unary transformation that applies `inner`, then `outer`. With no stages, produce a bidirectional identity endpoint. |
| `(slot :field)` | Produce a bidirectional partial field accessor; it does not extract a complete owner value. |
| `(struct :field endpoint :other endpoint)` | Construct a compound object whose unique keyword slots are connected to the supplied cells, including unresolved cells. `(struct)` constructs an empty object. |
| `(switch predicate)` | Produce a unary gated transformation. A known true predicate contributes the input; false or unresolved predicates contribute no new value. Non-boolean predicate results are errors. |
| `(switch value condition)` | Preserve the compiler's existing value-first two-argument gate form. |
| `(constraint:+ a b c)` | Declare `a + b = c`, including both subtraction inferences. |
| `(history 5)` | Produce a unary recorder stage whose application retains the latest five incoming values in local arrival order. |

A stage accepts one endpoint and exposes one endpoint. A multi-input domain operation needs an explicit network that supplies its other dependencies; a pipe does not guess them or spread a collection into arguments. An unresolved stage can remain partial until its binding is supplied.

`slot` keys and `history` capacities are fixed configuration for a factory application: unresolved configuration waits, unchanged configuration is reused, and a conflicting resolved change is an explicit error. Construct a fresh stage to choose a different key or capacity. In contrast, `compose` and predicate-form `switch` retain their stage or predicate cell identities, preserving deferred and live callable bindings.

A pipe describes left-to-right unary transformation composition:

```clojure
(define twice (network [x] (* x 2)))
(define increment (network [x] (+ x 1)))
(define answer (|> 3 twice increment))
(display answer)
;; 7
```

Conventional composition describes the same transformation from right to left:

```clojure
(define transform (compose increment twice))
(display (transform 3))
;; 7
```

The pipe may allocate intermediate cells internally. Callers should not reproduce those cells to express an ordinary chain. Preserve a named cell when it is itself meaningful: an externally supplied fact collection, independently retained state, an observation port or a feedback endpoint.

For a single stage, `(assignments framework)` is usually clearer than `(|> framework assignments)`. A pipe is useful when it reveals an actual composition, rather than merely adding syntax.

## 6. Compose views without unwrapping components

A slot stage exposes an endpoint in a compound value:

```clojure
(def-cells left right)
(define position-of (slot :position))
(<-> (position-of left) (position-of right))

;; The same endpoint relationship expressed as two pipes.
(<-> (|> left (slot :position))
     (|> right (slot :position)))
```

Slot access must preserve partial information. It connects the requested field; it must not wait for, materialize or serialize the complete owner. Pipes and composition preserve reverse contributions that a stage actually supplies at its input. They do not infer an inverse for an arbitrary transformation. Slot access is a relationship; a lossy transformation remains directional.

Connect endpoint expressions directly, as above. In the current compiler, `(define selected (position-of left))` copies the derived value into a named cell; it does not alias the accessor endpoint. Writing `selected` therefore does not by itself write back to `left`. If a named bidirectional connection is needed, declare it explicitly:

```clojure
(def-cells selected-position)
(<-> selected-position (position-of left))
```

Naming the accessor factory itself, `(define position-of (slot :position))`, is different: it names a callable transformation. Calling that transformation still exposes the endpoint, so the inline `<->` examples remain bidirectional.

For application-defined data abstractions, centralize slot names in named accessors:

```clojure
(define assignments (slot :assignments))
(define on-going-assignment (slot :on-going-assignment))
```

The abstraction's implementation owns its representation. Application networks use those accessors consistently, including in tests of the public contract.

Construct explicit interfaces with `struct`:

```clojure
(def-cells request observation)
(define harbor (struct :requests request :observations observation))
(-> 42 ((slot :observations) harbor))
(display observation)
;; 42, without resolving request
```

`struct` connects cells; it does not snapshot their current values. Nested structures preserve those connections. Slot labels are unique literal keywords; an odd number of arguments, non-keyword labels, or duplicate labels is an error. Empty and partially informed structures are valid. `struct` composes the existing fixed-key slot-accessor propagators; it does not inspect field contents or implement separate slot storage. This is a primitive extension through ordinary application syntax, not a compiler grammar change.

## 7. Make time, identity and retention explicit

Reactive reevaluation is not an event clock. A recorder must identify what constitutes a distinct observation, which order it uses and what it retains. Repeating propagation must not duplicate an incoming observation merely because a propagator ran again.

`(history 5)` records incoming values in the propagator graph. Each stage application owns an independent recorder, even when two applications reuse the same factory value. A session may host that graph and manage its lifetime, but history does not inherently depend on an editor session. The output is a vector of up to five values, oldest retained value first. An unresolved input initially produces an empty vector:

```clojure
(define incoming)
(define recent (|> incoming (history 5)))
(display recent)
;; [] until incoming contributes a value
```

The recorder compares the current payload and its source-support stamp. Reevaluating the same current stamp adds nothing. The same payload under a new source version or support stamp is a new observation. This is current-observation deduplication, not an all-time receipt ledger: an old stamp received again after other observations is not guaranteed to be suppressed.

Order is local observation arrival order, expressed with a recorder-local counter. It is not wall-clock time or domain event order. The harness's stable event ordering and receipt identities remain separate responsibilities.

The recorder observes publication values when propagation runs; it is not a transport-event audit log, because batched updates may settle before intermediate values are observed. If every event must be retained, accumulate identified event facts at the boundary first.

The recorder's private buffer contains at most five records; it is not a five-element view over its own unbounded history. Generic runtime retention of previous output publications, provenance or immutable checkpoints is a separate policy, so this bound is not a claim that the entire session uses constant memory.

Sampling, debouncing, delay, batching, synchronization and wall-clock windows remain proposed capabilities in this guide. They need explicit contracts; external timing or effects additionally need session interpreters. They are not implied by a pipe or by ordinary propagation.

Use stable fact identities and an explicit ordering rule for asynchronous inputs. Distinguish event time from receipt time. State whether a late fact changes a retained window and whether an identity conflict is rejected or represented as a contradiction.

Do not implement retention by silently mutating global state. Scope any required merge and accumulation behavior to the session, with commutativity, idempotence and associativity where facts merge independently.

## 8. Compose observation separately

Domain relationships should remain legible when tracing is added or removed:

```clojure
;; Schematic: normalize, validate and observe are supplied transformations.
(network [source destination audit]
  (-> (|> source normalize validate) destination)
  (-> (|> source observe) audit)
  (list destination audit))
```

Use existing tracing, provenance, history and visualization facilities. Optional diagnostics must preserve domain semantics. Required receipts or audit records remain part of their boundary contract.

The existing tracer can inspect a partial declaration:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/tracers.lain")
(load-lain "examples/lain/subagents/evaluation-framework.lain")
(define framework (make-evaluation-framework))
(display (trace:children framework))
```

A ready graph does not mean deferred domain primitives or live effect handlers have been implemented. Inspect child calls to see the internal relationships. The current tracer can omit unresolved primitive calls; named network declarations expose known topology while primitive bodies remain open. It also has a known rendering limitation for reverse `<->` edges: a missing reverse arrow is not proof that runtime propagation is one-way. See the [known issues](TODO.md).

## 9. Preserve declaration and effect boundaries

Pure primitives normalize observations, derive facts and compute effect requests. Outside interpreters invoke Harbor or Pi, own process lifecycles, reconcile uncertain launches and publish receipts and observations back to the session.

Serialize only bounded, resolved transport fields. Keep session identities attached to facts and receipts. Reactive reevaluation must not redispatch an acknowledged request. Recorded replay consumes recorded observations and must not invoke live handlers; opening an example defaults to observation or synthetic operation.

Lain composes these relationships. It does not duplicate an existing runtime's child lifecycle FSM.

## 10. Simplify the evaluation-framework constructor

The framework has an explicit orchestration interface:

| Accessor | Relationship |
| --- | --- |
| `assignments` | Shared assignment facts: user ordering enters and completed evaluation results feed back. |
| `on-going-assignment` | Derived work offered to the runtime. |
| `assignment-result` | Runtime execution observations enter. |
| `evaluation:harbor` | A separate Harbor endpoint with `harbor:requests` and `harbor:observations`. |

Trials and assessments are internal cells. Each internal protocol accepts only the cells it relates, rather than receiving the framework bundle. The constructor makes their shared dependencies visible:

```clojure
(define make-evaluation-framework
  (network []
    (let-cell [shared-assignments ongoing-assignment runtime-observations
               trials assessments]
      (define harbor (make-harbor))
      (evaluation:assignment-protocol shared-assignments trials assessments)
      (evaluation:execution-protocol shared-assignments trials
                                     runtime-observations ongoing-assignment)
      (evaluation:assessment-protocol trials (harbor:observations harbor) assessments)
      (evaluation:external-protocol shared-assignments trials
                                    assessments (harbor:requests harbor))
      (struct :assignments shared-assignments
              :on-going-assignment ongoing-assignment
              :assignment-result runtime-observations
              :harbor harbor))))
```

`make-harbor` constructs `(struct :requests requests :observations observations)` from two local cells. The framework no longer depends on `harbor:framework` to invent its structure. These cells identify actual independently supplied facts and derived ports, rather than steps in an execution sequence. Keep the protocol calls as concurrent fan-out.

Assignments need stable identities. Their order and result facts are separate contributions: user reorder changes pending priority, completed results attach to the matching ID, and already-running work continues. Result feedback must preserve the current user order. A structured assignment view can connect separate order and results cells so feedback need not replace an entire ordered snapshot. Order updates require explicit source revisions; monotonically merging two ordered vectors does not implement reordering.

The `evaluation:assignment-trials`, `evaluation:runnable-assignments`, `evaluation:assessment-updates` and Harbor policy bodies remain session-extension points. They must implement the ordering/result contract above, exclude completed work, and preserve stable request IDs across reorder. The local synthetic fixture verifies order propagation to runtime/Harbor ports and result feedback without overwriting order; it does not certify a live Harbor scheduler. Outside effect handlers still own acknowledgements, uncertain-launch reconciliation and cleanup. Constructing or tracing the interface launches no live work.

## 11. Choose small, reusable abstractions

A useful network component is composable, encapsulated, declarative, inspectable and directionally explicit. Generalize after a meaningful operation recurs. Similar-looking wiring alone is not a reason to add a universal operator.

Use data for facts and declarations, and functions or propagators for transformations and interpretation. Prefer a direct relationship when no extra abstraction is needed. Names should expose domain meaning: `constraint:+` names a justified arithmetic relationship; `assignments` names a public protocol view.

## 12. Practical reasoning procedure

1. State the domain relationships and the information each participant can supply.
2. Find the smallest meaningful existing transformations.
3. Identify justified symmetric inferences and their preconditions.
4. Compose endpoints with accessors, pipes and ordinary network operators.
5. Declare concurrent relationships; keep external effects explicit.
6. Add retained cells only when they represent meaningful independent state.
7. Check cycles, convergence, conflicts, event identity, ordering and bounds.
8. Attach observation and other independent capabilities compositionally.
9. Simplify redundant plumbing while preserving important named boundaries.

Complete and review constraints before filling in primitive bodies. Verify the composed behavior with partial inputs and controlled observations, rather than relying only on a concrete happy path.

## 13. Common anti-patterns

| Anti-pattern | Correction |
| --- | --- |
| Treating declaration order as a schedule | Express dependencies through connections. |
| Allocating cells before identifying relationships | Begin with transformations and public endpoints. |
| Unwrapping owners to connect two fields | Compose partial accessor endpoints. |
| Reducing a symmetric relationship to one calculation | Declare the valid inverse inferences explicitly. |
| Assuming a lossy transformation is reversible | Preserve directional semantics. |
| Treating reevaluation as a new event | Use explicit identities, order and retention. |
| Mixing domain decisions with transport or process ownership | Derive requests, then interpret outside propagation. |
| Adding an application-specific wiring operator for every case | Reuse focused transformations when semantics match. |
| Piping solely to hide a short, meaningful fan-out | Keep simultaneous relationships explicit. |

## 14. Evaluation checklist

- [ ] The program can be explained as a network of relationships.
- [ ] Important transformations and public endpoints are immediately visible.
- [ ] Symmetry is semantically justified; lossy operations remain directional.
- [ ] `->`, `<->` and pipes preserve the intended partial-information behavior.
- [ ] Intermediate cells represent meaningful state or are hidden by composition.
- [ ] No behavior relies on textual execution order.
- [ ] Cycles, conflicts and convergence have explicit semantics.
- [ ] Time, ordering, event identity and retained history are bounded and explicit.
- [ ] The abstraction owns its representation; callers use named accessors.
- [ ] Observation composes without changing domain decisions.
- [ ] Session-scoped effects, receipts, replay and disposal preserve ownership.
- [ ] Examples distinguish executable library behavior from deferred domain policy.

## 15. Guiding principle

Favor expressive transformations, meaningful symmetry, generic coordination and declarative composition. Cells and lower-level machinery serve those relationships. The ideal Lain program reads as a description of what is connected, what information can be inferred and which properties the network maintains.
