# Lain syntax: functional networks

This document defines the production Compiler 2 surface language. It supersedes
the earlier `def-net`, explicit-output-vector, `def-constraint`, `cell-expr`,
and `::` syntax. Historical reports describe their own checkpoints.

## Boundary

The compiler reads source, traverses a slot-backed AST through named CPS
predicates and handlers, and declares topology in an immutable `Net`.
Application and lexical lookup use flat GUR in that same network.

Compilation does not execute external effects. Runtime operators emit requests;
the registered effect boundary performs them and records receipts.

The migration changes compiler syntax, declaration, application lowering, and
syntax-sensitive runtime/TUI consumers. It does not change GUR, compound-object,
cell merge, TMS, scheduling, the live environment kernel, session extensions,
or the external effect execution boundary.

## Grammar

```text
expression := literal
            | symbol
            | (define symbol)
            | (define symbol expression)
            | (network parameters expression+)
            | (let [symbol expression ...] expression+)
            | (let-cell [symbol ...] expression+)
            | (when expression expression+)
            | (if expression expression expression)
            | (cond [expression expression ... else expression])
            | (expression expression*)

parameters := (symbol* [& symbol]) | [symbol* [& symbol]]
```

`nil` is a literal. An omitted definition value is distinct from a literal
`nil`. A source entry point reads exactly one form. `compile-program` compiles
nonempty ordered reader forms in a shared environment.

There is no `do` form. Bodies contain ordered expressions and return the final
expression's cell. Definitions can be top-level program forms.

## Every expression returns a cell

| Expression | Information in its returned cell |
| --- | --- |
| Literal | Its value |
| Symbol | The value of its live lexical binding; unavailable symbols wait |
| `define` | A binding declaration receipt |
| `network` | A canonical first-class callable declaration |
| Application | The final expression's cell value from the applied body |
| `let` / `let-cell` | The final body result |
| Effect request operator | Its existing effect receipt |

```clojure
42
(define answer 42)
(network (x) (+ x 1))
((network (x) (+ x 1)) 41)
```

The second expression returns a receipt describing the source and target cells,
not the value `42`. Evaluate `answer` to observe the named value.

```clojure
(let []
  (define answer 42)
  answer)
```

## Definitions refine named cells

`define` resolves or reserves its target before compiling the source expression.
It declares a connection from the source cell to that target and returns an
inspectable receipt immediately. Propagation supplies the target's information.

```clojure
(let []
  (define answer)
  (define answer 42)
  answer)
```

Both definitions refer to the same target. Conflicting information follows the
existing cell/TMS merge rules; a later definition does not silently replace it.

```clojure
(let []
  (define answer 1)
  (define answer 2)
  answer)
```

This produces a conflict in the ordinary value environment. Versioned runtime
blocks retain their separate candidate-selection policy; that policy does not
change the meaning of ordinary `define` inside a network.

Use `let` for lexical shadowing:

```clojure
(let [answer 1]
  (list (let [answer 2] answer)
        answer))
```

`let-cell` declares local waiting cells. Its local declarations block parent
lookup even before values arrive.

## Network declaration and application

```clojure
(define increment
  (network (x)
    (+ x 1)))

(increment 41)
(apply increment (list 41))
```

Declaration captures a live environment cell ID and retains the body AST. It
does not compile or run the body. Application installs a flat-GUR application
immediately. A usable callable declares its stable invocation frame, argument
boundaries, body topology, and result boundary once.

Late operators, arguments, captured values, and linked-list tails refine the
existing topology. No host environment map or child applied network is needed.

```text
operator cell -> flat GUR application
argument cells -> concrete inbound -> local parameter cells
captured environment -> scope frame -> flat GUR lexical access
compiled body -> concrete outbound -> primary result
returned list structure -> positional outbound member ports
```

Both call shapes use ordinary application. `apply` traverses a finite live
linked-list spine to obtain argument cell IDs. Its members need not have values.
Malformed or cyclic spines report errors. Fixed arity is checked when the
callable's topology is declared.

Rest parameters retain their production behavior:

```clojure
(define first-extra
  (network (required & remaining)
    (car remaining)))

(first-extra 1 2 3)
```

## Returned linked lists define additional ports

The body return always remains the primary result cell. A scalar has no member
ports. An empty list has zero member ports. A returned compound linked list
declares one stable port per outer list position.

```clojure
(define constrain-+
  (network (a b c)
    (-> (+ a b) c)
    (-> (- c b) a)
    (-> (- c a) b)
    (list a b c)))

(let-cell [a b c]
  (constrain-+ a b c)
  (-> 3 b)
  (-> 5 c)
  (list a b c))
```

