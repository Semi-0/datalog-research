# Temporal behavior and event removal

Temporal behavior and legacy event APIs have been removed, including their
compiler entry points, operators, merge registrations, history adapters, and
behavior-dependent benchmark. Git history preserves the previous implementation;
there is no supported legacy installer or automatic syntax translation.

Use `main/compile-source-with-tms` or `main/compile-expr-with-tms` for the current
TMS-enabled compiler. Runtime input, clock, bridge, and display boundaries now
publish supported values with explicit source identities and revisions through
TTMS. Generic primitive application preserves membership and support. Inputs
sharing a source must agree on its revision; mixed revisions remain pending.

Graph composition belongs to explicit graph-union propagators, not a global
semantic graph merge method. Revision changes and withdrawal use TTMS. Public
trace inspection continues returning graph payloads. Cell-history retention is
an explicit fold over immutable snapshots, independent of TTMS selection.

Both reducer-cell and reducer-subnet remain supported. Compound-object reduction
and dispatch still use reducer-subnet; removing temporal behavior does not make
those reducers obsolete. Reducer-subnet consumes ordinary public source slots,
not specially tagged event facts.

Unbound historical syntax retains ordinary waiting-cell semantics. Archived
programs using removed operators must migrate explicitly; merely loading their
source does not prove a successful run.

See [merge removal evidence and reproduction](../experiments/merge_deprecation/README.md)
for the retained dispatch inventory, counterexamples, verification, and limits.
Earlier behavior-specific documents and benchmark reports describe historical
implementations, not available APIs.
