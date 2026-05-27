# Compound Data Design Draft

This document describes the current `compound_data` design and the implemented compound-to-compound merge path for nested `cdr` behavior.

## Goal

Represent linked lists as propagator constraints where:

- collection cells hold compound network state
- `p:car` / `p:cdr` are structural writers
- `c:linked-list` dispatches updates outward
- nested `cdr` (tail is another collection cell) is reconciled via `cell-merge`

## Data model

Compound values are network extension maps (top-level `:graph`, `:env`, `:dict`) with extra slots:

- state/content: `:out-ids` (default `#{}`)
- continuation/strongest: `:updated*` (default `(atom #{})`)

```clojure
(defn state-subnet [state]
  (apply dissoc state [:out-ids :updated*]))

(defn continuation-subnet [continuation]
  (apply dissoc continuation [:out-ids :updated*]))
```

## Runtime flow

1. `p:car` / `p:cdr` emit `compound-update` (`{:head id}` / `{:tail id}`).
2. `cell-merge :compound-data` updates compound state (`merge-compound-data`).
3. `strongest-value :compound-subnet` runs internal subnet and returns continuation (`:updated*` + `:out-ids`).
4. `c:linked-list` dispatches each updated slot:
   - element target -> scalar strongest message
   - compound target -> `compound-sync` payload

## Compound-to-compound merge contract

`compound-sync` carries:

- source subnet extension map
- slot frontier (vector of outer node ids)

```clojure
(defrecord CompoundSync [subnet slots])
```

`cell-merge :compound-sync` calls `merge-compound-sync` and applies this policy:

- walk mergeable slots (sync slots, else source boundary slots)
- for each slot:
  - install missing entry (graph/env/dict) when absent in target
  - merge cell entries via `cell-merge` on cell content
  - merge graph nodes by unioning inputs/outputs
  - propagator mismatch at same slot => contradiction
- any slot contradiction => whole compound contradiction

This keeps the code concise by reusing one slot-merge path and existing merge semantics.

## Nested `cdr` behavior

Previous limitation: dispatch skipped compound targets and nested tail reconciliation was indirect.

Current behavior:

- compound targets receive `compound-sync` from `c:linked-list`
- `cell-merge` handles that sync directly
- nested tail slots are reconciled through slot-wise merge
- contradiction policy is explicit and local (fail fast on slot conflicts)

`dispatch-target?` no longer filters out compound cells; compound-safe routing happens by payload type.

## Validation notes

Coverage includes:

- compound sync installs missing slot entries
- propagator conflict in a synced slot returns contradiction
- flat and per-layer nested propagation tests pass

Known gap (tests must fail until implemented):

- nested `car`/`cdr` accessor from `coll0` alone must **not** reach deep `head2` today
- `p-cons-five-access-from-coll0-only-does-not-reach-head2` encodes this requirement

Run:

```bash
clojure -M:test propagators-linked-list-access-test propagators-compound-data-test
```
