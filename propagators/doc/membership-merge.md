# Membership-aware TTMS merge

The generic cell merger recognizes `:membership/observations` as an explicit content domain. Existing support collections retain their existing handler. This domain is useful when a changing compound depends both on present values and on excluded members.

A publication has these layers:

```clojure
{:base compound-value
 :support #{active-value-premises}
 :membership #{membership-premises}
 :premise-state #{known-source-states}}
```

`:support` requires usable active values. `:membership` records decisions that selected this compound; a decision may require a source's retraction. `:premise-state` is knowledge about source freshness, not an extra value dependency. Source identities and timestamps are original premises, not inferred from matching payloads.

For example, replacing `[7 8]` with `[8]` after A is retracted at version 2 uses B's active premise as value support and A's version-2 retraction as a membership guard. The result remains usable. A stale accessor update supported by A at version 1 cannot restore the removed structure.

Raw content is an observation set plus a joined source frontier. Merge unions observations and joins frontiers. It does not discard observations, so merge is associative, commutative, and idempotent. Tests enumerate all triples of representative contents, including empty state, independent sources, retraction, and contradiction.

Projection selects an observation only when its value premises are active, its value and membership premises are mutually compatible, and each required premise exactly matches the unique current state of its source. Conflicting statuses at the same source version select neither claim. The base domain's ordinary generic merge and strongest-value projection combine the selected payloads. Contradiction keeps its original provenance and can disappear when its supporting version is superseded.

The ordinary identity routes, layered procedure publication, Compiler-2 scalar application, and bidirectional slot transport preserve membership guards. Membership-aware slots project and embed observations with their original premises, forwarding source states in both directions. Absence is valid membership evidence, not an available value input. Freshness resolves versions of the same source; it does not make arbitrary claims from independent sources overwrite one another.

Opt in before installing a slot accessor by seeding its owner with an empty membership container. This avoids retaining the ordinary accessor's earlier unversioned structural assertions. Already-retained legacy evidence can be lifted into the new domain, but lifting cannot recover observations previously discarded by the legacy handler.

This is an explicit additional merge domain, not a new rule for every ordinary map. It retains historical evidence, so no bounded-memory or garbage-collection guarantee is claimed. See `examples/lain/network_replay` for a compiled reducer and display consumer.
