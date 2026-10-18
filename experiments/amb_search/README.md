# AMB search cost and branch elimination

Measured on 2026-10-19 against `e8a77ea`, using the editor Pythagorean example
and the extension `(-> (list 3 4 5) domain)`. Both variants return `[3 4 5]`.
The plain variant changes only the display expression to `(display valid)`;
the replay variant retains the existing semantic history pipeline.

## Reproduce

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.profile
```

The diagnostic uses the existing synchronous FIFO profiler in an isolated JVM.
It changes no production scheduling, cell merging, or search policy. Its
process-global function redefinitions must not run inside the live editor JVM.
Namespace loading precedes measurement. Results include compilation, source
loading, and propagation, but exclude frontend inspection/rendering. These are
single diagnostic samples with instrumentation overhead, not throughput claims.
The plain variant ran first; the replay variant benefited from JVM warmup.

## Current bottleneck

| Measurement | Plain display | Semantic replay |
| --- | ---: | ---: |
| Complete load and extension | 2,086 ms | 67,962 ms |
| Extension alone | 468 ms | 59,643 ms |
| Runner transitions | 6,750 | 45,921 |
| Relationship-observer activations | 0 | 727 |
| Observer activation self time | 0 | 58,699 ms |
| Runner patch self time | 763 ms | 5,633 ms |
| Learning, inclusive | 3.29 ms / 22 calls | 0.98 ms / 22 calls |
| Repair decision, inclusive | 2.51 ms / 14 calls | 0.75 ms / 14 calls |
| Capture grouping, inclusive | 0 | 222 ms / 35 calls |

Observer work accounts for about 86% of the replay sample. Search learning and
decision call counts are identical across variants. The cost is repeated
observation rather than no-good resolution in this small fixture. Inclusive
timings overlap their children and must not be added together.

`semantic-trace.observation/acquire` constructs a fresh root graph before
testing `same-root-evidence?`. Root graph construction selects connected nodes
and calls `relationship-observer/snapshot`. Every node label currently calls
`lexical-binding-names` again; propagator labels also rebuild the application
operator lookup. This repeats work within a snapshot, and equivalent samples
repeat it before their equality check can discard them.

A second replay sample, with timers inside those boundaries, completed in
68,073 ms with the same 45,921 transitions and `[3 4 5]` result:

| Boundary | Calls | Inclusive time | Self time |
| --- | ---: | ---: | ---: |
| Root graph projection | 727 | 57,399 ms | 22,365 ms |
| Selected-node snapshots | 1,454 | 27,465 ms | 13,665 ms |
| Lexical label index construction | 154,631 | 13,799 ms | 13,799 ms |
| Connected-root selection | 737 | 7,703 ms | 7,703 ms |

Each root projection builds two selected-node snapshots on this path. The
154,631 label-index constructions demonstrate repeated work directly, rather
than inferring it solely from the slower wall time. This sample adds timers
and is not an estimate of the exact gain from changing any single function.

The narrow first optimization candidate is to construct label indexes once per
snapshot and owning network. Avoiding equivalent acquisitions requires a
separate invalidation contract covering topology, values, dependencies,
premise states, and labels. Neither optimization is implemented by this probe.

## What establishes branch elimination

A no-good with literals `L` blocks every assignment `A` satisfying `L ⊆ A`,
provided its external premise context is current. Therefore, if failure depends
only on `a=1`, learning `{a=1}` excludes every completion of that assignment,
regardless of independent choices `b` and `c`. With two alternatives each, that
single no-good excludes four complete branches. With independent domain sizes
`m` and `n`, it excludes `m*n` branches.

`policy/repair` considers only sources participating in an active conflict.
`admissible-index` rejects alternatives already blocked by applicable no-goods.
Thus repair can skip later independent choices and known-invalid alternatives.
This is conditional on sound, precise failure support; an opaque predicate
depending on all choices can initially produce only a full-assignment no-good.
The Pythagorean fixture's composed Boolean constraint does not itself prove
maximal pruning or a speedup on arbitrary problems.

Two executable regressions establish the relevant behaviors:

* `dependency-directed-repair-eliminates-an-entire-independent-subtree` compiles
  ordinary Lain with three binary choices and a predicate depending only on
  `a`. The runner returns `[2 1 1]`; only `a` advances through withdrawal and
  reselection (timestamp 3). Both later choices retain timestamp 1. The actual
  learned value is the unary no-good, and all four forbidden completions are
  checked along with the four allowed completions.
* `finite-domain-resolution-preserves-the-forbidden-assignments` exhausts all
  16 subsets of four binary assignments. For every subset, closure is
  idempotent and blocks exactly the same complete assignments as its input.
  Together with exhaustive-domain resolution, this explains why a smaller
  minimized set can preserve monotone logical knowledge.

These are a general set-inclusion argument and finite executable checks, not
a formal verification of the entire propagator runtime.

## Verification

The two new regressions pass: **2 tests, 96 assertions, zero failures/errors**.
The complete learning and Lain test namespaces produced **146 passes and three
failures**. All three failures are in the existing
`original-whole-compound-gating-is-a-reproduced-runtime-gap` characterization:
it expects failure, whereas execution now completes. Loading that test directly
from `HEAD` reproduces the same three failures independently of the new tests.
No production change or assertion weakening was made to hide that discrepancy.

Raw local reports: `/tmp/amb-current-profile.edn`,
`/tmp/amb-current-detail.edn`,
`/tmp/amb-pruning-focused.log`, `/tmp/amb-pruning-tests.log`, and
`/tmp/amb-existing-characterization.log`.
