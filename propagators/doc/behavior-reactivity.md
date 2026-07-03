# Behavior Reactivity

Source files:

- `propagators/datastructures/behavior.clj`
- `propagators/datastructures/behavior_algebra.clj`
- `propagators/datastructures/reducer_cell.clj`
- `propagators/datastructures/tms.clj`
- `propagators/stdlib/arithmetic/behavior.clj`
- `propagators/compiler_common/core.clj`
- `propagators/compiler_behavior/core.clj`
- `propagators/compiler_behavior/application.clj`
- `propagators/compiler_2/helpers.clj`
- `propagators/datastructures/compound_object.clj`
- `propagators/cells/cell_protocol.clj`
- `test/propagators/behavior_algebra_test.clj`
- `test/propagators/behavior_arithmetic_test.clj`
- `test/propagators/behavior_compiler_test.clj`
- `test/propagators/behavior_test.clj`
- `test/propagators/compile_2_test.clj`
- `test/propagators/reducer_cell_test.clj`
- `test/propagators/tms_test.clj`

## Status

This is the current reactive behavior and TMS experiment. It does not change the
scheduler kernel. Behavior support is installed as a normal network-local cell
protocol, like intensity and dependency values. TMS support is a reducer-cell
specialization: it stores monotone claim and premise facts in ordinary cells and
projects the currently active truth view through strongest.

The important current boundary is:

- behavior owns temporal retention and current-value projection;
- TMS owns support/premise selection and retraction-like projection;
- compound objects own slots and structural transport;
- compiler-2 can compile behavior arithmetic and test-local TMS primitives, but
  there is no public combined behavior/TMS syntax yet;
- the kernel still only merges messages, computes strongest, and wakes
  neighbors.

## Model

A behavior source is a sparse compound object of timestamped events. Events are
attached with `p:event`, which is just `obj/p:slot` under an internal event slot
key. The source cell therefore stays an ordinary compound-object collection.

`p:behavior` reduces that sparse event collection into a retained history view:

```clojure
(p:behavior source-id merge-net-id init-id out-id)
```

The reducer is responsible for retention. It may keep every point event, emit
constant/open intervals, or keep a bounded window. Nothing in the kernel deletes
history; a newer retained view supersedes an older one through behavior merge
evidence.

## Content vs Strongest

Behavior cells use the existing cell split deliberately:

| Cell field | Behavior meaning |
|------------|------------------|
| `content` | retained sparse history compound object |
| `strongest` | layered summary with current/latest value in `:base` |

The retained history itself is not a Clojure map as a public value. It is a
compound-object map whose public slots are temporal records:

```clojure
6  -> {:at 6 :value :x}
10 -> {:at 10 :value :y}
```

For constant histories, the public slots are segment starts:

```clojure
0 -> {:from 0 :to 6 :value value/nothing}
6 -> {:from 6 :to :infinity :value :x}
```

The strongest projection is also a compound-object layered value:

```clojure
{:base latest-value
 :behavior/summary {:behavior/latest-time t
                    :behavior/retained-count n
                    :behavior/retained-interval {:from first-retained
                                                 :to latest-retained}}}
```

This summary is necessary because the scheduler wakes and stores cells based on
strongest changes. If a late event expands history but does not change the latest
value, the summary still changes through the retained count or retained interval.
That lets the kernel remain unchanged while behavior content grows monotonically.

The summary is deliberately small. It does not duplicate source keys, reducer
identity, or retained history keys. Those details remain in cell content and in
hidden behavior metadata used by `cell-merge`. Operators that care about history
should pull the retained compound-object history from cell content. Ordinary
operators can use `:base` and ignore the summary.

## Why This Shape

The goal is to treat reactivity as a reducer over monotone temporal facts, not
as mutation or deletion. Incoming events only add knowledge. A reducer decides
what retained view should currently represent that knowledge: all point events,
a sparse window, or explicit intervals. Forgetting is therefore a reducer
result, not garbage collection in the kernel.

