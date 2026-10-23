"""Read completed cells without rewriting their original evidence."""
import hashlib
import json
from pathlib import Path
from lifecycle import Failure
from outcomes import EXPECTED_ASSERTIONS


def completed_cell(source, task, model_id):
    if source is None:
        return None
    cell = Path(source) / task
    result_path = cell / 'result.json'
    checkpoint = cell / 'checkpoint.json'
    if not result_path.is_file() or not checkpoint.is_file():
        return None
    previous = json.loads(result_path.read_text())
    if json.loads(checkpoint.read_text()).get('stage') != 'complete':
        return None
    if previous['outcome'] in ['interrupted', 'resource_stopped', 'transition_failed', 'runtime_error', 'setup_declined']:
        return None
    if not previous.get('model', '').startswith(model_id):
        raise Failure('setup_declined', 'Continuation model differs from requested model')
    result = dict(previous)
    assessment_path = cell / 'correctness-assessment.json'
    if assessment_path.is_file():
        assessment = json.loads(assessment_path.read_text())
        evidence = Path(assessment['evaluator_evidence'])
        proof = json.loads((evidence / 'result.json').read_text())
        valid = all([assessment.get('correct') is True,
                     assessment.get('independent_assertions') == EXPECTED_ASSERTIONS[task],
                     assessment.get('independent_failures') == 0,
                     assessment.get('independent_errors') == 0,
                     assessment['original_result_sha256'] == hashlib.sha256(result_path.read_bytes()).hexdigest(),
                     assessment['candidate_sha256'] == proof['candidate_sha256'],
                     assessment['evaluator_sha256'] == proof['evaluator_sha256'],
                     proof.get('exit') == 0, proof.get('stop_reason') is None])
        if not valid:
            raise Failure('setup_declined', 'Continuation assessment evidence failed verification')
        result.update(correct=True, acceptance_exit=0,
                      acceptance_counts={'tests': assessment['independent_tests'],
                                         'assertions': assessment['independent_assertions'],
                                         'failures': 0, 'errors': 0},
                      assessment_path=str(assessment_path), timing_valid=False)
    result['continued_from'] = str(result_path)
    result['pass_policy'] = 'independent-correctness-v1'
    result['execution_outcome'] = previous['outcome']
    if result.get('correct') is True:
        result['outcome'] = 'passed'
    return result
