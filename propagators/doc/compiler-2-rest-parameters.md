# Rest parameters in anonymous networks

Anonymous networks (`::`, also spelled `cell-expr`) accept a final rest binding:

```clojure
(def first-extra (:: [x & xs] (car xs)))
(first-extra 1 2 3) ;; 2
```

Every supplied argument is an input. The body result is always the expression's
output. To connect that result to an existing cell, use `->`; passing an extra
cell does not designate an output. Fixed-arity anonymous networks reject extra
arguments. Explicit-output `network` and `def-net` retain their existing calling
convention; rest syntax in those declarations is rejected for now.

`&` must occur once, immediately before one final distinct binding name. Zero
trailing arguments bind the existing `:compiler-2/list-empty` marker. `(list)`
now produces that same marker.

Rest declarations compose a wrapper around the existing fixed-arity closure
constructor. `[x & xs]` becomes an ordinary closure with parameters `[x xs]`;
only its callable wrapper carries the rest signature. `rest-application` uses
`combinator/branch` to select the rest handler or ordinary callable delegation.
The ordinary closure representation and body-application path know nothing
about rest packing.

The rest handler declares a compound cons-list over inbound-only proxy
cells for the trailing arguments. It does not read their strongest values to
pack them. Late values and late callable definitions therefore remain reactive,
and writing through a rest-list accessor does not write back to caller inputs.
The generated topology uses deterministic application-local identities and the
normal flat-GUR declaration path. Runner, Net, TMS and compound-slot semantics
are unchanged. Application tracing retains the original caller argument cells,
not the internal packed-list argument. There is no private execution loop or
dynamic namespace resolution.

See `examples/lain/variadic-composition.lain` for ordinary Lain implementations
of `pipe` and `compose`, including empty and four-stage calls. No primitive
environment extension is needed. This adds rest binding, not an `apply`/spread
operator or general destructuring. Domain-specific adapters with fixed callback
signatures are not made variadic by this change.

Tests cover malformed parameters, fixed/explicit output contracts, empty and
multi-element lists, lexical capture, late values/callables, stable repeated
activation, no write-back, and the complete example file.
They also check wrapper/delegate separation, ordinary-handler forwarding,
unknown-handler rejection, and original-argument tracing (including zero rest
arguments).
