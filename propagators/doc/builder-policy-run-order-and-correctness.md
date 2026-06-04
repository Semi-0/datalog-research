# Builder Policy, Run Order, and Correctness

Status: **documented on `main`** from stage-1 experiments; implementation is on branch `experiment/eager-install-stage-1`.

Related:

- [Eager Install and Arithmetic Procedure](eager-install-and-arithmetic-procedure.md) — stage 1/2 plan and `:queue` decision
- [Core Runtime Model](core-runtime.md) — installers, `run-tasks`, compile surface
- [Layered Procedure Network](layered-procedure-network.md) — extension cells and apply path
- [Compound Object Slot Sync](compound-object-slot-sync.md) — tests that bypass compile policy

Source files (on `experiment/eager-install-stage-1`, not `main`):

- `propagators/builder_policy.clj` — `*builder-policy*`
- `propagators/compile.clj` — `flush-queued-tasks`, `eval-do`, `eval-net` / `eval-net*`
- `propagators/network_builder.clj` — `install-propagator*`, `seed-cell*`, `run-queued-tasks`
- `test/propagators_eager_install_test.clj` — intentional `:queue` usage

---

## Correctness goal

A propagator network should reach the **same stable observable result** regardless of **fair task order**, once:

1. The **topology** (cells, propagators, wiring) is fixed, and  
2. The **inputs** (seeded cell values) are fixed.

That is the usual **confluence** expectation for constraint propagation: different dequeue orders should not change the quiescent strongest views, except where the theory intentionally allows ambiguity (contradiction, partial support sets).

This document separates three notions that are easy to conflate:

| Notion | What varies | What must stay invariant |
|--------|-------------|---------------------------|
| **Scheduler order** | Order of `run-tasks` on the same queue | Final cell strongests after quiescence |
| **Construction order** | Order of install/seed **within one flush boundary** | Same as above **after** that boundary’s inputs are complete |
| **Flush boundary placement** | *When* queued work is drained relative to more wiring/seeding | **Not** invariant unless the network is written for that boundary |

**Builder policy** (`*builder-policy*`) only affects **construction + when the compile path drains its task queue**. It does **not** change propagator semantics. It **does** change **intermediate** network states seen by later compile steps or tests if those steps assume “wired but not yet run.”

Default global policy remains **`:lazy`** so existing tests and callers keep “declare first, run when I say” semantics.

---

## Policies

Defined in `propagators.builder-policy`:

| Policy | Install (`eval-application` / `install-propagator*`) | Seed (`eval-seed` / `seed-cell*`) | Who runs tasks |
|--------|-----------------------------------------------------|-----------------------------------|----------------|
| **`:lazy`** (default) | Wire topology only | Update cell only | Caller: `nb/run-propagators`, `run-tasks`, inner activations |
| **`:queue`** | Enqueue new propagator ids on compile `:tasks` | Enqueue neighbor propagator ids | `flush-queued-tasks` at compile flush boundaries |

**`:queue` is not “run on every install.”** It accumulates tasks on the compile context and drains them **once per flush boundary**. That is batch evaluation at controlled points, not per-line quiescence.

Explicit network-builder bang APIs are **outside** this policy:

- `nb/install-propagator!` — always install + enqueue  
- `nb/seed-cell!` — always seed + enqueue  
- `nb/run-propagators` — explicit batch run  

Compound-object and many compound-runtime tests use those directly; binding `*builder-policy*` to `:queue` is a **no-op** for them.

---

## Compile flush boundaries

When `*builder-policy*` is `:queue`, `propagators.compile/flush-queued-tasks` runs after:

| Form | Flush? | Notes |
|------|--------|-------|
| `(do …)` | **Yes** — end of `do` | Sequential eval of body, then one drain |
| Top-level `eval-net` / `eval-net*` | **Yes** — end of expression(s) | Single `eval-net` form → one drain at end |
| `(let-cell […] …)` | **No** mid-block flush | Only the **outer** `eval-net` flush (if the whole form is one top-level eval) |
| Single installer application | **Yes** — if that application is the whole `eval-net` form | e.g. `(layered/p:apply-layered2 …)` alone |

```text
:lazy
  compile → wire only → caller runs scheduler later

:queue
  compile → enqueue installs/seeds → flush at (do) or eval-net end → run queued tasks once
```

### Canonical safe pattern (layered bootstrap)

Same as stage-1 eager-install tests — **install and seed in one `do`**, then rely on flush:

```clojure
(do
  (layered/p:layered-procedure proc extension)
  (seed extension extension-value))
```

Under `:queue`, flush runs **after** both forms: extension is seeded before `p:layered-procedure` tasks run. This matches manual `:lazy` + `nb/run-propagators` on procedure props.

### Unsafe pattern (split across compile calls)

Many helpers in `test/propagators_layered_procedure_test.clj` today:

```clojure
;; step 1 — compile: install slot propagators → flush runs them
(install-layered-inputs net a b)

;; step 2 — compile: install apply propagator → flush runs apply
(install-apply (:net input) …)

;; step 3 — compile: seed base/provenance inputs
(seed-layered-inputs …)

;; step 4 — manual run again
(nb/run-propagators …)
```

