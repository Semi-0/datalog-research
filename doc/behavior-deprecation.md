# Temporal behavior deprecation

Temporal behavior is deprecated in favor of the project's TTMS direction.
Active runtime roots use `main/tms-bindings`: ordinary primitive bindings plus
the existing TMS premise operators. They no longer install `behavior`,
`behavior-cell`, behavior history/construction operators, or `be:` arithmetic.
This change does not implement a TTMS adapter or change TTMS, TMS, cell merge,
GUR, compound-object, or scheduler semantics.

Use `main/compile-source-with-tms` or `main/compile-expr-with-tms` for the current
TMS-enabled compiler. The old `*-with-behavior-tms` entry points and the temporal
behavior compiler/operator namespaces carry `:deprecated` metadata. Their
explicit historical opt-in remains for archived callers and regression tests;
it is not a supported path for canonical network closures. Physical removal is
separate from this deprecation.

The demo's line join now projects `car` and `cdr`, concatenates their cells, and
routes the result directly. It retains the same `print-lines` call shape and
external block effect boundary, without constructing a behavior reducer subnet.

Old behavior construction calls are no longer runtime declaration heads.
Unbound symbols retain normal waiting-cell semantics; no special rejection or
translation of deprecated syntax is installed.

Focused verification passed 3 bootstrap tests / 19 assertions and 2 demo/slider
integration tests / 8 assertions. Every individual test finished under one
second. The full compiler suite passed 147 tests / 464 assertions; the shared
runtime suite passed 158 tests / 572 assertions, with every Var below three
seconds. The workspace's mirrored bootstrap and demo checks also pass.

The separate workspace assembly mapping was subsequently corrected: the existing
wired assembly has its own UI-owned workspace namespace, and the deprecated graph
facade delegates session creation to it. Mirrored slider, server, and file-watch
checks now pass. Terminal TUI repair remains excluded.
