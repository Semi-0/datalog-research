# Accumulating GUR

Status: current main GUR implementation.

This document explains the current accumulating GUR code from the public facade
down to its submodules. The goal is natural-language orientation: what each
file owns, what it must not own, and how one recursive application moves through
the system.

## What Accumulating GUR Is

GUR is the recursive declaration layer of the coordination language. It is not
the default programming model for ordinary behavior programs. It is the
power-user tool for recursive topology: compiler construction, macro-like
expansion, recursive AST/list traversal, recursive lexical accessors, and
higher-order operators.

Accumulating GUR's core idea is one owner network per recursive application.
When a closure is applied, the system creates one `applied-net-id` cell. All
frames, frame props, scoped routes, application requests, lazy `when` bodies,
and task facts accumulate into the named-network value stored in that one cell.
Recursive calls do not create nested owner cells as the semantic model.

The split is:

- declaration facts live in the accumulated network value;
- cell merge owns how those network facts are joined;
- the runner primitive owns runtime cursors and caches;
- recursive closure bodies declare topology, but do not mutate the outer graph;
- output leaves the owner through ordinary messages.

## Public Surfaces

### `propagators.gur`

`propagators.gur` is the canonical public namespace. It re-exports the
accumulating implementation. New compiler and macro work should require this
namespace unless it explicitly needs internal implementation helpers.

Important exports:

- `recursive-closure` builds a closure value from a name and body function.
- `recursive-closure?` recognizes accumulating GUR closure values.
- `def-recursive` is the source-level macro for defining recursive closures.
- `p:apply-closure` is the usual installer for applying a recursive closure.
- `p:accumulate-apply-closure` installs only the request-emitting primitive.
- `p:run-accumulated-network` installs only the owner-network runner.
- `p:when-topology` installs a lazy topology builder.
- `application-key` and the index keys expose the internal fact vocabulary for
  tests, graph tools, and docs.

This namespace intentionally hides the older GUR variants. `gur.subenv` remains
available as a deprecated compatibility and routing-helper namespace, but it is
not the target for new GUR users.

### `propagators.gur.accumulating`

This is the implementation facade. It collects the pieces from `core`, `facts`,
`source`, and `runner`.

Its most important function is `p:apply-closure`. That installer creates a fresh
`applied-net-id`, ensures the closure, argument, output, and owner cells exist,
installs the request-emitting primitive, installs the runner primitive, and
stores a mapping from the deterministic `application-key` to the owner cell.

In plain language:

1. Make an owner cell for this application.
2. Watch the closure, arguments, and output.
3. When enough information exists, emit an application request into the owner.
4. Let the runner expand and execute the accumulated owner network.
5. Project the selected output cell back to the outside.

## Declaration Submodules

### `propagators.gur.accumulating.core`

`core.clj` owns closure values and declaration-time frame accumulation.

The closure value is deliberately simple:

```clojure
{:gur/accumulating-recursive-closure? true
 :gur/name name
 :gur/body body-fn}
```

The body function receives a frame context, a frame network, argument cell ids,
and an output cell id. It returns either a network or a map containing a network
and prop ids. The body declares topology; it does not run that topology.

Major responsibilities:

- `recursive-closure` and `recursive-closure?` define the closure value.
- `strongest-or-nothing` safely reads a cell strongest value, returning
  `nothing` when the cell does not exist.
- `strip-compiler-symbols` removes compiler-local symbol bindings from an
  accumulated network fragment so separate frames do not collide on ordinary
  source names.
- `bind-local-alias` registers frame-local names inside the scoped routing
  model. This lets tests and compiler probes inspect frame locals by scoped
  name without making names global.
- `boundary-cell-ids` expands input/output boundary ids to include accessor
  parent cells discovered through scoped slot routing. This is what lets
  accessor-related boundary updates wake the owner.
