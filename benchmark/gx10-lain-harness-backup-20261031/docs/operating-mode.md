# Operating mode: parent = plan orchestrator, subagents = deliverable owners

Adopted 2026-10-27 at the user's request. Binding for this experiment.

## Parent responsibilities (foreground) — high level only
1. **Own the plan**, not the details: a unit ledger in `progress.json.plan[]` with, per unit,
   `id`, `deliverable` (verifiable claim), `owner` (subagent id / foreground), `status`
   (`ready|running|done|blocked|superseded`), `evidence` (receipt path), `depends_on`.
2. **Dispatch deliverable-granular units**: each spawn states ONE verifiable deliverable, hard
   boundaries (no live/model/container/network unless the unit is explicitly live), where to
   write receipts **incrementally**, and the exact final-message format. No "research X" units.
3. **Interfere only on problems**: read a result when it lands, or intervene when a unit is
   blocked/deadline-signalled/contradicts another receipt. Never narrate a unit's internals.
4. **Never poll.** No sleeps, no result/output loops. Terminal results arrive as callbacks.
   Mid-run output is read only (a) when the user asks, or (b) when a deadline/stuck signal says
   the unit may be overrunning — then extract partial progress instead of discarding it.
5. **Integrate before verifying**: no milestone, gate, or final claim is marked done while a
   relevant unit is unintegrated. Launch is never progress.
6. **Reassess readiness after every launch/result**, and keep using free capacity for ready,
   independent units instead of doing their work serially in the foreground.

## Retrieving progress from units that overrun (mandatory)
- Spawn with a **soft deadline** + grace, and require receipts written **incrementally** so a
  killed unit still leaves evidence.
- On `status=deadline` or `status=stuck`: read `subagent_result`, then `subagent_output`
  (`mode=raw`) for the last evidence; harvest whatever receipts exist on disk; record the unit
  as `partial` with exactly what is and is not proven; re-dispatch **only the missing slice**
  (never the whole unit again, and never a duplicate of a running unit).
- A deadline kill is **not** a gate failure and not a pass: it is a blocker plus harvested facts.
- Long Clojure gates: one warm JVM per unit, realistic deadlines (≥40m), verified `-Sdeps
  :paths` command form (the `-Spaths` form does not exist; `:extra-paths` merge silently fails).

## Boundaries that stay with the parent (never delegated)
- Deciding whether a component is *done*, what the architecture *conforms* to, and whether the
  live single-child trial may start.
- Any live effect (the one evaluated Lain child), admission/security changes, and any write to
  the immutable release or shared editor/model.

## Unit template (used verbatim in every spawn prompt)
> Deliverable: one verifiable claim. Boundaries: what must NOT happen. Evidence: write
> `receipts/<unit>/…` incrementally, including exact command, exit code, revision, and
> `Ran N tests containing M assertions`. Final message: ≤10 lines, claim + verdict + evidence
> path + the single remaining gap.
