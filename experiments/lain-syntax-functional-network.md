# Lain syntax: functional-network iteration

This document describes the implemented experimental compiler in
`experiments.functional-network.compiler`. It is a design contract for this
iteration, not a replacement for production Lain syntax. Examples of recursive
programs are written in Lain and use generic compiler and propagator machinery;
there are no Fibonacci, map, or filter host implementations.

## Boundary

```clojure
{:owns '#{experiments.functional-network
          experiments.functional-network.compiler}
 :reuses '#{compiler-2-reader compiler-2-ast compiler-2-cps
            live-lexical-access flat-gur compound-object-slots
            session-extension effect-boundary}
 :fixed '#{production-compiler production-runtime
           gur-kernel compound-object-kernel tms scheduler}
 :model-mutation :none}
```

The experiment changes definition returns and network declarations locally.
Production modules and syntax remain unchanged. Compiler entry points declare
topology; propagation and external effect execution are separate operations.

## Core notation

The preferred surface is:

```text
expression := literal
            | symbol
            | (define symbol expression)
            | (network (symbol ...) expression ...)
            | (let [symbol expression ...] expression ...)
            | (let-cell [symbol ...] expression ...)
            | (if expression expression expression)
            | (when expression expression ...)
            | (expression expression ...)
```

Bodies contain at least one expression. `let` uses name/expression pairs;
`let-cell` uses names only. Network formals are fixed symbols; the normalizer
also accepts a vector of formals. Vectors here describe bindings or formals,
not runtime linked lists.

`apply`, `list`, `cons`, `car`, `cdr`, arithmetic, comparisons, `switch`, and
connection operators are environment operators, not additional special forms.
The inherited parser also accepts derived notation and aliases such as `def`
and `cell-expr`; this iteration prefers `define` and `network`.

`compile-source` reads one expression. Wrap several expressions in a body, or
use `compile-program` with ordered reader forms. This is not a file-loading API.

## Every expression returns a cell

The compiler returns a cell ID, not its current strongest value. These are
different result contracts:

| Expression | Information carried by its result cell |
|---|---|
| `42` | Literal value `42` |
| `answer` | Information projected from the lexical binding |
| `(define answer 42)` | Binding declaration receipt |
| `(network (x) (+ x 1))` | Canonical flat-GUR callable with declaration metadata |
| `(increment 41)` | Applied body's result information |
| `(list a b c)` | Compound linked-list structure referring to member cells |
| Effect request operator | Receipt information from the effect protocol |

A network value is callable declaration data, rather than an executed body.
A definition receipt identifies the declaration and its source and target
cells. Looking up the defined name accesses the target cell, not that receipt.

```clojure
(let []
  (define answer 42)
  answer)
```

The final expression determines a body's result. Earlier expressions can
declare bindings and connections. Source traversal order does not impose an
external-effect execution order.

## Definitions and lexical scope

```clojure
(let-cell [input]
  (define increment (network (x) (+ x 1)))
  (list input (increment input)))
```

`let-cell` declares initially unavailable cells. The application and its
dependencies can exist before `input` receives information. Lexical access
uses the existing live environment topology, flat-GUR lookup, and binding-value
projection. Network declarations capture the lexical environment ID.

`define` connects the compiled source cell to a named target and returns a
receipt. It reserves the destination before compiling the source, supporting
self-reference. It is not an overwrite assignment; conflicting information
still follows existing cell semantics. Undefined symbols can produce waiting
lookup topology rather than requiring an available value at compilation.

```clojure
(let []
  (define make-adder
    (network (bias)
      (network (x) (+ bias x))))
  (define add-two (make-adder 2))
  (add-two 40))
```

The returned callable retains access to the invocation's `bias` binding.
Lexical shadowing and late captured values use the same access topology.

## Network application and return interface

```clojure
(let []
  (define add (network (a b) (+ a b)))
  (add 2 3))
```

Application compiles the operator and argument expressions to cells and
installs flat-GUR application topology. A usable callable declares its body in
the same active `Net`. Arguments may remain unavailable: body propagators wait
for the information they require. Late operators and values wake existing
dependencies. The compiler does not execute an external action here.

The body's final expression supplies the primary result. No explicit output
formal or last-argument return convention is needed.

```clojure
(let-cell [a b c]
  (define constrain-+
    (network (a b c)
      (-> (+ a b) c)
      (-> (- c b) a)
      (-> (- c a) b)
      (list a b c)))
  (constrain-+ a b c))
```

A returned linked list remains the primary compound result. Its member cells
are also connected to distinct, named output-port cells, one per position.
Repeated members have distinct positional ports. Members need not contain
values before their ports are registered. A late tail extends the interface
without rebuilding established ports; equivalent reactivation adds no topology.
Returned invocation inputs can connect back to caller cells, as required by
the constraint example.