- `build-frame-fragment` is the heart of frame declaration. It builds a new
  frame network, copies boundary cells from the runtime net, binds arguments,
  installs `:self` and `:out`, runs the closure body as declaration, and records
  the result as frame facts.
- `p:accumulate-apply-closure` installs the primitive that watches a closure
  application and emits an application-request fragment when the application is
  ready.
- `p:when-topology` installs the lazy topology primitive. If the condition cell
  is `nothing`, it does not build the body. Any non-`nothing` value builds the
  body exactly once for that `when` key.
- `contextual-api` gives source bodies their contextual `ctx/apply`,
  `ctx/recur`, and `ctx/when` operations.

The most important guard in this file is that recursive application emits a
request, not an immediately expanded frame, unless the application has enough
usable information and has not already been requested or declared. Duplicate
work prevention is split: declaration facts are idempotent, and the runner owns
runtime skip caches.

### `propagators.gur.accumulating.facts`

`facts.clj` defines the monotone facts stored in the accumulated network dict.

The main dict keys are:

- `frame-index-key`: set of declared application frame keys.
- `frame-prop-index-key`: set of propagator ids introduced by frames or lazy
  `when` bodies.
- `task-index-key`: map from task cause to prop ids and indexes.
- `application-request-index-key`: map from application key to the requested
  closure/argument/output ids.

The application key has the shape:

```clojure
[:gur/application closure-id [arg-ids...] out-id]
```

That key is the semantic identity of one recursive application. Re-emitting the
same application should rediscover the same declaration, not make a fresh
unrelated frame.

Important functions:

- `add-task-facts` records that a cause should run a set of prop ids at a given
  index. Indexes are monotone; seeing a later index for the same cause means the
  same task should be considered again.
- `application-request-fragment` builds a tiny named-network fragment saying
  "this application should be expanded."
- `application-requests` and `application-requested?` read those pending
  requests.
- `frame-declared?` checks whether a frame is already declared in either the
  runtime net or the applied owner net.
- `record-frame-fragment` marks a frame declared, records its prop ids, adds
  a frame task, and stores its scope.
- `record-when-fragment` records lazy topology introduced by `ctx/when`.

This file should stay about declaration facts only. It should not run
propagators, inspect parent values, or manage runtime cursors.

### `propagators.gur.accumulating.ids`

`ids.clj` gives accumulating GUR deterministic internal ids.

The key rule is that redeclaring the same semantic frame must produce the same
internal ids. Otherwise rerunning a closure would keep growing new equivalent
cells and props.

Important functions:

- `stable-node-id` turns structured parts into a stable `NodeId`.
- `stable-id-generator` returns a deterministic `ids/new-node-id` replacement
  for one frame or lazy body.

`core/build-frame-fragment` and `core/p:when-topology` temporarily bind
`ids/new-node-id` to this generator while declaring a body. That is a narrow,
local mechanism for idempotent declaration, not a global id policy.

### `propagators.gur.accumulating.source`

`source.clj` is the compiler/source DSL bridge. It lets users write recursive
closures using the existing primitive compiler syntax instead of manually
constructing closure body functions.

Major responsibilities:

- `contextual-installers` adds `ctx/apply`, `ctx/recur`, and `ctx/when` to an
  installer map when those operations are available in the runtime frame.
- `default-installers` starts from `compile/default-installers` and layers the
  contextual operations on top.
- `recursive-definition` lowers a source body into a function that declares
  frame topology. It checks argument counts, binds source symbols to frame cell
  ids, compiles the body into the frame net, and returns the declared net and
  prop ids.
- `source-recursive-closure` wraps that lowered source definition in an
  accumulating `recursive-closure`.
- `def-recursive` is the macro used by normal source definitions. It accepts
  optional metadata such as `:name`, `:installers`, and `:seed-values`.

The DSL is only syntax over the declaration/evaluation split. `def-recursive`
does not make recursion a host-language function. It creates a closure value
whose body declares propagator topology when the runner expands a frame.

