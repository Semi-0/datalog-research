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
