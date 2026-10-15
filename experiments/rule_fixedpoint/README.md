# Bounded DB–rule fixed-point experiment

This is an additive experiment;
no production runner, compiler, layered dispatcher or TTMS policy is changed.

The DB, rules and proof publication are fixed. Six interchangeable binding
matchers share declaration semantics: AMB repair, checkpoint, concurrent
checkpoint, concurrent learning, indexed full join, and indexed occurrence delta.
Indexed rules with an omitted guard bypass redundant compiled validation;
genuine guards remain compiled Lain networks and use batched candidate inputs.
An independent arithmetic contractor demonstrates genuine backward refinement;
Horn implication is not interpreted as reversible fact generation.

Source: `examples/lain/rule_fixedpoint/`.
Tests: `propagators.experimental.rule-fixedpoint-test` and
`propagators.experimental.rule-fixedpoint-validation-test` and
`propagators.experimental.rule-fixedpoint-catalog-test`.

```sh
clojure -M:test rule-fixedpoint
```

Run the Lain-declared diamond, withdraw one grounding edge, then restore it:

```sh
clojure -M -m examples.lain.rule-fixedpoint.demo indexed-delta
```

The same command accepts `repair`, `checkpoint`, `concurrent`,
`concurrent-learning`, or `indexed-full`; the Lain declaration stays unchanged.

Run the actual dataflow fixture with reachability and taint:

```sh
clojure -M -m examples.lain.rule-fixedpoint.demo indexed-delta dataflow
```

The complete `dataflow.lain` declaration is:

```clojure
(rule-set
  (rule :flow-to-reach
    (relation :reach :x :y)
    (relation :flow :x :y))
  (rule :reach-via-reach-flow
    (relation :reach :x :z)
    (relation :reach :x :y)
    (relation :flow :y :z))
  (rule :taint-via-taint-flow
    (relation :taint :x :z)
    (relation :taint :x :y)
    (relation :flow :y :z)))
```

The standard session-extension API installs the generic `relation`, `rule` and
`rule-set` operators only in this opt-in session. The Clojure driver supplies
facts and solver selection, not a second hard-coded rule set. See
[LAIN_DECLARATIONS.md](LAIN_DECLARATIONS.md) for ownership and isolation tests.

Convenience constructors also remain for unit tests and genuine guard controls:

```clojure
(step-rule :reach :reach :edge)                        ;; unconditional
(step-rule :answer :left :right
  (network (x y z) (= (+ x y) z)))                    ;; genuine guard
```

An explicit constant-true network is still a genuine guard, not inferred to be
unconditional. No guard is bypassed based on its implementation. Unconditional
AMB questions no longer invent a constant-true guard internally either.

Programmatic composition:

```clojure
(require '[examples.lain.rule-fixedpoint.catalog :as catalog]
         '[examples.lain.rule-fixedpoint.solvers :as solvers]
         '[examples.lain.rule-fixedpoint.engine :as engine])

(let [declared (catalog/compile-source (slurp "examples/lain/rule_fixedpoint/reach.lain"))
      options (solvers/attach (:rules declared)
                              {:strategy :indexed-delta :domain [0 1 2]} declared)]
  (-> (engine/prepare (:rules declared) options
                      {:ab {:predicate :edge :tuple [0 1]}
                       :bc {:predicate :edge :tuple [1 2]}})
      engine/seed engine/run))
```

All guarded rules require `solvers/attach`; the low-level guard-free engine
rejects a guard without this shared compiled-query adapter. Concurrent strategies
also require a runtime-owned executor bound to `solver-strategy/*executor*`;
the demo and benchmark manage that executor and close it in `finally`.

Updates use `engine/inject`, explicit timestamps/statuses, and then `engine/run`.
`engine/rule-state` updates authority; `engine/restrict` changes one rule's
head-demand control. `engine/select-strategy` switches prepared strategies in the
**same evaluator Net** without recompiling the catalog or reinstalling topology.
A demand does not invent missing body facts or retract
previously derived valid facts. For new rules/domain, rebuild the environment.

Benchmark command (bounded at 30 seconds / one million activations per sample):

```sh
clojure -J-Xmx2g -M -m examples.lain.rule-fixedpoint.benchmark experiments/rule_fixedpoint/RESULTS_RECHECK.edn 5
```

The validation-only follow-up compares the original validator, unconditional
fast path and batched genuine guards:

```sh
clojure -J-Xmx2g -M -m examples.lain.rule-fixedpoint.validation-benchmark experiments/rule_fixedpoint/VALIDATION_RECHECK.edn guards 5
```

See [VALIDATION_OPTIMIZATION.md](VALIDATION_OPTIMIZATION.md) for its mechanism,
parity checks and measured results. Use a new output filename to preserve the
recorded historical matrices.

See [PLAN.md](PLAN.md) for the actual protocols and invariants,
[REPORT.md](REPORT.md) for measured results and limitations, and
[VERIFICATION.md](VERIFICATION.md) for tests and baseline failures.
An incomplete bounded run is not an empty answer set or a speed win.
The original six-mode harness saves completed rows to `<output>.partial.edn`
while running. The focused validation harness writes its final matrix on completion.
`RESULTS.edn` is the original pre-optimization matrix, not a measurement of the
current optimized implementation.
