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

The following names are deliberately unresolved policy propagators. A session
extension supplies their pure transformations, using its ordinary source-loading
or primitive-extension mechanism; it may do so after construction.

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
These are required adapter contracts, not guarantees established by unresolved
policy names alone.

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

## Verification and remaining implementation

Run from the repository root:

```sh
clojure -M:test propagators.compiler-2-subagent-harness-test \
  propagators.compiler-2-combinator-test
```

The harness tests load the actual `composition.lain`, extend deferred policies,
and exercise connected fields, user ordering, runtime commands/status/completion,
Harbor grading feedback, repeated publications, controlled replay, and independent
sessions. Fixture policies use small normalized field shapes and perform no I/O.
They establish composition behavior, not a real pending-work scheduler or a
production socket/Pi protocol implementation.

Still required before live acceptance: production policy primitives, the selected
Pi adapter and socket interpreter, real receipt/reconciliation persistence,
protocol validation, and cancellation/disposal cleanup probes. These declarations
are ready for those session extensions; they are not a completed live Harbor/Pi
benchmark and do not change grading.
