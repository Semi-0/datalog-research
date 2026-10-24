# Direct input returns lose the caller cell connection

Status: confirmed runtime boundary bug, documented 2026-10-24; not fixed here.

The intended same-cell interpretation of `(network [input] input)` would preserve
its returned input's connection. The current implementation instead publishes the
scalar return through a one-way copy boundary.

## Reproduction

```clojure
(define a)
(define identity-network (network [x] x))
(define b (identity-network a))
(-> 42 b)
```

Observed: `b` becomes `42`, while `a` remains `the-nothing`. Forward propagation
from `a` to `b` works. An explicit `(<-> a b)` propagates the reverse update too.
These cases were executed in the ordinary editor runtime.

The semantic graph currently shows `a -> identity-network -> b`, consistent with
that execution. Changing the tracer to draw reciprocal arrows alone would claim
reverse propagation that does not exist.

Returning `(list input)` follows the returned-member interface path and gets
inout-port presentation. It also returns a list, so it is not a scalar identity
workaround. Current returned-member registration lives in
`propagators/compiler_2/runtime/returned_outputs.clj`; application return wiring
and the projection must be assessed together before a fix.

## Required verification for a future fix

- Direct returned inputs preserve the agreed caller-cell connection, including
  publication from the returned side when the original input is empty.
- Derived scalar results remain distinct; equal values do not imply identity.
- Aliases and repeated or nested applications preserve ownership and scope.
- Supported callables retain correct support, retraction, conflict, and recovery
  behavior; a reverse connection must not invent support or overwrite plain data.
- Tracers render reciprocal edges only where the runtime contract warrants them.

The fix requires a reviewed application-boundary contract. This report does not
change execution, cell identities, tracer projection, or the user's workspace.
