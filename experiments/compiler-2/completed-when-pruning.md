# Completed flat-GUR availability pruning experiment

`dev/propagators/compiler_2/experimental/completed_when_pruning.clj` retires
completed `when-effect` controls after their bodies have been declared. It does
not collapse cells, redirect ports, reconstruct activations, or alter kernels.
Surviving activation functions retain their exact identities.

Eligibility requires a persistent flat-GUR completion marker, the deterministic
control ID, actual constructor provenance with matching installed activation,
exact condition input, and zero outputs. Waiting controls remain installed.
Ownership through retired controls is subsumed to original surviving ancestors.
Cell evidence and the entire dictionary are preserved. Export/import validation
uses the existing complete network-facts representation.

This is a trusted-constructor experiment, not generic opaque-reference proof.
It requires persistent completion markers: clearing markers or rollback to
pre-declaration state is unsupported. TTMS retraction/premise replacement safety
is unverified. It cannot reduce compilation work already performed.

## Running the focused tests

The workspace port uses its existing namespaces and a development-only classpath:

```sh
clojure -Sdeps '{:aliases {:pruning {:extra-paths ["dev" "test"]}}}' -M:pruning dev/verify_completed_when_pruning.clj
```

Each test var must independently complete within 3000 ms. Coverage includes
completed retirement, waiting/late activation, stale/missing constructor evidence,
inert completed controls under unavailable/contradictory inputs, and idempotence.

## Actual tracing workload

The separate wired workspace experiment used `live-cell-traces.lain` with the
TTMS visualization extensions: **405 cells / 363 propagators → 405 / 350**.
Thirteen completed controls were removed in 211 ms (one sample; startup excluded).
Six evidence/reactivation comparisons took 180–193 ms each. A new waiting guard
remained installed and returned 9 after late input in 338 ms. Surviving ownership
ancestor sets matched after removing retired boundaries. Displayed tracer graphs
matched, while their evidence frames reflected the rewrite. No latency benefit
or generic TTMS optimization support is claimed.

The measurement depends on the separately recorded wired working-tree source;
it is not a clean-production-checkout benchmark. The original LAN session was
not replaced, and comparison performed no external I/O effects.

## Verification receipt

Focused checks ran with the published infrastructure dependency
`cc5c706ebea1656a39bcccfb3e2da3cdc70e0213`: **5 tests, 16 assertions, zero failures
or errors**. Each test var had a 3000 ms deadline; the slowest completed in 287 ms.
The full compiler suite was not run because this experiment is development-only
and changes no production compiler path.

## Workspace port boundary

This port comes from `lain-compiler/main` commit `921267c` and
`lain-infrastructure/main` commit `cc5c706`. Only namespace names and paths are
adapted. The required export/import codecs and their tests are included; rewrite
policy beyond completed-control pruning is not included. Production compiler,
GUR, scheduler, cells, and TTMS kernels are unchanged. The measured large fixture
is still combined-workspace evidence, not a workspace-main benchmark.

### Verified workspace checks

The isolated workspace-main port passed **12 tests / 63 assertions**, zero
failures or errors, with a 3000 ms deadline per test var. The slowest completed
in 208 ms. Codec checks cover all four Net fields, metadata and extra fields,
qualified cycles, opaque values/functions, no activation during conversion,
ordered recipes, malformed triples and adjacency. Pruning checks cover completed
controls, persistent markers, waiting/late activation, stale receipts, and
idempotence. These are focused checks; no full workspace suite was run.
Two initial launches failed in temporary test setup (classpath and namespace-list
errors); the final corrected runner and port passed. The large tracing fixture
was not rerun in this legacy workspace.
