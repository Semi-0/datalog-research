# Compiler 2 application runtime ownership

Status: current after the flat-GUR application refactor. See
[Flat GUR and Compiler 2 Application](flat-gur-compiler-2-application.md).

## Owned behavior

| Concern | Owner |
| --- | --- |
| Compile operator and operands in CPS order | `compiler/handlers.clj` |
| Declare callable values and retained closure source data | `compiler/declarations.clj` |
| Dispatch application topology by protocol | `runtime/application.clj` |
| Declare same-network recursive application | `propagators.gur` flat facade |
| Declare and traverse live lexical frames | `model/env.clj` |
| Project primitive and closure inputs and outputs | named concrete boundaries in `runtime/application.clj` |
| Inspect realized calls | traversal of flat GUR name bindings |
| Merge values, evidence, and contradictions | cell protocols and TMS, unchanged |
| Schedule runnable propagators | generic scheduler, unchanged |

The runtime application boundary owns `ApplicationTopology`,
`PrimitiveApplication`, `ClosureApplication`, and `ConstraintApplication`.
Primitive installers and compiled closure bodies extend the active `Net` through
flat effects. They do not create applied child networks.

## Removed paths

Production caller searches reached zero before these modules were deleted:

```clojure
'#{runtime.retained-application
   runtime.closure-frame
   runtime.lexical-application
   runtime.application-layers}
```

The application runtime no longer owns operator unwrapping, application scopes,
pending base readers, strongest-value operator classification, result
rescoping, or copied lexical frames.

`runtime/application-output.clj` remains for externalizing values across the
sub-environment/runtime boundary. It is not part of ordinary application
selection.

## Observable topology

```text
operator -> flat apply -> inbound args -> frame -> body -> outbound -> result
                         captured env -> scope-frame
```

`application-topologies`, `application-topology`, and
`application-topology-for-result` expose these relations to call-graph, session,
retraction, and TUI consumers. Execution and inspection therefore share one
representation.
