# Recursive Compound Propagator

Source files:

- `propagators/recursive.clj`
- `propagators/compile.clj`
- `propagators/datastructures/compound_object/map.clj`
- `propagators/datastructures/compound_object/reduce.clj`
- `propagators/datastructures/reducer_subnet.clj`
- `test/propagators/recursive_compound_test.clj`

## Status

Terminology (canonical):

- Direct recursive activation
  - `recursive/recursive-closure` + `recursive/p:recursive-compound` path that
    expands and executes an inner network during activation.
  - In tests/logs this is the existing `direct recursive activation` behavior.

- Network-valued recursion
  - `closure/p:apply-network` path that emits a declaration network value without
    running it; execution is performed by the outer scheduler.

- Self-refining closure
  - Recursive design where the closure cell is also the accumulator (`p:self-refining-recursive-compound`).

- Explicit accumulator recursion
  - Recursive design with a separate accumulator net (`p:accumulating-recursive-compound`).

Use these terms consistently in discussion/docs and avoid `directed recursion` as a separate term.

Recursive compound propagation is implemented as activation-local network
expansion. It does not change the core scheduler contract and does not introduce
a new primitive data type.

The original propagator remains the compatibility path:

```clojure
(recursive/recursive-closure step-f)
(recursive/recursive-closure step-f {:max-depth 1024})
(recursive/p:recursive-compound closure-id arg-id out-id)
(recursive/p:recursive-compound closure-id [arg-id ...] out-id)
```

The experiment on 2026-06-09 added retained semantic frame declarations:

```clojure
(recursive/frame-fragment frame slot-values)
(recursive/frame-index frame-net)

(recursive/p:self-refining-recursive-compound closure-id arg-id out-id)
(recursive/p:accumulating-recursive-compound closure-id arg-id acc-id out-id)
(closure/p:apply-network closure-id network-id out-id)

(obj/p:map-slots-with-recursive-closure closure-id source-id out-id)
(obj/p:map-slots-with-recursive-accumulator closure-id acc-id source-id out-id)
(obj/p:reduce source-id merge-net-id init-id out-id)

(obj/install-declared-nested-recursive-map-with-closure
 network closure-id source-value out-id)
(obj/install-declared-nested-recursive-map-with-accumulator
 network closure-id acc-id source-value out-id)

(obj/install-accessor-nested-recursive-map-with-closure
 network closure-id source-id source-shape out-id)
(obj/install-accessor-nested-recursive-map-with-accumulator
 network closure-id acc-id source-id source-shape out-id)
```

`p:recursive-compound` is a normal propagator installer. Its activation reads a
closure-valued cell, creates boundary avatars, exposes a self closure under the
activation-local network dict key `:recursive/self`, applies the closure, runs
the inner network to quiescence, and diffs avatar output back to the real output
cell.

## Current Direction

The current evidence points to network-valued expansion as the default recursion
direction.

The default recursion shape should be:

```text
recursive request
-> closure/p:apply-network expands declaration network
-> expanded network contains stable named declarations and accessor topology
-> caller runs the expanded topology through the ordinary scheduler
```

Direct recursive activation remains useful as a compatibility and prototyping
path. It works for Fibonacci and the nested reduce accessor experiment, but it
interleaves topology expansion with evaluation. In the mixed nested map/vector
experiment, that interleaving can produce localized contradictions for nested
vector outputs. Network-valued expansion avoided that failure by declaring the
complete accessor topology before evaluation.

## Runtime Invariants

The experiment keeps these constraints explicit:

- Network declaration is separate from network evaluation. Recursive steps build
  ordinary propagator topology and return a network value. Evaluation happens by
  running that network to quiescence at the activation boundary.
- The outer scheduler still sees only propagator messages. Recursive activations
  do not imperatively edit the outer network.
- Retained recursive state is named-network declaration data. Frame facts are
  stored under deterministic dict keys like `[:recursive/frame frame slot]`.
- Accumulation is monotone. New frame facts are joined with old frame facts by
  `named/join`; incompatible facts become contradiction instead of replacing
  older facts.
- Accumulation is idempotent at the named interface. Frame fact cell ids are
  deterministic from `[frame slot]`, so re-declaring the same fact does not keep
  creating semantically new named commitments.
- Successful recursive computation is composed out of propagators. The
  Fibonacci experiment uses `prop/switch` for the base case and `prop/+` for the
  recursive combine. Direct writes are reserved for evidence seeding and failure
  boundaries such as contradiction or max-depth overflow.

## Why This Fits The Runtime

Normal propagators return messages, not graph edits. Recursive propagation
therefore cannot mutate the outer graph during `eval-propagator` without
changing the core runtime contract.

Instead, recursion happens inside the inner network value:

```text
outer cells
-> avatar frame
-> closure applies to activation-local network
-> closure may install recursive compounds into that network
-> inner run-tasks reaches quiescence
-> changed avatar output becomes an outer message
```

This keeps recursive work isolated from the outer scheduler. The recursive
network can expand and resume because a network is ordinary immutable data.

## Recursive Closure Step

`recursive-closure` wraps a step function. The step receives:

```clojure
{:closure-net closure-net
 :self-id self-id
 :acc-id acc-id
 :input-ids input-ids
 :output-ids output-ids
 :network network
 :depth depth}
```

The step returns either an updated activation-local network or:

```clojure
{:network updated-network
 :frame-fragment named-network-fragment}
```

Recursive calls are ordinary topology installs against `self-id`:

```clojure
(recursive/p:recursive-compound self-id n-minus-1 fib-1)
(recursive/p:recursive-compound self-id n-minus-2 fib-2)
```

Each recursive activation increments `:recursive/depth` in the closure net. If
the configured `:max-depth` is reached, the recursive closure writes
`contradiction` to its output avatars.

## Named Frame Declarations

A recursive frame is named by semantic data, for example:

```clojure
[:fib 5]
```

The frame can declare monotone facts:

```clojure
(recursive/frame-fragment
 [:fib 5]
 {:input 5
  [:child 0] [:fib 4]
  [:child 1] [:fib 3]
  :combine :+
  :output 5
  [:status :expanded] true
  [:status :done] true})
```

