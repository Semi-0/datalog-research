# Layer-dispatch publication check — 2026-10-09

The refactor and portable constraint proposal are committed in `8779809`.
The local KIROSHI constraint `:constraint/layered-dispatch-is-layer-blind` is a
persisted candidate, not human-approved. No existing constraint had the same
layer-dispatch scope; no facts were superseded.

Before publication, fetching revealed nine remote commits beyond the original
`1d9a319` base. Remote main was `2b3efe2`; those commits include the maintained
Compiler 2 migration and XR changes. They overlap unrelated dirty files in the
original checkout, so no pull, stash, rebase, or merge was performed there.

A managed integration checkout merged `8779809` and `origin/main` without
conflicts. A separate managed baseline checkout used clean `origin/main`.
Neither checkout includes the original working tree's unrelated uncommitted
readiness, TTMS, or XR changes.

## Verification

The original working checkout passed the final 178 dispatcher/arity/plain-layer
assertions before commit. The earlier 1,301-assertion focused result and benchmark
report describe that working-tree context, not a clean latest-main checkout.

On the clean merged checkout, dispatcher, arity, plain layered procedure, plain
layered support, support collection, and glitch namespaces passed. The complete
ten-namespace focused run reported **1,159 pass, 100 fail, 4 error**. Failures were
in compiled premise transport, TTMS branching, TTMS primitive applications, and
visualization higher-order callbacks.

The same four namespaces were compared independently:

| Checkout | Pass | Fail | Error |
|---|---:|---:|---:|
| Clean origin/main | 105 | 8 | 10 |
| Merged refactor | 122 | 100 | 4 |

The failing test cases are present on both baselines. Original-runtime exceptions
abort assertions that the revised runtime reaches; the counts are therefore not
a measure of new regressions or repairs. The comparison does not establish
compatibility of these experimental consumers with the newer compiler.

Both runs used:

```sh
clojure -M:test propagators.premise-transport-test propagators.ttms-branching-test propagators.ttms-primitives-test propagators.experimental.visualization-composition-test
```

Logs remain at `/private/tmp/layered-publish-origin-tests.log` and
`/private/tmp/layered-publish-merged-tests.log`. Integration and baseline managed
checkouts are retained for follow-up.

## Publication boundary

Push is held: do not force-push, incorporate unrelated dirty fixes, weaken test
assertions, or patch the protected production compiler to publish this refactor.
Obtain direction on migrating the experimental TTMS/view consumers and fixtures
to the newer compiler contract, or explicitly publishing with these known
integration limitations. The original checkout and unrelated edits remain intact.
