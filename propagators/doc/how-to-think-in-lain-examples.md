# How to Think in Lain — Worked Examples

Companion to [`how-to-think-in-lain.md`](how-to-think-in-lain.md). Every section
of the guide gets one runnable example here.

Each block is executed by a real compiler-2 session:

```clj
clojure -M -m tools.lain-examples-doc
```

A block fenced with `lain` starts a fresh session; a block fenced with `lain+`
continues the previous one. Inside a block, a line of the form `;; => name value`
asserts that the binding `name` projects to `value`. Projection conventions,
defined in [`tools/lain_example_runner.clj`](../../tools/lain_example_runner.clj):

| projection | meaning |
|---|---|
| `8`, `true`, `:negative`, `"total=7"` | a value reached the binding |
| `:unresolved` | nothing known yet (the guide's `?`) |
| `:contradiction` | two incompatible claims |
| `[1 2 3]` | a connected list known to be complete |
| `[1 :unresolved]` | a connected list with an open tail (the guide's `(1 . ?)`) |

Struct-valued bindings are live accessor networks rather than plain data, so
assert on their `(slot …)` readings instead of on the struct binding itself.

Recursive networks always put the completeness guard first, exactly as
`propagators/compiler_2/runtime/lain/list.lain` does:
`(if (empty-list? xs) base (... (position-of x (cdr xs)) ...))`. A recursive
call built outside a guard is compiled structurally forever and never finishes
loading.

## 1. Core philosophy: compose relationships, not computations

The useful object is a reusable transformation between endpoints, not a call
sequence. Two independent networks compose into a third.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define celsius->fahrenheit (network [c] (+ (* 1.8 c) 32)))
(define wind-chill-adjust (network [f] (- f 2)))
(define room-temp 20)
(define answer (|> room-temp celsius->fahrenheit wind-chill-adjust))
;; => answer 66.0
```

## 2. The three semantic layers: verbs, relations, sentences

* verbs — factories that build transformations (`network`, `struct`, `slot`, `|>`, `compose`);
* relations — named endpoints that hold information (`def-cells`, `let-cell`, `<->`);
* sentences — statements that connect them (`->`, applications, assertions).

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define scale (network [x] (* x 3)))
(define shift (network [x] (+ x 10)))
(def-cells raw result)
(-> 4 raw)
(-> (|> raw scale shift) result)
;; => result 22
```

## 3. Think like a circuit designer, not a programmer

Nothing here says "compute the sum". Three ports are wired into one additive
relationship and two of them are driven.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells total left right)
(constraint:+ left right total)
(-> 7 left)
(-> 5 right)
;; => total 12
```

## 4. Declare relationships, not sequences

The same three sentences in a different textual order produce the same network
and the same answer. Textual order never schedules execution.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b c)
(-> 3 a)
(-> (+ a 1) b)
(-> (* b 2) c)
;; => c 8
```

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b c)
(-> (* b 2) c)
(-> 3 a)
(-> (+ a 1) b)
;; => c 8
```

## 5. Symmetry and multidirectionality

Ask what relationship exists, then wire it so information can enter from any
endpoint. The same additive relationship answers addition and subtraction
questions.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b c)
(constraint:+ a b c)
(-> 4 a)
(-> 6 b)
(define sum c)
;; => sum 10
```

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b c)
(constraint:+ a b c)
(-> 10 c)
(-> 4 b)
(define missing a)
;; => missing 6
```

## 6. Coordination operators: `->` vs `<->`

`->` forwards and can be chained; `<->` declares symmetric coordination and
never chains.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b)
(-> a b)
(-> 7 a)
(define forwarded b)
;; => forwarded 7
```

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b)
(-> a b)
(-> 7 b)
(define back-propagated a)
;; => back-propagated :unresolved
```

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b)
(<-> a b)
(-> 7 b)
(define coordinated a)
;; => coordinated 7
```

## 7. Network transformation is compositional abstraction

`|>` is left-to-right; `compose` returns a stage and applies right-to-left.
Both take time to learn — that is normal, not a sign of a broken abstraction.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define increment (network [x] (+ x 1)))
(define double (network [x] (* x 2)))
(define piped (|> 3 increment double))
(define composed ((compose increment double) 3))
;; => piped 8
;; => composed 7
```

## 8. Cells are for meaningful state, not plumbing

Plumb a transient value with `let-cell` inside one relationship. Reach for a
retained cell when the value is part of the public interface or must accumulate
information.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define average
  (network [a b]
    (let-cell [sum]
      (constraint:+ a b sum)
      (/ sum 2))))
(define answer (average 3 7))
;; => answer 5
```

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b)
(-> 1 a)
(-> a b)
(define answer b)
;; => answer 1
```

## 9. Make time and history explicit

`history` is the declared temporal view: it records incoming publications in
arrival order and ignores reevaluation of the same publication.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define incoming)
(-> 7 incoming)
(define samples (|> incoming (history 4)))
;; => samples [7]
```

