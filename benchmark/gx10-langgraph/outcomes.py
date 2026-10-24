"""Normalize execution independently from correctness and workflow evidence."""
from dataclasses import dataclass
import json
from pathlib import Path


@dataclass(frozen=True)
class Execution:
    outcome: str
    reason: str
    requests: int


def classify_error(text):
    value = text.lower()
    if any(marker in value for marker in ("401", "403", "unauthorized", "invalid api key")):
        return "authentication_error"
    if any(marker in value for marker in ("429", "502", "503", "504", "connection reset", "connection closed")):
        return "transient_provider_error"
    return "provider_error"


def normalize(events, exit_code):
    """A settled or exit-zero process is insufficient evidence of a valid response."""
    requests = 0
    settled = False
    ended = False
    last_stop = None
    errors = []
    for envelope in events:
        event = envelope.get("event", envelope)
        kind = event.get("type")
        if kind == "provider_request":
            requests += 1
        if kind == "agent_end":
            ended = True
        if kind == "agent_settled":
            settled = not event.get("aborted", False)
            if not settled:
                errors.append(("interrupted", "Pi reported aborted settlement"))
        if kind in ("message_end", "turn_end", "assistant_end"):
            message = event.get("message", event)
            if message.get("role") == "assistant" or kind == "assistant_end":
                last_stop = message.get("stopReason")
                error = message.get("errorMessage")
                if last_stop == "aborted":
                    errors.append(("interrupted", error or "Assistant response aborted"))
                elif last_stop == "length":
                    errors.append(("incomplete_response", "Assistant output was truncated"))
                elif error or last_stop == "error":
                    detail = error or str(last_stop)
                    errors.append((classify_error(detail), detail))
    if errors:
        return Execution(errors[0][0], errors[0][1], requests)
    if exit_code != 0:
        return Execution("runtime_error", f"Pi exit code {exit_code}", requests)
    if not ended or not settled or last_stop != "stop":
        return Execution("incomplete_response", "Missing normal assistant ending, agent_end or non-aborted settlement", requests)
    return Execution("completed", "Normal assistant response and settlement observed", requests)


def retryable(execution, prior_attempts):
    return execution.outcome == "transient_provider_error" and prior_attempts < 3


def assessment(execution, reward, assertions, verifier_exit, workflow_valid, timing_valid):
    if assertions <= 0 or verifier_exit != 0:
        correctness = "invalid_verification"
    elif reward == 1:
        correctness = "passed"
    elif reward == 0:
        correctness = "failed"
    else:
        correctness = "invalid_verification"
    return {"execution": execution.outcome, "execution_reason": execution.reason,
            "correctness": correctness, "assertions": assertions,
            "workflow_valid": workflow_valid, "timing_valid": timing_valid}


def read_events(path):
    events = []
    with Path(path).open() as stream:
        for line in stream:
            if line.strip():
                events.append(json.loads(line))
    return events
