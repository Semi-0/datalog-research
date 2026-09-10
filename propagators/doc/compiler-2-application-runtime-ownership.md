# Compiler 2 direct GUR application ownership

Compiler 2 treats every ordinary application as a declaration plus propagator
composition. Compilation creates an inspectable application value and installs
`gur.accumulating/p:apply-closure` from the operator and argument cells to the
result cell. The declaration is not an evaluator input.

## Boundaries

| Responsibility | Owner |
| --- | --- |
| Application and closure IR | `compiler_2/model/application_value.clj`, `compiler_2/model/closure_value.clj` |
| Application and closure declaration | `compiler_2/compiler/declarations.clj` |
| Closure call planning and body topology declaration | `compiler_2/runtime/application.clj` |
| Lexical frame and local selection | `compiler_2/model/env.clj` |
| Application requests and stable frame facts | `gur/accumulating/facts.clj` |
| Accumulated topology execution and boundary projection | `gur/accumulating/runner.clj` |
| External effects | the effect runtime |

Ordinary symbol compilation uses `p:lexical-access-local-first` followed by
`p:binding-value`. Captured scope stays in an environment cell. Closure frames
declare `scope-frame` and canonical local relations; application does not copy
lexical values into a host frame or unwrap scoped candidates.

Canonical GUR closures carry a stable retained declaration cell and captured
cell IDs, including the lexical environment ID. The runner projects those cells
through the accumulated frame boundary. Frame IDs and body node IDs derive from
the application key, so equivalent declarations with the same semantic seed are
stable and repeated activation does not duplicate topology.

## Readiness

The application propagator is installed before operator information is
available. `nothing`, contradiction, late arguments, and late outputs use the
same cell readiness protocol. Once a canonical GUR closure and sufficient
boundary information are available, the application emits an application
request. The runner expands each request once and publishes output changes as
messages.

Primitive operators are canonical GUR closures whose bodies install their
existing concrete propagators. Higher-order results therefore flow directly
into downstream operator cells without conversion or application-kind dispatch.

## Removed runtime paths

Static caller searches proved that application layers, lexical application,
retained application, closure-frame application, pending base readers, scoped
operator unwrapping, result rescoping, and the synchronous application evaluator
had no remaining production callers. Their modules and adapter-only tests were
removed.

`runtime/application_output.clj` remains for explicit external observation of a
finite sub-environment result. It is outside ordinary Compiler 2 application.

## Verification contract

Focused tests cover retained IR before evaluation, late operators and arguments,
contradictory operators, output-first propagation, higher-order composition,
captured bindings and shadowing, returned closures, recursive mapping/filtering,
invalid arity, deterministic redeclaration, idempotent topology, TMS evidence,
session inspection, call-graph publication, effects, and late linked-list tails.

The canonical test runner may combine namespaces. Verification reports list each
requested suite separately and do not describe focused coverage as all tests.
