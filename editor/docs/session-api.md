# Managed editor sessions

Ordinary Run uses the immutable session API and its host boundary. An explicit
`compilerSession: false` selects the legacy compatibility runtime. Backend-owned
managed sessions continue when all editor views close. Stop session retires the
owner and verifies effect cleanup. Externally owned views remain read-only.

POST commands to the existing authenticated `/api` endpoint. Responses retain the
`{ok, result}` / `{ok: false, error}` envelope. Successful managed operations return
`sessionId`, `runId`, `generation`, `compilerSession`, committed `source`, ordered
`extensions`, ordered `appliedExtensions` (`{id, source}`), `runtime`, and `lifecycle`.

```json
{"op":"open-session","source":"(define result 42)","extensions":[]}
{"op":"session-status","sessionId":"<sessionId>"}
{"op":"extend","sessionId":"<sessionId>","generation":"<generation>","source":"(define extra (+ result 1))"}
{"op":"complete","sessionId":"<sessionId>","generation":"<generation>"}
{"op":"inspect","sessionId":"<sessionId>","generation":"<generation>","display":{"cell":"result","mode":"value"}}
{"op":"list-sessions"}
{"op":"reopen-session","sessionId":"<sessionId>","pane":"view"}
{"op":"close","pane":"view"}
{"op":"stop-session","sessionId":"<sessionId>","generation":"<generation>"}
```

Run uses the same service with `{op: "run", pane, source, extensions, generation}`.
Mutations carrying a stale generation fail before compilation or retirement.
Inspect current metadata before retrying; requests are never automatically replayed.
The catalog also supports headless inspect, completion, and extension.

Close extension editor and Reopen extension editor change only local visibility.
Remove extension from runtime… confirms that the run and remaining effects will
restart, then sends `remove-extension` with `sessionId` (or `pane`), `generation`,
and the selected `extensionId`. It rebuilds the committed base and remaining
committed declarations in order, preserving their stable IDs and the session ID
while assigning a new run ID. It never includes unrelated draft edits or rereads
committed imports. Base and bootstrap capabilities are not removable.

Editor compilation retains a 100,000-step limit and allows 180 seconds for the
existing interactive search programs. The general immutable session API keeps its
60-second default; this is an editor policy supplied through its supported
`propagation-options`.

Candidates compile before retirement. Host `replace!` and `rerun!` share verified
retirement; old callbacks are rejected. Cleanup failure leaves
`lifecycle: "retirement-failed"`, blocks activation and execution, and never
repeats cleanup or starts a replacement automatically. Inspect/reconcile the host
receipts explicitly; Stop can complete once resource retirement is verified.

Portable workspace formats and source/layout data are preserved. Import restores
drafts without executing them. Browser reconnect references are saved separately
under `lain-tiled-editor-reconnect-v1`; `restore` accepts a pane-keyed `reconnect`
map containing session/run/generation references. Existing owners reconcile to
current metadata without recompilation. Missing owners after a backend restart
require explicit Run. Unsaved base code, extension edits, and pending drafts are
preserved; edits to extensions removed elsewhere are recovered into the pending
draft.

Closing a managed view is not test teardown. Tests and health probes must
explicitly stop temporary owners. `editor/close!` is explicit service teardown and
stops owned entries, including owners without views.

## Reloading a live definition

The editor palette action **Reload definitions in this session** submits the full
main source with the current owner generation. The same command can target a
headless owner.

```json
{"op":"reload","sessionId":"<sessionId>","generation":"<generation>","source":"<complete revised main source>"}
```

A successful revision returns its `reloaded` names. The compiler selects a new
lexical child for future evaluations. The owner, run ID, user ingress cells and
outstanding callbacks remain live. Each changed binding and its dependent
network definitions are declared in the same child. The editor also checks
transitive `load-lain` snapshots. A reload with identical main text can pick up
an edited imported Lain definition.

An ordinary application already installed in the graph retains its original
closure. To choose a revision for each incoming request, wire an identified
packet through `editor:live-call` when declaring the session.

```clojure
(define request-policy (network [packet] packet))
(define next-packet (editor:live-call request-policy incoming-packet))
```

Each incoming finite packet needs a unique, nonempty string `:id`. This site
captures the packet and callable on first dispatch. Outstanding requests finish
in their original closure. The live output follows the current request ID, so a
late completion from an older request cannot overwrite it. An existing Soul
program can place this site between its conversation request and model input.
Previously installed ordinary wires continue to use their original closures.

Reload supports adding and editing top-level network and literal definitions
while preserving callable arity. Changes to installed wiring, removed bindings,
and changed native primitive artifacts return an error. A rejected revision
keeps the current generation and the local draft. The palette action changes
the session source; the editor's Save file action persists a file-backed draft.

The editor service must load the version that provides `reload` and
`editor:live-call` at startup. Running owners on an older JVM keep their
existing capabilities. Installing a request boundary for the first time changes
the wiring and needs an explicit new run. For Soul, one possible initial site
is `(editor:live-call request-policy (conversation-request chronology))` where
`request-policy` is a named unary network and its result feeds the model input.
