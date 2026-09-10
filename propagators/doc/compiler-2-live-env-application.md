# Compiler-2 Live Env And Application Boundary

## Current Status

Compiler 2 uses accumulating GUR for primitive and user-authored callable
application. The same substrate also supports lazy topology, HOP chains,
cdr-gated list traversal, map/filter construction, and `when` as
presence-gated topology.

## Direct GUR Application

Application has this declared shape:

```text
compile operator and argument forms to cell ids
retain inspectable application IR
install gur/p:apply-closure immediately
wait through ordinary propagator readiness
declare deterministic body topology in the accumulated subnet
project declared outputs through explicit boundary relations
```

The application propagator does not classify the operator, unwrap scoped
values, materialize arguments, or keep pending-reader state. `nothing` waits,
contradiction propagates as evidence, and late usable information wakes the
already-installed network.

Closure IR retains the source AST, parameters, output declaration, and lexical
environment cell id. A first-class callable cell contains a canonical
accumulating-GUR closure referencing that declaration and its captured cells.

## Live Lexical Scope

Closure invocation imports captured cells through declared GUR boundaries. It
then composes:

```clojure
(p:scope-frame parent-env-id scope-id local-symbols)
(p:declare-canonical-local symbol scope-id binding-id)
(p:binding-value binding? binding-id value-id)
```

Lexical lookup is structural and local-first. If a scope declares `x`, lookup
waits for that local binding instead of falling through to a parent `x`.
Neither the environment nor its arguments are copied into an
application-specific host object.

## Persistent Applied Topology

Closure body topology remains in the accumulated network:

```text
application = retained declaration plus canonical apply propagator
env/args/outputs = boundary cells
updates = wake existing topology
```

Stable semantic ids make equivalent activation idempotent. Recursive closure
application composes the same application propagator, so linked tails add only
missing topology. Tail-call optimization is outside this design.

## Effects

Closure metadata explicitly declares boundary input cells, boundary output
cells, and the narrow `Net` dictionary keys needed by its body. Effectful
operators emit effect descriptions to an outbox boundary cell. Only the effect
runtime drains those descriptions and performs external actions.

## Multi-Client Proof Slice

The web-client proof slice remains pure runtime/coordinator code rather than a
complete browser server. It demonstrates independent Compiler 2 sessions, a
declarative `.lain` routing model, late client growth, and targeted message
updates through the same retained GUR topology.

## Remaining Scope

This change does not replace the independent layered and generic procedure
application systems. Compound values remain accessor-backed until an explicit
observer requests a finite value.
