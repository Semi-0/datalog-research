# High-level user, evaluation, and Pi harness

`composition.lain` connects three actor interfaces. The injected socket, Harbor,
and Pi objects expose transport boundaries; they are not additional copies of
actor state. Opening or loading these files does not start a socket server,
invoke a plugin, or launch a child.

| File | Constructs | Composes |
| --- | --- | --- |
| `user-socket.lain` | `make-user`, `make-socket` | `user-from-socket`: accept commands and publish feedback |
| `evaluation-framework.lain` | `make-evaluation-framework`, `make-harbor` | `evaluation-framework-from-harbor`: maintain trials, prepare, assess, and enrich assignments |
| `runtime-pi.lain` | `make-runtime`, `make-pi` | `runtime-from-pi`: request execution/control and incorporate observations |

The constructors allocate independent partial interfaces. Their slots remain
connected to the original cells even when values are unavailable. The verb
networks take the cells they relate; the dependency-injection networks compose
those verbs around one actor. The final constructor result is a struct, while
relationship networks return explicit connected endpoint lists where needed.
No relationship materializes a complete actor, request graph, or state graph.

## Public connections

The user exposes `:assignments`, `:controls`, and `:feedback`. The runtime exposes
`:commands`, `:assignment-result`, `:controls`, and `:status`. `execute-assignment`
is the connected `(slot :commands)` accessor: its destination is an assignment
request endpoint, not an arbitrary plugin command string.

The evaluation framework's ports retain their existing meanings:

- User and framework share assignment facts bidirectionally.
- The framework's ongoing assignment is offered to the runtime's command port.
- Confirmed runner results return through the framework's assignment-result port.
- Harbor assessments enrich the shared assignment facts.
- User cancellation controls reach the runtime; runtime status reaches user feedback.

Feedback does not loop into the socket command input. Pi progress/receipt
observations do not directly become assignment results. Completion normalization
must explicitly identify a terminal outcome and collected result; Harbor still
owns task assessment.

The socket, Harbor, and Pi interfaces each expose `:requests` and
`:observations`. Their outside interpreters consume requests and publish observed
facts. The Lain files do not select a live backend or install an interpreter.
Observation/synthetic mode is the default until the session is explicitly extended.

## Session-extension policy contracts

The Lain files declare the following policy propagators without choosing their
implementations. `harness-session/session-extension` supplies their pure
transformations together as one ordinary session extension. The socket, Harbor,
and Pi primitive modules can also be installed separately for focused tests;
loading a declaration file alone still performs no outside I/O.

| Policy | Inputs | Output |
| --- | --- | --- |
| `socket:normalize-commands` | socket observations | validated, identified command facts |
| `user:derive-assignments` | command facts | submission/order/cancellation facts about assignments |
| `user:derive-controls` | command facts | cancellation controls for the runtime |
| `user:describe-commands` | command facts | acknowledgements and domain-command rejection/error facts |
| `user:describe-assignments` | assignment facts | feedback facts about progress and assessment |
| `socket:encode-feedback` | feedback facts | bounded outbound transport requests |
| `pi:normalize-observations` | Pi observations | normalized event/receipt facts |
| `pi:derive-execution-requests` | assignment requests, normalized observations | execution/reconciliation request facts |
| `pi:derive-control-requests` | controls, normalized observations | cancellation/status request facts |
| `pi:derive-completions` | normalized observations | confirmed assignment results |
| `pi:derive-status` | normalized observations | feedback facts about execution status/failure |

`user:describe-commands` feeds the user's feedback endpoint independently of
assignment/control derivation. An acknowledged or rejected command therefore
has an output path even when it creates no assignment and requests no control.
Normalized rejection facts retain command identity and a reason; the feedback
policy can expose generic `:errors`/`:general-info` channels and attach provenance
without changing the actor constructor. Malformed framing or invalid wire data
is rejected by the socket interpreter before domain publication. A well-framed
but unsupported domain command is represented as a normalized rejection and
reported through this feedback relationship.

The framework's existing evaluation and Harbor policies remain separate. Policies
must emit compatible fact contributions; none may silently overwrite another
producer's facts. Required accumulation/merge behavior belongs to the session's
runner composition and must be associative, commutative, and idempotent.