The runtime kernel already has a useful discipline: cells keep `content`, expose
`strongest`, and wake neighbors only when strongest changes. Behavior values fit
that discipline by putting durable history in content and a small scheduling
summary in strongest. The summary is only large enough to say "the current value
or retained history window changed." It is not the history itself.

This keeps three boundaries clear:

- declaration stays separate from evaluation;
- behavior retention is domain policy in the reducer;
- history-aware operators opt into inspecting content instead of forcing every
  ordinary operator to carry history.

## Reducer State

The reducer accumulator is also slot-addressable. `empty-history-state` returns a
compound-object layered value with:

```clojure
:behavior/events  -> compound-object event evidence
:behavior/history -> compound-object retained history
```

Reducer helper code may convert these slots to Clojure maps internally for
sorting and windowing, but the accumulator value passed between reducer rounds is
a compound object. Tests assert this with `behavior-history-state-is-slot-addressable`.

## Reducer Policies

Current reducer helpers:

- `event-history-reducer-net`: retained point-event history. Sparse events stay
  point facts; an event at `6` does not imply `6 -> infinity`.
- `constant-history-reducer-net`: explicit constant/open intervals. If the first
  observed event is after `0`, the reducer emits an explicit `value/nothing`
  interval before it.
- `window-history-reducer-net n`: keeps the last `n` retained point records while
  the strongest summary records the retained interval and count.
- `retained-value-reducer-id domain`: keeps every retained point version while
  strongest still exposes the latest one.
- `dominant-source-reducer-id domain tag`: lets one tagged source dimension
  dominate replacement. Behavior closure application uses this so a newer
  operator/closure version can replace an older application result even when the
  closure body depends on different lexical cells.

Different reducer ids in one behavior output cell contradict in v1. Equal source
evidence with unequal retained history also contradicts. A behavior update whose
source evidence is a superset replaces the older retained view, except for
explicit retained-version reducers that accumulate version history.

## General Reducer Cell

`propagators.datastructures.reducer-cell` is a parallel, smaller reducer value
for slotful evidence:

```clojure
{:reducer/id id
 :reducer/merge-net merge-net
 :reducer/strongest-net strongest-net
 :reducer/slots slots}
```

Cell merge owns the retained `:reducer/slots` map, but the retention policy is
itself a reducer network. Matching reducer id, merge-net, and strongest-net
updates run the merge net over:

```clojure
:content -> current retained slots
:update  -> incoming slot update
:out     -> new retained slots
```

Different reducer ids or reducer nets contradict. The strongest view runs the
strongest net once as a pure projection and exposes:

```clojure
{:reduced/dependence dependence
 :reduced/epoch epoch
 :reduced/result result}
```

The reducer net must bind `:slots` and `:out` in its dict. It may also bind
`:dependence` and `:epoch`; otherwise the strongest view uses a default
dependence of `#{[:reducer/id id]}` and a deterministic epoch derived from the
reducer id, reducer net identity, and merged slots.

This is not a migration of the existing behavior protocol. It is the shared
shape we can specialize later:

- behavior events can be reducer slots, with the reducer net computing retained
  history and current value;
- lexical traversal can emit candidate slots, with the reducer net choosing the
  nearest candidate and recording selected scope dependence;
- TMS support puts claims, supports, and premise states in slot values, while
  strongest projects the currently active result and dependence.

The current tests cover reducer-cell merge/idempotence/conflict behavior, raw
projection through `p:reduced-result`, installer helpers, a lexical-candidate
selection probe, and a flat-GUR traversal that emits reducer slots while walking
a live `p:cons` list. Behavior still has its established cell protocol, and TMS
currently uses reducer-cell directly.

## Reducer-Cell TMS Experiment

`propagators.datastructures.tms` specializes reducer-cell into a small truth
maintenance projection. It does not add a kernel TMS. Claims and premise states
are compound-object values stored as ordinary reducer slots:

```clojure
(tms/claim :c1
           :answer
           10
           [(tms/support :a
                         (scoped/name-ref [:scope :child] 'x)
                         :lexical/read)])

(tms/premise-state :a 0 true)
(tms/premise-state :a 1 false)
```

