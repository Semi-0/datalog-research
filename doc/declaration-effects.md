# Compiler declaration effects

Compiler declarations now record flat-GUR effects when they allocate cells,
seed values, install propagators, or publish lexical addresses. Application,
rest-argument packing, delayed bodies, observation primitives, versioned
definitions, and sub-environments return those recorded effects directly.

```clojure
(-> current-net
    declarations/begin
    (declarations/ensure-cell result-id)
    (declarations/seed-cell literal-id 42)
    (declare-body input-ids result-id)
    declarations/result)
;; => {:effects [...] :messages [...]}
```

The compiler still builds an immutable temporary Net so subsequent expressions
can inspect declarations made earlier in the same compilation. This view is
not a child execution network. Flat GUR commits the returned declarations into
the active Net and schedules their propagators through the existing runtime.

The recording buffer belongs to one declaration window. It is immutable and
removed from the publicly returned compiled Net. A delayed application opens
a fresh window over its current runtime Net. Existing declarations and
already-published names are suppressed by identity; only a touched frame's
lexical metadata is inspected. Installer registration visits its returned
propagators and their boundary cells, never the whole network.

Multiple compiler seeds of a cell within the same atomic declaration publish
only the final seed. This matters when wrapping an ordinary callable with a
variadic call adapter. It does not replace or bypass runtime cell merge policy:
the final message is still merged by the ordinary runtime.

Sub-environments emit their body declarations and result projection. The outer
runner executes them; compilation does not run a hidden nested scheduler.

The former `network-diff`, `cell-diff`, and whole-network lexical scans are
removed. This reduces declaration-adapter work; it does not shrink the tracer's
generated topology or change its cycle prevention algorithm.

Ownership remains in the compiler. Generic GUR, cells, compound objects, TMS,
the scheduler, and environment module semantics are unchanged. The research
mirror preserves its additional explicit topology-result name exports.

Verification: compiler 158 tests / 540 assertions; runtime 160 tests / 585
assertions. Each individual test ran under three seconds. The cyclic tracer
reached quiescence in 1.77 seconds with the local compiler dependency.

Web integration: 32 tests / 163 assertions. Research mirror: 210 tests / 748
assertions, including the aggregate Compiler 2 suite and explicit observation
metadata exports. All passed under the same individual-test deadline.
