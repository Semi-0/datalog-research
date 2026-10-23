"""Verify and copy the pinned streaming guard into each private attempt."""
import hashlib
import json
from pathlib import Path
import shutil
from lifecycle import Failure, save

HERE = Path(__file__).resolve().parent


def require_ready(root):
    policy_path = root / 'agent/benchmark-policy.json'
    policy = {}
    if policy_path.is_file():
        policy = json.loads(policy_path.read_text())
    prepared = root / 'artifacts/anti-repeat-package.json'
    if not policy.get('anti_repeat_required') and not prepared.is_file():
        return
    path = root / 'artifacts/anti-repeat-ready.json'
    if not path.is_file():
        raise Failure('setup_declined', 'Streaming repetition guard did not initialize')
    ready = json.loads(path.read_text())
    if ready.get('version') != '0.2.1' or ready.get('watching') is not True or ready.get('vigilant_coordination') is not True:
        raise Failure('setup_declined', 'Streaming repetition guard readiness differs')


def prepare(root, policy):
    manifest_path = Path(policy['manifest'])
    raw = manifest_path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != policy['manifest_sha256']:
        raise RuntimeError('Pinned anti-repeat manifest differs')
    manifest = json.loads(raw)
    if manifest['name'] != 'pi-anti-repeat' or manifest['version'] != policy['version']:
        raise RuntimeError('Pinned anti-repeat version differs')
    source = Path(manifest['source'])
    for name, expected in manifest['files'].items():
        path = source / name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Pinned anti-repeat artifact differs: ' + name)
    destination = root / 'agent/extensions/pi-anti-repeat'
    shutil.copytree(source, destination)
    entry = root / 'agent/extensions/anti-repeat.ts'
    shutil.copyfile(HERE / 'anti-repeat.ts', entry)
    benchmark_policy = root / 'agent/benchmark-policy.json'
    settings = json.loads(benchmark_policy.read_text())
    settings['anti_repeat_required'] = True
    save(benchmark_policy, settings)
    save(root / 'artifacts/anti-repeat-package.json', {'manifest': manifest, 'entry': str(entry),
         'manifest_sha256': policy['manifest_sha256'],
         'wrapper_sha256': hashlib.sha256(entry.read_bytes()).hexdigest()})
    return entry