Under **`:lazy`**, steps 1–2 only wire; step 4 is the first real propagation.  
Under **`:queue`**, step 2’s flush runs **apply before step 3 seeds** → wrong partial state, exceptions (e.g. `/` with keywords), or silent wrong merges.

**Correctness relative to run order is preserved for the scheduler inside a fixed network; the bug is observing a network that was never meant to be executed at that flush point.**

---

## What should be order-independent

### Scheduler / task queue

For a **fixed** network value and a **fixed** set of seeded inputs, draining the task queue in any **fair** order should yield the same quiescent strongests, modulo intentional partial/contradiction theory.

That property is what `run-tasks` and `nb/run-propagators` are expected to preserve. Tests that only change **which prop id is dequeued first** (same multiset of tasks) should not change outcomes.

### Not automatically invariant

| Situation | Why |
|-----------|-----|
| Running propagators **before** required seeds | Prop sees `nothing` or weak values; merge may no-op or leave garbage |
| Running **between** two compile steps that were written for `:lazy` | Flush exposes an intermediate network the test never asserted under lazy |
| **Inner** eager subnets (`p:apply-layered`, compound activations) | Already run to quiescence inside one propagator; outer policy does not replace that |
| **Contradiction / support** paths | Multiple stable readings may be theory-defined; not “any order same number” |

---

## Experiment: global `:queue` on test suites

Binding `*builder-policy*` to `:queue` for entire namespaces (not recommended as a global fixture) produced:

| Suite | Result | Reason |
|-------|--------|--------|
| `propagators-compound-object-test` | **All pass** | No `compile/eval-net`; uses `install-propagator!` / `seed-cell` / `run-propagators` |
| `propagators-compound-data-test` | **1 fail** (`car-does-not-read-collection`) | `compile/eval-net` flush runs `p:car` at **build** time; test expects collection still `nothing` until explicit `seed-and-run` |
| `propagators-layered-procedure-test` | **6 errors / 7 tests** | Split install vs seed vs apply across separate `eval-layered` calls; flush runs apply/operators early |
| `propagators-network-test` | **1 error** | Compile-only test assumes no propagation until caller runs |
| `propagators-eager-install-test` | **Pass** (with `with-queue-policy`) | Expressions shaped for flush boundaries |

**Compound propagator** tests in `propagators-network-test` / `propagators-compound-diagnosis-test` (`compound-propagator` constructor) are unchanged by builder policy unless they go through `compile` with `:queue`.

---

## Guidelines

### For library correctness

1. Treat **propagator definitions** as order-independent **given complete inputs**.  
2. Do **not** assume “install never runs” globally if `:queue` or `install-propagator!` may be used.  
3. Document **required construction order** only where partial networks are intentional (bootstrap `do`, reactive extension cells).

### For compile and macros

1. Prefer **one flush boundary** per logical bootstrap: `(do install … seed …)` or `eval-net*` with multiple forms and **one** final flush.  
2. Avoid emitting a **standalone** installer call in `eval-net` if the next step in the same helper still needs to seed inputs for that propagator.  
3. Stage 2 `install-arithmetic-procedure` should use `:queue` + flush like eager-install tests, not split compile calls.

### For tests

| Approach | When |
|----------|------|
| Default (no binding) | Topology-only build, explicit `run-propagators`, “not run yet” assertions |
| `(binding [*builder-policy* :queue] …)` locally | Expression matches production bootstrap (`do` install + seed) |
| Refactor helpers | Bundle wire+seed+apply in one `do` or one `eval-net*` before dropping manual runs |
| **Do not** global `use-fixtures` `:queue` on layered/compound-data suites | Hides real flush-boundary bugs |

`with-queue-policy` in `propagators_eager_install_test.clj` is the reference pattern.

---

## Relation to stage 2

Stage 1 chose **`:queue` + flush boundaries** for arithmetic procedure bootstrap, with default **`:lazy`** globally.

Stage 2 (`install-arithmetic-procedure`) must:

- Emit installs/seeds inside compile shapes that flush **after** all seeds needed for that bootstrap, or  
- Use explicit `nb/run-propagators` under `:lazy` like today’s layered test helpers.

Changing the **default** to `:queue` without refactoring every `compile/eval-net` test helper would change observable behavior for “build network only” tests — that is a **breaking** semantic change, not a scheduler tweak.

---

## Decision record (short)

| Question | Answer |
|----------|--------|
| Should task scheduling be order-independent at quiescence? | **Yes** — design goal for the runtime. |
| Does `:queue` break that goal? | **No** for scheduler; **yes** for “intermediate network” if flush runs too early. |
| Safe default for the repo? | **`:lazy`** until call sites are flush-aware. |
| Safe scoped use? | **`:queue`** inside `(do install seed)` and tests in `propagators_eager_install_test.clj`. |
| Global test fixture `:queue`? | **No** — fails layered apply path and compound-data “not run yet” tests. |

---

## See also

- §1.5 and §1.9 in [eager-install-and-arithmetic-procedure.md](eager-install-and-arithmetic-procedure.md) for hypothesis table and stage-1 results.  
- [layered-procedure-network.md](layered-procedure-network.md) — reactive extension vs one-shot bootstrap (stage 2).