The returned input members make their refinements observable at the caller.
The result above is the linked list containing `2`, `3`, and `5`, rather than
an implicit last-argument scalar.

Duplicate members have distinct positional ports. A nested list is one outer
member, not a flattened collection of ports. Late tails add only missing ports.
Unknown compound return structures remain pending; unusable values wait.
Cyclic lists and non-list tails are unsupported and report precise errors.

Inspection reads named topology and reports the primary return, member ports,
and pending/completed structure status. It does not rewrite the GUR
propagator's output set after installation.

## Higher-order construction

`define` is a binding propagator, so a constructor can fill a caller's named
cell with a network declaration:

```clojure
(define define-constraint
  (network (name definition-network)
    (define name
      (network (arguments)
        (apply definition-network arguments)
        arguments))))

(let-cell [addition a b c]
  (define-constraint addition constrain-+)
  (addition (list a b c))
  (-> 2 a)
  (-> 3 b)
  (list a b c))
```

This composes ordinary definitions, network declarations, list construction,
and application. No constraint-specific compiler form is involved.

## Conditional topology and recursion

`if` is a value-selection operator: both operand expressions are compiled.
Use callable selection to compile only the selected recursive body:

```clojure
(define fib
  (network (n)
    ((if (<= n 1)
       (network () n)
       (network ()
         (+ (fib (- n 1))
            (fib (- n 2))))))))
```

`when` declares body topology on availability. A usable `false` is available;
`when false ...` therefore declares its body. For boolean guarding, compose a
switch:

```clojure
(when (switch true condition)
  expression)
```

Recursive network construction uses flat GUR. It is on-demand topology growth,
not tail-call optimization. Compound linked-list HOPs may recursively compose
mapping and filtering stages; the acceptance tests cover multiple chained
lists and late tails.

## Primitive and effect extensions

Primitives are canonical callable cells. Syntax-sensitive primitives use the
inspectable `:operator/compiler-operands` declaration hook. Its CPS continuation
returns a cell binding, just like ordinary handlers. Known aliases preserve
visible declaration links. A late/computed callable uses ordinary GUR
application; compile-time-only operand hooks retain that restriction.

An immutable runtime session extension supplies program bindings and effect
specifications. Programs invoke those bindings normally:

```clojure
(define send
  (network (value)
    (emit value)))

(send 42)
(apply send (list 42))
```

`emit` above is a fixture-provided extension capability, not a built-in external
action. Propagation emits requests; draining the effect boundary invokes the
registered handler, records success/failure receipts, and prevents replay.

## Migration

Removed forms fail with migration guidance. There is no runtime conversion:

```text
def / def-cell / def-cells -> define
cell-expr / ::            -> network with a body return
def-net / compound        -> define + network
explicit output vectors  -> ordinary parameters + explicit connections + list
def-constraint           -> define + network + connections + returned inputs
```

Existing files must be rewritten before loading. In particular, an old
single-output network implicitly connected its final expression to that output;
the new source must write that connection explicitly when it is still needed.

## Executable evidence

Versioned definitions compose the same closure compiler with a returned-member
projection. Ordinary application copies each returned port to its caller cell;
versioned application installs the existing `p:block-premise` at that crossing.
It does not gate every input or change TMS merge semantics.

```clojure
(application/closure-callable
 compile* name declaration-id lexical-environment-id closure-info
 (partial premise-output-boundary candidate-contexts caller-targets))
```

Candidate input cells project caller-supplied evidence while excluding claims
supported by their own definition's returned-output feedback. Outer cells keep
all claims and premise history. Other definitions' support and caller withdrawal
remain inputs. State-only content does not fill a fresh invocation cell.

This is version-selection policy inside the versioned-definition operator. The
ordinary callable and generic GUR/TMS contracts are unchanged.

Compiler tests cover named CPS dispatch, all-cell returns, definition receipts
and conflicts, lexical capture/shadowing, late information, direct/list apply,
rest parameters, scalar/empty/nested returns, member port topology, Fibonacci,
multi-stage list HOPs, and stable identities/counts. Runtime tests cover effect
requests, delayed arguments, execution receipts, failures, and replay.

These are bounded acceptance scenarios, not an all-context equivalence proof
or a claim that every compound-object traversal is supported. Publication and
complete suite outcomes are recorded in the migration verification report.

Literal `nil` and explicit `(define x nil)` are distinct from an omitted value.
They do not imply general `nil` transport through every operator: the fixed
readiness/value-projection path does not currently produce a selected `nil`
primitive branch, and `when nil` is unsupported. Changing that requires a
separate review of the shared value/readiness contract.
