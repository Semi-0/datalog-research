# Dependency-directed backtracking

AMB choices and supported procedures compose in an ordinary propagator network.
A target failure carries the premises that made it fail. `learn` turns that
certificate into a no-good, and `backtrack` asks a participating decision to
try another alternative. The runner drives this feedback to quiescence.

## Selecting procedures from files

The opt-in [file compiler](../experimental/file_premise/compiler.clj) adds one
file-owned premise to every network declared in a loaded file, including
networks declared later inside procedure bodies. Files contain ordinary Lain;
they do not need explicit `ttms-premise-closure` calls.

The runnable example loads three files into separate lexical frames:

[`one.lain`](../../examples/lain/amb/file_premise_selection/one.lain):

```clojure
(define procedure (network (x) (+ x 1)))
```

[`ten.lain`](../../examples/lain/amb/file_premise_selection/ten.lain):

```clojure
(define helper (network (x) (+ x 10)))
(define procedure (network (x) ((network (y) (helper y)) x)))
```

[`goal.lain`](../../examples/lain/amb/file_premise_selection/goal.lain):

```clojure
(define target (network (procedure) (= (procedure 5) 15)))
```

The [query](../../examples/lain/amb/file_premise_selection/target.lain) selects
between the exported procedures and returns only the accepted decision:

```clojure
(let-cell [repairs decision]
  (define backtrack-unless
    (network (target decisions)
      (let [error (learn (require target) decisions)]
        (backtrack decisions error))))

  (define chosen (amb (list 1 2) repairs decision))
  (define procedure (if (= chosen 1) one/procedure ten/procedure))
  (define target (goal/target procedure))

  (-> (backtrack-unless target (list decision)) repairs)
  (switch chosen target))
```

The first procedure produces `6`, so the target fails. Its failure depends on
the current decision, `one.lain`, and `goal.lain`. Learning records that choice
as invalid under those file premises. Repair withdraws the current choice and
publishes the second alternative at a newer epoch. The second procedure
produces `15`, so the result is **`2`**.

The accepted answer carries exactly the current AMB premise, the `ten.lain`
premise, and the `goal.lain` premise. It excludes the unselected `one.lain`
premise. Both top-level definitions and the nested network in `ten.lain` use
the same file source cell. Identically named exports from different files
remain separate; queries refer to them as `one/procedure` and `ten/procedure`.

`decision` is a record of the current choice, including its source and epoch;
`chosen` is its scalar value. `(list decision)` supplies the existing learning
and repair operators with their sequence of choice records. `error` is learned
knowledge, not a thrown exception. `require` distinguishes a failed Boolean
target from a pending value.

## Why the backtracking is dependency-directed

[Learning](../experimental/amb/learning.clj) separates failure support into
choice literals and external context. A literal identifies a choice source
and alternative index. External context retains exact file source, epoch, and
status. Stale choice certificates are rejected. A no-good applies only while
its external context remains supported; it is not a permanent ban on that
procedure under every target.

[Repair policy](../experimental/amb/policy.clj) considers decisions participating
in the active conflicts and looks for an admissible alternative, in reverse
record order. Finite-domain resolution derives a no-good without choice
literals when all alternatives fail under the same context, yielding
`{:action :unsatisfiable}`. TTMS support compaction and logical no-good
resolution remain separate operations.

This file example tests one decision with two alternatives. The separate
[three-procedure example](../../examples/lain/amb/native_procedure_pipeline.lain)
chooses a composition from 27 combinations and accepts `[2 3 1]`, corresponding
to `5 -> 15 -> 45 -> 42`. Neither example establishes minimum conflict sets,
optimal search, or a performance improvement.

## Compiler and file ownership

[File ownership](../experimental/file_premise/files.clj) is an immutable
composition over existing compiler and runtime APIs:

```clojure
(require '[propagators.experimental.file-premise.files :as files])

(def world
  (-> (files/initial-world)
      (files/load-file "one.lain" 'one)
      (files/load-file "ten.lain" 'ten)
      (files/load-file "goal.lain" 'goal)
      (files/query (slurp "target.lain"))))
```

Paths in this snippet are relative to the working directory. Run the checked-in
fixtures from the repository root with:

```sh
clojure -M -m examples.lain.amb.file-premise-selection
```

The world contains `:net`, the primitive `:env`, its `:props`, and `:files`.
Each file descriptor contains its canonical `:path`, actual `:premise-id`, and
`:exports` mapping names to cell bindings. A query adds `:compiled`, whose
`:cell` is the result cell. Loading reads the filesystem; evaluation returns a
new world. Callers own installation of that value.

The compiler overrides only the network rule through `compose-rules`. It calls
the existing network handler and then installs the existing supported-callable
constructor using the file source cell. Captured compiler state handles delayed
nested declarations. The query uses the default compiler. Generic application
handlers carry callable support through the existing arithmetic operations;
no arithmetic unwrapping, compiler option, scheduler change, or production
loader change is introduced.