Assignment IDs survive reorder and completion. Event facts carry stable IDs and
explicit ordering; effect requests carry stable effect IDs. Reorder changes the
priority of pending assignments only. A running assignment continues unless an
explicit cancellation is processed. Normalization rejects malformed or
cross-session inputs instead of treating them as another session's observations.

The outside interpreter owns launch claims, acknowledged receipts, uncertain
launch reconciliation, bounded transport serialization, connection/process
ownership, and cleanup verification. Repeated propagation of an acknowledged
effect must not launch another child. A socket disconnect is not implicitly a
cancellation. Session disposal and explicit cancellation have distinct contracts.
Recorded replay supplies recorded observations with live handlers disabled.
The implemented pure policies, session-scoped fact merge, effect boundary, and
controlled transport tests exercise these contracts. They do not by themselves
establish real plugin confinement, remote delivery, or live grading acceptance.

## Pi package boundary

The selected plugin is a session dependency. Do not install both plugins into one
session or infer API compatibility from their common purpose:

- Pinned `pi-subagents` 0.81.0 uses `subagents_enable` and `subagent`, including
  package-specific status/stop actions.
- Pinned `pi-better-subagents` 0.19.1 uses `subagent_spawn_batch`,
  `subagent_result`, and `subagent_stop`.

Package-specific request/response translation belongs behind the Pi interface.
Pi owns its children; this harness does not duplicate Pi's lifecycle interpreter
or the FSM's transition rules. See the [pinned comparison setup](../../../benchmark/pi-subagent-comparison/README.md).

## One session extension and its outside interpreters

The host entry point is `examples.lain.subagents.harness-session`. Its
`session-extension` combines socket/user, evaluation/Harbor, and Pi primitive
bindings. `open!` loads the actual orchestration with that extension and composes
the session's runner with the fact domain; no global merge methods are installed.
`FactLog` is an immutable leaf value containing identified event facts ordered by
`[order id]`. Its merge rejects foreign sessions and conflicting reuse of an event
ID, and is associative, commutative, and idempotent. The current scheduling offer
is a derived value with withdrawal support, separate from accumulated facts.
The three host ingress cells are explicitly connected to the actor observation
endpoints; publishing to an observed copy of a slot is not an input connection.

| API | Responsibility |
| --- | --- |
| `(session-extension identity pi-config)` | Construct the one primitive-extension bundle; no outside calls. |
| `(open! identity options)` | Create the owned session, load the orchestration, and attach explicitly selected interpreter dependencies. |
| `(observe! owner channel fact-log)` | Publish facts through the owning `:socket`, `:pi`, or `:harbor` inlet. |
| `(pump! owner)` / `(pump! owner max-steps)` | Claim and dispatch currently eligible requests up to a bound, returning observations; no duplicated FSM transition rules. |
| `(poll! owner poll-id)` | Perform one explicitly identified Pi collection pulse; the caller owns timing and retention. |
| `(facts-at owner port)` | Inspect a fact port, such as `:assignments` or `:results`, through its public leaf interface. |
| `(current-offer owner)` | Inspect the current assignment offer; unavailable information is not an empty queue. |
| `(socket-port owner)` | Inspect the optional owned loopback listener's port. |
| `(artifacts owner)` | Export bounded plain ordered facts, receipt-entry vectors, and ownership metadata. |
| `(close! owner)` | Dispose owned resources and cache the cleanup outcome; incomplete cleanup is an error. |

`artifacts` returns plain transport data, not `FactLog` records, vector-keyed maps,
or actor graphs. Harbor and socket receipts are entry vectors. Pi receipts expose
trial entries by assignment and polling entries by assignment/poll identity.
The export is checked by the shared bounded-data validator. `close!` marks ingress
closed under the harness lock, then joins resources outside that lock so a client
callback cannot deadlock while waiting to publish a late observation. Repeated
close calls return the same recorded success or failure.

