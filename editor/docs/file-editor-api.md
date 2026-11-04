# Files and editor bridges

File-backed blocks watch the server workspace. A clean editor refreshes its main
source when the file changes. A dirty editor displays both versions and pauses
Run until the conflict is resolved. Either resolution preserves the other version
in a separate, unlinked block. File changes never execute code automatically.
Run saves the main source first and verifies its content and version again before
execution. Extension drafts remain separate.

The file service is independent of session creation. Set `LAIN_EDITOR_FILE_SOCKET`
to an absolute Unix socket path in an existing directory, or supply `:socket-path`
to `server/start!`. Production uses the deployment state directory's
`editor-files.sock`. The socket is owner-only (0600). Each request and response is
one JSON line; responses contain `id`, `ok`, and `result` or `error`. The same
operations are available through the authenticated editor HTTP API.

Paths are relative to the configured server workspace. Absolute paths, traversal,
and symlinks are rejected. Sources are limited to 1 MiB. Versions are SHA-256
content hashes, suitable for conditional writes.

```json
{"id":1,"op":"files/list","path":""}
{"id":2,"op":"files/read","path":"examples/demo.lain"}
{"id":3,"op":"files/watch","path":"examples/demo.lain"}
{"id":4,"op":"files/changes","paths":["examples/demo.lain"],"since":0}
{"id":5,"op":"files/write","path":"examples/demo.lain","baseVersion":"<hash>","source":"(define x 1)"}
{"id":6,"op":"files/append","path":"examples/demo.lain","baseVersion":"<hash>","source":"\n(define y (+ x 1))"}
```

Omit `baseVersion` only to create a new file. Existing files require their current
version; conflicts fail without overwriting. `files/poll` accepts a `paths` array
and rescans snapshots. `files/changes` returns cached watcher observations newer
than `since`, plus a process-local sequence cursor. Watch paths and reset the
cursor after reconnecting to a restarted backend. Observations contain the latest
snapshot, rather than an edit history. Writing or watching never creates a session.

The explicit editor bridge targets a connected browser workspace. List views to
obtain its workspace ID, pane ID, and current owner generation, then queue a command:

```json
{"op":"editor/list"}
{"op":"editor/open-file","workspace":"<workspace>","path":"examples/demo.lain"}
{"op":"editor/extend-code","workspace":"<workspace>","pane":"<pane>","generation":"<generation>","source":"(define next (+ x 1))"}
{"op":"editor/extend-file","workspace":"<workspace>","pane":"<pane>","generation":"<generation>","path":"extensions/next.lain","version":"<hash>"}
{"op":"editor/extend-primitives","workspace":"<workspace>","pane":"<pane>","generation":"<generation>","path":"extensions/primitives.clj","version":"<hash>","entry":"extensions.primitives/bindings","revision":0}
{"op":"editor/status","workspace":"<workspace>","request":"<request>"}
```

Commands return a request receipt; poll status until `applied` or `failed`. An
applied extension receipt includes the current owner metadata. Use its generation
for the next extension. Stale generations fail without replay. Extension commands
reuse the existing owner and preserve unrelated editor drafts. File versions are
checked again before application. Native primitive factories are trusted Clojure
code, with the existing primitive environment contract.

The socket deliberately rejects `open-session`. Session lifecycle operations use
the session API; the `file-bridge` adapter is the explicit connection between
captured files and existing runtime owners.
