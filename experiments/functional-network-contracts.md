# Functional-network contracts and proof boundary

Historical experiment audit. Production migration uses relocated tests and the
shared compiler. See [current migration evidence and blockers](../doc/functional-network-migration.md).
The evidence below remains specific to its recorded experiment snapshot.

This audit concerns the functional-network experiment at base commit
`755a305c79d5c4c380e9e119f147e69c9e35177c`. Production Compiler 2, the parser,
CPS engine, flat GUR, compound objects, cells, and effect boundary are unchanged.
The added contract tests and this report are working-tree evidence, not a
mechanized proof or a submitted KIROSHI verification receipt.

## Contract, rather than legacy result compatibility

The new language deliberately returns cells containing definition receipts and
uses a network body's return expression as its output interface. Matching the
old last-argument constraint result is not its correctness criterion.

```clojure
;; Conceptual judgments for the existing entry point and runtime:
;; compile : Expr × EnvironmentId × Net -> Compiled
;; Compiled = {:cell NodeId :net Net :props [NodeId] ...}
;; propagate : Net × ScheduledPropagators -> Net
;; drain-effects : Session -> Session
```

Compiler correctness should be stated against these new language semantics.
Eliminating an old construct with observational equivalence is a separate claim
and still requires a translation preserving that construct's original contract.

## Current executable evidence

| Property | Observations checked | Remaining limit |
| --- | --- | --- |
| Call shape | Positional call and list-based `apply` both return 5; both reject wrong arity. Existing suites check unavailable input members and late list tails. | Runtime `apply` waits for a complete finite argument-list spine; positional calls already know their shape. No timing equivalence is claimed while the spine is unavailable. |
| Returns | Source/form/AST entry points return cells; definitions return binding receipts; primitives are first-class cell values; network returns determine scalar or list interfaces. | No proof quantifying over every expression or program context. |
| Lexical capture | Shadowing, returned closures, and captured values arriving after application. A returned closure observes a later write to its captured parameter. | No full compiler simulation or proof over arbitrary nesting and updates. |
| Late updates | Existing list structure and member-value cases; a named output port receives a late value without replacing the port. Equivalent reactivation preserves the graph. | No global confluence, retraction, or arbitrary conflicting-update theorem. |
| Effects | Both call shapes emit one equal payload without execution; draining executes once and records a handled receipt. Existing tests cover deferred declarations, recursion, late requests, replay prevention, and handler failures. | No arbitrary effect-trace equivalence or ordering guarantee for independent requests. |

The new `functional-network-contract-test` namespace contains eight test vars
and 32 assertions. The complete experiment run passed 65 tests and 177
assertions, with no failures or errors. Maximum individual duration was
1495.687833 ms; namespace/fixture startup was 6071.543791 ms, measured separately.
All individual tests met the three-second limit.

## What returned-list output registration means

For this network:

```clojure
(network (x) (list x x (+ x 1)))
```

Application produces one primary result cell containing the compound linked
list. The output walker obtains member cell IDs through `:car` and `:cdr`
slots. For each list position it declares:

```clojure
;; member-cell -> concrete outbound propagator -> named port-cell
;; name = [application-id position]
;; port-id = (gur/stable-node-id [application-id :output-port position])
```

The contract test inspects actual graph edges for all three positions and
observes values `[4 4 5]` at three distinct port cells. Repeated member cells
remain separate output positions. Another test confirms that a port is declared
while its member is unavailable and receives the later value through propagation.

This is topology construction, not a materialized snapshot of list values.
When a returned member is an invocation input, the declared route also forwards
information back to that invocation's caller cell.

The canonical GUR application declaration still has its original result and
invocation output edges. The member port cells are separate nodes added inside
its declared topology, associated with the application through their names.
They are not appended to that original application's `:outputs` set. A dedicated
graph test checks this distinction. Consumers that require all member outputs
on that one node's declared output set are not supplied such a representation.

## Local output-walker argument

Assume a well-formed, acyclic list whose slot identities remain fixed, and the
existing flat-GUR contract of deterministic, one-time branch declaration. This
is a source-level argument conditional on those contracts, not a mechanized proof.

1. Empty list: `output-list-body` declares no member ports.
2. Cons at position k: it obtains that cons's head cell ID, declares exactly
   the stable port for k, connects head to port, and schedules a scanner for
   the tail at k+1.
3. Induction: after k usable cons nodes, positions 0 through k-1 correspond to
   exactly those heads. A later usable cons extends the prefix by one position;
   an unavailable tail adds nothing until it becomes usable.
4. Member values are absent from the registration decision. Usable later
   information travels through the declared concrete copy boundary.
5. Stable IDs and one-time branch declaration account for equivalent
   reactivation adding no duplicate ports. Cycles and malformed tails report
   errors rather than being interpreted as further positions.

This establishes the intended structural correspondence under the assumptions.
It does not establish conflict propagation after a member becomes contradictory,
retraction of already exposed topology, or arbitrary mutation of list slots.

## What is needed for a compiler proof

The experimental compiler already reuses the CPS compiler and source parser.
Adding further parser support is necessary only for a chosen additional syntax;
it is not a prerequisite for proving the supported core.

Define a reference semantics for that core, including lexical frames, cell
information, list interfaces, waiting, invalid arity, and effect requests. Then
relate a source expression/environment to its compiled cells and topology.
Prove preservation by expression cases, and simulation under admissible external
updates and fair propagation. Observe result information, output positions,
waiting/errors, and effect requests/receipts up to a consistent renaming of
generated identities. An implementation-language interpreter or whole-program
rewrite is not by itself a local elimination proof.

The eight new tests are useful counterexample checks and implementation evidence
for those obligations. They do not replace the reference semantics, induction,
or simulation proof. No production repair or model mutation is authorized or
needed by this audit.
