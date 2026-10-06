# Functional network experiment

This experiment reuses Compiler 2 CPS traversal, canonical flat-GUR closures,
compound slots, and the existing effect boundary. Its source and tests are local
to `experiments.functional-network`; production modules are unchanged.

## Module boundary

```clojure
{:owns
 '#{experiments.functional-network
    experiments.functional-network.compiler}

 :fixed
 '#{propagators.compiler-2.cps-core
    propagators.compiler-2.language.parser
    propagators.compiler-2.runtime.application
    propagators.compiler-2.model.env
    propagators.compiler-2.runtime.session.extension
    propagators.compiler-2.runtime.boundary.effects
    propagators.gur
    propagators.datastructures.compound-object
    propagators.datastructures.tms}

 :compile-result '{:cell NodeId :net Net :props [NodeId]}
 :execution-owner :existing-propagator-runtime
 :external-effect-owner :existing-effect-boundary}
```

Experiment tests and these documents are included. Scheduler, merge semantics,
production syntax, runtime APIs, and the KIROSHI database are outside this change.

## Compiler entry point

`experiments.functional-network.compiler` exposes the complete experimental
core through source, reader-form, AST, and ordered-program entry points:

```clojure
(require '[experiments.functional-network.compiler :as compiler]
         '[propagators.network-builder :as nb])

(def compiled
  (compiler/compile-program
   '[(define increment (network (x) (+ x 1)))
     (define answer (increment 41))
     answer]))

(:cell compiled) ; result NodeId, not the observed value
(nb/run-propagators (:net compiled) (:props compiled))
```

`compile-source`, `compile-form`, and `compile-expr` share these call shapes:

```clojure
(compiler/compile-source source)
(compiler/compile-source source compiler-env)
(compiler/compile-source source compiler-env {:net network :seed semantic-id})
```

The compiler reuses the existing reader, source AST constructors, CPS dispatch,
lexical access, primitives, and canonical flat-GUR application. Functional
network declarations and receipt-returning definitions have experimental
handlers. A generic CPS continuation converts inherited raw expression results
into cells: this removes the inherited compiler-hook exception for primitive
values without naming or specializing any primitive. The source core includes
literals, symbols, sequences, `let`,
`let-cell`, conditionals, availability guards, `define`, `network`, and
application. Explicit output declarations and dedicated constraint forms are
rejected, including when supplied as ASTs. `compile-program` accepts ordered
reader forms; `compile-source` retains the shared reader's one-form contract.
It does not introduce a file reader or external IO.

Every expression returns a cell binding. A definition's result cell contains a
binding receipt. A network declaration's result cell contains a callable
receipt. Application returns its body's result cell, and a returned linked list
registers its members as output ports. Reading a cell's strongest value is a
test observation, not the compiler's return contract. The old last-argument
constraint result convention is deliberately not part of this core.

Default identity seeds use semantic AST fields instead of compound-object
implementation identities. Explicit options can provide a network and seed.
An existing live session environment can be used directly; the effect fixture
supplies its session extension's bindings, including `apply` and `emit`.
Compilation never runs propagation or drains effects.

All experiment language and effect tests now use this public compiler entry
point. The production baseline in the expressiveness tests intentionally keeps
using production Compiler 2. KIROSHI selection was refreshed at revision 390:
grounded, with one omitted fact and existing verification gaps. No model facts
were persisted or approved.

The final entry-point verification passed 57 tests and 145 assertions, including
15 compiler API tests. Maximum individual duration was 1297.964292 ms;
namespace/fixture startup was 6610.925667 ms, reported separately. The tests
cover source/form/AST agreement, ordered definitions, result-cell and receipt
contracts, first-class primitives, lexical capture and shadowing, delayed
availability, deterministic identities, recursive linked-list application,
and effect request/execution separation. Production source and fixed kernels
have no diff; the unrelated production test change remains untouched.

## Declaration and application

```clojure
(define constrain-+
  (network (a b c)
    (-> (+ a b) c)
    (-> (- c b) a)
    (-> (- c a) b)
    (list a b c)))

(apply constrain-+ (list a b c))
```

`network` returns a cell containing an inspectable, callable receipt. It retains
fixed formal input symbols, lexical capture, and the body declaration. Its
experimental output-interface metadata is `:body-return`: output members are
not discovered at declaration time. The reused production closure internally
still uses its existing implicit-return representation.

`apply` accepts a compound linked-list cell. The flat-GUR input walker discovers
member cell identities through `:car` and `:cdr` slots without requiring their
values. A finite, complete interface is required before the underlying fixed
arity closure can be applied. Late structure resumes the existing walker.
Direct fixed-arity calls remain available as an existing compiler convenience.

