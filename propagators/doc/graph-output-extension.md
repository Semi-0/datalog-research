# Additive graph output boundaries

`network-builder/extend-propagator-outputs` returns a new immutable Net with
additional propagator-to-cell edges. `network-patch/extend-propagator-outputs`
describes the same operation as a declaration effect for the existing runner.

```clojure
(patch/extend-propagator-outputs application-propagator-id [member-port-id])
```

The target must already be a propagator. Output identifiers must identify cells
or be unused NodeIds. Validation precedes construction, so a rejected boundary
leaves the caller's Net unchanged. Missing output cells are declared. Existing
cell values and graph edges are preserved, and duplicate additions are a no-op.

Both sides of each edge are updated: the propagator's graph outputs include the
cell, and the cell's graph inputs include the propagator. This exposes a growing
interface to graph observers without a separate connectivity index.

The constructor's original positional activation arguments remain unchanged.
Extending graph outputs does not recreate the activation function, schedule it,
or change its value-return arity. Body propagators continue to perform writes.

New cells belong to the effect emitter in the creation relationship graph.
Existing cells and the target propagator keep their creation relationships.
Dataflow membership and creation ownership are distinct.

Applications may extend their graph interface before output values arrive.
Completion is a separate semantic status; it cannot be inferred from the
current edge count. Removal, activation-interface mutation, and scheduler
policy are outside this contract.
