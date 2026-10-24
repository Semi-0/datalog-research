# Desktop 4: top-down view development

The canonical incremental-development experiment, raw run records, and current
limitations are in [`experiments/top_down_constraints`](../../../experiments/top_down_constraints/README.md).

## Stack design: domain files composed in Lain

Load `examples/lain/top_down/stack-design.lain` as the review entry point.
Load `examples/lain/top_down/stack-live-trace.lain` for the interactive,
real-child editor walkthrough described in `stack/README.md`.
It defines `(define import load-lain)` and uses ordinary `(import "...")`
expressions, with project-root-relative paths, to load these domains in order:

- `stack/structure.lain`: named-field stack, anchored expansion, and reference constructors/accessors;
  `struct` remains an explicit live-field implementation obligation.
- `stack/constraints.lain`: constraint composition and unresolved check contracts.
- `stack/traversal-state.lain`: explicit pending/discovery/connection contracts.
- `stack/hierarchy.lain`: frozen reader contracts and tail-recursive
  compound-node-to-stack transformation.
- `stack/fixtures.lain`: pinned expected outcomes and edit sequence.
- `stack/example.lain`: a concrete nested network whose result is `15`.

The entry point orchestrates capture, root selection, transformation, checking
and display. Imports use the existing loader, not new syntax: they share the
current program environment, have explicit ordering, and do not introduce
namespaces or an idempotent module cache. Load the entry once in a fresh block;
do not independently reload its dependencies into the same session.

This is an executable, versioned specification. Pending implementation blocks
remain honest unresolved definitions until replaced; the concrete bindings can
resume the retained traversal when frozen child information arrives. The stack
scene is rendered by the editor, with browser acceptance still tracked in the
experiment report. Review the data contracts before extending the host. Verify with:

```sh
clojure -M:test graph.lain-editor.stack.stack-design-test
```

## Earlier checkpoint experiment

This follows [the top-down discussion](../../../doc/lain-top-down-agent-orchestration.md):
execute the known portions, retain explicit holes, and inspect a checkpoint.
No missing combinator is implemented or replaced with a dummy value here.

- `desktop4.lain` preserves the desktop 4 draft observed on 2026-10-19, including
  its AMB/replay setup and its original `juxapose`, `'amv` and quoted-name forms.
- `domain-extension.lain` preserves the observed `(3 4 5)` domain extension.
- `view.lain` isolates the view composition for a neighboring editor block.
  Its `traced-network` is an explicitly open input. Blocks have independent
  sessions: moving source beside another block does not import its bindings.
- `scan-extension.lain` adds the checkpoint inspection and displays its report.
  It uses the existing frozen `observe-network` mechanism and a small opt-in
  primitive adapter in `scanner.clj`, without shared evaluator changes.

In an independent block, load/run `view.lain`, then apply this extension:

```clojure
(load-lain "examples/lain/top_down/scan-extension.lain")
```

The Lain extension declares which cells are implementation obligations, derived
values or external inputs. Its callback receives a frozen snapshot and a cell
identity, so it can observe the cell even when an ordinary value-dependent
calculation would wait for that cell's value. Labels in the inspection extension
are strings and do not depend on the proposed name-predicate API.

The standalone composition currently reports these declared observations:

| Declared role | Cells | Observed status |
| --- | --- | --- |
| Implementation | `pred-or`, `cell-name-eq?`, `pred-not`, `layers-view`, `direct-neighbor-of?`, `any?`, `juxapose` | `unresolved-implementation` |
| Derived value | `target?`, `rest-nodes?`, `layered-view`, `view` | `waiting` |
| External input | `traced-network` | `awaiting-input` |

These are current observations of explicitly selected cells, not an enumeration
of all future topology or a completion percentage. An `available` value does not
prove that an implementation satisfies its contract. Contradictions and
unavailable inspection are distinct from implementation holes. Earlier frozen
snapshots remain unchanged when later refinements supply information.

The source spells `juxapose`; the intended spelling may be `juxtapose`. Likewise,
`'amv` may be intended as `'amb`. Neither is silently renamed: these are naming
decisions for the accepted composition. `rest-nodes?` is currently declared but
not passed into `layers-view`; its eventual role is another explicit decision.

Verification:

```sh
clojure -M:test graph.lain-editor.stack.top-down-scanner-test
```

The focused suite passes two tests / 14 assertions. It checks checkpoint
classification, immutable earlier observations, unavailable-inspection errors,
and an ordinary Lain extension that supplies a missing callable: its report
changes from `unresolved-implementation` to `available` and the waiting call
produces `3`. The standalone `view.lain` plus inspection extension was also run
and produced the twelve observations listed above. Lint and whitespace checks
passed; no full repository suite or complete view implementation is claimed.

The intended next refinement is to define one combinator's contract and supply
it through a Lain extension, then observe which waiting results advance. The
inspection extension observes only; it does not dispatch agents or certify
completion from a reduction in empty cells.
