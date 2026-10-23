"""Independent correctness, workflow and timing evidence."""
import re

EXPECTED_ASSERTIONS = {'distinct': 14, 'order': 17, 'events': 42}


def apply_recovery_evidence(result, audit_path):
    """Retain every failure; only explicit recovery evidence clears its workflow effect."""
    import json
    recovered_lengths = set()
    audit = []
    if audit_path.exists():
        audit = [json.loads(line) for line in audit_path.read_text().splitlines() if line]
    for event in audit:
        if event['kind'] == 'length_recovered':
            recovered_lengths.update(event['timestamps'])
    for failure in result.get('stream_failures', []):
        timestamp = failure.get('timestamp')
        if failure.get('stop_reason') == 'length' and timestamp is not None and timestamp in recovered_lengths:
            failure['recovered'] = True
    result['runtime_diagnostics'] = audit
    result['recovery_checked'] = True
    errors = [failure.get('error') for failure in result.get('stream_failures', [])
              if failure.get('error') and not failure.get('recovered')]
    result['unresolved_assistant_errors'] = errors


def test_counts(text):
    runs = re.findall(r'Ran (\d+) tests containing (\d+) assertions', text)
    endings = re.findall(r'(\d+) failures, (\d+) errors', text)
    if not runs or not endings:
        return {'tests': 0, 'assertions': 0, 'failures': None, 'errors': None, 'complete': False}
    tests, assertions = map(int, runs[-1])
    failures, errors = map(int, endings[-1])
    return {'tests': tests, 'assertions': assertions, 'failures': failures, 'errors': errors, 'complete': True}


def tests_passed(exit_code, counts, expected=None):
    return (exit_code == 0 and counts['complete'] and counts['assertions'] > 0 and
            counts['failures'] == 0 and counts['errors'] == 0 and
            (expected is None or counts['assertions'] == expected))


def thinking_evidence_satisfied(result):
    if result.get('thinking_required', True) is False:
        return True
    return bool(result.get('thinking_observed'))


def classify(result, own_text, acceptance_text):
    own = test_counts(own_text)
    acceptance = test_counts(acceptance_text)
    result['own_test_counts'] = own
    result['acceptance_counts'] = acceptance
    result['own_assertions'] = own['assertions']
    result['correct'] = tests_passed(result.get('acceptance_exit'), acceptance, EXPECTED_ASSERTIONS[result['task']])
    failures = result.get('stream_failures', [])
    unresolved = [failure for failure in failures if not failure.get('recovered')]
    errors = result.get('assistant_errors')
    if result.get('recovery_checked'):
        errors = result['unresolved_assistant_errors']
    thinking_satisfied = thinking_evidence_satisfied(result)
    result['thinking_requirement'] = 'required'
    if result.get('thinking_required', True) is False:
        result['thinking_requirement'] = 'not_applicable'
    workflow = all([tests_passed(result.get('own_tests_exit'), own), result.get('confined'),
                    thinking_satisfied, result.get('search_succeeded'), result.get('fetch_succeeded'),
                    result.get('agent_final_response'), result.get('agent_end_observed'),
                    not errors, not unresolved, not result.get('timed_out'),
                    result.get('pi_exit') == 0, not result.get('resource_stopped'),
                    not result.get('nonterminating'), not result.get('interrupted'), not result.get('supervision_stop'),
                    not result.get('workflow_diagnostics')])
    result['workflow_pass'] = workflow
    result['protocol_pass'] = result['correct'] and workflow
    if result.get('interrupted'):
        outcome = 'interrupted'
    elif result.get('resource_stopped'):
        outcome = 'resource_stopped'
    elif result.get('nonterminating'):
        outcome = 'nonterminating'
    elif result.get('supervision_stop'):
        outcome = result['supervision_stop']['outcome']
    elif result.get('timed_out'):
        outcome = 'timed_out'
    elif unresolved or errors or not result.get('agent_end_observed') or not result.get('agent_final_response') or result.get('pi_exit') != 0:
        outcome = 'runtime_error'
    elif result['protocol_pass']:
        outcome = 'passed'
    else:
        outcome = 'task_failed'
    # Execution and workflow remain inspectable; coding success follows the evaluator.
    result['execution_outcome'] = outcome
    result['pass_policy'] = 'independent-correctness-v1'
    result['outcome'] = outcome
    if result['correct']:
        result['outcome'] = 'passed'
    if unresolved:
        reasons = [item.get('stop_reason') for item in unresolved]
        if all(reason == 'length' for reason in reasons):
            result['failure_kind'] = 'output_token_exhaustion'
        else:
            result['failure_kind'] = 'incomplete_agent_stream'
    if result.get('supervision_stop'):
        result['failure_kind'] = 'no_meaningful_progress'
        if outcome == 'budget_exhausted':
            result['failure_kind'] = 'request_or_token_budget'
    if outcome == 'timed_out':
        result['failure_kind'] = 'execution_deadline'
        if result.get('evaluation_timeouts'):
            result['failure_kind'] = 'evaluation_timeout'
    result['timing_valid'] = (outcome not in ['interrupted', 'resource_stopped', 'nonterminating', 'runtime_error', 'timed_out', 'stalled', 'budget_exhausted'] and
                              not failures and
                              result.get('swap_in_pages_delta') == 0 and result.get('swap_out_pages_delta') == 0)
    if not result.get('agent_final_response') and 'failure_kind' not in result:
        result['failure_kind'] = 'incomplete_agent_ending'
    result['elapsed_censored'] = outcome in ['interrupted', 'resource_stopped', 'nonterminating', 'timed_out', 'stalled', 'budget_exhausted']
    return result


def smoke_capabilities_verified(result):
    """Verify smoke primitives; retain a complete output-budget failure as model evidence.

    This never changes workflow_pass, protocol_pass or the recorded outcome.
    Unknown stream failures, resource stops and failed assertions block validation.
    """
    failures = result.get('stream_failures', [])
    known_budget_end = bool(failures) and all(
        item.get('type') == 'message_end' and item.get('stop_reason') == 'length' and not item.get('error')
        for item in failures)
    execution = result.get('execution_outcome', result.get('outcome'))
    ending_verified = execution == 'passed' or (known_budget_end and execution == 'runtime_error')
    tools = set(result.get('tool_names', []))
    return all([ending_verified, {'read', 'edit', 'bash'}.issubset(tools),
                result.get('correct'), result.get('confined'), thinking_evidence_satisfied(result),
                result.get('search_succeeded'), result.get('fetch_succeeded'),
                tests_passed(result.get('own_tests_exit'), result.get('own_test_counts', test_counts(''))),
                result.get('agent_end_observed'), result.get('pi_exit') == 0,
                not result.get('assistant_errors'), not result.get('resource_stopped'),
                not result.get('nonterminating'), not result.get('interrupted'), not result.get('supervision_stop')])
