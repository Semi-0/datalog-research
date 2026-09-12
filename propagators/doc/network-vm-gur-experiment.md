# Monotone Network VM GUR Experiment

Status: the flat effect model is now the canonical public GUR path. The older
experimental measurements in this document remain historical context.

The public `propagators.gur` namespace delegates to flat GUR. Recursive
declarations emit bounded monotone effects into the active `Net`.
Accumulating GUR remains available through the explicit
`propagators.gur.accumulating` namespace.

## Instruction Vocabulary

The v1 VM instructions are intentionally small:

| Instruction | Meaning |
| --- | --- |
| `declare-cell id` | Ensure a cell exists in the network value. |
| `declare-prop id inputs outputs activate` | Ensure a propagator with stable id and graph edges exists. |
| `bind-name scope name id` | Record a scoped name binding as network declaration data. |
| `tell cell-id partial-info` | Queue a cell message; cell merge owns refinement. |
| `schedule cause prop-ids index` | Record runnable propagators under a monotone cause/index. |
| `advance` | Execute one pending message or scheduled task. |
| `temperature` | Count pending messages plus unconsumed scheduled task indexes. |

There is no `assign`: cell updates are monotone `tell` messages. There is no VM
`push` in v1: recursive frames are compiled into declarations rather than a
stack.

## GUR Mapping

The tiny `propagators.network-vm.gur` layer treats GUR concepts as macros over
VM instructions:

| Accumulating GUR concept | Network VM experiment shape |
| --- | --- |
| `application-request-fragment` | `tell` a named-network request fragment into a request cell. |
| `record-frame-fragment` | Emit `declare-cell`, `bind-name`, `declare-prop`, and `schedule`. |
| Task facts | `schedule cause prop-ids index`. |
| Mailbox settlement | Pending declaration/message temperature. |
| Runner cursor | Executor-local consumed task cursor. |

Scalar `factorial` and `fib` tests pass by expanding requests into stable frame
declarations. `when` is lazy: if the condition cell is `nothing`, no body
topology is declared; once the condition is non-`nothing`, the body declarations
are emitted once.

## Boundary

The scalar baseline stays in `propagators.network-vm.executor`. The nested VM
experiment lives separately in `propagators.network-vm.nested` and
`propagators.network-vm.nested.gur`. Neither replaces accumulating GUR, neither
re-exports through `propagators.gur`, and neither changes `propagators.core` or
cell merge.

## Network Value As Nested VM

The nested executor tests a stronger idea: a network-valued cell can be treated
as a child VM value with an owner executor. Propagators still do not mutate
graph/env/cell entries directly. They return messages, instructions, or effects:

```clojure
{:target :self :op :tell ...}
{:target [:cell vm-cell-id] :op :declare-cell ...}
{:target [:cell vm-cell-id] :op :declare-prop ...}
{:target [:cell vm-cell-id] :op :bind-name ...}
{:target [:cell vm-cell-id] :op :schedule ...}
```

The executor performs those effects. When an effect targets `[:cell id]`, the
executor advances the child VM state outside the cell and writes the updated
child network declaration back through a normal `tell` to `id`. The cell still
owns refinement through ordinary cell merge.

Child VM declarations are named-network mergeable. `declare-cell`,
`declare-prop`, installer effects, and name bindings record stable dict
commitments, so writing the child net back into the owner cell does not rely on
unnamed graph/env changes. Runtime queues, task cursors, and child temperature
remain executor-local and are not stored in the child cell content.

Following the propagator paper's dynamic-install rule, attaching a new stable
propagator schedules it once when first declared. Re-declaring the same stable
cell, prop, name, or installer key is idempotent.

## Nested GUR/HOP Result

`propagators.network-vm.nested.gur` is intentionally tiny. A recursive closure
application declares an apply prop into the target VM. The closure body returns
declaration effects. `ctx/when` declares a condition prop that emits no topology
while the condition cell is `nothing`, then emits the body topology once after
the condition becomes non-`nothing`.

The HOP mapper experiment uses the normal compound-object APIs through a
deterministic installer bridge:

- source lists are built with live `obj/p:cons`;
- mapper bodies use `obj/p:car`, `obj/p:cdr`, and `obj/p:cons`;
- assertions install bounded `obj/p:car` / `obj/p:cdr` reader cells after
  evaluation;
- no source list is seeded as `cons-list-value` or `lazy-cons-list-value`.

Focused result from `propagators.network-vm-nested-test`:

| Scenario | Status |
| --- | --- |
| effect-targeted `declare-prop` inside a child cell | passes |
| duplicate child declaration does not grow topology | passes |
| child VM output is written back through owner-cell `tell` | passes |
| late child input reheats a cold child VM | passes |
| mapper chain `[1 1 1 1 1]` through `double-value`, depths `1/5/10/15` | passes |
| bidirectional identity output `car = 9` back to source, depths `2/5` | passes |
| bidirectional double output `car = 32` back to source at depth `5` | passes |
| late cdr attachment wakes a depth-3 mapper chain | passes |

One useful debugging result: the late-cdr test initially failed because the test
attached the new tail to an unconnected terminal id. After correcting the
terminal id, the existing accessor and `ctx/when` path woke the recursive tail.
That supports the narrower hypothesis that nested VM effects can preserve lazy
topology and bidirectional HOP handoff without mutating compound-object
accessor internals.

## Flat Main-Network Effects

`propagators.gur.flat` is a smaller parallel experiment. It removes the
child VM and treats recursive GUR as delayed topology effects over the main
network:

```clojure
(fvm/declare-cell id)
(fvm/declare-prop prop-id inputs outputs activate)
(message cell-id partial-info)
(install/installer-effects net install-key [head-id list-id]
                           (obj/p:car head-id list-id))
```

The flat effect set is intentionally bounded to cell declarations, propagator
declarations, and name bindings. Cell writes are ordinary messages, and existing
installer-shaped APIs are expanded by the authoring layer before the flat effect
dispatcher sees them.

The recursive layer in `propagators.gur.flat` keeps the same
high-level shape as nested GUR: `apply` declares an application prop, `recur`
declares another application prop with a deterministic frame key, and `when`
declares no body topology while the condition is `nothing`. The difference is
ownership: all declared cells, props, and compound-object accessors are installed
directly into the one VM network. There is no child network cell, no mailbox,
no child-network delta writeback, and no flat-VM-local task cursor. The main
`propagators.core` scheduler applies declaration effects and schedules newly
installed props.

This still preserves the architecture boundary: propagators return effects, and
the executor applies them. The GUR layer does not call `merge-cell-entry`, and
cell values still flow through `core/eval-cell*`.

Focused flat-effect result from `propagators.network-vm-flat-test`:

| Scenario | Status |
| --- | --- |
| duplicate declaration does not grow topology | passes |
| mapper chain `[1 1 1 1 1]` through `double-value`, depths `1/5/10/15` | passes |
| bidirectional identity output `car = 9` back to source, depths `2/5` | passes |
| bidirectional double output `car = 32` back to source at depth `5` | passes |
| late cdr attachment wakes a depth-3 mapper chain | passes |
| rerun after quiescence does not grow topology | passes |
| flat GUR does not directly call `merge-cell-entry` | passes |

### Threaded Installer Surface

`propagators.install` is now the preferred authoring surface for flat
declarations. It keeps installers as the core abstraction, but lets declaration
code use scoped cell names:

```clojure
(-> ctx
    (i/$ {:xs xs :mapper mapper :acc acc :out out})
    (i/car :head :xs)
    (i/cdr :rest :xs)
    (i/>> :mapper :head :mapped)
    (i/cons :mapped :mapped-rest :out)
    (i/when :rest
      (i/recur [:rest :mapper :acc] :mapped-rest)))
```

The helpers only emit flat VM declaration effects and ordinary messages. They
do not run propagation and do not mutate cell entries; `core/eval-activation-result`
and `core/run-tasks` remain the execution path. The raw installers (`obj/p:car`,
`prop/*`, etc.) remain the substrate for compatibility and custom declarations.

## Speed Snapshot