## Runner Submodules

### `propagators.gur.accumulating.runner`

`runner.clj` exposes the public runner installer.

`p:run-accumulated-network` installs one primitive propagator for one
`applied-net-id`. That primitive watches the applied-net owner, import ids, and
external output ids.

The runner keeps a private state map of atoms:

- `:task-cursor`: which task indexes have already been consumed.
- `:request-cache`: application requests already expanded or known declared.
- `:request-scan-cache`: fast path for request maps that are fully expanded.
- `:prop-state-cache`: last observed prop input/output state.
- `:prop-io-cache`: cached observed ids per prop.
- `:mailbox-epoch`: monotone index for mailbox-derived tasks.
- `:last-input-token`: identity token for skipping identical runner turns.
- `:boundary-cache`: cached boundary ids for the current parent inputs.

This state is runtime state. It is intentionally not stored in the accumulated
network and not part of recursive semantics.

On activation, the runner:

1. Builds an identity token from watched parent cell entries.
2. If the token is unchanged, emits no messages.
3. Computes or reuses boundary cell ids.
4. Delegates to `executor/run-accumulated-messages`.

### `propagators.gur.accumulating.runner.executor`

`executor.clj` is the private execution engine for one accumulated owner.

The word "mailbox" means the child-to-owner accumulated network fragment stored
in the `applied-net-id` cell. It is declaration data, not a runtime queue.

Major phases:

1. **Reset and import boundary cells**
   `prepare-run-net` starts from the accumulated net, clears the owner mailbox,
   ensures boundary cells exist, imports relevant parent cell content, and marks
   external output avatars.

2. **Add boundary task facts**
   `add-boundary-task-facts` compares parent boundary cells against the child
   copies. When a boundary value changed, it records a boundary task. This keeps
   late input/output changes visible to the accumulated net.

3. **Expand application requests**
   `expand-application-requests` scans application request facts. If a request
   has not already been expanded and its closure is an accumulating closure, it
   calls `core/build-frame-fragment` and merges the resulting declaration into
   the child net.

4. **Run pending task facts**
   `run-accumulated-child` repeatedly asks `runner.tasks` for the next pending
   task index, runs the task's props with a state cache, expands any new
   application requests, and marks the task index consumed in the local cursor.

5. **Settle mailbox fragments**
   `settle-accumulated-child` repeatedly runs the child, reads the owner mailbox,
   prunes already-expanded requests, adds mailbox task facts when needed, resets
   the mailbox, and merges the mailbox into the child. This prevents a half-
   merged mailbox from waiting for a later runner turn.

6. **Export messages**
   `run-accumulated-messages` diffs selected child output cells back to the
   parent, exports direct child accessor messages for neighboring accessors,
   forwards external messages, clears runtime mailboxes, and finally emits the
   updated accumulated net back to `applied-net-id` if it changed.

The executor has step budgets for child runs and mailbox reconciliation. If
those are exceeded, it throws. That is intentional: an infinite or explosive
recursive declaration should be visible instead of hidden.

### `propagators.gur.accumulating.runner.tasks`

`runner/tasks.clj` owns task selection and prop-level skip caching.

Task facts are stored in the accumulated network. The cursor saying which task
indexes have already run is local to the runner primitive.

Important functions:

- `next-pending-task-fact` finds the next task/index pair that has not been
  consumed by the runner cursor. Boundary tasks are sorted after declaration
  tasks, so a boundary index does not run before the props it should wake are
  declared.
- `indexed-prop-ids` reads the accumulated prop index.
- `run-props-with-state-cache` drains a queue of prop ids. Before running a
  prop, it reads the cells the prop observes. If the observed state is identical
  to the last state this runner saw for that prop, it skips the prop. Otherwise
  it calls `core/eval-propagator`, records instrumentation, updates the cache,
  and continues with any tasks produced by ordinary propagation.

