# Values on semantic graphs

Open `workspace.json` from the editor's Open JSON menu. Desktop 1 shows scalar
values, desktop 2 shows each public layer of a layered input and its extracted
base, and desktop 3 starts with a `the-nothing` input for a late-message demonstration.
Fresh editor startup stays blank; these programs load only on request.

```clojure
(display
  (relationship:with-values
    (relationship:roots-semantic input)))
```

`relationship:with-values` is an ordinary one-input, one-output operator and can
be passed as a callable. Its pure graph modifier adds presentation metadata;
it does not rename nodes, change edges or aliases, or change values. Graph union,
explicit tracing and opening a child body retain the request. Cells gain value
rows below their names; propagators keep only their names. Cell frames stay
rounded, propagator frames stay square, and both keep code-sized text. Dimensions
grow to contain the rows so Vijual's size-aware layout reserves the space.

Every public layer gets a row, with `:base` first. Large nested layer values use
bounded previews, wrapped at 52 characters; raw sampled values remain available
through node inspection. False, nil, `the-nothing` and `the-contradiction` stay distinct.
Accessor source slots and named-network slots are read without writing them.

Observed value or relationship updates fill the affected node, invert its text,
and fade over 900 ms. Repeated updates restart that node's hint; unmount cancels
owned animations. Reduced-motion users get a brief static hint. The editor polls
visible outputs every two seconds, so this visualizes observed changes rather
than every scheduler activation. No activation timeline is inferred.