The claim, support, and premise-state records expose their fields through
compound-object slots. A support has:

```clojure
{:support/premise premise
 :support/source source
 :support/kind kind}
```

The `:support/source` value may be a `propagators.scoped-address/name-ref`.
The focused test registers a child scope with `gur.subenv.env` and verifies that
the same source address resolves through `env/resolve-dispatch` to
`[:dispatch/subenv owner local]`. That means provenance can carry the same
scoped addresses used by sub-env routing. It does not mean the TMS projection
executes lexical routing itself; reducer strongest still decides the active
truth view from retained slots.

The TMS merge net retains raw claim/premise facts and also normalizes latest
premise state into `[:tms/latest-premise premise]` slots. The reducer strongest
view computes:

- active premise state from those merge-retained latest-premise slots;
- active claims whose supports are all active;
- proposition entries, including conflict when active claims for one proposition
  justify different values;
- reduced dependence and epoch metadata through the strongest net.

This gives retraction-like behavior at the reducer-cell strongest boundary:
adding a later false premise does not delete old cell content, but the projected
TMS view can change from `:answer -> 10` to `:answer -> nothing`.

Raw projection to an ordinary output cell is intentionally weaker.
`tms/p:tms-proposition` can tell the currently active value to a plain cell, but
if a later TMS epoch makes that proposition inactive, the old plain value cannot
be retracted by sending `nothing`. TMS-aware consumers should inspect the reducer
cell strongest view or use a future TMS-aware cell protocol, not rely on a raw
plain-cell adapter for retraction.

The recursive traversal test follows the intended no-materialization shape:
a flat-GUR recursive declaration walks a live linked list with `i/car` and
`i/cdr`, then emits each claim as slotful reducer evidence with
`i/reducer-slot`. The reducer does not inspect or materialize the source list.

This makes the current TMS experiment a useful bridge for future lexical/TMS
work: recursive traversal can emit compound claim/support facts, support sources
can point at scoped sub-env addresses, and reducer strongest can project a
truth view with dependence/epoch metadata. The missing piece is still a
repo-wide TMS-aware cell protocol for retraction/justification; plain output
cells remain monotone.

## Behavior + TMS Composition

The current composition point is not a new behavior runtime. It is the fact that
TMS claim values are ordinary compound-object values, so a claim can carry a
behavior value:

```clojure
(tms/claim :left-behavior
           :behavior
           (behavior/latest-value 0 :left #{[:definition :left]})
           [(tms/support :left [:premise :left] :test/support)])
```

The behavior value keeps its retained history and strongest/current-value rules.
The TMS view decides whether that behavior is currently active by looking at the
latest premise state:

```clojure
(tms/premise-state :left 0 true)
(tms/premise-state :left 1 false)
(tms/premise-state :right 1 true)
```

This means one TMS reducer cell can hold several behavior-valued definitions
with different premises. Bringing one premise in and kicking another out does
not delete any behavior or premise facts. It only changes the reducer strongest
projection:

```text
left active, right inactive  -> proposition :behavior is left behavior
left inactive, right active  -> proposition :behavior is right behavior
left active, right active    -> proposition :behavior is contradiction
```

That is the tested shape in `tms-selects-between-behavior-valued-claims`. It
proves that a TMS cell can select between behavior histories, and that
retraction-like behavior comes from latest premise epochs rather than mutation.

The inverse composition is also the intended direction but is not yet promoted
to a public helper: behavior events can carry TMS support metadata, or a
behavior stream can emit premise-state facts into a TMS reducer cell. Those are
domain-level propagators on top of reducer-cell; they do not require scheduler
changes.

Plain output cells are still the wrong boundary for retraction. If a TMS
projection writes a behavior value to a normal cell and a later premise retracts
it, the old normal-cell value remains monotone content. Consumers that need the
current active behavior must inspect the TMS reducer-cell strongest view or use
a future TMS-aware behavior cell protocol.

