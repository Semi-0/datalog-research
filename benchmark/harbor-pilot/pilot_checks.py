"""Local endpoint admission and durable Harbor/Pi outcome checks."""
import json
import re
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def backend_key(root, model):
    command = json.loads((root / 'server-command.json').read_text())
    if command.count('--api-key') != 1 or command.count('--served-model-name') != 1:
        raise RuntimeError('Backend must declare exactly one API key and served model')
    key = command[command.index('--api-key') + 1]
    served = command[command.index('--served-model-name') + 1]
    if not isinstance(key, str) or not key or served != model:
        raise RuntimeError('Backend credential or served model is invalid')
    return key


def verify_connection(base_url, model, key):
    endpoint = urlparse(base_url)
    if endpoint.scheme != 'http' or endpoint.hostname != '127.0.0.1' or endpoint.path != '/v1':
        raise RuntimeError('Pilot inference must use the explicit local /v1 endpoint')
    url = base_url + '/models'
    request = Request(url, headers={'Authorization': 'Bearer ' + key})
    with urlopen(request, timeout=10) as response:
        models = json.load(response)
    if [entry['id'] for entry in models['data']] != [model]:
        raise RuntimeError('Actual served model differs from the selected local model')
    rejected = Request(url, headers={'Authorization': 'Bearer invalid-harbor-pilot-fixture'})
    try:
        with urlopen(rejected, timeout=10):
            raise RuntimeError('Backend accepted the deliberately invalid credential')
    except HTTPError as error:
        if error.code != 401:
            raise
        error.close()
    return {'endpoint': base_url, 'served_model': model, 'authenticated_models_status': 200,
            'invalid_credential_status': 401, 'credential_source': 'backend/server-command.json --api-key'}


def trial_directory(job):
    trials = []
    for path in job.glob('*/result.json'):
        if 'trial_name' in json.loads(path.read_text()):
            trials.append(path.parent)
    if len(trials) != 1:
        raise RuntimeError('Expected exactly one durable Harbor trial')
    return trials[0]


def verifier_counts(job):
    text = (trial_directory(job) / 'verifier/clojure.log').read_text()
    counts = re.findall(r'Ran (\d+) tests containing (\d+) assertions\.\s*(\d+) failures, (\d+) errors\.', text)
    if len(counts) != 1:
        raise RuntimeError('Missing or ambiguous independent assertion counts')
    tests, assertions, failures, errors = map(int, counts[0])
    if tests < 1 or assertions != 4:
        raise RuntimeError('Pilot verifier must execute its unchanged four assertions')
    return {'tests': tests, 'assertions': assertions, 'failures': failures, 'errors': errors}


def pi_execution(job):
    path = trial_directory(job) / 'agent/pi.txt'
    messages = []
    errors = []
    ended = False
    settled = False
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        event = json.loads(line)
        kind = event.get('type')
        if kind == 'agent_end':
            ended = True
        elif kind == 'agent_settled':
            settled = not event.get('aborted', False)
            if not settled:
                errors.append({'outcome': 'interrupted', 'error': 'Pi settlement was aborted'})
        elif kind == 'message_end' and event.get('message', {}).get('role') == 'assistant':
            message = event['message']
            messages.append(message)
            reason = message.get('stopReason')
            detail = message.get('errorMessage')
            if reason == 'aborted':
                errors.append({'outcome': 'interrupted', 'error': detail or reason})
            elif reason == 'length':
                errors.append({'outcome': 'incomplete_response', 'error': 'Assistant response truncated'})
            elif reason == 'error' or detail:
                outcome = 'provider_error'
                if re.search(r'401|403|unauthorized|invalid api key', str(detail), re.IGNORECASE):
                    outcome = 'authentication_error'
                errors.append({'outcome': outcome, 'error': detail or reason})
    output_tokens = sum(message.get('usage', {}).get('output', 0) for message in messages)
    outcome = 'completed'
    if errors:
        outcome = errors[0]['outcome']
    elif not ended or not settled or not messages or messages[-1].get('stopReason') != 'stop' or output_tokens <= 0:
        outcome = 'incomplete_response'
    return {'outcome': outcome, 'assistant_errors': errors, 'assistant_messages': len(messages),
            'generated_tokens': output_tokens, 'agent_end': ended, 'agent_settled': settled,
            'transcript': str(path)}
