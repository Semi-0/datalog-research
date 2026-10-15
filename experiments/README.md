# Experiments

This is the central home for experiment proposals, research reviews, benchmark
records, verification reports, and historical delivery checkpoints.

Documents preserve their original claims and dates. A historical report is not
evidence that its old implementation, command, or result is current. The latest
search review explicitly separates published findings from untested hypotheses.

Runnable examples remain under [`examples/lain/`](../examples/lain/). Runtime
and architecture contracts remain under [`propagators/doc/`](../propagators/doc/)
and [`doc/`](../doc/). Generated benchmark data and topology dumps were not moved.
Run commands from the repository root unless a document explicitly says otherwise.

## Research and coordination

- [Search topology and strategy: problem, criteria, and recent literature](research/propagator-search-topology-research.md)
- [Cells as agents](research/cells-as-agents.md)
- [Agent network composition examples](research/agent-network-examples.md)

## AMB and relational reasoning

- [Composable AMB with TTMS](amb/README.md) — [runnable example](../examples/lain/amb/)
- [Checkpoint versus withdrawal experiment](amb/CHECKPOINT_REPORT.md) — [raw results](amb/CHECKPOINT_RESULTS.edn)
- [Three network-owned solver experiments](amb/STRATEGY_REPORT.md) — [raw results](amb/STRATEGY_RESULTS.edn)
- [Concurrent immutable range workers](amb/PARALLEL_REPORT.md) — [raw results](amb/PARALLEL_RESULTS.edn)
- [All-answer enumeration: repair versus checkpoints](amb/ENUMERATION_REPORT.md) — [raw results](amb/ENUMERATION_RESULTS.edn)
- [Lain compiler versus builder checkpoints](amb/COMPILER_CHECKPOINT_REPORT.md) — [raw results](amb/COMPILER_CHECKPOINT_RESULTS.edn)
- [Swappable Lain solvers and brute force](amb/SWAPPABLE_SOLVER_REPORT.md) — [raw results](amb/SWAPPABLE_SOLVER_RESULTS.edn)
- [Concurrent checkpoints: Lain compiler versus builder](amb/CONCURRENT_COMPILER_BUILDER_REPORT.md) — [raw results](amb/CONCURRENT_COMPILER_BUILDER_RESULTS.edn)
- [Naive Datalog](naive_datalog/README.md) — [runnable examples](../examples/lain/naive_datalog/)
- [Relational Datalog](relational_datalog/README.md) — [runnable examples and data](../examples/lain/relational_datalog/)
- [Relational proof experiment](relational_datalog/PROOFS_REPORT.md)
- [Clause terms and solver strategies](relational_datalog/CLAUSE_TERMS_REPORT.md)

## Visualization and topology tracing

- [Visualization composition](visualization_combinators/README.md) — [runnable examples](../examples/lain/visualization_combinators/)
- [Composition experiment report](visualization_combinators/REPORT.md)
- [Compound levels](visualization_combinators/COMPOUND_LEVELS.md)
- [Interactive windows](visualization_combinators/INTERACTIVE_WINDOWS.md)
- [High-level tracer scenarios](visualization_combinators/topology_scenarios/README.md)
- [Compound tracer baseline](visualization_combinators/topology_scenarios/COMPOUND_REPORT.md)
- [Recursive tracer repair](visualization_combinators/topology_scenarios/TRACER_REPAIR_REPORT.md)
- [Static Clojure call graph](static_call_graph/README.md) — [runnable example](../examples/lain/static_call_graph/)
- [Vijual graph layout experiments](visualization/vijual-graph-visualization.md)

## TTMS and structural premises

- [Support collection and retraction](visualization_combinators/SUPPORT_RETRACTION_REPORT.md)
- [Structural-premise experiment](visualization_combinators/TTMS_STRUCTURAL_PREMISE_EXPERIMENT.md)
- [Slice progress](visualization_combinators/TTMS_SLICE_PROGRESS.md)
- [Premise transport plan](visualization_combinators/TTMS_PREMISE_TRANSPORT_PLAN.md)
- [Premise transport results](visualization_combinators/TTMS_PREMISE_TRANSPORT_RESULTS.md)
- [Consumer migration](visualization_combinators/TTMS_CONSUMER_MIGRATION.md)

The canonical [TTMS contract](../propagators/doc/ttms.md) remains outside the
experiment reports.

## Compiler and functional-network experiments

- [Functional-network experiment](functional-network.md)
- [Historical syntax](lain-syntax-functional-network.md)
- [Contracts and proof boundary](functional-network-contracts.md)
- [Expressiveness assessment](expressiveness.md)
- [Functional web integration trial](compiler-2/functional-web-integration-trial.md)
- [Functional-network delivery](compiler-2/functional-network-delivery.md)
- [Retained application comparison](compiler-2/compiler-2-lexical-application-comparison.md)
- [Completed availability pruning](compiler-2/completed-when-pruning.md)
- [Direct GUR application incident and recovery](compiler-2/compiler-2-direct-gur-application-incident-report.md)

## Runner and runtime experiments

- [Continuation runner port](runtime/continuation-runner-port.md)
- [Graph-declared application outputs](runtime/graph-declared-application-outputs.md)
- [Eager installation and arithmetic procedures](runtime/eager-install-and-arithmetic-procedure.md)
- [Builder policies and execution order](runtime/builder-policy-run-order-and-correctness.md)
- [Experiment commands](runtime/experiments-and-commands.md)
- [Multi-client watcher bottleneck](runtime/multi-client-runtime-bottleneck.md)
- [Runner phase profiling](runner-profiling/tracer-runner-profile.md)

## Benchmarks, synth, and other historical experiments

- [Large-EDB delta benchmark](benchmarks/bench-delta-on-large-edb.md)
- [Incremental path expansion benchmark](benchmarks/bench-incremental-path-expansion.md)
- [Compound slot benchmark](benchmarks/compound-object-bench.md)
- [Behavior/event split benchmark](benchmarks/behavior-event-split-benchmark.md)
- [Clock-driven synth](overtone_synth/README.md) — [runnable example](../examples/lain/overtone_synth/)
- [Synth report](overtone_synth/REPORT.md)
- [Historical compound-data draft](compound-data/compound-data-design-draft.md)
- [Layered dispatch publication](layered/layered-dispatch-publication.md)
- [Missing-slice recovery](history/missing-slices-recovery.md)
- [Local LLM Fibonacci results](ollama-lain-tail-fib/results.md)
- [Local LLM prompt](ollama-lain-tail-fib/prompt.txt)
