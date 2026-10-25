# Reactive list networks

Load these ordinary Lain networks into a compiler-2 session:

```clojure
(load-lain "propagators/compiler_2/runtime/lain/list.lain")
```

| Network | Relationship |
| --- | --- |
| `(map-list f xs)` | Each available cons head is transformed by `f`; its tail remains connected to the mapped source tail. |
| `(filter-list predicate xs)` | Heads whose predicate resolves true are retained in order; false heads connect directly to the filtered tail. An unresolved predicate leaves that output pending. |
| `(zip-with f xs ys)` | Corresponding available heads are combined by `f`. Each position waits for both input cons cells. |
| `(zip xs ys)` | Corresponding heads become pair lists `(list x y)`. |

```clojure
(define increment (network [x] (+ x 1)))
(define doubled (map-list (network [x] (* x 2)) (list 1 2 3)))
(define selected (filter-list (network [x] (>= x 4)) doubled))
(define answer (map-list increment selected))
;; Available answer members: 5, 7.

(define totals (zip-with (network [x y] (+ x y))
                        (list 1 2) (list 10 20)))
;; Available totals members: 11, 22.
```

`(list)` produces a known empty-list value. `(list x y)` constructs cons cells
whose final tail is that same known empty value. `empty-list?` is true for that
value and false for a known nonempty cons. It remains pending for an unresolved
cell; emptiness and missing information are different facts. As with the other
compiler predicates, it reads the current supported base and preserves support
and premise state in its result.

Explicit cons cells can still have open tails:

```clojure
(let-cell [later]
  (define xs (cons 1 later))
  (define ys (map-list increment xs))
  (car ys))
;; Resolves to 2 before later has a value.
```

Here the mapped tail stays connected to `later`. Publishing another cons extends
the mapped prefix; publishing an empty list closes it. A currently available
prefix does not establish final length. A resolved scalar tail such as `42` is
not an empty-list value and does not take the empty branch.

The networks compose `cons`, `car`, and `cdr` propagators through `if` relationships
selected by `empty-list?`. Known empty inputs produce known empty outputs;
unknown tails leave only the corresponding recursive outputs pending. Filter
also selects its returned head/tail through `if` when the predicate changes.
`zip` and `zip-with` return the common prefix and close when the selected shorter
input is empty. They do not materialize or reconstruct source object graphs.

These are ordinary GUR applications: argument cells, returned cons owners, and
field references remain live. The empty-list value only describes a list's
terminal shape; it does not imply that its head values are available. Changes,
withdrawal, and recovery propagate through the existing supported accessors and
call boundaries. `when` continues to install topology when its guard becomes
available; these value-dependent returns use `if` selection instead.

Plain values, chained transformations, and late-tail behavior are verified by
[the functional GUR tests](../../../../test/propagators/compiler_2_functional_network_gur_test.clj).
The [GUR integration tests](../../../../test/propagators/compiler_2_gur_tms_integration_test.clj)
also check TTMS updates, retraction and recovery through returned cons objects.
Supported owners publish immutable live accessor references. Accessors reread
the current owner and field, combining support and premise state. Selecting a
shorter list withdraws the old tail's availability while preserving reusable
internal topology. [Supported selection acceptance](../../../../test/propagators/compiler_2_supported_compound_if_test.clj)
checks the complete nested-object sequence, direct/piped access, relay calls,
stale updates, contradictions, withdrawal/recovery, and list-tail predicates.
The formerly failing dynamic `choose`/nested-struct scenario in the combined
probe now passes all 14 checks against production code without private overrides.