Status uses separate monotone facts such as `[:status :expanded]` and
`[:status :done]`. It intentionally does not update one `:status` slot from
`:expanded` to `:done`, because that would be non-monotone.

These fragments are normal named networks. Subsumption is checked with
`named/named-network->=`, and merging is `named/join`.

## Two Accumulation Designs

### Self-Refining Closure

`p:self-refining-recursive-compound` treats the closure cell as both input and
output. Recursive calls update the retained closure net, and closure cell merge
uses `named/join` on compatible closure nets.

This is concise at the call site:

```clojure
(recursive/p:self-refining-recursive-compound self n-1 fib-1)
```

It is expressive when the closure itself should remember its semantic expansion.
The cost is that closure values now need a structural merge rule, and the
closure cell changes as semantic frames accumulate.

### Explicit Accumulator

`p:accumulating-recursive-compound` keeps the closure stable and sends retained
frame declarations through a separate named-network accumulator cell.

The recursive call has one extra port:

```clojure
(recursive/p:accumulating-recursive-compound self n-1 acc fib-1)
```

This makes dataflow more explicit and keeps closure identity simpler. It is the
better default for higher-order compound-object operations because map/reduce
style operators can thread one accumulator through many recursive applications.

### Comparison

| Design | Retained state | Merge path | Expressiveness | Main cost |
| --- | --- | --- | --- | --- |
| `p:recursive-compound` | None | Output diff only | Computes recursive values | No semantic frame history |
| Self-refining closure | Closure net | Closure merge + `named/join` | Closure carries its own expansion | More coupling in closure cell merge |
| Explicit accumulator | Separate named-network cell | Cell merge + `named/join` | Best fit for higher-order map/reduce | One extra accumulator port |

The chosen direction for further higher-order work is the explicit accumulator.
The self-refining closure remains useful as a compact comparison and for cases
where a closure should intentionally retain its own declaration history.

## Compile DSL Support

The default compile installer vocabulary includes:

```clojure
prop/id
prop/+
prop/-
prop/<=
prop/not
prop/switch
closure/p:apply-closure
closure/p:apply-network
recursive/p:recursive-compound
recursive/p:self-refining-recursive-compound
recursive/p:accumulating-recursive-compound
```

That lets recursive branch topology be written with the same DSL as other
network construction:

```clojure
(let-cell [n-1 n-2 fib-1 fib-2]
  (seed n-1 n-minus-1)
  (seed n-2 n-minus-2)
  (recursive/p:recursive-compound self n-1 fib-1)
  (recursive/p:recursive-compound self n-2 fib-2))
```

The frame-template vocabulary can also express arithmetic and guards:

```clojure
(let-cell [one n-1 base? recur?]
  (seed one 1)
  (prop/- n one n-1)
  (prop/<= n one base?)
  (prop/not base? recur?))
```

`let-cell` now creates fresh lexical cells for declared locals. That matters for
recursion: each recursive frame must get fresh `n-1`, `fib-1`, and similar
scratch cells instead of reusing prior frame bindings from the network dict.
Plain symbol references outside `let-cell` still resolve through the dict.

## Network-Valued Expansion

`closure/p:apply-network` applies a closure to a network-valued cell and emits
the expanded network as a value:

```clojure
(closure/p:apply-network expander template expanded)
```

It is declaration-only:

- It does not run the expanded network.
- It does not install the expanded network into the outer graph.
- It is intended for declaration closures that add deterministic named-network
  facts or deterministic topology.
- If the closure creates fresh random ids on every activation, repeated
  expansion will not be idempotent. Frame declarations therefore use stable ids
  derived from `[frame slot]`.

This is the direction for frame topology as data:

```text
frame request + frame-template closure + prior declaration network
-> closure/p:apply-network
-> larger declaration network
-> outer scheduler decides when to evaluate declared propagators
```

That keeps "what topology should exist" separate from "run this topology now."

## Fibonacci Proof

`test/propagators/recursive_compound_test.clj` implements Fibonacci as a
test-local recursive closure.

The closure step:

1. reads concrete numeric `n`
2. writes contradiction for negative or non-integer input
3. installs and runs a switch-guarded base branch first
4. stops if the base branch wrote the output
5. otherwise lazily installs recursive branches for `n - 1` and `n - 2`
6. runs both recursive branches
7. installs `prop/+` only after both child outputs are usable

The base branch is intentionally lazy:

```text
n -- prop/switch(base?) --> out
```

If `out` is no longer `the-nothing` after that branch runs, no recursive
topology is built for that activation.

## Wrapped In A Normal Compound

The tests also wrap the recursive propagator inside a normal compound closure:

```clojure
(closure/p:apply-closure wrapper n out)
```

The wrapper installs `recursive/p:recursive-compound` in its own closure body.
This proves the recursive helper can be used as a one-time recursive compound
inside the existing runtime compound mechanism.

## Compound Object Experiment

`propagators/datastructures/compound_object/map.clj` adds experimental
higher-order map installers over compound-object public slots:

```clojure
(obj/p:map-slots-with-recursive-closure closure source out)
(obj/p:map-slots-with-recursive-accumulator closure acc source out)
```

The important rule from the nested experiment is: do not recurse by turning a
nested compound value back into a native Clojure map/vector and rebuilding it at
the end. Nested traversal is now modeled as accessor topology:

```clojure
source --(p:slot k)--> child-source
child-source --recursive map/reduce--> child-out
child-out --(p:slot k)--> out
```

That is the same mechanism used by nested `p:car` / `p:cdr`. It means a later
write to a leaf slot, for example `top/:second/:value`, propagates through the
middle object back to `top` by normal slot synchronization.

There are now two map implementations:

- Dynamic map propagator: `obj/p:map-slots-with-recursive-*` reads the source
  value during activation and runs each leaf recursive application internally.
  This was useful for proving semantics, but it couples declaration and
  evaluation.