These ports are explicit connections in the active network. They are **not**
added to the original GUR application propagator's `:outputs` set. Inspection
must traverse the connected ports and their names. Scalar returns create no
list-member ports; `(list)` creates an empty interface.

The prototype lowers `(list)` to the existing empty-list marker
`:compiler-2/list-empty`. This is an implementation convention, not a new
generic compound-object representation.

## Runtime `apply`

```clojure
(let []
  (define add (network (a b) (+ a b)))
  (apply add (list 2 3)))
```

`apply` obtains argument cell IDs from a finite compound linked-list spine;
it does not materialize argument values. It waits for an unavailable spine and
declares the call when its shape is known. A direct call knows the argument
count immediately, so the two forms can declare topology at different times.
Arity mismatches, malformed list tails, and cycles are explicit errors.

Fixed formals remain sufficient for a generic constructor:

```clojure
(define define-constraint
  (network (name definition-network)
    (define name
      (network (args)
        (apply definition-network args)
        args))))
```

The generated callable takes one list-valued argument. This does not introduce
computed formals: `(network args ...)` remains unsupported.

## Selection and availability

Ordinary `if` selects result information. Its operand expressions are compiled
as topology; do not use it as a promise that recursive branch bodies will be
compiled lazily. To delay a body, select a network value and then apply it:

```clojure
(let []
  (define fib
    (network (n)
      ((if (< n 2)
         (network () n)
         (network () (+ (fib (- n 1)) (fib (- n 2))))))))
  (fib 6))
```

This tested program returns `8`. GUR compiles the selected network's body on
application, including further recursive applications.

`when` is an availability guard, not a truth guard. Both `nothing` and
contradiction wait; `false` is usable information. Boolean-controlled topology
uses `switch` to make a false branch unavailable:

```clojure
(when (switch true condition)
  (define answer expression))
```

## Higher-order linked-list programs

```clojure
(let []
  (define map-list
    (network (f xs)
      (let-cell [answer]
        (when (switch true (= xs :compiler-2/list-empty))
          (define answer (list)))
        (when (switch true (not (= xs :compiler-2/list-empty)))
          (define answer
            (cons (f (car xs)) (map-list f (cdr xs)))))
        answer)))
  (map-list (network (x) (+ x 1)) (list 1 2 3)))
```

The result is a compound list with members `2`, `3`, and `4`. Map/filter chains
and `zip-with` compose using the same operators and closures. Recursion grows
missing topology as list tails become available. This is recursive topology
construction, not a claim of tail-call optimization or bounded total size.

## Effects

Effect operators are supplied through the existing session extension API.
`emit` in the experiment tests is one such extension, not a default builtin:

```clojure
(let []
  (define announce (network (x) (emit x)))
  (announce 42))
```

```text
compile expression -> declare topology
propagate          -> publish effect request
drain boundary     -> execute registered handler -> settle receipt
```

The runtime session owns unexpected propagation failures. The effect boundary
owns handler/external failures and records failed receipts. Compilation does
not drain requests, and replay does not repeat an already handled request.
Independent requests have no newly guaranteed execution ordering.

## Verification and remaining limits

The combined experiment run on current `main` passed **73 tests, 193
assertions**. Each test var completed within three seconds; the maximum was
approximately 0.781 seconds. Namespace/fixture startup took approximately
4.460 seconds and was measured separately.

Default compilation declares a live root from `default-bindings`. Explicit
environment arguments must be live environment IDs and use the supplied
network. The experiment schedules declaration effects using the current
`:op :network/declare-propagator` format. These integrations reuse current
production APIs without modifying their implementation.

| Contract | Test namespace |
|---|---|
| Source/form/AST APIs, validation, cell returns | `functional-network-compiler-test` |
| Definitions, closures, application, list ports | `functional-network-test` |
| Call shape, capture, late updates, port graph edges | `functional-network-contract-test` |
| Fibonacci, map/filter/zip, late tails, stable topology | `functional-network-gur-test` |
| Request/execute separation and failure receipts | `functional-network-effects-test` |
| Baseline comparison and expressiveness limits | `functional-network-expressiveness-test` |

These are bounded examples, not a proof for all programs. Dedicated
`def-constraint`, `def-net`, compound output declarations, and explicit network
output formals are outside this compiler core. Computed formal descriptions
are rejected. Arbitrarily large recursive programs, destructive list-slot
changes, and universal observational equivalence are not established.
The inherited reader's optional definition handling also leaves literal `nil`
definition semantics outside this document's verified contract.

See [the experiment overview](functional-network.md),
[contract evidence](functional-network-contracts.md), and
[expressiveness analysis](expressiveness.md) for implementation details and
the distinction between tested behavior and stronger proof obligations.
