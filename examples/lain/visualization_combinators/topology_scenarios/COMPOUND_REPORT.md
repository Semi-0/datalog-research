# Composed Lain tracer verification — October 1, 2026

Historical baseline. [The recursive tracer repair report](TRACER_REPAIR_REPORT.md)
records the subsequent fixes and current verification limits. The 28 projection
failures below no longer describe the repaired working tree.

## Program

`composed.lain` defines two functions and two compound networks:

```clojure
(def shift (:: [x] (+ x 1)))
(def square (:: [x] (* x x)))
(def-net stage [x] [out]
  (-> (square (shift x)) out))
(def-net pipeline [x] [out]
  (let-cell [middle]
    (stage x middle)
    (stage middle out)))
```

The requested outer chain is:

```text
source → shift → prepared → stage → left → pipeline → result
```

There is also an intentionally unrelated `(stage other unused)` invocation.
With `source = 2`, the results are `prepared = 3`, `left = 16`, then the
pipeline's internal stages produce `289` and `84100`. Tests seed values after
loading the Lain file; the fixture itself remains available for different inputs.

## A. High-level chain: complete, but not isolated

Both ordinary and opt-in TTMS environments compute `84100` correctly and retain
the requested chain. However, the graph also includes `other → stage → unused`:
**10 nodes / 8 edges instead of the intended 7 nodes / 6 edges**. It remains
over-broad before and after input injection.

`connected-root-node-keys` traverses operational adjacency in both directions.
The two `stage` applications share their callable-definition cell, so they belong
to one operational component even though their value flow is independent. The
test records this operational path, with the three consecutive `stage` labels
representing the first application, the definition, and the sibling application:

```text
source → shift → expression-result → forwarding → prepared
       → stage application → stage definition → sibling stage application → other
```

This is consistent with the raw selector's operational connected-component
contract. It is insufficient for interpreting the result as only the selected
semantic dataflow chain: the subsequent application projection removes callable
edges but does not reselect the seed-connected semantic component.

## B. Compound sub-network: correct core, incomplete abstraction

The test composes existing public operations:

```clojure
(dataflow/dataflow-graph
 network
 (observer/snapshot
  network
  (disj ((observer/child-node-keys parent-key) network) parent-key)))
```

Removing `parent-key` is explicit: `child-node-keys` returns parent plus immediate
children. Keeping it projects the outer application alongside its interior, not
a replacement/expansion of that outer application.

The tests independently identify expected child calls from declared arguments
and local ports in each application's lexical frame. They do not construct an
oracle by reading projected edges. Assertions prove:

- Every intended inner connection exists.
- Exactly the intended child applications are visible, without sibling or parent
  applications.
- The selected outer `stage` produces `16`.
- The selected `pipeline` produces `84100`.
- Its two separately expanded `stage` instances produce `289` and `84100`.
- Those repeated instances have distinct local input IDs and disjoint child
  application identities.

The strict complete edge/node-set assertions nevertheless fail:

| Selected scope | Expected semantic edges | Actual projected edges |
|---|---:|---:|
| Outer `stage` | 5 | 6 |
| `pipeline` | 4 | 6 |
| First nested `stage` | 5 | 6 |
| Second nested `stage` | 5 | 6 |

For `stage`, the intended path is `x → shift → intermediate → square → result → out`.
The compiler additionally routes the explicit forwarding expression's return
cell to `out`. `forward-edges` renders argument-to-argument forwarding, not the
forwarding expression's return-cell relation. Consequently that extra return
cell appears to feed `out` without its producer being shown.

For `pipeline`, the second stage's explicit output and implicit return route both
appear. The core chain is connected, but administrative return wiring has not been
abstracted away. This is not missing child ancestry or incorrect arithmetic.

Local port names are also lost in the rendered projection: ordinary execution
labels them with numeric values, and TTMS execution labels them `cell`. The graph
still samples their correct values. This is observed diagnostic evidence, not a
passing assertion of readable labeling.

## Verification and boundaries

```sh
clojure -M:test propagators.experimental.compound-tracer-test propagators.experimental.tracer-topology-test
```

- Complex suite: **2 tests, 92 assertions; 64 pass, 28 fail, 0 errors**.
- Prior scenario suite: **3 tests, 226 assertions; all pass**.
- Combined: **290 pass, 28 fail, 0 errors**.

Of the 28 failures, 12 assert exact outer scope (nodes, edges, application count,
before/after input injection in both environments); 16 assert exact inner node
and edge sets (four scopes in both environments). These are intentionally retained
acceptance tests for the requested clean semantic views, not exceptions or runtime
execution failures. They are registered in the test runner and will remain red
until the projection gap is resolved. The earlier two legacy error-reporting
failures are separate and were not part of this batch.

Only fixture/test/report files and test registration changed. No production
tracer, runner, compiler, compound synchronization, TTMS, or renderer was patched.
No commit, push, or KIROSHI mutation was performed in this follow-up.

Grounding at revision 480 retained explicit projections (approved September 9)
and persistent bidirectional compound relations (approved September 24). The next
design decision belongs to projection/selection: define semantic-component
selection separately from operational selection, specify how child scope replaces
the parent, and normalize compiler return/boundary wiring using declared roles.
Do not change propagation or ancestry to hide visualization artifacts.

This is headless evidence. It does not claim UI acceptance, arbitrary recursive
GUR expansion, cross-owner nested Net paths, or live retraction coverage.