- Declared topology builder:
  `obj/install-declared-nested-recursive-map-with-*` traverses the source shape
  during construction, builds the source object and the output object with
  recursive `p:slot` accessors, installs one recursive propagator per leaf,
  returns `:prop-ids`, and does not run the network. The caller runs the
  returned propagators from the outside. This is the stricter
  declaration/evaluation split.

The test input:

```clojure
{:left [0 1 2]
 :right {:a 3
         :b [4 5]}}
```

maps through Fibonacci to:

```clojure
{:left [0 1 1]
 :right {:a 2
         :b [3 5]}}
```

Both accumulation designs retain frames including `[:fib 5]`, and both are
idempotent under repeated named-network joins.

Nested reduce has two test paths:

```clojure
;; direct recursive activation over accessor-built slots
{:left [0 1 2]
 :right {:a 3
         :b [4 5]
         :empty []}}
;; => 15

;; network-valued expansion emits accessor topology, then the caller runs it
;; => 15
```

The result is mixed:

- Direct recursive activation can reduce nested numeric leaves through slot
  accessors.
- Direct recursive activation can map scalar leaves and first-level vector
  slots, but it is not robust for a nested map that contains vector children
  under a sibling; those nested vector output slots currently collapse to a
  localized contradiction.
- Network-valued expansion handles both nested map and nested reduce for the
  same input because it declares the whole accessor topology first and evaluates
  it afterward.

A public recursive reducer installer is still future work; it should reuse the
same accessor/accumulator pattern rather than introduce a separate recursion
data structure.

## Existing Reducer Cell

`obj/p:reduce` is already a useful compound-object reducer:

```clojure
(obj/p:reduce source merge-net init out)
```

It emits a `reducer-subnet` value whose strongest value folds usable public
source slots through a merge network with dict keys `:acc`, `:update`, and
`:out`.

The reducer is shallow today. It normalizes the source value and folds immediate
public slots only:

```clojure
{:a 1
 :nested {:b 2
          :c [3 4]}}
;; reducer output:
#{[:a 1]
  [:nested {:b 2 :c [3 4]}]}
```

It does not recursively enter `:nested`, `:b`, `:c`, or vector indexes by
itself. The new test-only helper composes repeated shallow reductions over
nested values and gets:

```clojure
#{[[:a] 1]
  [[:nested :b] 2]
  [[:nested :c 0] 3]
  [[:nested :c 1] 4]}
```

That proves the reducer can be reused as a building block for nested reduction,
but a public propagator-native nested reducer still needs explicit traversal
topology or bounded iteration.

## Benchmark Snapshot

Local benchmark on 2026-06-09, source:

```clojure
{:left [0 1 2 3 4]
 :right {:a 5
         :b [6 7]}}
```

Each row was warmed up twice, then run 8 times. All variants produced 8 retained
Fibonacci frames and a mapped leaf sum of 33. The declared variants mapped 8
leaf values and ran only through the outer scheduler.

| Implementation | Accumulation | Total ms | Avg ms/run |
| --- | --- | ---: | ---: |
| Dynamic map propagator | Self-refining closure | 734.57 | 91.82 |
| Dynamic map propagator | Explicit accumulator | 421.96 | 52.75 |
| Declared topology | Self-refining closure | 373.33 | 46.67 |
| Declared topology | Explicit accumulator | 376.69 | 47.09 |

This is a small, local measurement, not a statistically rigorous benchmark. It
does show the expected shape: the declared-topology implementation avoids
per-leaf internal runs and lets the scheduler settle the whole graph in one
network evaluation. The two declared accumulation styles are close on this small
input because both share the same leaf topology and deterministic frame
fragments.

## Four Approach Assessment

| Approach | Performance | Expressiveness | Conciseness | Practicality | Robustness |
| --- | --- | --- | --- | --- | --- |
| Direct recursive activation | Medium. Runs activation-local inner networks and re-enters the scheduler during expansion. | Good for tree recursion and immediate computation. | Good; `p:recursive-compound` is a small call. | Works for Fibonacci and nested reduce. | Not robust for mixed nested map output yet; nested vector children under a map sibling can contradict. |
| Network-valued declaration / `apply-network` | Medium to good. It avoids running during expansion, but still needs a later evaluator. | Best for semantic topology and named declarations. | Medium; needs closure plus network value. | Experimental but clean. | Stronger for nested map/reduce because complete accessor topology is declared before evaluation. |
| Existing reducer cell | Good for shallow folds. It folds immediate public slots through one reducer-subnet value. | Good for top-level compound slots, not nested traversal by itself. | Good; one `obj/p:reduce` call plus merge-net. | Works now. | Strong for shallow data; nested behavior is explicit and tested. |
| Bounded iteration | Expected best for linear/tail recursion because topology is fixed once. | Good for loops, scans, and reductions; weaker for tree recursion unless reformulated. | Medium; needs state/step/done/project closures. | Design only for now. | Strong if per-step ids are stable and max steps are explicit. |

Default choice: use network-valued declaration / `closure/p:apply-network` for
new recursive compound-object work. Keep direct recursive activation for small
immediate recursive computations and backward compatibility.

Evidence:

- Direct recursive activation calls `boundary/run-internal-network` during
  recursive activation. The nested reduce accessor experiment passes; the
  direct nested map experiment records a localized contradiction for nested
  vector outputs under `:right`.
- Dynamic nested map uses per-leaf temporary network runs; declared nested map
  now returns `:prop-ids` for source accessors, recursive leaf propagators, and
  output accessors, then lets the caller run the graph.
- `closure/p:apply-network` emits expanded network data and does not run it.
  The experiment uses it to emit both nested map and nested sum topology.
- `obj/p:reduce` folds immediate `public-slot-keys`; nested data remains an
  ordinary slot value unless another reducer/traversal step is explicitly
  composed.
- Bounded iteration is not implemented yet, so its performance entry is a design
  expectation, not a measured result.

## Bounded Iteration Direction

Bounded iteration is the TCO-inspired path for cheap linear recursion:

```clojure
(iteration/p:bounded-iterate state-closure
                             step-closure
                             done-closure
                             project-closure
                             init-state
                             max-steps
                             out)
```

