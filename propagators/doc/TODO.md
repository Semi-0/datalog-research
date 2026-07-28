# Compiler-2 Runtime TODO

## Unify Reflective Application Inspection

The runtime can execute versioned closure applications, retain their topology,
and retract their premises, but its reflective graph surfaces do not currently
describe that behavior through one coherent application model.

This is an architectural problem rather than a large-network performance
problem. It reproduces in a fresh runtime with one closure and two
applications.

### Reproduction

In a fresh versioned TUI client:

```clojure
(def-cells first-result second-result graph)

(def-net inc1 [x] [result]
  (<-> (+ x 1) result))

(inc1 4 first-result)

(inc1 10 second-result)

(call-graph inc1 graph)
```

Expected reflective result:

```text
4 -> call inc1 -> first-result
10 -> call inc1 -> second-result
```

Current result:

```text
nothing
```

A control case with a block-local closure does produce a graph, but only for
potential calls in the closure body:

```text
closure [x] -> potential ->
closure [x] -> potential <->
closure [x] -> potential +
```

It contains no realized facts for the two applications of the closure.

### Current Architectural Mismatches

#### 1. `call-graph` indexes calls by caller

Each retained application publisher writes to the stable graph cell belonging
to `:application/caller`. Consequently, `call-graph` answers "what can or did
this closure call?" It cannot answer "where was this closure called?"

Both questions are useful and must remain distinct:

- outgoing calls made by a closure;
- incoming applications of a closure;
- one realized application and its inputs, outputs, premise, and retained IR.

The runtime needs one canonical realized-application fact from which caller and
callee indexes can be derived.

#### 2. Runtime and inspection interpret operator values differently

Runtime application unwraps scoped operator answers before recognizing and
activating a closure. Semantic graph projection and `call-graph` inspect the
raw strongest value instead.

For a versioned top-level `def-net`, the raw operator value is a scoped network
value rather than direct closure information. Execution therefore succeeds
while reflection fails to recognize the same operator as a closure.

Operator normalization must be a shared primitive used by execution,
application indexing, semantic projection, and inspection propagators.

#### 3. Trace subscriptions have the wrong effect identity

Boundary-effect collapse groups ordinary effects by receipt ID and epoch.
Independent `:xr/trace-subscribe` requests can therefore collapse into one
subscription even though each has a distinct boundary ID and target cell.

This explains why an earlier single trace could work while later traces in
another client were never installed. Trace subscription identity must include
the subscription boundary ID.

#### 4. Retained topology and active truth are not explicit in inspection

Editing a versioned block intentionally retains old topology. Inspection must
not confuse these separate quantities:

- retained application sites;
- active application sites supported by current premises;
- retracted application sites;
- scheduler activations of an application propagator.

"Times called" must not mean scheduler activation count. A useful reflective
count is the number of active realized applications, optionally accompanied by
retained and retracted counts.

### Proposed Reflective Model

Represent every compiled application with a stable fact containing at least:

```clojure
{:application/id application-id
 :application/caller caller-id
 :application/operator operator-id
 :application/inputs input-cell-ids
 :application/outputs output-cell-ids
 :application/context context-id
 :application/premises premise-ids
 :application/status :active}
```

The status should be derived from premise support rather than stored as an
independent mutable truth.

Build inspection propagators over these facts:

```clojure
(calls-from caller outgoing-graph)
(calls-to inc1 incoming-graph)
(application-count inc1 count)
(application-summary inc1 summary)
```

Example summary:

```clojure
{:active 2
 :retained 4
 :retracted 2}
```

These should be ordinary propagator compositions. Asynchronous trace delivery
should transport an already-defined graph query, not define different graph
semantics.

### Required Invariants

1. Execution and reflection resolve the same normalized operator value.
2. Every active closure application has one stable realized-application fact.
3. Caller and callee views are indexes over the same facts.
4. Retracting a premise changes active truth without deleting retained
   topology.
5. Scheduler reactivation does not create another logical call fact.
6. Independent trace subscriptions cannot deduplicate each other.
7. TUI, XR, direct semantic queries, and inspection propagators project the
   same graph content.
8. Disabled inspection adds no coupling to closure execution beyond publishing
   the canonical application fact.

### Acceptance Tests

- One closure applied twice in separate versioned blocks produces two active
  incoming call facts.
- An outgoing call graph for a caller containing two call sites reports both
  sites without being confused with incoming calls.
- Editing one application block retracts only its old active call fact while
  retaining its topology.
- Repeated scheduler activation leaves the logical application count
  unchanged.
- A versioned `def-net` and an equivalent block-local closure produce the same
  reflective application shape.
- Two clients can install independent trace subscriptions in the same epoch.
- Direct trace, TUI output, and XR rendering show the same two realized calls.
- Session export and replay preserve the observable active, retained, and
  retracted application summary.

### Diagnostic API

Use the runtime API to preserve failing sessions before changing topology:

```clojure
{:op :instance/export}
```

The export includes clients, commit history, block/display snapshots, runtime
errors, boundary outbox effects, and topology counts. Replaying the manifest in
an isolated session allows inspection of application IR and trace subscription
state without mutating the live runtime.