This is a performance layer, not a semantic layer. Correctness should come from
the accumulated facts and ordinary propagation; the cache only avoids rerunning
props whose observed state did not change.

### `propagators.gur.accumulating.runner.instrumentation`

`runner/instrumentation.clj` contains optional debug hooks.

It exposes dynamic vars:

- `*prop-run-observer*`: receives prop scheduling events such as considered,
  skipped, and ran.
- `*phase-observer*`: receives coarse timing events for runner phases.
- `*current-task-fact*`: identifies which task fact is currently being run.

These hooks are for debugging and benchmarking. They are not part of recursive
semantics and should not leak into accumulated network content.

## End-To-End Flow

For a normal `(gur/p:apply-closure closure-id arg-ids out-id)`:

1. The installer creates a fresh `applied-net-id`.
2. It installs `p:accumulate-apply-closure`.
3. It installs `p:run-accumulated-network`.
4. The apply primitive watches the closure, arguments, and output.
5. When the closure and enough boundary values are usable, it emits an
   application-request fragment into `applied-net-id`.
6. Cell merge joins that fragment into the owner network value.
7. The runner sees the owner change, imports boundary cells, and expands the
   request into a deterministic frame fragment.
8. The frame body declares ordinary cells and propagators into the owner net.
9. Task facts tell the runner which props to run.
10. Running those props may emit output values, accessor messages, lazy `when`
    fragments, or more application requests.
11. The runner settles those fragments until no pending task/mailbox work
    remains within the step budget.
12. The runner diffs selected outputs back to parent cells and emits the refined
    owner network back to `applied-net-id`.

## Code-Level Walkthrough

This section follows the actual code path with small examples.

### 1. Define A Recursive Closure

The source-facing form is `gur/def-recursive`:

```clojure
(ns example
  (:require [propagators.gur :as gur]))

(gur/def-recursive double-once
  [x out]
  (::* x 2))
```

That macro expands through `propagators.gur.accumulating.source/def-recursive`.
The macro does not create a host function. It creates a closure value:

```clojure
(def double-once
  (source-recursive-closure
   :double-once
   '[x out]
   '(do (::* x 2))))
```

`source-recursive-closure` then wraps the source body in the small closure map
defined by `core/recursive-closure`:

```clojure
{:gur/accumulating-recursive-closure? true
 :gur/name :double-once
 :gur/body body-fn}
```

The important point: `:gur/body` is not executed when the closure is defined.
It is executed later, when the runner expands one frame. At that moment it
receives concrete frame-local cell ids for `x` and `out`, compiles the source
body into ordinary propagator topology, and returns that declared topology.

### 2. Apply The Closure

The normal public installer is:

```clojure
(gur/p:apply-closure closure-id [x-id] out-id)
```

The implementation in `propagators.gur.accumulating/p:apply-closure` is:

```clojure
(defn p:apply-closure
  [closure-id arg-ids out-id]
  (let [arg-ids (vec arg-ids)
        applied-net-id (ids/new-node-id)]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network
                       (conj (into [closure-id applied-net-id] arg-ids) out-id))
            [apply-prop n1] ((core/p:accumulate-apply-closure
                              closure-id arg-ids applied-net-id out-id)
                             n0)
            [runner-prop n2] ((runner/p:run-accumulated-network
                               applied-net-id
                               (into [closure-id] arg-ids)
                               [out-id])
                              n1)]
        [[apply-prop runner-prop]
         (net/assoc-net-dict-entry
          n2
          (facts/application-key closure-id arg-ids out-id)
          applied-net-id)]))))
```

This code installs two propagators:

- `apply-prop`: watches the closure/args/output and emits an application
  request into the owner cell.
- `runner-prop`: watches the owner/import/output cells and executes the
  accumulated owner network.

It also stores this dictionary entry:

```clojure
[:gur/application closure-id [x-id] out-id] -> applied-net-id
```

That dictionary entry is useful for tests and graph tools. The semantic owner
is still the `applied-net-id` cell.