`pi-config` explicitly identifies `:plugin`, its pinned `:version`, `:provider`,
`:model`, an absolute `:cwd`, and the admitted `:tools` vector. Execution supplies
`:pi-hooks` with `:call!`, `:reconcile!`, and `:dispose!`, and `:harbor-callbacks`
with `:prepare`, `:grade`, and `:dispose!`. These are dependencies, not inferred
capabilities. Pi receipt recording can be supplied through `:pi-hooks :recording`;
`:recording-paths` selects the Harbor and socket boundary recordings.

| Mode | Behavior |
| --- | --- |
| `:observe` (default) | Derive and inspect relationships; `pump!` invokes no live handlers and no socket listener is opened. |
| `:synthetic` | Invoke explicitly supplied controlled callbacks. Registered fixtures require no model inference; a controlled loopback socket is optional. |
| `:replay` | Supply recorded observations through the inlets; the pump invokes no live handlers and live socket options are rejected. |
| `:live` | Invoke explicitly admitted Pi and Harbor interpreters. A user socket still requires `:socket-options {:mode :live}`. |

The socket binds only `127.0.0.1`, using an ephemeral port by default. It is an
application command boundary, not the protected editor's instance/debug API.
Foreign-session envelopes, malformed JSON/UTF-8, and oversized frames are wire
rejections. A well-framed unsupported command produces domain rejection feedback
without fabricating an assignment or cancellation. Disconnect alone is not a
cancellation. `close!` releases the listener and owned client threads.

## Effect receipts and bounded transport

The outside effect boundary records a stable claim before invoking an interpreter.
Acknowledged effects are not redispatched by reactive reevaluation. Uncertain
execution, including a possibly partial socket write, requires reconciliation or
an explicit operator decision rather than a blind retry. Recordings preserve the
session and effect identities; one session cannot consume another's receipts.

The socket's `received` response acknowledges framing and retention, before domain
feedback. A successful `send!` reports local writes, not a remote client's delivery
acknowledgement. No connected recipient reports `:not-connected`; a possibly
partial write reports `:uncertain` with the completed-write count. These outcomes
must remain visible in receipts.

Transport adapters serialize bounded resolved event/request fields. They do not
materialize an actor, workflow, state graph, or the complete propagator network.
Unknown or contradictory fields, unsupported objects, and exceeded transport
bounds fail explicitly. Socket feedback projection and Pi bridge translation are
outside boundaries; the live relationships continue to use connected slot views.

## Verification and live acceptance

Run controlled checks from the repository root:

```sh
clojure -M:test \
  propagators.compiler-2-harness-primitives-test \
  propagators.compiler-2-harbor-extension-test \
  propagators.compiler-2-harness-effect-boundary-test \
  propagators.compiler-2-socket-extension-test \
  propagators.compiler-2-pi-extension-test \
  propagators.compiler-2-harness-session-test \
  propagators.compiler-2-subagent-harness-test \
  propagators.compiler-2-combinator-test
node --test examples/lain/subagents/pi_bridge_test.mjs
```

The six primitive/boundary/session suites are registered in the ordinary Clojure
CI floor. `deploy/ci.sh` also runs the offline Node bridge fixtures and already
requires Node explicitly. Those fixtures exercise package translation and protocol
validation without invoking a live plugin or model. The composition tests load the
actual Lain files and exercise late binding, ordering, separate status/completion
paths, feedback, duplicates, replay, and independent sessions. Socket tests also
use controlled loopback connections and verify owned cleanup.

Controlled checks and the combined synthetic harness are distinct from live
Harbor/Pi acceptance. The admitted live bridge currently targets pinned
`pi-better-subagents` 0.19.1 in the existing pilot environment; pure request/response
translation for both pinned packages does not establish both live integrations.
Real child tool inventory, confinement, durable reconciliation, task correctness,
and verified lifecycle cleanup still require live evidence. Grading is unchanged.

At the current GX10 handoff, no new live experiment or model inference was run:
the established SSH control connection stopped responding, and fresh public-key
authentication was denied. Earlier inventory also showed other active Docker
jobs, so available RAM alone was not sufficient admission evidence. Restore the
connection and repeat the existing ownership/resource admission before running a
live smoke. This is not a deployment or scored-benchmark acceptance claim.