The state closure declares deterministic cells for step `i`, the step closure
wires `state_i -> state_(i+1)`, the done closure produces `done_i`, and the
project closure exposes a candidate output guarded by `prop/switch`. The whole
loop is a fixed graph of `max-steps` copies, so it avoids recursive runtime
expansion and keeps intermediate execution cells collectable when the loop
network is not retained.

## Current Limits

- V1 is concrete-input only; it does not implement relational Fibonacci or
  backward propagation from output to input.
- Recursive expansion is activation-local and returns boundary messages only;
  it does not edit the outer graph during `eval-propagator`.
- There is no memoization. Fibonacci intentionally builds a recursive tree as a
  proof of recursive network expansion, not as an efficient numeric algorithm.
- Recursive steps should avoid installing primitive arithmetic over unusable or
  contradiction child outputs. The Fibonacci test checks child outputs before
  installing `prop/+`.
- The dynamic compound-object map helper is experimental and host-recursive over
  nested maps/vectors. It demonstrates reuse of named-network accumulation, but
  the declared-topology builder is the better fit for the runtime invariant that
  declaration and evaluation stay separate.
- Direct nested map inside one recursive activation is not robust enough for the
  mixed map/vector case. The current robust path is network-valued declaration
  first, then evaluation.
- Nested reduce is tested both as direct accessor recursion and as
  network-valued accessor expansion. A public recursive reducer should be built
  as another accumulator-based higher-order propagator.
- `obj/p:reduce` is shallow; nested reduction currently requires explicit
  composition of repeated shallow reducers.

## Nested Object Recursion: Why Network Accumulation Became Default

Experiment date: 2026-06-10

Goal:
- verify whether recursion over nested objects should be implemented as
  immediate recursive activation or as declaration network accumulation via
  `closure/p:apply-network`.

Setup:
- same nested source used across approaches:
  `{:left [0 1 2] :right {:a 3 :b [4 5] :empty []}}`
- two mapped operators: recursive Fibonacci and a recursive sum.
- two recursion styles:
  - direct recursive activation (`recursive-closure` running an inner network in
    activation path)
  - network-valued declaration (`closure/p:apply-network` producing a network
    that is run by the caller later)

Findings:
- direct activation maps scalar slots and first-level vectors, and reduces nested
  numeric leaves correctly;
- direct activation also sets a localized contradiction on `[:right :b]` and
  `[:right :empty]` in this mixed nested map/vector case,
  showing incomplete robustness when nested vectors appear under nested map
  branches.
- network-valued declaration produces full nested map and nested reduce topology
  first, then evaluation of that topology succeeds end-to-end with consistent
  leaf counts and values for both map and reduce.

Conclusion used for direction:
- recursive network accumulation is the default for nested object mapping and
  reduce patterns because declaration and evaluation stay separated and the
  complete accessor topology is established before propagation runs.
- direct recursive activation remains supported as an immediate path for
  simpler recursive shapes and compatibility tests, but is not the default for
  nested object semantics.
- Bounded iteration is a design direction and has no public implementation yet.

## Current Tests

`test/propagators/recursive_compound_test.clj` covers:

- `fib(0)`, `fib(1)`, `fib(2)`, `fib(5)`, and `fib(10)`
- DSL installation of `recursive/p:recursive-compound`
- lazy base branch behavior
- negative input contradiction
- unusable input leaving output empty
- max-depth contradiction
- recursive propagation wrapped inside a normal compound closure
- compatible closure merge over retained named networks
- self-refining closure accumulation
- explicit named-network accumulator accumulation
- monotone subsumption and idempotence of retained frame declarations
- recursive map over compound vectors
- recursive map over nested map/vector compound objects
- declared recursive map topology over nested map/vector compound objects using
  source and output slot accessors
- direct recursive nested reduce over accessor-built compound objects
- network-valued expansion for nested map and nested reduce
- the current direct nested-map limitation for vector children inside a nested
  map

`test/propagators/compound_object_test.clj` covers:

- shallow `obj/p:reduce` over maps and vectors
- nested compound values remaining immediate reducer slot values
- test-only composition of repeated shallow reducers to fold nested leaves
- accessor-built nested slot updates propagating from `:second/:value` back to
  the top object

## Chronological Experiment Log

Total executed recursion experiments: `6`

Separate from that total:

- `1` auxiliary non-recursive comparison reused repeated shallow `obj/p:reduce`
  composition for nested folding.
- `1` bounded-iteration follow-up exists only as a design direction and was not
  executed or benchmarked.

Benchmark method for the entries below:

- measured locally on `2026-06-10`
- warmed up twice, then timed for `8` end-to-end runs
- Fibonacci timings use `fib(10)`
- map benchmarks use `{:left [0 1 2 3 4] :right {:a 5 :b [6 7]}}`
- nested stress benchmarks use
  `{:left [0 1 2] :right {:a 3 :b [4 5] :empty []}}`

1. `2026-06-04` Plain direct recursive activation.
   Assumption: recursion can expand and run an activation-local inner network,
   with concrete input, no retained semantic frame history, and no mutation of
   the outer graph during `eval-propagator`.
   Outcome: `recursive/p:recursive-compound` computes Fibonacci, supports lazy
   base branching, depth guards, compile DSL installation, and wrapping inside a
   normal compound closure.
   Benchmark: `fib(10)` averaged `19.76 ms/run` (`14.40` min, `26.32` max).

2. `2026-06-09` Retained semantic frame accumulation.
   Assumption: recursive progress can be represented as monotone named-network
   frame facts with deterministic frame ids, merged by `named/join`.
   Outcome: both self-refining closure and explicit accumulator preserve
   semantic frames such as `[:fib 5]` and are idempotent by subsumption; the
   explicit accumulator is materially cheaper on the same Fibonacci workload.
   Benchmark: self-refining `fib(10)` averaged `212.99 ms/run`; explicit
   accumulator `fib(10)` averaged `137.71 ms/run`.

