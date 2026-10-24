"""Freeze public contracts and Codex outlines before any pilot model request."""
import argparse
import hashlib
import json
import time
from pathlib import Path

from tokenizers import Tokenizer

MODEL = Path('/home/lain/experiments/qwen36-35b-a3b-nvfp4-20261023/model')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def freeze(tasks, outlines, output):
    if output.exists():
        raise ValueError('Frozen outline receipt already exists; never overwrite it')
    source = json.loads(outlines.read_text())
    tokenizer = Tokenizer.from_file(str(MODEL / 'tokenizer.json'))
    records = {}
    for name in ['distinct', 'order', 'events']:
        text = source['outlines'][name]
        tokens = tokenizer.encode(text, add_special_tokens=False).ids
        if len(tokens) > 128:
            raise ValueError(f'{name} outline exceeds 128 Qwen tokens: {len(tokens)}')
        task = tasks / name
        records[name] = {'outline': text, 'qwen_token_count': len(tokens), 'token_ids': tokens,
            'outline_sha256': digest(text.encode()),
            'public_contract': (task / 'instruction.md').read_text(),
            'files_sha256': {str(path.relative_to(task)): digest(path.read_bytes())
                for path in sorted(task.rglob('*')) if path.is_file()}}
    result = {'cohort': source['cohort'], 'frozen_epoch': time.time(),
        'model_revision': '1355db6a052410cfd62085d94b58866fd0f2c3c5',
        'tokenizer_sha256': digest((MODEL / 'tokenizer.json').read_bytes()),
        'outlines_source_sha256': digest(outlines.read_bytes()), 'tasks': records}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x') as handle:
        handle.write(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tasks', type=Path, required=True)
    parser.add_argument('--outlines', type=Path, default=Path(__file__).with_name('outlines.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = freeze(args.tasks, args.outlines, args.output)
    print(json.dumps({name: record['qwen_token_count'] for name, record in result['tasks'].items()}))
