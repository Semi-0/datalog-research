# Compiler 2 layout

- `cps_core.clj` — canonical stack-safe compiler assembly and public compile
  entrypoints.
- `compiler/` — CPS predicates, handlers, declarations, dispatch, the default
  operator basis, and a deprecated `core` namespace shim.
- `language/` — parser and AST representation.
- `model/` — compiler environment and retained closure/application/operator
  values.
- `runtime/` — compiler execution and the reusable live runtime:
  - delayed application, closure frames, retained/lexical application, and
    lazy topology execution live at the root;
  - `session/` owns state, commands, program compilation, and replay;
  - `tui/` owns block state, version history, and versioned commits;
  - `boundary/`, `inspection/`, `bridge/`, and `operators/` isolate their
    respective runtime concerns.
- `operators/` — behavior, TMS, and reducer operator families.
- `deprecated/` — retained synchronous compiler implementation and its old
  compatibility core.

`main.clj` is the public compiler façade. Root-level `core.clj` and
`predicate_core.clj` plus `compiler/core.clj` are compatibility façades. New
compiler entrypoint code should depend on `cps-core`; implementation code
should depend on the responsibility-specific namespaces under `compiler/`.

`propagators.compiler-2.runtime` is the public live-runtime façade. Servers,
TUIs, dashboards, and other presentation or transport entrypoints stay under
`graph`. `graph.compiler-2-runtime` remains as a deprecated source
compatibility façade; the existing semantic graph projector/REPL is still a
graph-owned inspection dependency.

Current implementation status, unresolved correctness issues, and their
dependency order are tracked in
[`../doc/compiler-2-progress-and-priorities.md`](../doc/compiler-2-progress-and-priorities.md).

## Conditional expressions

`if` and `cond` suspend their branch bodies. Only a selected body builds its
application topology; `cond` also defers later predicates until earlier ones
are false. An unknown condition waits. A `cond` without a matching clause or
`else` returns nothing. Recursive branches need no manual `network` wrapper:

```clojure
(define sum-to
  (network [n acc]
    (cond [(<= n 0) acc
           else (sum-to (- n 1) (+ acc n))])))
```

The compiler rewrites both forms to suspended zero-argument networks selected
through the shared conditional propagator and invoked by ordinary callable
application. Supported application gates each candidate's output with the
current selection support. Changing or retracting the condition withdraws the
old result; selecting a previously built branch reuses its topology. Declared
topology remains in the monotone network even when its result is inactive.

`select` is the eager value selector: `(select condition then-value else-value)`.
Use it when both argument computations should exist independently, or to select
already-declared callable values explicitly. Passing a callable to another
network does not defer construction of that network's argument expressions;
the lazy operand rewrite belongs to a statically resolved `if` expression.

`runtime/supported_application/` owns supported callable ports, candidate frames
and output publication. The experimental `ttms-application` extension retains
the `ttms-premise-closure` constructor and uses this same runtime.
