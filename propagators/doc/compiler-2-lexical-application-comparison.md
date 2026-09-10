# Compiler 2 direct GUR application

This document replaces the historical retained-frame comparison. Compiler 2 no
longer selects between compatibility, lexical, layered, or retained application
installers.

## Declaration flow

For every ordinary call, Compiler 2:

1. compiles the operator and operands into cells;
2. records an application IR value with operator AST, operator cell, argument
   cells, context, and output relations;
3. installs canonical `gur/p:apply-closure` immediately;
4. leaves readiness and evidence merging to the propagator network.

Application IR remains inspectable before propagation. It does not contain a
lowering tag and is not read to decide execution.

Closure declarations retain their AST, parameters, output declaration, lexical
environment ID, and declaration cell. The callable value is an accumulating-GUR
closure referencing that declaration and its captured cell IDs. Primitive
callables use the same value protocol and declare their concrete propagators
inside a GUR frame.

## Topology growth

An application request is keyed by operator, arguments, and output. The runner
expands a usable canonical closure into deterministic topology once. Recursive
and higher-order calls emit more application requests into the same accumulated
network. Equivalent reactivation reuses frame facts and stable IDs; lazy list
tails add only the missing suffix.

This supports recursive construction of a propagator network. It does not claim
tail-call optimization.

## Lexical scope

Captured scope is an environment cell. A closure frame declares its relationship
to that cell through `scope-frame`, then uses local selection and binding-value
propagators. Captured cells cross the accumulated boundary by ID and retain their
cell contents, claims, conflicts, and provenance. No application-specific frame
materialization or scoped-value unwrapping is involved.

## Runtime behavior

Unavailable or contradictory operator information does not trigger a special
application branch; the concrete propagator has insufficient usable information
and waits. Arguments and output information may wake the same network in either
direction. An upstream call that produces a closure can therefore wake an
already-installed downstream call.

Effects remain descriptions inside the network. Only the effect runtime performs
external actions.

## Verification

The verification ledger is maintained in
`compiler-2-application-runtime-ownership.md`. Benchmark output reports the
single `:direct-gur` strategy, retained application declarations, accumulated
frame counts, topology size, result, and timing. Historical compatibility versus
retained-frame numbers no longer describe the implementation.
