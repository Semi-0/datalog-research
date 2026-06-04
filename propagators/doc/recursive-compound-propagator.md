# Recursive Compound Propagator

Source files:

- `propagators/recursive.clj`
- `propagators/compile.clj`
- `test/propagators_recursive_compound_test.clj`

## Status

Recursive compound propagation is implemented as activation-local network
expansion. It does not change the core scheduler contract and does not introduce
a new primitive data type.

The public API is:

```clojure
(recursive/recursive-closure step-f)
(recursive/recursive-closure step-f {:max-depth 1024})
(recursive/p:recursive-compound closure-id arg-id out-id)
(recursive/p:recursive-compound closure-id [arg-id ...] out-id)
```

`p:recursive-compound` is a normal propagator installer. Its activation reads a
closure-valued cell, creates boundary avatars, exposes a self closure under the
activation-local network dict key `:recursive/self`, applies the closure, runs
the inner network to quiescence, and diffs avatar output back to the real output
cell.

## Why This Fits The Runtime

Normal propagators return messages, not graph edits. Recursive propagation
therefore cannot mutate the outer graph during `eval-propagator` without
changing the core runtime contract.

Instead, recursion happens inside the inner network value:

```text
outer cells
-> avatar frame
-> closure applies to activation-local network
-> closure may install recursive compounds into that network
-> inner run-tasks reaches quiescence
-> changed avatar output becomes an outer message
```

This keeps recursive work isolated from the outer scheduler. The recursive
network can expand and resume because a network is ordinary immutable data.

## Recursive Closure Step

`recursive-closure` wraps a step function. The step receives:

```clojure
{:closure-net closure-net
 :self-id self-id
 :input-ids input-ids
 :output-ids output-ids
 :network network
 :depth depth}
```

The step returns an updated activation-local network.

Recursive calls are ordinary topology installs against `self-id`:

```clojure
(recursive/p:recursive-compound self-id n-minus-1 fib-1)
(recursive/p:recursive-compound self-id n-minus-2 fib-2)
```

Each recursive activation increments `:recursive/depth` in the closure net. If
the configured `:max-depth` is reached, the recursive closure writes
`contradiction` to its output avatars.

## Compile DSL Support

The default compile installer vocabulary includes:

```clojure
prop/+
prop/switch
closure/p:apply-closure
recursive/p:recursive-compound
```

That lets recursive branch topology be written with the same DSL as other
network construction:

```clojure
(let-cell [n-1 n-2 fib-1 fib-2]
  (seed n-1 n-minus-1)
  (seed n-2 n-minus-2)
  (recursive/p:recursive-compound self n-1 fib-1)
  (recursive/p:recursive-compound self n-2 fib-2))
```

`let-cell` now creates fresh lexical cells for declared locals. That matters for
recursion: each recursive frame must get fresh `n-1`, `fib-1`, and similar
scratch cells instead of reusing prior frame bindings from the network dict.
Plain symbol references outside `let-cell` still resolve through the dict.

## Fibonacci Proof

`test/propagators_recursive_compound_test.clj` implements Fibonacci as a
test-local recursive closure.

The closure step:

1. reads concrete numeric `n`
2. writes contradiction for negative or non-integer input
3. installs and runs a switch-guarded base branch first
4. stops if the base branch wrote the output
5. otherwise lazily installs recursive branches for `n - 1` and `n - 2`
6. runs both recursive branches
7. installs `prop/+` only after both child outputs are usable

The base branch is intentionally lazy:

```text
n -- prop/switch(base?) --> out
```

If `out` is no longer `the-nothing` after that branch runs, no recursive
topology is built for that activation.

## Wrapped In A Normal Compound

The tests also wrap the recursive propagator inside a normal compound closure:

```clojure
(closure/p:apply-closure wrapper n out)
```

The wrapper installs `recursive/p:recursive-compound` in its own closure body.
This proves the recursive helper can be used as a one-time recursive compound
inside the existing runtime compound mechanism.

## Current Limits

- V1 is concrete-input only; it does not implement relational Fibonacci or
  backward propagation from output to input.
- Recursive expansion is activation-local and returns boundary messages only;
  it does not edit the outer graph during `eval-propagator`.
- There is no memoization. Fibonacci intentionally builds a recursive tree as a
  proof of recursive network expansion, not as an efficient numeric algorithm.
- Recursive steps should avoid installing primitive arithmetic over unusable or
  contradiction child outputs. The Fibonacci test checks child outputs before
  installing `prop/+`.

## Current Tests

`test/propagators_recursive_compound_test.clj` covers:

- `fib(0)`, `fib(1)`, `fib(2)`, `fib(5)`, and `fib(10)`
- DSL installation of `recursive/p:recursive-compound`
- lazy base branch behavior
- negative input contradiction
- unusable input leaving output empty
- max-depth contradiction
- recursive propagation wrapped inside a normal compound closure