### 3. The Apply Prop Emits A Request, Not A Frame

The apply prop is installed by `core/p:accumulate-apply-closure`. Its activation
calls `accumulate-apply-messages`:

```clojure
(defn- accumulate-apply-messages
  [runtime-net closure-id arg-ids applied-net-id out-id]
  (let [app-key (facts/application-key closure-id arg-ids out-id)
        applied-net (strongest-or-nothing runtime-net applied-net-id)
        {:keys [closure arg-values out-value]}
        (apply-state runtime-net closure-id arg-ids out-id)]
    (cond
      (facts/frame-declared? runtime-net applied-net app-key) []

      (or (facts/application-requested? runtime-net app-key)
          (and (net/net? applied-net)
               (facts/application-requested? applied-net app-key)))
      []

      (not (enough-information-to-apply? closure arg-values out-value)) []

      (not (recursive-closure? closure)) []

      :else
      [(message applied-net-id
                (facts/application-request-fragment
                 closure-id arg-ids out-id))])))
```

Read this literally:

1. If the frame is already declared, do nothing.
2. If the application was already requested, do nothing.
3. If the closure/args/output are not usable enough yet, do nothing.
4. If the closure cell does not hold an accumulating GUR closure, do nothing.
5. Otherwise, send a message to `applied-net-id`.

The message payload is a tiny named-network fragment:

```clojure
(facts/application-request-fragment closure-id [x-id] out-id)
```

which produces dict content shaped like:

```clojure
{[:gur/accumulating :application-requests]
 {[:gur/application closure-id [x-id] out-id]
  {:closure-id closure-id
   :arg-ids [x-id]
   :out-id out-id}}}
```

So application is monotone declaration. The apply prop does not directly expand
the frame and does not mutate the owner cell.

### 4. Cell Merge Stores The Request In The Owner

The message goes through the normal kernel path:

```text
message -> eval-cell -> cell-merge -> strongest-value -> enqueue neighbors
```

The important design point is that the request becomes content of the
`applied-net-id` cell through normal `cell-merge`. Accumulating GUR does not use
an imperative side table for pending applications.

At this stage the owner network may contain only request facts and no frame
topology yet.

### 5. The Runner Imports Parent Boundary Cells

When the runner wakes, `runner/p:run-accumulated-network` calls:

```clojure
(executor/run-accumulated-messages state
                                   parent-net
                                   applied-net-id
                                   import-ids
                                   external-output-ids
                                   boundary-ids)
```

The executor first prepares a child run net:

```clojure
(prepare-run-net parent-net
                 acc-net
                 applied-net-id
                 import-ids
                 external-output-ids
                 boundary-ids)
```

In natural language, this does three things:

1. Start from the accumulated owner network value.
2. Clear the owner mailbox cell inside the child run.
3. Copy selected parent boundary cell content into same-id child cells.

For example, if `closure-id`, `x-id`, and `out-id` are boundary ids, the child
run gets local cells with those ids and the same content/strongest values as the
parent. This is how the frame body sees the current argument and output
information without reading host data.

### 6. The Runner Expands The Request Into A Frame

Inside `run-accumulated-child`, the first step is:

```clojure
(expand-application-requests child-net
                             applied-net-id
                             request-cache
                             request-scan-cache)
```

For each request, `expandable-request` reads:

```clojure
closure = strongest closure-id
args    = strongest each arg-id
out     = strongest out-id
```

If the closure is an accumulating recursive closure, it calls:

```clojure
(acc/build-frame-fragment n
                          closure-id
                          arg-ids
                          applied-net-id
                          out-id
                          closure
                          arg-values
                          out-value)
```

`build-frame-fragment` computes the same application key:

```clojure
(facts/application-key closure-id arg-ids out-id)
```

Then it computes a deterministic frame scope and self cell:

```clojure
scope   = [:gur/accumulating-frame (:gur/name closure) app-key]
self-id = (stable-node-id [app-key :self])
```