## Compiler-2 + TMS + Behavior Status

Compiler-2 currently covers these pieces:

- behavior arithmetic can be compiled through `behavior-env`;
- `execute-sub-env` can compile a behavior expression in a child environment and
  react to later behavior input updates;
- compiler-2 now keeps the compiler-facing TMS/behavior operators in
  `propagators.compiler-2.tms-behavior`;
- `propagators.compiler-2.main/compile-source-with-behavior-tms` compiles with
  the behavior+distributed-TMS env by default;
- compiler-2 default envs use distributed TMS primitives: premise/content input,
  premise believe/retract, `tms-closure`, and distributed `premise-closure`;
- `distributed-premise-closure` remains as the explicit long name for the same
  distributed sugar;
- centralized `premise-closure` is legacy compatibility only and is exposed
  through a legacy env helper for old reducer-cell storage tests;
- distributed `premise-closure` runs the wrapped network through a hidden output
  and emits only the premise-marked distributed update to the explicit output
  cell;
- test-local TMS primitives can still be called from compiler-2 expressions to
  emit premise states, claims, and TMS insert facts;
- compiler-2 tests cover multi-round premise bring-in/retraction, arithmetic
  propagator chains, closure application plus premise-marked outputs, and
  `p:cons` / `p:car` / `p:cdr`-based insertion.

What is not yet present is a full compiler-2 syntax that says "compile this
behavior-producing expression as a TMS-supported behavior definition" with
implicit storage and epoch policy. For now, the stable substrate is explicit:
behavior values, distributed `premise-closure`, distributed premise
annotations, and legacy reducer-cell TMS slots where old callers opt in.

## History Algebra

`propagators.datastructures.behavior-algebra` defines pure operations over
already-retained history records. It does not reduce event sources and it does
not decide retention. That remains the behavior reducer's job.

The algebra is intentionally idempotent. It borrows the differential-dataflow
idea of joining compatible facts and consolidating duplicate results, but it
does not use multiset multiplicities. Behavior history is monotone knowledge:
repeating the same temporal fact is the same fact, not a stronger weighted row.

Current operations:

- `consolidate`: remove duplicate temporal facts.
- `history-union`: set-like union of retained history records.
- `history-map-values`: transform payload values while preserving temporal
  record shape.
- `history-negate-values`: numeric negation of payload values, preserving time.
- `history-join`: binary temporal synchronization with an arbitrary combiner.
- `history-join-all`: n-ary temporal synchronization across every input
  history.
- `history-add-values`: `history-join` with numeric `+`.

Temporal join is explicit about continuity:

- point with point joins only at the same tick;
- interval with interval joins on half-open overlap `[from, to)`;
- point with interval joins only when the point lies inside the interval;
- a point event is never treated as continuing to infinity.

`history-join-all` repeatedly applies this same rule so an output fact exists
only where every input history has a compatible temporal fact:

```clojure
6 -> 2
6 -> 7
6 -> 10
;; joined with + => 6 -> 19

6 -> 2
7 -> 7
6 -> 10
;; joined with + => no output fact
```

For intervals, n-ary join emits the shared intersection:

```clojure
{:from 0 :to 10 :value 1}
{:from 4 :to 12 :value 2}
{:from 6 :to 8  :value 3}
;; joined with + => {:from 6 :to 8 :value 6}
```

Keyed joins are supported by passing `:key-fn`, or separate `:left-key-fn` and
`:right-key-fn`, to `history-join`. This gives the useful shape of
differential-dataflow joins while keeping behavior histories idempotent.

The borrowed idea from differential dataflow is structural: join compatible
facts, transform payloads, and consolidate duplicate results. The multiset part
is intentionally not borrowed. Behavior history is idempotent because it is
knowledge retained by a cell; observing the same fact twice should not make that
fact stronger.

There is no history-level `reduce` operation. A behavior is already built by a
reducer, so algebraic operators should transform or synchronize retained facts,
emit new facts into an output behavior source, and let that output behavior's
reducer decide the retained view.

