"""Explicit model capabilities; thinking evidence is required only when enabled."""
import json

from lifecycle import Failure


def thinking_enabled(model, sampling):
    supported = model.get('supports_thinking', True)
    requested = sampling.get('thinking', True)
    if type(supported) is not bool or type(requested) is not bool:
        raise Failure('setup_declined', 'Thinking capability and request must be booleans')
    return supported and requested


def prepared_thinking(root):
    path = root / 'artifacts/model-capabilities.json'
    if not path.is_file():
        return True
    value = json.loads(path.read_text())['thinking_required']
    if type(value) is not bool:
        raise Failure('setup_declined', 'Prepared thinking capability is invalid')
    return value
