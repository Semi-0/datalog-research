# Agent Network Composition Examples

Status: theoretical examples, 2026-09-15; not implemented APIs.

Read [Cells As Agents](cells-as-agents.md) for the information semantics,
contradiction provenance, and commitment boundary underlying these examples.

## Structures That Could Become More Cohesive

The sketches below describe relationships, not scheduler instructions or APIs.
Each structure uses ordinary propagation internally and an explicit runtime
boundary for reality-facing ports.

### 1. Open Linked-List Processing With Incremental Effects

```text
source observations -> open list -> map/filter HOP -> result observations
                                                        |
                                                  decision policy
                                                        |
                                                  sink requests
                                                        |
                                                  sink receipts
```

**Today:** compound slots and GUR express the list and recursive computation;
boundary requests express output. Coordinating an open producer, incremental
delivery, and retention spans those mechanisms and host policy.

**With agent cells:** source and sink are persistent participants. Each stable
element identity can name a decision and its delivery receipt. The tail can stay
unknown while an action concerning a known element becomes ready. A one-time
consumer can explicitly select a snapshot or prefix of this same structure.

**Composition gain:** another mapper, observer, or sink can attach to declared
ports without requiring the producer to finish. A compound stage can expose the
same kinds of ports as a primitive participant.

**Limit:** map can often produce incremental results; a final reduction generally
requires termination. A filter's rejection is not permanent if its predicate
can later change. Prefix retirement needs every relevant consumer's progress
and future-update contract, not merely a sink acknowledgment.

### 2. Sensor, Estimator, And Actuator Feedback Loops

```text
sensor facts -> estimator -> decision -> actuator request
     ^                                      |
     |                              receipt / new measurement
     +--------------------------------------+
```

**Today:** inference lives in the network; polling, input admission, action
delivery, and wakeups require runtime orchestration around it.

**With agent cells:** sensors declare observation ports, estimators expose partial
knowledge, and actuators expose request and receipt ports. A decision refers to
the evidence or input frontier it used. A deadline arrives as an observation.

**Composition gain:** simulated sensors and actuators can interpret the same
declarations as live devices. Several estimators can share one observation
source, and a higher-level controller can consume a lower-level loop's declared
state and receipts.

**Limit:** old measurements remain historical facts; the current estimate changes.
Physical stability, sampling policy, and action latency are separate obligations
from monotonicity. Every fresh receipt must not automatically trigger a fresh
action, or the loop may generate work without new external information.

### 3. Shared UI, Trace, And Diagnostic Observers

```text
                     /-> UI projection -> UI requests
shared network facts --- > trace projection -> trace requests
                     \-> diagnostic projection -> log requests
```

**Today:** explicit projections and boundary outboxes already cover much of this.
Subscription lifecycle and asynchronous publication also involve runtime state.

**With agent cells:** observers declare what they observe, which input revision
they processed, and whether results are provisional or committed. Several views
can share facts while advancing independently.

**Composition gain:** an observer becomes a participant that can be attached to
a compound network, itself inspected, and detached through an explicit scope.
User gestures return through declared input ports. A one-time trace is an
observer whose scope closes after its selected observation has been delivered.

**Limit:** a complete execution trace and a semantic snapshot are different
products. A snapshot may coalesce intermediate states; an audit log may not.
Observation must not unintentionally keep the entire observed graph alive, and
publication receipts must not continually invalidate their own source revision.

### 4. Dynamic Groups Of Collaborating Agents

```text
discovery facts -> pure agent declarations -> agent instances
                                             |   |   |
                                             observations
                                                  |
                                              aggregate
```

**Today:** GUR can declare dynamic topology, while external resource acquisition
and lifetime management are handled separately.

**With agent cells:** a propagator can declare one participant per discovered
device, file, connection, or job. Each has a stable identity and public ports.
An aggregate consumes their observations without knowing their host handles.

**Composition gain:** a group can expose a compound interface and participate in
another group. The same higher-order declaration can describe a fleet of sensors,
a set of file watchers, or a collection of simulated peers.

**Limit:** storing a network blueprint, replaying one instance, and creating a
new instance must have distinct meanings. Re-evaluation cannot silently acquire
duplicate resources. Closing a group requires membership and lifetime rules;
silence from an agent is not proof that it has disappeared.

### 5. Causal Workflows With Receipts

```text
evidence -> decision A -> request A -> receipt A -> decision B -> request B
```

**Today:** requests and receipts already support this shape, but a multi-stage
workflow needs explicit handling of correlation, delivery, and failure.

**With agent cells:** each participant publishes immutable requests and outcome
facts. Downstream decisions depend on a particular receipt, not on incidental
activation order. Failure and cancellation are facts that policy can interpret.

**Composition gain:** sequential stages, independent branches, and joins use the
same ports as other networks. A nested workflow exposes public receipts while
keeping internal topology behind a compound interface.

**Limit:** a retry needs a defined relationship to the original request identity.
Compensation is a new action, not deletion of the old action. Local deduplication
does not guarantee exactly-once execution across an external crash boundary.

### 6. Replicated Observation And Decision Networks

```text
peer A facts <-> transport declarations <-> peer B facts
      |                                      |
 local inference                         local inference
```

**Today:** network data and monotone merge offer ingredients, but peer transport,
delivery state, and external action ownership are additional concerns.

**With agent cells:** peers exchange identified facts through declared ports.
Local computation can continue while remote observations arrive later.

**Composition gain:** transport adapters could connect locally simulated peers
or remote processes using the same logical interface. Networked agents become
ordinary declared relationships rather than only a host deployment arrangement.

**Limit:** this is the most speculative example. Serialization, identity,
authority to execute effects, and duplicate delivery need protocols. Monotone
fact exchange does not settle competing external actions or prove that no unseen
message exists. [CALM](https://arxiv.org/abs/1901.01930) provides relevant limits
on coordination-free logical computation, not a guarantee for arbitrary IO.
