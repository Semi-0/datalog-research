# Compiler 2 application runtime ownership

Ordinary symbol compilation uses `p:lexical-access-local-first` followed by
`p:binding-value`. Its result is the binding's value, not a scope-source lookup
candidate. Explicit `p:lexical-access` and `p:access-binding` remain separate
provenance APIs. Their tests are active contracts, not obsolete compiler tests.

## Reachability of the former application scope-source uses

| Behavior | Reachable caller | Owner after cleanup |
| --- | --- | --- |
| Preserve a scope wrapper while externalizing its base | Transient closure output export | `runtime/application_output.clj` |
| Remap closure environment addresses and materialize accessor slots | Transient closure output export | `runtime/application_output.clj` |
| Unwrap explicit scoped operators and closures | Direct application and closure activation APIs | `runtime/application_layers.clj` |
| Select a compatible scope and combine dependencies | `runtime/lexical_application.clj` and direct application activation | `runtime/application_layers.clj` |
| Refine dependencies on an already-scoped result | Direct application activation; ordinary results remain ordinary | `runtime/application_layers.clj` |
| Declare base readers and select evaluation IDs | `p:apply-application-with`, including explicit layered inputs | `runtime/application_layers.clj` |
| Resolve a result's binding address, base-layer parent, or original cell | Injected compiler boundary in `execute-sub-env-messages-with` | `runtime/application_layers.clj`, called by `runtime/sub_environment.clj` |

`runtime/application.clj` owns closure call planning and application orchestration.
`runtime/sub_environment.clj` owns child-frame declaration, compilation, topology
publication, and result publication. Both use `runtime/activation.clj` to run
declared propagators and boundary-input readers. Existing public application
entry points remain available.

No scope-source behavior in this map was proven unreachable across these public
APIs. Removing the injected-compiler result fallback would narrow that contract,
even though current CPS symbol lookup does not need it. The cleanup therefore
isolates it instead of silently retiring it. Unused closure-output and provenance
candidate bindings, the forward declaration, and dynamic topology resolution
were removed; topology effects are now a direct dependency of the layer adapter.

The deprecated `closure-body-env` still has active closure-frame test callers.
Those tests and the explicit provenance tests are retained. Reader-discarded
behavior tests (`#_`) are not counted as coverage and were not revived or deleted
by this change.

## Verification

`compiler-2-application-runtime-test` checks raw child-frame results (including
`false`), provenance compatibility and message refinement, result-address
selection, base-reader routing, and scalar boundary export. Existing Compiler 2
and layered-procedure suites cover closure frames, compiler composition,
accessor results, and explicit provenance integration.

Verified on this cleanup: 172 tests, 763 assertions, no failures or errors.
These are focused suites, not the full repository test set.

| Namespace under `propagators` | Tests | Assertions |
| --- | ---: | ---: |
| `compiler-2-application-runtime-test` | 6 | 48 |
| `compiler-2-cps-test` | 7 | 37 |
| `compiler-2-composition-test` | 8 | 41 |
| `compiler-2-closure-frame-test` | 9 | 47 |
| `compile-2-test` | 104 | 416 |
| `compiler-2-call-graph-test` | 6 | 20 |
| `compiler-2-block-premise-test` | 3 | 11 |
| `compiler-2-organization-test` | 3 | 28 |
| `compiler-2-gur-linked-list-test` | 1 | 4 |
| `layered-procedure-test` | 16 | 45 |
| `cell-protocol-test` | 9 | 66 |

The broader run emitted a Meander dependency reflection warning at
`meander/util/epsilon.cljc:758:24`.

The historical distinction is visible in `cf0959a` (initial CPS application
runtime) and `f7020b6` (canonical closure locals, with the older scope adapters
otherwise retained). The cleanup preserves their surviving contracts while
making their ownership explicit.