## Behavior Arithmetic

`propagators.stdlib.arithmetic.behavior` provides behavior-history arithmetic in
parallel with primitive arithmetic. These are normal propagator installers:

```clojure
(behavior-arithmetic/+ a b out)
(behavior-arithmetic/+ a b c out)
(behavior-arithmetic/negate a out)
(behavior-arithmetic/- a b out)
(behavior-arithmetic/* a b out)
(behavior-arithmetic// a b out)
```

They are built on the general `behavior-arithmetic/behavior-propagator`: all
node ids except the last are behavior inputs, and the last node id is the output.
On activation, it reads retained behavior history from input cell content,
synchronizes all input histories through `history-join-all`, applies the
operator to the joined payload values, and emits a new behavior value to the
output cell. Unary operators use the same path with one input. The core
primitive arithmetic namespace remains unchanged.

For point-event histories, arithmetic only combines values at the same timestamp:

```clojure
{:at 6 :value 2} + {:at 6 :value 7}
;; => {:at 6 :value 9}

{:at 6 :value 2} + {:at 7 :value 7}
;; => no output fact
```

For interval histories, arithmetic combines only the overlapped interval:

```clojure
{:from 0 :to 10 :value 2}
+ {:from 5 :to 12 :value 7}
;; => {:from 5 :to 10 :value 9}
```

For more than two inputs, every input must participate at the same point or
shared interval:

```clojure
{:at 6 :value 2}
+ {:at 6 :value 7}
+ {:at 6 :value 10}
;; => {:at 6 :value 19}
```

This gives behavior-aware operators the useful join shape from
differential-dataflow, while preserving behavior's idempotent set-like history.

## Compiler-2 Integration

Compiler-2 can use behavior arithmetic by compiling with
`propagators.compiler-2.helpers/behavior-env`. This environment binds arithmetic
symbols such as `+`, `-`, `*`, and `/` to behavior-aware operators while keeping
the surface source unchanged:

```clojure
(compile-source "(+ a b)" (behavior-env) {:net behavior-net})
```

The compiled application still follows compiler-2's retained application model:
the application object records the operator, arguments, output, and context.
During evaluation, the operator's `application-activate` metadata calls the same
`behavior-arithmetic/behavior-messages` path used by direct stdlib behavior
propagators. That means compiled and hand-wired behavior arithmetic share the
same temporal semantics.

For example, if `a` and `b` are behavior cells:

```clojure
a: {:at 6 :value 2}
b: {:at 6 :value 7}

(+ a b)
;; => {:at 6 :value 9}
```

If the inputs later gain a new shared timestamp, normal scheduler activation
updates the compiled output history:

```clojure
a: {:at 6 :value 2}, {:at 8 :value 3}
b: {:at 6 :value 7}, {:at 8 :value 10}

(+ a b)
;; => {:at 6 :value 9}, {:at 8 :value 13}
```

## Behavior Compiler V1

`propagators.compiler-behavior.main` is a parallel compiler for behavior-valued
programs. It reuses compiler-2's AST, parser, env, closure-info data, and
application IR, but routes evaluation through behavior arithmetic and behavior
application.

Compiler-neutral graph-building mechanics live in
`propagators.compiler-common.core`: sequence compilation, symbol lookup,
argument compilation, argument/operator object installation, application IR
recording, and result annotation. Compiler-2 uses those helpers unchanged for
current-value behavior, while the behavior compiler supplies different literal,
closure, and application semantics.

Behavior compiler v1 semantics:

- literals compile to constant behavior histories;
- externally bound symbols are expected to be behavior-valued cells when callers
  want behavior semantics;
- arithmetic applications use behavior arithmetic and `history-join-all`;
- closures compile to closure-info data wrapped in a behavior value;
- closure behavior retains point-version history while strongest exposes the
  latest closure payload;
- `compile-expr` and `compile-source` accept `{:timestamp t}` so compiler-emitted
  literal and closure behavior facts can be versioned explicitly;