Then it temporarily makes `ids/new-node-id` deterministic for this frame:

```clojure
(with-redefs [ids/new-node-id (acc-ids/stable-id-generator app-key)]
  ...)
```

This is why rerunning the same frame declares the same cells and props instead
of growing fresh duplicates.

The base frame network is built roughly like this:

```clojure
(-> net/empty-net
    (copy-runtime-cell runtime-net closure-id)
    (copy-boundary-cells runtime-net [arg-ids... out-id] [arg-values... out])
    (nb/ensure-cell applied-net-id)
    (ensure-cell-value self-id closure)
    (env/bind-in-scope scope :self self-id)
    (env/bind-in-scope scope :out out-id)
    (bind-frame-args scope arg-ids))
```

Then the closure body declares its topology:

```clojure
((:gur/body closure) ctx base arg-ids out-id)
```

Finally the declared body is recorded as frame facts:

```clojure
(facts/record-frame-fragment body-net app-key scope prop-ids)
```

That adds:

```clojure
[:gur/accumulating :frame-declared app-key] -> true
[:gur/accumulating :frames]                -> #{app-key ...}
[:gur/accumulating :props]                 -> #{prop-id ...}
[:gur/accumulating :tasks]                 -> {[:frame app-key] ...}
[:gur/accumulating :scope app-key]         -> scope
```

### 7. Source Bodies Compile To Ordinary Propagator Topology

Suppose the source closure was:

```clojure
(gur/def-recursive double-once
  [x out]
  (::* x 2))
```

When the frame expands, `source/recursive-definition` runs:

```clojure
(compile/eval-net-output-with-bindings
 frame-net
 (installer-fn runtime)
 {'x x-id, 'out out-id}
 '(do (::* x 2))
 out-id)
```

The result is just normal propagator topology inside the frame network. The GUR
runner does not know what multiplication means. It only knows that the body
returned prop ids, and those prop ids are now task facts.

For a recursive body using `ctx/recur`:

```clojure
(gur/def-recursive countdown
  [n out]
  (let [one 1
        done? (::<= n 0)
        continue? (::not done?)]
    (cond
      done? 0
      continue? (ctx/recur (switch continue? (::- n one)) out))))
```

The source installer for `ctx/recur` calls the contextual runtime function:

```clojure
(:recur runtime) network arg-ids out-id
```

That runtime function is supplied by `core/contextual-api`, and it installs
another `p:accumulate-apply-closure` against the same `applied-net-id`. So a
recursive call is just another request emitter aimed at the same owner network.

### 8. `ctx/when` Lazily Builds More Topology

`ctx/when` exists for the linked-list/HOP case where `cdr = nothing` should mean
"do not build the tail yet."

The source installer eventually calls:

```clojure
(p:when-topology condition-id bindings body-expr installers applied-net-id)
```

The activation is intentionally simple:

```clojure
(cond
  (value/nothing? condition)
  []

  (value/contradiction? condition)
  [(message applied-net-id value/contradiction)]

  (facts/when-applied? runtime-net when-key)
  []

  :else
  [(message applied-net-id
            (delayed-body-fragment runtime-net
                                   when-key
                                   bindings
                                   body-expr
                                   installers))])
```

So:

- `nothing` waits and emits no topology;
- contradiction propagates contradiction to the owner;
- an already-applied `when` does nothing;
- a present value builds the body and emits that body as an accumulated network
  fragment.

This is how map-list can wait for a later `cdr` without treating an
accessor-shell as an empty list.

### 9. Task Facts Drive Execution

Frame expansion records task facts through `facts/add-task-facts`.

A task fact looks conceptually like:

```clojure
{[:frame app-key]
 {:prop-ids #{p1 p2 p3}
  :indexes #{0}}}
```

Boundary or mailbox updates can add later indexes:

```clojure
{[:boundary x-id]
 {:prop-ids #{p1 p2 p3}
  :indexes #{123456}}}
```

`runner.tasks/next-pending-task-fact` compares those task indexes with the
runner-local `task-cursor`. If an index has not been consumed, it returns the
next task to run.

Then `run-props-with-state-cache` considers each prop. If the prop's observed
cell state is unchanged from the previous run, it skips the prop. If something
changed, it calls:

```clojure
(core/eval-propagator prop-id remaining-tasks current-net)
```

That is the ordinary propagator evaluator. GUR is not a separate evaluator; it
is choosing which declared props to feed into the existing evaluator.

### 10. Mailbox Reconciliation Feeds New Facts Back Into The Owner

While child props run, they may emit messages to `applied-net-id`. Inside the
child run, `applied-net-id` acts as the owner mailbox. Its strongest value may
be a named-network fragment containing:

- new application requests;
- lazy `when` body topology;
- external messages;
- task facts.

`settle-accumulated-child` loops:

1. run child tasks;
2. read the mailbox;
3. prune requests whose frames are already declared;
4. add mailbox task facts when the mailbox contains new runnable props;
5. reset the mailbox to `nothing`;
6. merge the mailbox fragment into the child net;
7. repeat until no mailbox work remains.

This is why a newly emitted recursive request can be expanded and run in the
same runner activation instead of waiting for a later parent scheduler turn.

### 11. Outputs Leave Through Diffs And Messages

At the end of `run-accumulated-messages`, selected child cells are externalized:

```clojure
(output/externalize-output-cells child1 boundary-output-ids)
```

Then diffed against the parent:

```clojure
(diff/diff-internal-output-cells diff-view
                                 parent-net
                                 boundary-output-ids)
```

The resulting messages update the parent output cells through normal cell
merge. The runner may also export:

- accessor neighbor messages from scoped slot routing;
- explicit external messages from the child queue;
- the updated accumulated owner network back to `applied-net-id`.

So the outer graph is still changed only by messages.

## Important Invariants

- **One owner per application**: one `applied-net-id` owns all accumulated frame
  facts for that application.
- **No nested frame-net semantic regression**: recursive calls add facts to the
  same owner network instead of making nested child owner cells.
- **Declaration and evaluation stay separate**: accumulated networks store
  declarations and task facts; runner atoms store runtime cursors.
- **Propagators emit messages/facts only**: recursive apply/recur logic does not
  directly mutate cell entries.
- **Cell merge owns joins**: accumulated fragments become content through normal
  `cell-merge`.
- **Deterministic ids are required**: frame and lazy-body declarations must
  redeclare the same ids when the same semantic application is seen again.
- **Task facts are monotone**: later task indexes can make the same cause
  runnable again; the runner cursor decides what this primitive has consumed.
- **`ctx/when` is topology-lazy**: `nothing` means wait; non-`nothing` builds
  the body once for that deterministic key.
- **Accessor routing remains scoped**: accumulating GUR reuses subenv scoped
  address conventions, but not subenv's nested-owner semantic model.
- **Failures should stay visible**: step-budget exceptions and contradictions
  are design evidence, not noise to hide.

## What This Does Not Own

Accumulating GUR does not own behavior versioning, behavior retention, TMS
support sets, or garbage collection. Behavior-aware propagators and behavior
cell merge own time/version policy. A future TMS should own support,
justification, and retraction. Compound objects own structural accessors and
slot topology. GUR only owns recursive declaration and execution of accumulated
network facts.

## Current Evidence

The main evidence lives in `test/propagators/gur_accumulating_test.clj` and
`test/propagators/compiler_2_gur_linked_list_test.clj`.

Covered scenarios include scalar recursion, list map/reduce/filter, nested map,
late cdr routing, `obj/p:cons` HOP mapper depths `5/10/15`, filter depths
`5/10`, idempotent rerun checks, and a compiler-2 linked-list lexical-access
prototype returning `15`.