Application compiles the body and installs an output walker on its result cell.
A scalar result stays one result cell. A returned linked list adds stable, named
member output ports and concrete outbound connections. Returned input members
also connect back to the caller's cells, making the addition relation work in
all three tested directions. Late output tails add only missing ports.

```clojure
(compile-and-register-body body-declaration routes context invocation result-id)
;; body declaration -> definition routes -> returned-output registration
```

The output walker registers identities, not materialized member values. Empty
lists add no member ports; unavailable and contradictory information waits.
Malformed tails and cyclic interfaces produce explicit errors. The experiment
lowers `(list)` to the existing empty-list marker because the reused production
zero-element list constructor currently leaves its result unavailable.

## Definitions are connections

```clojure
(define bind-value
  (network (target value)
    (define target value)))

(bind-value destination 42)
```

`define` compiles its destination and source to cells, declares a concrete
source-to-destination propagator, and returns a binding receipt cell. It does
not classify the source as a value or closure. Reserving the destination before
compiling the source allows recursive definitions.

If the destination is an invocation input, the declaration connects back to
the caller. Nested calls follow the existing named inbound boundaries in the
same network graph; no application index or separate child network is added.
The tests cover delivering both a value and a closure to caller cells.

## Effects

The effect tests install an `emit` operator through the unchanged session
extension API. A recursive Lain visitor traverses a compound linked list using
ordinary `car`, `cdr`, availability guards, and flat-GUR application. It emits
requests while the test boundary handler alone performs a controlled external
action. Closure declaration performs neither emission nor execution.

Tests verify late arguments, receipt delivery, replay prevention, and handler
exceptions becoming failed receipts. The fixture explicitly declares the
boundary outbox. No runtime API or scheduler policy is changed.
The ordinary `define-constraint` constructor below also wraps this effectful
visitor successfully: requests precede execution, and the boundary handles all
three emitted values.

## Higher-order construction through ordinary apply

```clojure
(define define-constraint
  (network (name definition-network)
    (define name
      (network (args)
        (apply definition-network args)
        args))))

(define-constraint constructed constrain-+)
(constructed (list a b c))
```

This composition works with the existing experiment implementation. `args` is
one ordinary parameter whose value is a compound linked-list cell. The existing
`apply` walks that list and connects member cells to the captured callee's fixed
inputs. `define` delivers the new callable receipt to the destination cell.
No computed formal-input description is required.

Tests verify all three addition directions, late member values, and stable
topology on reactivation. The callee and its wrapper each register three output
ports even while the member values are unavailable. The constructor itself is
ordinary Lain code, with no constraint-specific host implementation.

The earlier `(network args ...)` spelling is still unsupported: it requests
runtime formal-input descriptions instead of declaring one parameter. The
diagnostic test remains, but that extra capability is unnecessary for this
constructor. Broader equivalence to every production constraint feature remains
unverified.

## Verification and architecture evidence

The initial prototype checks below preceded the compiler entry-point extension.
Verification used base HEAD
`ab30fcbdc471665eebe3606e7edd92ac5ff1f771`, observed 2026-10-06 UTC:

| Check | Outcome |
| --- | --- |
| Experiment and effect suites | 38 tests, 92 assertions passed; maximum 1226 ms |
| Existing application-runtime suite | 4 tests passed |
| Existing CPS suite | 7 tests passed |
| Existing composition suite | 8 tests passed |
| Existing flat network VM suite | 14 tests passed |
| Four existing suites combined | 33 tests, 126 assertions passed; maximum 1507 ms |
| Ordinary apply-based constructor | Three directions, late values, and stable topology verified |
| Computed input descriptions | Unsupported; unnecessary for the constructor above |

The runner executes each test var sequentially with a three-second limit and
reports namespace/fixture startup separately. Run the default experiment suites
with `test/experiments/run_functional_network.clj`; optional namespace arguments
select the existing suites. No full project-wide regression claim is made.

KIROSHI's read-only selection for compiler declaration and recursive topology
returned revision 388, `:grounding-status :grounded`, and one omitted fact.
Relevant current principles include declaration/evaluation separation,
propagator composition, and delayed deterministic topology. The model still
reports verification gaps for flat-GUR application, pull-only materialization,
and stable identities; these local tests do not globally resolve those facts.
The flat-default decision is a candidate, not a governing current principle.

This is evidence preparation for an implementation experiment, with no new
component ownership or hierarchy transition. Verification occurred before
publication; the implementation commit records the experiment sources, tests,
and documentation. It is not a submitted KIROSHI verification receipt. No facts
were persisted, approved, or superseded.

Publication targets `codex/compiler-2-application-runtime-cleanup` in
`Semi-0/datalog-research`. The unrelated changes to
`test/propagators/compile_2_test.clj` and `.agent-memory/` are excluded.
