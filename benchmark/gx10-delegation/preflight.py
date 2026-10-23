"""Inspect the installed pinned extension and exercise its real outer sandbox."""
import hashlib
import json
from pathlib import Path
import subprocess

from lifecycle import Failure, save
from runtime import PACKAGES, NODE, HERE, base_spec, node_environment

LOCK_SHA256 = 'bf85d29f78ceb7fc06472e556903568e7d6e60e46b04130550464447602b06bf'


def installed_evidence():
    lock = PACKAGES / 'package-lock.json'
    if hashlib.sha256(lock.read_bytes()).hexdigest() != LOCK_SHA256:
        raise Failure('setup_declined', 'Installed npm dependency lock differs')
    package = PACKAGES / 'node_modules/@erichll/pi-sandbox'
    version = json.loads((package / 'package.json').read_text())['version']
    if version != '0.28.0':
        raise Failure('setup_declined', 'Unexpected sandbox extension version')
    files = {str(p.relative_to(package)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in package.rglob('*') if p.is_file()}
    expected = json.loads((HERE / 'extension-lock.json').read_text())
    if files != expected:
        raise Failure('setup_declined', 'Installed extension source differs from pinned hashes')
    return {'version': version, 'lock_sha256': LOCK_SHA256, 'files': files}


def sandbox_check(output):
    output.mkdir(parents=True, exist_ok=False)
    try:
        save(output / 'installation.json', installed_evidence())
        bwrap = subprocess.run(['/usr/bin/bwrap', '--ro-bind', '/', '/', '--unshare-user',
            '--unshare-pid', '--proc', '/proc', '/usr/bin/true'], capture_output=True, text=True, timeout=30)
        save(output / 'bubblewrap.json', {'exit': bwrap.returncode, 'stderr': bwrap.stderr})
        if bwrap.returncode != 0:
            raise Failure('setup_declined', 'OS sandbox unavailable: ' + bwrap.stderr.strip())
        root = output / 'worker'
        for name in ['checkout', 'agent', 'tmp', 'artifacts', 'sessions']:
            (root / name).mkdir(parents=True)
        sentinel = output / 'sibling-secret'
        sentinel.write_text('unrelated sentinel must survive')
        env = node_environment(root)
        env['GX10_SENTINEL'] = str(sentinel)
        job = {'root': str(root), 'cwd': str(root / 'checkout'), 'env': env}
        spec = output / 'launch.json'
        save(spec, base_spec([job], output / 'sandbox-result.json', 'sandbox-check'))
        result = subprocess.run([str(NODE), str(HERE / 'agents.mjs'), str(spec)], env=env,
                                capture_output=True, text=True, timeout=60)
        (output / 'sandbox.txt').write_text(result.stdout + result.stderr)
        if result.returncode != 0 or sentinel.read_text() != 'unrelated sentinel must survive':
            raise Failure('setup_declined', 'Real package sandbox smoke failed; inspect sandbox.txt')
        save(output / 'result.json', {'outcome': 'passed', 'sandbox': 'outer-process'})
    except Exception as error:
        save(output / 'result.json', {'outcome': getattr(error, 'outcome', 'runtime_error'), 'error': repr(error)})
        raise