```lain+
(-> 7 incoming)
;; => samples [7]
```

Re-publishing the same value is reevaluation, not a new sample. A genuinely
different second value is a contradiction, not a second entry: a cell that is
never reset holds one strongest value, so `history` records the publications
that a live cell actually receives.

## 10. Separate domain logic from crosscutting behavior

The domain pipeline stays pure. Permission and audit are expressed as ports and
stages around it, and the gate reacts when evidence arrives later.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define permission)
(define permitted? (network [x] permission))
(define cleaned)
(-> 6 cleaned)
(define answer (|> cleaned (switch permitted?)))
(define audit-log (|> cleaned (history 4)))
;; => answer :unresolved
;; => audit-log [6]
```

```lain+
(-> true permission)
;; => answer 6
;; => audit-log [6]
```

## 11. Preserve abstraction boundaries

A slot is a connected view of one field. The owner stays an opaque compound;
nothing reaches into its internals.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define incoming)
(define object (struct :nested (struct :value incoming)))
(-> 42 (|> object (slot :nested) (slot :value)))
;; => incoming 42
```

## 12. Build a vocabulary of small transformations

Small named networks become the project's vocabulary and compose without
rewriting the surrounding structure.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define increment (network [x] (+ x 1)))
(define square (network [x] (* x x)))
(define negate (network [x] (- x)))
(define answer (|> 5 increment square negate))
;; => answer -36
```

## 13. Practical reasoning procedure

Expose endpoints, declare the relationship, compose existing stages, then verify
with an assertion.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells width height perimeter)
(define perimeter-of (network [w h] (* 2 (+ w h))))
(-> 3 width)
(-> 4 height)
(-> (perimeter-of width height) perimeter)
;; => perimeter 14
```

## 14. Anti-patterns: what *not* to do in Lain

**A — imperative pipeline instead of relational design.** `->` is a directed
edge, so a plain chain cannot answer an inverse question; a declared
relationship can. `next` follows the guide's multi-port idiom: it wires the
justified directions and returns its endpoints as a list.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells x y)
(-> (+ x 1) y)
(-> 5 y)
(define inverse-answer x)
;; => inverse-answer :unresolved
```

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define next
  (network [x y]
    (-> (+ x 1) y)
    (-> (- y 1) x)
    (list x y)))
(def-cells x y)
(next x y)
(-> 5 y)
(define inverse-answer x)
;; => inverse-answer 4
```

**G — unjustified bidirectionality.** Lossy transformations cannot be inverted.
Coordinating a filtered view with its source does not recover the source.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define keep-positive (network [x] (switch x (> x 0))))
(def-cells source mirror)
(<-> source (keep-positive source))
(-> 5 mirror)
(define recovered source)
;; => recovered :unresolved
```

**Contradictions are information.** Two incompatible claims about one cell are
reported, not silently overwritten.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define c)
(-> 1 c)
(-> 2 c)
(define answer c)
;; => answer :contradiction
```

## 15. Evaluation checklist

One example that satisfies the checklist: meaningful endpoints, a declared
relationship, preserved boundaries, and an observable inverse question.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define product
  (network [a b c]
    (-> (* a b) c)
    (-> (/ c b) a)
    (-> (/ c a) b)
    (list a b c)))

(def-cells price quantity total)
(product price quantity total)
(-> 4 price)
(-> 3 quantity)
(define bill total)
;; => bill 12
```

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define product
  (network [a b c]
    (-> (* a b) c)
    (-> (/ c b) a)
    (-> (/ c a) b)
    (list a b c)))

(def-cells price quantity total)
(product price quantity total)
(-> 12 total)
(-> 3 quantity)
(define bill price)
;; => bill 4
```

## 16. The guiding principle

> Do not describe how to compute. Describe what is connected to what, and let
> information flow.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells left right sum)
(constraint:+ left right sum)
(-> 2 left)
(-> 3 right)
(define answer sum)
;; => answer 5
```

## 17. Connected accessors: lists and objects are networks

`car`, `cdr`, and `slot` are connected views. A literal list is only a
convenient description of the underlying cons-cell network.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define xs (list 1 2))
(define end (cdr (cdr xs)))
(define answer (empty-list? end))
(define raw (list 1 2 3))
;; => answer true
;; => raw [1 2 3]
```