The network VM HOP paths are currently correctness/performance experiments, not
replacements for accumulating GUR. Local benchmark on 2026-06-29:

```bash
clojure -M:gur-accumulating-bench 1 3
clojure -Sdeps '{:aliases {:benchtest {:extra-paths ["test"]}}}' \
  -M:benchtest -e '<flat/nested mapper benchmark expression>'
```

| Mapper HOP depth | Accumulating GUR median | Kernel-flat effects chain-only median | Nested VM batched-delta chain-only median |
| --- | ---: | ---: | ---: |
| `5` | `159.869 ms` | `45.463 ms` | `146.771 ms` |
| `10` | `282.182 ms` | `53.368 ms` | `381.147 ms` |
| `15` | `355.017 ms` | `68.313 ms` | `752.480 ms` |

The same flat/nested run with bounded output reads:

| Mapper HOP depth | Kernel-flat effects plus bounded read median | Nested VM batched-delta plus bounded read median |
| --- | ---: | ---: |
| `5` | `23.658 ms` | `160.738 ms` |
| `10` | `47.207 ms` | `407.068 ms` |
| `15` | `60.116 ms` | `803.135 ms` |

Kernel-flat effects improve substantially over nested VM because they remove
child VM interpretation and child-network writeback. They also improve over the
first flat executor by removing the duplicate flat-VM-local task map/cursor:
newly declared props go straight into the ordinary `core/run-tasks` queue.
Depth `15` chain-only improved from the earlier flat-local `398.551 ms` median
to `68.313 ms`, about `82.9%` faster in this quick run. It also beat the current
accumulating GUR HOP benchmark in this mapper-chain shape: depth `15`
accumulating was `355.017 ms`, while kernel-flat was `68.313 ms`.

The first nested implementation wrote the whole growing child network back to
the parent cell after many child steps. Its quick depth-15 chain-only median was
about `2514 ms`, and plus bounded read was about `2918 ms`. A delta payload
reduced that to about `1853 ms` / `1990 ms`. The retained batched-delta version
records executor-local child deltas and flushes one delta to the parent cell
when the child has no more internal work, while still letting cell merge own the
eventual parent-cell refinement.

Profile evidence for depth `15`: child writeback messages dropped from `907` to
`1`, and measured child-writeback merge time dropped from about `2379 ms` to
about `1.3 ms`. The nested child still has `610` live propagators, so the
remaining gap against accumulating GUR is now mostly the literal nested-VM
execution model rather than whole-network parent writeback.

### Compiler-Fragment Trial

I also tested a more compiler-like variant where `ctx/apply` and `ctx/when`
compile returned declaration effects into one `declare-fragment` effect instead
of letting the nested VM advance each declaration instruction. It did not
improve HOP chaining, so it is not retained.

The fully direct form, compiling from an empty fragment, is not semantically
valid for current compound-object installers: `obj/p:car` / `obj/p:cdr` need
the existing external cell graph nodes when they wire edges. Without those graph
nodes the installer fails with `unknown node`. A safer shell-delta variant kept
the existing cell graph nodes and passed correctness, but it was slower than the
batched-delta path:

| Mapper HOP depth | Batched-delta chain-only median | Shell-delta compiler-fragment chain-only median | Batched-delta plus bounded read median | Shell-delta compiler-fragment plus bounded read median |
| --- | ---: | ---: | ---: | ---: |
| `5` | `212.473 ms` | `227.857 ms` | `171.461 ms` | `176.590 ms` |
| `10` | `422.289 ms` | `510.324 ms` | `428.993 ms` | `479.760 ms` |
| `15` | `811.656 ms` | `884.290 ms` | `864.892 ms` | `907.189 ms` |

The practical conclusion is that "compile the topology" is not automatically
faster in this implementation. To be correct for live compound-object accessors,
the compiled fragment still has to carry current cell adjacency, and that
larger graph merge costs more than the declaration-step reduction saves.

Run the focused test with:

```bash
clojure -M:test propagators.network-vm-flat-test
clojure -M:test propagators.network-vm-test
clojure -M:test propagators.network-vm-nested-test
```