3. `2026-06-09` Dynamic higher-order recursive map over compound objects.
   Assumption: a higher-order recursive map can inspect compound slots during
   activation and run one recursive leaf application immediately for each slot.
   Outcome: recursive map works over compound-object structure and retains frame
   data, but declaration and evaluation are coupled and the implementation pays
   for activation-local per-leaf runs.
   Benchmark: dynamic self-refining map averaged `66.06 ms/run`; dynamic
   accumulator map averaged `47.21 ms/run`; both produced mapped leaf sum `33`.

4. `2026-06-10` Declared accessor topology for nested recursive map.
   Assumption: nested traversal should be declared up front with `p:slot`
   accessors, with source and output shape both represented as graph topology
   instead of rebuilding native Clojure maps/vectors after recursion.
   Outcome: the declared path keeps declaration separate from evaluation and
   preserves accessor semantics for later slot updates; it is cleaner than the
   dynamic path and stays aligned with the scheduler contract.
   Benchmark: declared self-refining map averaged `55.94 ms/run`; declared
   accumulator map averaged `56.61 ms/run`; both mapped `8` leaves with total
   mapped leaf sum `33`.

5. `2026-06-10` Direct nested recursion stress test.
   Assumption: one direct recursive activation should be able to recurse through
   nested mixed map/vector structure and also assemble correct nested output in
   the same activation style.
   Outcome: nested reduce succeeds and returns `15`, but nested map is not
   robust for mixed nested map/vector branches and localizes contradiction at
   `[:right :b]` and `[:right :empty]`.
   Benchmark: direct nested map averaged `20.74 ms/run`; direct nested sum
   averaged `10.37 ms/run`.

6. `2026-06-10` Declaration-first network accumulation via
   `closure/p:apply-network`.
   Assumption: recursion over nested objects should accumulate declaration
   topology first, then run the expanded network after the full accessor graph
   exists.
   Outcome: nested map and nested reduce both succeed on the same mixed nested
   source because the complete accessor topology is declared before evaluation;
   current structural idempotence is still incomplete because repeated expansion
   creates fresh topology ids.
   Benchmark: network-valued map averaged `23.80 ms/run`; network-valued sum
   averaged `8.61 ms/run`; both operated on `6` nested leaves.

Auxiliary comparison: repeated shallow reducer composition.
Assumption: nested reduction can be approximated by explicitly composing several
shallow `obj/p:reduce` passes rather than adding recursive reducer topology.
Outcome: this works as a comparison point and produces the nested leaf set, but
it is not itself the recursion mechanism and does not replace accessor-based
recursive traversal.
Benchmark: averaged `3.15 ms/run` on
`{:a 1 :nested {:b 2 :c [3 4]}}`.

## Four Strategy Propagation Graphs

These diagrams are generated from the real outer propagation networks installed
by each strategy. They show the runtime contract each strategy exposes to the
ordinary scheduler.

Plain direct recursive activation:

```text
          +---------+        
          | closure |·+      
          +---------+ |      
                      |      
                      |      
                      |      
                      | +---+
                      | | n |
                      v +---+
                      |   |  
               +·····<+···+  
               |      |      
+-----+    +-------+  |      
| out |    | recur |··+      
+-----+    +-------+         
   |           |             
   +·····<·····+             
```

Assumption represented: the closure and argument cells are inputs; only the
output cell receives the activation result.

Self-refining direct recursion:

```text
                           +---+
                           | n |
                           +---+
                             |  
                   +····<····+  
                   |            
+---------+   +--------+        
| closure |·>·| refine |        
+---------+   +--------+        
     |             |            
     +······<······v            
                   |            
               +-----+          
               | out |          
               +-----+          
```

Assumption represented: the closure cell is both input and output, so retained
frame declarations are merged back into the closure value.

Explicit-accumulator direct recursion:

```text
      +---+          +-----+ 
      | n |          | out | 
      +---+          +-----+ 
        |               |    
        +·······>·······^    
                        |    
                    +-------+
                    | accum |
                    +-------+
                        |    
        +·······>·······^    
        |               |    
   +---------+       +-----+ 
   | closure |       | acc | 
   +---------+       +-----+ 
```

Assumption represented: the closure remains stable; accumulated frame
declarations flow through a separate `acc` cell.

Declaration-first network accumulation:

```text
               +----------+               
               | expander |·+             
               +----------+ |             
                            |             
                            |             
                            |             
                            | +----------+
                            | | template |
                            v +----------+
                            |       |     
                     +······<·······+     
                     |      |             
+----------+    +-------+   |             
| expanded |    | apply |···+             
+----------+    +-------+                 
      |              |                    
      +······<·······+                    
```

Assumption represented: a closure-valued expander and a template network are
inputs; the output is a larger network value that is evaluated separately.

## Network Graph Snapshots

These ASCII graphs were generated with `graph.vijual` from real network values,
not hand-simulated sketches.

Fibonacci uses the retained accumulator frame network from
`run-accumulating-fib 5`. The visualized nodes are the frame facts accumulated
in that named network; edges are extracted from `[:child ...]` frame facts.

```text
-----------+   +-----------+   +-----------+  
| fib 0 = 0 |   | fib 1 = 1 |   | fib 5 = 5 |·+
+-----------+   +-----------+   +-----------+ |
      |               |               |       |
      |               +········<······+       |
      |               ^               v       |
      +······<········+               |       |
                      |               |       |
                +-----------+   +-----------+ |
                | fib 2 = 1 |   | fib 3 = 2 | v
                +-----------+   +-----------+ |
                      |               |       |
                      +········<······+       |
                      |               ^       |
                      +·······<·······+       |
                                      |       |
                                +-----------+ |
                                | fib 4 = 3 |·+
                                +-----------+  
```

The cons-cell snapshot uses an installed topology network built from
`obj/p:cons`, `obj/p:car`, and `obj/p:cdr`. It is intentionally a raw topology
view: `p:slot`-style accessors synchronize both directions, so the visual graph
contains paired-looking wiring rather than a single semantic arrow per slot.

