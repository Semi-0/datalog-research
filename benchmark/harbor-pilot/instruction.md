Implement a small Clojure function in `/app/src/pilot.clj`, namespace `pilot`:

`(stable-distinct-by key-fn values)` returns a vector containing the first value for each distinct key, preserving input order. Empty input returns `[]`. Nil and false are valid distinct keys. Do not modify any other source files.

Run your own finite assertion checks before finishing. Use `timeout 120` for each shell test command. Read the existing file, implement the function, check it with Clojure, and finish with a concise explanation. This is an unscored integration pilot, separate from the scored benchmark.