Structs are discovered the same way, and that has a consequence: nothing declares
their slot set, so a write through an unmentioned slot creates it, and a slot
that is still pending reads exactly like a slot that will never arrive. There is
no completeness signal for a struct, and `nothing?` is not one; code that cares
says so with an explicit cell. Deriving completion from type descriptions is
tasked in `propagators/doc/TODO.md`.

```lain+
(define object (struct :a 1))
(-> 2 (|> object (slot :b)))
(define a-value (|> object (slot :a)))
(define b-value (|> object (slot :b)))
(define absent (|> object (slot :never)))
(define is-nothing (nothing? object))
;; => a-value 1
;; => b-value 2
;; => absent :unresolved
;; => is-nothing false
```

## 18. Complete lists and open prefixes

An unresolved tail stays open until something fills it. Downstream stages
process the available prefix and wait for the rest.

```lain
(load-lain "propagators/compiler_2/runtime/lain/list.lain")

(define tail)
(define xs (cons 1 tail))
(define doubled (map-list (network [x] (* x 2)) xs))
(define first-value (car doubled))
(define second-value (car (cdr doubled)))
(define complete? (empty-list? (cdr (cdr doubled))))
;; => first-value 2
;; => second-value :unresolved
;; => complete? :unresolved
```

```lain+
(-> (list 5) tail)
;; => first-value 2
;; => second-value 10
;; => complete? true
```

## 19. List-library composition is linear

`map-list`, `filter-list`, and `zip-with` are ordinary named stages.

```lain
(load-lain "propagators/compiler_2/runtime/lain/list.lain")

(define doubled (map-list (network [x] (* x 2)) (list 1 2 3)))
(define kept (filter-list (network [x] (>= x 4)) doubled))
(define paired (zip-with (network [x y] (+ x y)) kept (list 10 20)))
(define answer (car (cdr paired)))
(define complete? (empty-list? (cdr (cdr paired))))
;; => answer 26
;; => complete? true
```

## 20. Constraints make relationships multidirectional

`constraint:+` installs three justified directions of one arithmetic
relationship; the ports are symmetric even though the source text is not.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(def-cells a b c)
(constraint:+ a b c)
(-> 1 a)
(-> 2 b)
(define first a)
(define second b)
(define third c)
;; => first 1
;; => second 2
;; => third 3
```

## 21. Conditional relationships are still relations

`cond` and `if` select a relationship; `switch` gates information on evidence.
All of them stay inside the declarative style.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define classify
  (network [x]
    (cond [(< x 0) :negative
           (= x 0) :zero
           else :positive])))
(define below (classify -3))
(define mid (classify 0))
(define above (classify 8))
;; => below :negative
;; => mid :zero
;; => above :positive
```

## 22. Meaningful state inside recursive networks

A recursion that carries an accumulator keeps it as an explicit argument; a
`let-cell` is for state local to one invocation.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define sum-list
  (network [xs acc]
    (if (empty-list? xs)
      acc
      (sum-list (cdr xs) (+ acc (car xs))))))
(define answer (sum-list (list 2 3 4) 0))
;; => answer 9
```

## 23. Inspect a trace before changing the contract

`trace-network-once` and `run-flat-gur-once` are observations that complete
after the run. They stay pending until the trace is recorded, so the ordinary
dataflow answer is available immediately and the report is not.

```lain
(load-lain "propagators/compiler_2/runtime/lain/combinator.lain")

(define increment (network [x] (+ x 1)))
(define double (network [x] (* x 2)))
(define answer (|> 3 increment double))
(define report (trace-network-once answer))
(define completed report)
;; => answer 8
;; => completed :unresolved
```

## 24. Keep the public interface relational

Expose endpoints and relationships rather than implementation calls. The search
itself stays a forward-only function — a recursive search is lossy and is not
made invertible by wrapping it in `<->` (anti-pattern G) — so the public surface
is the three endpoints, and any inverse question needs its own relationship with
justified directions.

```lain
(load-lain "propagators/compiler_2/runtime/lain/list.lain")

(def-cells needle haystack result)

(define position-of
  (network [x xs]
    (if (empty-list? xs)
      0
      (if (= x (car xs))
        0
        (+ 1 (position-of x (cdr xs)))))))

(<-> (position-of needle haystack) result)
(-> 8 needle)
(-> (list 4 8 15) haystack)
(define answer result)
;; => answer 1
```
