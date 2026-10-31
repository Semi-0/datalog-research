# Guide for the orchestrator agent

Read this guide before decomposing work. It is operator guidance; adding this file does not automatically inject it into an existing Pi session or change a runner's prompt.

## Own the plan and evidence

Turn the task specification into independent, verifiable deliverables. For each unit, record its owner, dependencies, writable paths, acceptance command, receipt path and status. Resolve overlapping writes before dispatch. Dispatch only units whose dependencies are satisfied, within the current experiment's authorized concurrency and memory budget. A requested team size is not proof that its contexts fit in RAM.

For the main Lain harness, extend the existing session's capabilities through the session extender, RPC and focused primitive propagators. Let the main Lain composition determine execution relationships and eligibility. Keep RPC and the effect pump responsible for delivery, stable claims, receipts, uncertain-delivery reconciliation and cleanup. Do not introduce a second host scheduler that duplicates these decisions. The editor attaches to the session; its lifecycle must not own execution.

## Delegate through a small task file

Write one bounded specification per worker. Include:

- The goal and one concrete deliverable.
- The relevant source revision and prerequisite receipts.
- Required reading, with specific sections and paths; distinguish binding requirements from historical observations.
- Owned writable paths and read-only dependencies.
- Constraints, acceptance commands and expected evidence.
- An incremental receipt path and a concise final-response format.

Give the worker a short request pointing to that file. Use an independent child session; do not paste the parent's full conversation or assume that a fresh process alone proves which context was supplied. Verify the launch configuration and retained worker prompt. Ensure the specification and dependencies are readable at the paths visible inside the worker's sandbox. Host paths are not automatically sandbox paths.

Example worker request, after making these paths available inside its workspace:

```text
Read /app/tasks/pi-result-delivery.md. Complete only its deliverable.
Write incremental evidence to /app/receipts/pi-result-delivery.md.
Return status, changed paths, test counts and the receipt path.
Do not launch a live trial or spawn another team.
```

Example specification outline:

```text
Goal: verify that completed Pi results reach the main Lain session.
Deliverable: a reproducible result-delivery check and its receipt.
Revision and prerequisites: <exact candidate revision; prerequisite receipts>.
Read: <contract path and relevant sections; component source and tests>.
Ownership: <explicit writable files>; all other paths are read-only.
Constraints: preserve Lain composition ownership; no live model/container changes.
Acceptance: <exact component test command and required assertions>.
Evidence: record commands, revision, assertion/failure/error counts and blockers.
Receipt: /app/receipts/pi-result-delivery.md; update after each meaningful check.
Final: PASS|FAIL|BLOCKED, changed paths, test counts, receipt path.
```

Replace placeholders before dispatch. Verify required source/test paths, namespace names and the command's working directory against the actual candidate. State whether a test path is an existing reference or a new deliverable. Ask for an early plan or hypothesis, then evidence; do not demand a verdict before investigation. Permit edit-test-fix cycles within the time and resource budget rather than limiting a worker to one test invocation. Reuse a warm JVM when practical.

A short request reduces duplicated parent history; it does not make subsequent file reads free. File contents still enter model context. Request relevant sections and bounded source reads, and keep intermediate logs on disk. An `@file` expansion that inserts the whole file into the prompt provides no token saving over pasting that content.

## End the coordinator turn after dispatch

For Pi's callback-based subagent runtime, ending the assistant turn stops foreground model generation while leaving the interactive Pi process alive. Completion callbacks can then start a new turn. Saying that you are waiting while continuing to reason still occupies an inference request. Enable callbacks, retain the launch receipt and end the turn; do not poll, sleep or hold a tool call open waiting for completion.

Use this instruction when the assigned independent workers own the current work:

```text
Dispatch the independent tasks with completion callbacks enabled.
Record their IDs and ownership once, then END YOUR ASSISTANT TURN.
Leave the Pi process running. Do not continue foreground investigation,
reasoning, plan narration, sleeping or status polling.
On a completion callback, collect that worker's result and receipt.
If other independent workers remain, END YOUR TURN AGAIN.
Integrate and run final gates only after all implementation workers
are terminal. Handle failure/resource alerts promptly; reconcile
uncertain launches and do not create unauthorized replacements.
```

This is a coordination policy, not a runtime enforcement guarantee. Confirm an actual terminal assistant response and no new parent model request between callbacks. A naturally idle coordinator removes its active request; it does not prove that every worker context will fit in the KV pool.

## Collect, integrate and gate execution

Use the admitted runtime's completion notifications rather than repeated model polling. Collect every worker's result and receipt. Inspect missing, partial or contradictory evidence, integrate changes, then verify the combined candidate. A successful process exit does not establish a successful deliverable. A timeout is neither a pass nor permission to proceed; preserve partial evidence and reconcile any existing child before considering another authorized attempt.

A test worker running concurrently with implementation may report baseline checks only. It cannot certify the final integrated candidate. After every implementation worker finishes, integrate the changes and rerun the required gates. Identify the tested working tree with both its commit and its uncommitted changes; HEAD alone does not identify a modified candidate. Keep the tested tree stable throughout the final gate.

Before a live main-harness task, every involved component's unit suite must pass against the exact integrated candidate, with positive assertion counts. Exceptions, timeouts, propagation-budget exhaustion, failures, skipped-only results and zero assertions block live execution. Controlled or synthetic integration checks do not replace these gates. Preserve existing resource guards and reconcile uncertain delivery without duplicate launches. Report observed evidence separately from planned work and unverified claims.

## Existing GX10 experiment documents

Under `/home/lain/experiments/lain-harness-flash-once-20261027/`:

- `docs/operating-mode.md` describes coordinator responsibilities and deliverable ownership.
- `docs/integration-design.md` records the main-session integration contracts.
- `docs/live-run-checklist.md` records the gated single-child trial procedure.
- `task/assignment.txt` is only the small sum-to-seven evaluation fixture, not the architecture specification.

Read these selectively for the assigned unit. Revalidate recorded revisions, commands, deadlines and paths before execution. Historical Harbor team fanout instructions do not override the current main-harness trial's single-child authorization or the component gates.