- closure application evaluates temporally overlapping closure/input histories
  when available, and falls back to latest retained closure application for
  timestamp-as-version updates with no overlap;
- application output uses the operator/closure version as a dominant source, so
  updating a closure definition can replace the old result even when the new
  closure body depends on different lexical behavior cells.

The closure choice is still conservative: closure definitions retain version
points. When those points overlap argument history, each closure version applies
to its matching input segment. Updating the closure cell with a newer closure
payload still changes later/refired applications through the latest-closure
fallback when version points do not overlap the input history.

The timestamp option is compile metadata, not scheduler time. It says "the facts
emitted by this compilation are version `t`." This lets a program compile an
application once, then separately compile closure definitions at later
timestamps and merge those closure behavior values into the existing operator
cell. Normal propagation then re-runs the already-declared application and emits
the result for the latest retained closure version.

For example, an application can be declared before its operator has a concrete
closure value:

```clojure
;; compile once
(compile-source "(f a)" env {:net n})

;; later definition facts enter the same f cell
(compile-source "(:: [x] 1)" env {:timestamp 0}) ; f a => 1
(compile-source "(:: [x] 2)" env {:timestamp 1}) ; f a => 2
```

The input `a` does not need to change. The closure cell's strongest summary
changes because version `1` becomes the latest retained closure payload, so the
already-declared application propagator wakes and produces the new output.

Behavior closure application preserves the boundary discipline from compiler-2:
the body is evaluated in an activation-local network, and only the declared
result/output behavior is copied back to the application output. Inner locals do
not write to outer cells except through that output. The selected closure-info
payload still carries its lexical env and scope metadata, so an updated closure
version is applied with its own retained lexical environment rather than the
caller's accidental bindings.

## Compound Accessor Reactivity Experiment

Experiment date: 2026-06-11

Goal:
- verify that behavior values can move through the current compound-object
  accessor path, `obj/p:slot` / `p:network-slot`, without collapsing retained
  behavior content into only the current strongest projection.

Setup:
- source-slot projection: a collection source contains one behavior value under
  a map slot, and an accessor reads that slot;
- peer accessor sync: one accessor writes a behavior value into a compound slot
  and another accessor reads the same slot;
- event pipeline: `p:event -> p:behavior -> compound slot writer -> accessor
  reader`, then a later event updates the original behavior source.

Observed pre-fix failure:
- direct source-slot projection worked because `source-slot-messages` emitted
  the full source value;
- peer accessor sync failed with a `Long`/`Keyword` sort error in
  `behavior/latest-record`, because generic `p:id` sync copied the behavior
  strongest summary into another behavior cell's content. That mixed temporal
  history keys with summary/layer keys and broke retained-history projection.

Implementation result:
- demand-driven accessor peer sync now uses content-copy bi-sync for accessor
  avatars. It copies cell content through ordinary messages instead of copying
  strongest values through `p:id`.
- Declaration and evaluation remain decoupled: accessor declarations still store
  durable route/sync topology in the collection network; evaluation still runs
  activation-local accessor frames; results still leave through messages; taps
  and task frontiers are not persisted.
- Behavior-valued accessors now preserve retained behavior content and source
  evidence while the accessor cell's strongest still exposes the current/base
  projection.

Verification:
- `clojure -M:test propagators.behavior-test`
  - `56` pass, `0` fail, `0` error
- `clojure -M:test propagators.compound-object-network-slot-test`
  - `20` pass, `0` fail, `0` error
- `clojure -M:test propagators.compound-object-test`
  - `103` pass, `0` fail, `0` error
- `clojure -M:test`
  - `815` pass, `0` fail, `0` error

Remaining limits:
- this proves behavior-valued accessor projection and peer sync; it does not add
  a general dependence-tracked slot boundary;
- deprecated `compound_data.clj` remains unchanged;
- content-copy sync is intentionally used for demand-driven network-slot
  accessors, not as a global replacement for every primitive `p:id` relation.