Input files must contain nonempty sequences of top-level network definitions
with unique unqualified export names. Duplicate aliases and canonical paths
are rejected. A failed read, parse, validation, or evaluation does not return a
partially installed world.

## Declaring the composition in Lain

The opt-in [session extension](../experimental/file_premise/session.clj) exposes
`file-composition`, `file-load`, and `file-run`. The checked-in
[composition.lain](../../examples/lain/amb/file_premise_selection/composition.lain)
contains the module order, aliases, and query file; the Clojure bootstrap only
installs the extension and submits that source.

With paths relative to the current working directory:

```clojure
(let [one (file-load file-composition "one.lain" "one")
      ten (file-load one "ten.lain" "ten")
      definitions (file-load ten "goal.lain" "goal")]
  (file-run definitions "target.lain"))
```

`file-composition` is an empty immutable declaration value. `file-load` appends
a path and alias without reading the file. Paths are canonicalized by the file
owner during execution; aliases are unqualified strings in this Lain API.
A network can accept and return these composition values like other data.

`file-run` declares a session boundary request. The handler reads the modules
in order, evaluates the query in a fresh private file world, and installs that
world only after successful evaluation. Its result cell receives the ordinary
boundary receipt, with these additional fields for the example:

```clojure
{:boundary/status :completed :ready? true :answer 2 :support #{...}}
```

The receipt's `:support` is copied from the supported answer. An impossible
query completes with `:ready? false` and `:answer nil`; evaluation failures
produce the existing session failed-receipt diagnostics. Prior successful
worlds remain installed after a failed run. Actual worlds are retained in
`:propagators.experimental.file-premise.session/worlds`, keyed by boundary
request ID, for inspection or explicit availability updates through the file
owner API. Loaded modules are scoped to those worlds.

Repeated delivery of the same module/query/revision declaration reuses the
session receipt. An optional third argument requests a distinct evaluation:

```clojure
(file-run definitions "target.lain" 1)
```

The default revision is `0`. A new revision reads current file contents into
a new world; it does not replace definitions in a prior world. The revision
is an execution identity, separate from per-file TTMS premise epochs.

Use the existing live-session extension API to install the capability:

```clojure
(require '[propagators.compiler-2.runtime :as runtime]
         '[propagators.compiler-2.runtime.session.state :as state]
         '[propagators.experimental.file-premise.session :as file-session])

(runtime/install-session-extension!
 live file-session/session-extension
 {:outbox-id (state/boundary-outbox-id)})
```

Here `live` is an existing session. Client names are ordinary runtime bindings,
so avoid naming a client `file-composition`, which would shadow that binding.
The executable bootstrap runs from the repository root:

```sh
clojure -M -m examples.lain.amb.file-premise-session
```

This adds a session capability bundle and handler; the production session,
compiler, arithmetic, scheduler, and existing loader remain unchanged.

## Availability and limits

```clojure
(def withdrawn (files/publish world 'ten 2 :retracted))
(def recovered (files/publish withdrawn 'ten 3 :active))
```

Withdrawal makes the answer pending; it does not prove the target false.
Reactivation restores the answer with the new file epoch. Retracting the target
file also makes the answer pending. Older epochs are ignored by TTMS. Replaying
query propagators preserves the network environment and topology.

This experiment changes availability of already loaded definitions. It does
not replace changed file contents, watch files, unload callable topology,
retire accessors, discover files, or change choice domains. Use a fresh world
to load different definitions. File-level support is deliberately coarse:
all definitions in one file share one premise, while each file has its own
premise. No System Model facts are changed by this experiment.

## Verification

The focused suite checks exact answer support, separate exports, shared file
support, delayed nested compilation, no-fit exhaustion, selected and target
file withdrawal/recovery, stale epochs, unselected withdrawal, stable replay,
invalid files and updates, duplicate loads, failed-load preservation, and
default compiler parity.

```sh
clojure -M:test propagators.experimental.file-premise-test
```

On 2026-10-16, a clean export of `f984a5b` plus only the new experiment files
passed **6 tests / 53 assertions**, with no failures or errors. This is a focused
compatibility result. The related native procedure, application, and premise-selection
suites also passed 142 assertions in the working tree. No whole-repository or
performance claim is made.

The session composition extension was also checked against the same clean base:
**40 tests / 188 assertions**, with no failures or errors across the file-world,
file-session, existing session-extension, and functional-network-effect suites.
This includes live-session submission and retention, pure and deferred Lain
composition, exact receipt support, idempotent delivery, explicit revision
identity, invalid arguments, failed reads, and no-fit completion. Lint reported
zero errors and warnings.

```sh
clojure -M:test propagators.experimental.file-premise-session-test propagators.experimental.file-premise-test propagators.compiler-2.runtime.session-extension-test propagators.compiler-2.runtime.functional-network-effects-test
```