```text
                                +------+
                                | tail |
                                +------+
                                    |   
                                    ^   
                                    |   
+----------+                    +-----+ 
| read car |·+·+                | cdr | 
+----------+ | |                +-----+ 
      |      | |                    |   
      +······+·+····<···············+   
      ^      | |                    |   
      +······+·+······>·············+   
      |      | |                        
  +------+   | | +----------+   +-----+ 
  | cons |···>·+·| read cdr |   | car | 
  +------+   v ^ +----------+   +-----+ 
      |      | |       |            |   
      +······+<+·······+            |   
      |      | |       |            |   
      +······+·+·····<·^············^   
      |      | |       |            |   
      +······+·+·····>·+············+   
             | |       |            |   
+---------+  | | +---------+    +------+
| car out |··+·+ | cdr out |    | head |
+---------+      +---------+    +------+
```

## Complete Raw Runtime Networks

The diagrams below are complete `net-graph` renderings from actual runtime
network values. They are intentionally noisier than the semantic diagrams above.

For Fibonacci, the recursive inner activation networks are not retained after
activation. What is retained is the closure/accumulator frame network. That
network is a named network of fact cells, so the complete raw graph has cells
but no propagator edges. The self-refining closure and explicit accumulator
produce the same retained fact graph; they differ in where this network is
stored (`closure` cell versus `acc` cell).

Outer runtime topology for self-refining `fib(5)`:

```text
                          +---+
                          | 5 |
                          +---+
                            |  
                  +····<····+  
                  |            
+---------+   +-------+        
| closure |·>·| recur |        
+---------+   +-------+        
     |            |            
     +······<·····v            
                  |            
              +-------+        
              | out=5 |        
              +-------+        
```

Outer runtime topology for explicit-accumulator `fib(5)`:

```text
              +-----+                 +---+  
              | acc |···+             | 5 |·+
              +-----+   |             +---+ |
                 |      |                   |
                 +······+·····<·····+<······+
                        v     |     |        
                        |     |     |        
                        |     |     |        
                        |     |     |        
+-------+   +---------+ | +-------+ |        
| out=5 |   | closure |·>·| recur |·+        
+-------+   +---------+   +-------+          
    |                         |              
    +············<············+              
```

Internal activation network for self-refining `fib(5)`:

```text
+-----+                +------+                  +---+   
| out |··+             | prop |                  | 3 |   
+-----+  |             +------+                  +---+   
         |                 |                       |     
         |                 +······>····+           |     
         |                 ^           |           |     
         |                 +···········<···········+     
         |                 |           |                 
         | +------+    +------+     +---+                
         ^ | prop |·>··| self |     | 2 |···+            
         | +------+    +------+     +---+   |            
         |     |           |                |            
         |     +·····<·····+                |            
         |     ^                            |            
         |     +····>······+                |            
         |     |           |                v            
+------+ |  +---+       +---+               |            
| prop |·+  | 4 |       | 3 |               |            
+------+    +---+       +---+               |            
    |                      |                |            
    ^                      +·····>·····+    |            
    |                                  |    |            
+------+   +-----+     +------+    +------+ | +---------+
| out* |   | n=5 |··>··| prop |    | prop |·+ | closure |
+------+   +-----+     +------+    +------+   +---------+
    |                      |           |                 
    +················<·····v···········+                 
                           |                             
                      +--------+                         
                      | n-in=5 |                         
                      +--------+                         
```

Internal activation network for explicit-accumulator `fib(5)`:

```text
 +-----+     +-----+                            +------+   +---------+
 | n=5 |···+ | out |                            | prop |   | closure |
 +-----+   | +-----+                            +------+   +---------+
           |     |                                  |                 
           |     +···············>··················+                 
           |     |                                  |                 
           |     +················<·················+                 
           |     |                                                    
+--------+ | +------+     +------+                                    
| n-in=5 | | | out* |     | prop |                                    
+--------+ | +------+     +------+                                    
     |     |     |            |                                       
     |     |     |            +·····················<····+            
     |     |     ^            v                          |            
     +·····v·····+····<·+     |                          |            
           |     |      |     |                          |            
           | +------+   | +-----+    +------+    +---+   |            
           | | prop |·+ | | acc |    | prop |·>··| 3 |   |            
           | +------+ | | +-----+    +------+ |  +---+   |            
           |     |    | |                |    |     |    |            
           |     +····+·+·········<······+····+·····+    |            
           |          | |                |    |          |            
           |          | |     +···>······+    |          |            
           |          | |     |          |    |          |            
           |          | |     |          +···<+·····+    |            
           |          | |     |          |    |     |    |            
           |          | |     |          +·>··+·····+    |            
           |          | |     |               |     |    |            
           | +------+ | | +------+   +------+ | +------+ |            
           +·| prop |·^·+ | self |·>·| prop |·^·| acc* |·+            
             +------+ |   +------+   +------+ | +------+              
                      |                  |    |     |                 
                      |                  +····+·····+·<·········+     
                      |                  |    |     |           |     
                      |                  +····<·····+           |     
                      |                  |    |                 |     
                 +····+········<·········+    |                 |     
                 |    |                       |                 |     
              +---+   |               +---+   |               +---+   
              | 2 |···+               | 4 |···+               | 3 |   
              +---+                   +---+                   +---+   
```

These internal activation graphs are complete network renderings captured from
the real `run-recursive-frame` path. They are not retained by normal execution;
normal execution keeps only the outer messages and the retained frame network.
The boxes labeled `prop` are real propagator nodes, but the runtime propagator
record currently does not retain a human-readable operator name.

Complete retained frame network for `fib(5)`:

