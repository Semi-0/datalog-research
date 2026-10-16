# Compiler 2 application runtime ownership

Status: current after the flat-GUR application refactor. See
[Flat GUR and Compiler 2 Application](flat-gur-compiler-2-application.md).

## Owned behavior

| Concern | Owner |
| --- | --- |
| Compile operator and operands in CPS order | `compiler/handlers.clj` |
| Declare callable values and retained closure source data | `compiler/declarations.clj` |
| Dispatch the whole runtime callable observation | `propagators.application-dispatch/application-effects` multimethod |
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

## Opt-in supported callable application

Flat GUR passes the whole callable observation, frame context, invocation cell
IDs, and result cell ID to `application-dispatch/application-effects`. Its
dispatch value is the callable's class. The default method retains ordinary flat
application: wait on absent or contradictory operators, reject unknown values,
and declare each bare closure frame once. Representation-specific methods return
ordinary activation effects. Register methods before evaluating a network;
registration is process-wide Clojure multimethod state.

Requiring `propagators.experimental.ttms-application` registers a `Net` method.
Loading the compiler alone does not register it. Install that namespace's
`session-extension` alongside `experimental.ttms-primitives/session-extension`
to use native supported closures:

```clojure
;; Lain source; definition-source is a cell injected with real TTMS support.
(define f (ttms-premise-closure (network (x) (+ x 10)) definition-source))
(f 5)
```

The constructor combines the procedure and definition-source through the
existing layered procedure machinery. The source must carry its actual injection
cell ID, epoch, and status. The constructor never invents an epoch or replaces a
source ID with an intermediate cell. This is separate from the existing
three-argument distributed `premise-closure` binding.

The handler owns base projection, private candidate ports, and outward support
gates. Candidate frames are keyed by call site, declaration, and captured live
environment, so withdrawal/recovery reuses topology. The original body function
retains its compiler and lexical capture. A generic
`:application/output-boundary` frame-context callback handles explicit writes
through input cells; there is no new compiler option or TTMS import in the
compiler. Echoes of the candidate's own caller publication do not re-enter its
plain local frame.

Scalar and primitive outputs carry both callable and result support. Finite
linked lists retain plain declared structure, with separate gated public member
cells, including nested list members (the runnable source is
[`native_premise_selection.lain`](../../examples/lain/amb/native_premise_selection.lain)). Returning an input alias does not attach
the callable's support to the caller's original read input. Explicit definitions
targeting an input do publish through a caller gate. The gates preserve contradiction observations with their
support. The current committed ordinary binding/observation transport can suppress
a bare contradiction before it reaches a gate; this extension does not repair
that existing transport limitation. Candidates at one call site must have matching call and
return interfaces; incompatible arity, scalar/list shape, or list length fails
with an explicit diagnostic. List structure is declaration, not an availability
signal: an empty list has no member gate to withdraw. This adapter does not
generalize arbitrary mutable compound interfaces or change compound transport.

Run the executable AMB example with:

```sh
clojure -M -m examples.lain.amb.native-premise-selection
```

For input `5`, AMB rejects `x+1` and selects `x+10`, producing `[2 15]`.
The result retains both the selected choice premise and the definition-source
premise. This is finite satisfiability against a predicate, not global ranking.
Tests cover default/registration isolation, custom compiler capture, recursion,
late members, nested closures, explicit caller writes, gate-level contradiction evidence,
retraction/recovery, stale epochs, interface rejection, and replay stability.
Arithmetic, TTMS merge, cell evaluation, and the scheduler are unchanged by this
extension.


## Performance check: 2026-10-16

Verified independently of unrelated workspace edits: export `0dc50e7`, apply
only this feature's files, then run the selected compiler, AMB, and support
regressions. The clean candidate passed 1,373 assertions. The earlier bare-body
contradiction suppression was also reproduced on untouched `0dc50e7`.

[`application_bench.clj`](../../examples/lain/amb/application_bench.clj) records
compile/setup, run-to-quiescence, replay, and topology separately. Every sample
checks its result and stable replay topology. Measurements used Java 25.0.2 on
macOS arm64, excluding JVM startup. Ordinary fresh-JVM runs used 20 warmups and
80 samples; the native search used 15 warmups and 60 samples. Full distributions
and exploratory runs are saved in
[`application_bench_results.edn`](../../examples/lain/amb/application_bench_results.edn).

Independent JVM runs initially suggested about 4% overhead on small calls.
The activation now retains its immutable context helpers once per application
rather than allocating them on every wake. A paired experiment then alternated
the exact old activation function and the new activation in one warmed JVM,
with the rest of the candidate compiler fixed. It used 15 warmup pairs and 60
sample pairs per case, alternating order. Median execution ratios and 95%
bootstrap intervals (5,000 resamples, seed 101) were:

| Case | Change in execution time | 95% interval |
| --- | ---: | ---: |
| Primitive | +0.04% | -0.63% to +0.63% |
| Closure | -0.01% | -0.72% to +1.12% |
| Captured closure | +0.55% | -0.57% to +1.35% |
| Recursive closure | -0.19% | -0.69% to +0.64% |

All intervals include zero change; no execution regression was detected in this
paired dispatch comparison. This is a local measurement of these workloads,
not a performance guarantee for every graph. Separate-JVM exploratory results
remain in the data rather than being discarded.

The native `[2 15]` AMB search measured 96.83 ms compile/setup, 146.71 ms execution,
and 3.11 ms settled-network replay (medians). Its topology stayed at 477 cells
and 433 propagators on replay. There is no working native-callable baseline to
claim a speedup against: that composition previously failed.

```sh
clojure -M -m examples.lain.amb.application-bench ordinary 20 80
clojure -M -m examples.lain.amb.application-bench registered 20 80
clojure -M -m examples.lain.amb.application-bench native 15 60
# Supply the trusted baseline flat.clj from the exported 0dc50e7 source tree.
clojure -M -m examples.lain.amb.application-bench paired 15 60 /path/to/baseline/propagators/gur/flat.clj
```
