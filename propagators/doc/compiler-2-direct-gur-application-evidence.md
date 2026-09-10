# Compiler 2 Direct GUR Application Evidence

This note prepares evidence for the four Kiroshi constraints that were marked
`needs-verification`. It does not modify or approve the Kiroshi database.
The read-only database revision used for the constraint ledger was
`1647dd29b78613d1bce4e4ff2951bda159ea7f1d`.

## Ordinary and lazy GUR application

Constraint:
`:constraint/compiler-application-supports-ordinary-and-lazy-gur`.

Compiler 2 retains application IR in
`compiler_2/model/application_value.clj`, then
`compiler_2/compiler/declarations.clj` installs
`gur/p:apply-closure` directly from the operator cell and argument cells to
the result cell. The operator cell contains a canonical accumulating-GUR
closure. Closure topology is declared only after the operator is usable; a
missing operator, late arguments, and recursive tail declarations remain
ordinary propagation readiness.

Evidence:

- `compile-2-application-installs-application-propagator`
- `compile-2-application-before-closure-waits-for-later-input-fire`
- `compile-2-upstream-application-produces-downstream-operator`
- `accumulating-gur-when-topology-is-lazy-and-idempotent`
- `accumulating-gur-computes-list-map-reduce-filter`

## Stable identities for redeclaration

Constraint:
`:constraint/stable-identities-for-redeclaration`.

Application, retained closure declaration, frame, placeholder, and recursive
task IDs derive from semantic inputs through stable node-ID constructors.
Accumulated application requests and topology declarations use those IDs as
idempotence keys. Equivalent activation reuses the declaration and does not
increase cell or propagator counts.

Evidence:

- `compile-2-equivalent-application-redeclaration-is-deterministic`
- `accumulating-gur-application-request-is-idempotent`
- `accumulating-gur-task-facts-are-idempotent-and-indexed`
- `accumulating-gur-uses-one-owner-and-is-idempotent`
- versioned signature-repair tests for stable placeholder IDs

## No materialization or unwrapping

Constraint:
`:constraint/compiler-avoids-materialize-and-unwrap`.

Compiler 2 application does not read the strongest operator value, classify an
operator kind, unwrap a scoped value, build a host closure frame, or register a
pending base reader. It installs canonical GUR application immediately.
Operator readiness and contradiction behavior belong to concrete propagators
and the accumulating-GUR runner.

The obsolete application-layer, lexical-application, closure-frame, and
retained-application modules are deleted after dependency searches found no
executable callers.

Evidence:

- `compile-2-application-output-adapter-is-not-materializing`
- `compile-2-contradictory-operator-waits-without-application-policy`
- source audit for `application-scope`, `unwrap-operator`,
  `pending-base-readers`, strongest-value dispatch, and old apply operators

## Pull-only compound materialization

Constraint:
`:constraint/compound-materialization-is-pull-only`.

Application and lexical environments remain cell/accessor-backed. Captured
cell IDs, including the lexical environment ID, are imported through declared
GUR boundaries. Output projection follows accessor relations only at the
external observation boundary. Cyclic accessors are detected by identity and
remain accessor values instead of being recursively rebuilt.

Session diagnostics and retained versioned-call metadata are inspected by
walking accumulated network values on demand; they are not copied into the
outer kernel dictionary.

Evidence:

- `compile-2-lexical-compound-uses-env-slot-not-hidden-captures`
- `compile-2-escaped-closure-preserves-lexical-env-through-output`
- `accumulating-gur-hop-output-stores-scoped-slot-participants`
- `accumulating-gur-hop-output-cdr-update-is-bidirectional`
- `lain-loader-preserves-block-by-block-def-net-application`

## Verification ledger

Focused results from this worktree:

- direct Compiler 2 application selector: 9 tests, 20 assertions
- application runtime: 2 tests, 16 assertions
- composition: 7 tests, 37 assertions
- call graph: 6 tests, 19 assertions
- lexical and nested application selector: 8 tests, 16 assertions
- Compiler 2 TMS selector: 4 tests, 25 assertions
- core TMS: 13 tests, 46 assertions
- accumulating GUR: 22 tests, 85 assertions
- Compiler 2 GUR linked list: 1 test, 4 assertions
- versioned TUI commits: 14 tests, 73 assertions
- environment IO: 8 tests, 24 assertions
- session replay: 5 tests, 35 assertions
- runtime boundary: 2 tests, 9 assertions
- retraction inspection: 2 tests, 31 assertions
- loaded block-by-block application: 1 test, 1 assertion

The whole `compile-2-test` and file-loader namespace runners did not complete
within bounded two-minute and ninety-second runs. Their application-relevant
test vars were run directly as listed above.