```text
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+   +------------+   +------------+                                                   
                 |     f2     |   |     f3     |   |     f4     |   +------------+   +------------+                 
                 |  [:child   |   |  [:status  |   |  :combine  |   |     f5     |   |     f0     |                 
                 |  0] [:fib  |   | :expanded] |   |     :+     |   | :output 5  |   | :output 0  |                 
                 |     1]     |   |    true    |   +------------+   +------------+   +------------+                 
                 +------------+   +------------+                                                                    
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+                    +------------+   +------------+   +------------+   +------------+
                 |     f3     |   +------------+   |     f3     |   |     f5     |   |     f1     |   |     f2     |
                 |  [:child   |   | f5 :input  |   |  [:child   |   |  [:child   |   |  [:status  |   |  [:status  |
                 |  0] [:fib  |   |     5      |   |  1] [:fib  |   |  0] [:fib  |   |   :done]   |   | :expanded] |
                 |     2]     |   +------------+   |     1]     |   |     4]     |   |    true    |   |    true    |
                 +------------+                    +------------+   +------------+   +------------+   +------------+
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+                    +------------+   +------------+   +------------+   +------------+
+------------+   |     f2     |   +------------+   |     f5     |   |     f4     |   |     f5     |   |     f4     |
| f0 :input  |   |  [:child   |   | f3 :input  |   |  [:child   |   |  [:status  |   |  [:status  |   |  [:child   |
|     0      |   |  1] [:fib  |   |     3      |   |  1] [:fib  |   |   :done]   |   | :expanded] |   |  1] [:fib  |
+------------+   |     0]     |   +------------+   |     3]     |   |    true    |   |    true    |   |     2]     |
                 +------------+                    +------------+   +------------+   +------------+   +------------+
                                                                                                                    
                                                                                                                    
                                                                                                                    
                 +------------+                                                                       +------------+
                 |     f0     |   +------------+   +------------+   +------------+   +------------+   |     f4     |
                 |  [:status  |   |     f4     |   | f1 :input  |   |     f1     |   | f2 :input  |   |  [:child   |
                 |   :done]   |   | :output 3  |   |     1      |   | :output 1  |   |     2      |   |  0] [:fib  |
                 |    true    |   +------------+   +------------+   +------------+   +------------+   |     3]     |
                 +------------+                                                                       +------------+
                                                                                                                    
                                                                                                                    
                                                                                                                    
                                  +------------+   +------------+   +------------+   +------------+   +------------+
                 +------------+   |     f3     |   |     f2     |   |     f5     |   |     f3     |   |     f5     |
                 |     f2     |   |  :combine  |   |  [:status  |   |  [:status  |   |  [:status  |   |  :combine  |
                 | :output 1  |   |     :+     |   |   :done]   |   |   :done]   |   |   :done]   |   |     :+     |
                 +------------+   +------------+   |    true    |   |    true    |   |    true    |   +------------+
                                                   +------------+   +------------+   +------------+                 
                                                                                                                    
                                                                                                                    
                                                                                                                    
                                  +------------+   +------------+                                                   
                                  |     f2     |   |     f4     |   +------------+   +------------+                 
                                  |  :combine  |   |  [:status  |   |     f3     |   | f4 :input  |                 
                                  |     :+     |   | :expanded] |   | :output 2  |   |     4      |                 
                                  +------------+   |    true    |   +------------+   +------------+                 
                                                   +------------+                                                   
```

Complete runtime topology for self-refining nested HOP over `{:x [2]}`:

```text
               +-----+                         +---+               +-----+                     
           +···| fib |···+·+                   | 2 |··········+    | out |                     
           |   +-----+   | |                   +---+          |    +-----+                     
           |      |      | |                      |           |        |                       
           |      +······+·>······+               |           |        v                       
           |             | |      |               |           |        +··········<···········+
           |             | |      |               |           |        |                      |
           |             ^ v    +---+          +---+          |  +---------+                  |
           |             | |    | 1 |          | 1 |······+·+ |  | slot :x |                  |
           ^             | |    +---+          +---+      | | |  +---------+                  |
           |             | |      |                       | | |        |                      |
           |             | |      ^               +·······+<+·+········+                      |
           |             | |      |               |       | | |        |                      |
           |             | |      |               +·······+·+>+········+                      |
           |             | |      |               |       | | |                               |
  +---+    | +---------+ | | +--------+       +-----+     | | | +------------+                |
  | 2 |    | | closure | | | | slot 0 |       | net |     ^ v | |    slot    |                |
  +---+····+ +---------+·+·+ +--------+···>···+-----+·····>·+·+·|   :count   |·+·+            |
     |                            |               |       | | | +------------+ | |            |
     |                            |               |       | | |        |       | |            |
     |                            |               |       | | +>·······+·······+·+······+     |
     |                            |               |       | |          |       | |      |     |
     ^                            |               +·······+·<··········+       | |      |     |
     |                            |               |       | |                  | |      |     |
     |                            +·······<·······+       | |                  | |      |     |
     |                                                    | |                  | |      |     |
+--------+                     +-----+     +------------+ | |                  | | +--------+ |
| slot 0 |                     | net |     |    slot    | | |                  | | | slot 0 | |
+--------+                     +-----+     |   :count   |·+·+                  | | +--------+·+
     |                            |        +------------+                      | |      |      
     |                            |               |                            | |      |      
     |                            |               +·····················>······v·^······+      
     |                            |               |                            | |      |      
     |                            v               +···················<········+·+······v      
     |                            |                                            | |      |      
     +·············<··············+                                            | |      |      
     |                            |                                            | |      |      
     +·············>··············+                                            | |      |      
                                  |                                            | |      |      
               +-----+       +---------+    +---------+             +---+      | |  +-----+    
               | net |···>···| slot :x |    | slot :x |             | 1 |······+·+  | net |    
               +-----+       +---------+    +---------+             +---+           +-----+    
                  |               |               |                                     |      
                  |               |               +··················>··················+      
                  |               |               |                                     |      
                  |               |               +··················<··················+      
                  |               |               |                                            
                  +·······<·······+               |                                            
                  |                               |                                            
                  +···············<···············+                                            
                  |                               |                                            
                  +···············>···············+                                            
```

Complete runtime topology for explicit-accumulator nested HOP over `{:x [2]}`:

