# Scoped compiler sandbox

The CLI sends explicit owner operations and records mutation UUIDs before network
I/O. After an uncertain outcome, query its receipt; never blindly replay a mutation.
Keep scoped configuration files mode 0600. No credentials belong in Git.

On the Mac, forward the broker to loopback port 19580, then use:

```sh
python3 deploy/session_sandbox/client.py --config /private/scope.json status
python3 deploy/session_sandbox/client.py --config /private/scope.json editor --assets /path/to/editor/public/lain-editor
```

On GX10, the explicit `--local` option selects the broker's loopback port 48870.
The editor command prints a sandbox-specific localhost URL and binds loopback only.
It serves the built editor assets and exposes reads and its own non-owning view
lease. Refresh translates the editor workspace into view metadata without forwarding
source or recompiling. Browser program mutations are refused. Use the CLI for
explicit owner operations. Neither closing the browser nor detaching its view stops
the owner. Pending receipts must be reconciled before reconnecting.

`bundle.py` uses the current Clojure CLI and `JAVA_HOME` (or Java on PATH). Build
and verify the frontend first; the frozen bundle runs an isolated arithmetic
preflight before it can be installed. Bootstrap refuses to replace active owners.
The repository CI does not automatically replace the experimental broker or its
frozen runtime; active preview owners remain independent of editor deployment.

See `propagators/doc/session-api.md` and `immutable-compiler-sessions.md` for the
compiler boundary, injected effects, admission and identity contracts.
