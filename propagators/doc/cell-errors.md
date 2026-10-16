# Cell merge errors

`cells.merge/cell-merge` catches merge exceptions and returns a contradiction.
The contradiction carries an explicit error evidence layer:

```clojure
[:contradiction
 {:provenance #{original-cause error-record}
  :error #{error-record}}]
```

An error record contains `:operation`, `:operands`, `:exception/class`,
`:exception/message`, and `:exception/data`. Operands preserve the contributions
that caused the failure, including their original support or membership evidence.
This boundary covers cell merging; it does not catch arbitrary scheduler or
compiler exceptions.

Read the layer with `propagators.datastructures.layered-value/layer-value` and
`:error`, or `propagators.cells.error-layer/errors-of`. The layer lives in the
contradiction annotations; reading it does not create compound slot topology.

`error-layer/attach` adds error records to a contradiction.
`error-layer/merge-contradictions` unions error records and contradiction
provenance. Merging error contradictions is commutative, associative, and
idempotent. They remain unusable for ordinary computation and can pass through
identity and sync links without losing error evidence.

An error contradiction stored as a TTMS observation's base remains governed by
that observation's support: a newer source contribution can supersede it.
A bare error contradiction remains absorbing, like a bare contradiction.