```text
                             +-----+       +---------+        +-----+                  
                             | net |···>···| slot :x |·+·+    | acc |                  
                             +-----+       +---------+ | |    +-----+                  
                                 |              |      | |        |                    
                                 |              +······<·+········+                    
                                 |              |      | |        |                    
                                 v              +······+·>········+                    
                                 |              |      | |                             
                                 +······<·······+      | |                             
                                 |              |      | |                             
             +---+          +--------+       +-----+   | |     +---+        +---------+
             | 2 |····>·····| slot 0 |       | fib |···+·>·····| 1 |        | closure |
             +---+          +--------+       +-----+   | |     +---+        +---------+
               |                 |              |      | |        |              |     
               |                 |              +······v·^·······<+··············+     
               |                 |              |      | |        |                    
               +········<········+              |      | |        |                    
               |                                |      | |        |                    
               +················>···············+      | |        |                    
                                                       | |        |                    
                                 +·················>···+·+········+                    
                                 |                     | |        |                    
                                 +···················<·+·+········+                    
                                 |                     | |                             
          +---------+       +--------+       +-----+   | |     +---+                   
          | slot :x |       | slot 0 |       | net |···+·+     | 1 |                   
          +---------+       +--------+       +-----+           +---+                   
               |                 |              |                 |                    
               +················>+··············+                 |                    
               |                 |              |                 |                    
               +················<+··············+                 |                    
               |                 ^                                ^                    
               +···············<·+··············+                 |                    
               |                 |              |                 |                    
               +·············>···+··············+                 |                    
                                 |              |                 |                    
+-----+                      +-----+         +-----+       +------------+              
| out |                      | net |         | net |       |    slot    |              
+-----+·+             +······+-----+·····+   +-----+···>···|   :count   |              
   |    |             |          |       |      |          +------------+              
   |    |             |          |       |      |                 |                    
   |    |             |          v       |      +········<········+                    
   |    |             |          |       |      v                                      
   +····+·········<···^·+        |       |      |                                      
        |             | |        |       |      |                                      
        v             | | +------------+ | +--------+                                  
        |             | | |    slot    | | | slot 0 |                                  
        |             | | |   :count   | | +--------+                                  
        |             | | +------------+ |      |                                      
        |             | |        |       |      ^                                      
        |      +······+·+········^··<····+      |                                      
        |      |      | |        |              |                                      
        | +---------+ | |     +---+           +---+                                    
        +·| slot :x |·+·+     | 1 |           | 2 |                                    
          +---------+         +---+           +---+                                    
```

## Final Conclusion

The executed evidence supports three decisions.

- Keep plain direct recursive activation for immediate tree-style recursive
  computation and compatibility.
- Use explicit-accumulator, declaration-first network accumulation as the
  default direction for recursive work over nested compound objects.
- Keep accessor topology as the representation of nested structure; do not
  rebuild nested recursive results by collapsing into native Clojure maps or
  vectors at the end.

The deciding experiment was the `2026-06-10` mixed nested map/vector stress
case: direct nested recursion remained fast enough but failed semantically on
`[:right :b]` and `[:right :empty]`, while declaration-first network
accumulation stayed correct with only modest end-to-end cost. That is the main
reason recursive network accumulation is now the preferred default.

## Follow-up: Demand-driven accessor topology

The next compound-object experiment separates structure from slot values more
strictly. The current `p:slot` representation keeps durable slot cells inside
the collection's named network. That is convenient for `obj/slot-value`, but it
means a normal slot value update also changes the collection value and wakes
collection dependents.

The experimental `obj/p:network-slot` keeps the collection cell as a structural
inner network only. The inner network records demanded accessor topology,
activation-local avatars, and bi-sync wiring. Durable slot values remain in the
outer accessor cells.

```text
collection cell
  strongest = structural/accessor network

outer accessor cells
  strongest = actual slot values

slot activation
  fetch collection inner net
  copy relevant outer cells into avatars
  run inner net
  project changed avatars back to outer cells
```

This is the shape we want for future TMS/switch work: structural conditions can
rewrite the collection's inner network, while ordinary value updates propagate
between accessor cells without rewriting the collection.

Focused evidence from `propagators.compound-object-network-slot-test`:

- raw map/vector source values can be projected to accessors without durable
  internal slot cells;
- repeated accessor declarations are idempotent;
- two accessors for the same slot sync through the inner network;
- after topology exists, a later accessor value update changes peer accessors
  but leaves the collection value unchanged.

Microbenchmark on `2026-06-10`, averaged over 8 timed runs after 2 warmup runs
and measuring setup separately from the later value update:

| accessors | strategy | setup ns | update ns | collection changed on update |
|---:|---|---:|---:|---|
| 2 | `p:legacy-slot` | 646,994 | 1,000,687 | yes |
| 2 | `p:network-slot` / `p:slot` | 757,734 | 344,067 | no |
| 10 | `p:legacy-slot` | 2,515,619 | 2,279,473 | yes |
| 10 | `p:network-slot` / `p:slot` | 4,294,234 | 1,299,890 | no |
| 100 | `p:legacy-slot` | 64,787,208 | 38,845,703 | yes |
| 100 | `p:network-slot` / `p:slot` | 77,206,369 | 33,355,682 | no |

Second microbenchmark on `2026-06-10`, also averaged over 8 timed runs after 2
warmup runs, using `200` accessors spread across `200` different slots and then
updating one accessor:

| shape | strategy | setup ns | update ns | collection changed on update |
|---|---|---:|---:|---|
| 200 accessors / 200 slots | `p:legacy-slot` | 334,783,843 | 170,275,536 | yes |
| 200 accessors / 200 slots | `p:network-slot` / `p:slot` | 155,213,739 | 366,062 | no |

After this benchmark, the public compound-object facade was moved so
`obj/p:slot`, `obj/p:car`, `obj/p:cdr`, and `obj/p:cons` use the network-slot
strategy by default. The old durable slot-cell implementation remains available
as `obj/p:legacy-slot`, `obj/p:legacy-car`, `obj/p:legacy-cdr`, and
`obj/p:legacy-cons` for compatibility tests and APIs that intentionally inspect
materialized slot cells inside the collection value.

Conclusion: the experimental model proves the semantic separation we wanted,
but the performance result depends on shape. For many accessors synced to the
same slot, `p:network-slot` still does real fan-out work through the inner
network and loses at `100` accessors. For many independent slots, it wins
strongly because a value update stays on the one accessor route and does not
rewrite the collection cell. The optimized version seeds only the activated
accessor route and projects changed avatars by comparison instead of installing
activation-local taps.
