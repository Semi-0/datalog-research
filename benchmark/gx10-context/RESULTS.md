# Protocol v3 results

Current fresh-run state, all outcomes, context occupancy and RAM measurements are
in [PROTOCOL-V3-REPORT.md](PROTOCOL-V3-REPORT.md). Validation commands, evidence and
exploratory preservation are in [VALIDATION.md](VALIDATION.md). Until that report
says complete, the protocol-v3 matrix/scan is incomplete.

The observations below are historical protocol-v2 diagnostics. Their original
not-started status predates the later exploratory run; none establishes a fresh
protocol-v3 maximum or model ranking.

# Historical context experiment results

Maximum-context scan: **not started**. No tested maximum or recommended maximum
has been established for any of the three artifacts.

| Artifact | Native metadata limit | Verified configured workflow | Maximum-context result |
| --- | ---: | --- | --- |
| Ornith BF16 | 262,144 | Prior monitored 32K coding diagnostic, with continuations | Not measured |
| Ornith Q4_K_M | 262,144 | Previous web/thinking preflight; no scored context scan | Not measured |
| Flash-Next IQ4_XS | 262,144 | Previous web/thinking preflight; no scored context scan | Not measured |

## Prior BF16 diagnostic, 21 October 2026

The structured inventory and observed result records are in
`results/2026-10-21-inventory-and-prior-diagnostic.json`. They were read back
from GX10 before this document was published; no new inference was requested
to produce that inventory. Raw diagnostic sessions remain on GX10 at the
recorded source directory, outside this repository.

The original 16K attempt failed before completion. A captured resumed request
allowed only 188 response tokens, and the upstream stream ended without a
completion marker. This supports a context/output-budget and stream-protocol
problem; it does not prove all previous model failures share that cause.

With a separate 32K alias and an 8192-token compaction reserve, the agent
completed coding in **576.97 seconds**. Its own suite passed **9 tests / 20
assertions**; the unchanged independent suite passed **3 tests / 14 assertions**.
Only the two task files changed. Thinking was observed and web search succeeded,
but both fetches failed during that timed attempt, so its full protocol result
was **failure**, despite correct code.

A URL-guided continuation was cancelled before any tool action and excluded.
A separate session snapshot omitted that user message. On that branch the
agent received a reminder to satisfy the failed fetch requirement, selected
its own URL, and fetched official Clojure source from GitHub. That continuation
timed out after **300.02 seconds**, including large-response compaction. A final
"continue" request completed in **35.82 seconds**, with both suites still passing.
Total recorded Pi time for the retained 32K trajectory was **912.81 seconds**.
No code fixes or successful reference URL were supplied on the retained branch.

No swap transfers were observed in these three 32K segments; minimum available
RAM was **45,534,142,464 bytes (~42.4 GiB)**. The diagnostic demonstrates one
completed monitored coding workflow. It is **not** an unassisted ten-minute
success, an isolated repetition under the new protocol, a filled-32K input test,
or a maximum-context measurement. The diagnostic session resumes and earlier
global configuration reuse make it unsuitable for scoring under the new
fresh-environment requirement.

## Future result records

The retained `result-template.json` describes historical protocol-v2 records.
Protocol v3 writes its result/checkpoint records directly through the shared
lifecycle, including every failure and interrupted retry. Keep native capacity/retrieval results separate from Pi
workflow results and prior diagnostics. Record isolation validation before
scoring. Do not substitute an agent's claim of success for independent checks.