This is also the intended responsibility boundary. Compound objects are the
structural carrier for behavior values: they own slots, accessors, and
bidirectional structural sync. They must preserve retained behavior content and
source evidence when behavior values move through slots. They do not need to
implement behavior reactivity internally. Time/version retention, latest
projection, windowing, and dominant-source replacement remain behavior reducer
and behavior merge policy.

## Kernel Boundary

This design intentionally does not change `propagators/core.clj`.

The kernel contract remains:

1. merge a message into cell content
2. compute strongest
3. store the merged cell only when strongest changes
4. wake downstream propagators only when strongest changes

Behavior therefore makes knowledge growth visible in strongest by carrying a
summary layer. History-aware operators can inspect cell content directly when
they need the retained sparse history. Ordinary operators can consume the
current value from `:base`.

If a future behavior domain needs to retain content changes whose strongest
summary does not change, that is a new kernel discussion and should not be
introduced as a hidden behavior change.

## Tests

`test/propagators/behavior_test.clj` covers:

- reducer state is slot-addressable via compound-object layers
- event before reducer installation
- reducer before later event
- behavior values moving through compound-object accessors without losing
  retained history
- late out-of-order events updating retained history without changing latest
- duplicate and conflicting same-tick events
- point-event histories do not imply continuation
- constant histories emit explicit open intervals
- window retention is reducer behavior
- behavior merge/strongest protocol rules

`test/propagators/behavior_algebra_test.clj` covers:

- idempotent consolidation and union
- value negation without changing temporal shape
- point, interval, point/interval, and open-interval joins
- keyed joins
- n-ary joins over points and intervals

`test/propagators/behavior_arithmetic_test.clj` covers:

- same-timestamp point arithmetic
- no implicit continuation across different point timestamps
- interval-overlap arithmetic
- late same-timestamp evidence updating output history
- unary negation and subtraction
- variadic behavior arithmetic over all input arguments

`test/propagators/compile_2_test.clj` covers compiler-2 behavior integration:

- compiled behavior arithmetic over same-timestamp point histories
- compiled behavior arithmetic refusing different point timestamps
- compiled interval-overlap arithmetic
- late behavior input updates re-firing compiled applications
- `execute-sub-env` compiling behavior arithmetic in a child env
- distributed compiler-2 TMS premise/source/epoch primitives used from compiled
  expressions
- `main/compile-source-with-behavior-tms` as the behavior+distributed-TMS
  default compiler entrance
- multiple premise bring-in/retraction rounds through the same compiled network
- TMS over an arithmetic propagator chain
- legacy centralized `premise-closure` sugar for premise-marked declared-output
  closures, without patching `p:apply-application`
- `distributed-premise-closure` sugar for premise-marked distributed TMS
  outputs, including definition retraction/bring-in and upstream premise
  retraction
- lower-level closure application with premise-marked outputs, without patching
  `p:apply-application`

`test/propagators/behavior_compiler_test.clj` covers behavior compiler v1:

- behavior arithmetic through the parallel compiler
- literals as constant behavior values
- closure declarations as retained-version behavior values
- applying the latest retained behavior closure to behavior arguments
- closure cell updates affecting later/refired applications
- repeated same-closure updates increasing closure history
- closure updates preserving lexical scope information
- timestamped closure definition compilations updating a once-compiled
  application
- closure locals staying isolated from outer cells except through output

`test/propagators/tms_test.clj` covers reducer-cell TMS:

- active claims selected by latest premise states
- claim/support/premise-state facts as compound objects
- support source values reusing scoped-address routing data
- premise source cells feeding premise identity from the network
- later premise epochs projecting `nothing` without deleting old facts
- recursive linked-list claim ingestion without materializing the list
- behavior-valued claims selected by TMS premise bring-in/retraction

Regression command:

```sh
clojure -M:test propagators.behavior-algebra-test
clojure -M:test propagators.behavior-arithmetic-test
clojure -M:test propagators.behavior-compiler-test
clojure -M:test propagators.behavior-test
clojure -M:test propagators.compile-2-test
clojure -M:test propagators.tms-test propagators.reducer-cell-test
clojure -M:test
```
