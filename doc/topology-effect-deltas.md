# Incremental topology lowering

Flat GUR invokes a compiler closure to produce topology in an immutable Net.
The lowering bridge translates that additive result into declaration effects
and cell messages. It must not republish unchanged lexical metadata.

The bridge previously generated the complete lexical index on each invocation
and scanned all cells twice. A two-node, bidirectional Lain trace performed 520
diff calls and replayed 126,759 identical name declarations. The debugger
measured 856 ms inside diff construction alone.

The bridge now selects unpublished lexical attributes and bindings against the
base Net's actual name dictionary before constructing effects. Comparing only
the internal lexical index would be incorrect: an indexed binding may not yet
have been published. Changed current-binding identities are still emitted.

Cell declarations and messages are collected in one scan. Unchanged cell
objects are skipped; changed content is compared explicitly, preserving the
existing nothing and evidence semantics. Public helper signatures are retained.
Environment implementation, flat GUR, scheduler, merge, TMS, and runner are
unchanged.

In a debugger run, the same cyclic trace completed in 2.30 seconds. Diff time
fell to 287 ms and identical name replays fell to 2,199. Remaining duplicate
names originate outside this bridge, including captured delayed-body snapshots.
Both nodes were expanded once; the trace returned the two directed edges.

The generated topology remains 9,769 cells/propagators. This change fixes
redundant lowering work; it does not claim to reduce that topology or establish
a performance guarantee for larger graphs. Times are measurements, not fixed
bounds. Tests run with a three-second individual deadline.
