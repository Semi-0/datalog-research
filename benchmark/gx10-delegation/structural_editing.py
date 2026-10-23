"""Pinned structural-editing installation and agent guidance."""
import hashlib
import json
from pathlib import Path
from lifecycle import Failure, save

TOOLS = ['clj_forms', 'clj_tree', 'clj_get', 'clj_draft', 'clj_edit']
GUIDANCE = ('Clojure structural editing is installed. Read the current file, then use clj_tree or clj_get '
            'to obtain handles and clj_edit for Clojure changes, rather than repeatedly rewriting text or '
            'counting brackets. Prefer a handle-scoped patch for small changes; use clj_draft for a new '
            'multi-line form if balancing is difficult. Inspect proposed repairs before applying repair=true. '
            'Re-read on stale handles. Never copy handle markers into source files. Structural validity '
            'does not prove semantics: inspect nesting, run clj_kondo and Lens diagnostics, then finite tests.')


def configure(root, env, config):
    if config is None:
        return None
    manifest = Path(config['manifest'])
    profile = json.loads(manifest.read_text())
    if profile.get('source_commit') != config['source_commit'] or profile.get('tools') != TOOLS:
        raise Failure('setup_declined', 'Structural editor identity or tool manifest differs')
    for field in ['binary', 'extension']:
        path = Path(profile[field])
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != profile[field + '_sha256']:
            raise Failure('setup_declined', 'Structural editor artifact failed verification: ' + field)
    lock = manifest.parent / 'package-lock.json'
    if hashlib.sha256(lock.read_bytes()).hexdigest() != profile['npm_lock_sha256']:
        raise Failure('setup_declined', 'Structural editor dependency lock differs')
    env['CLJFORM_BIN'] = profile['binary']
    env['GX10_STRUCTURAL_READ_PATHS'] = json.dumps([str(Path(profile['binary']).parent),
        str(Path(profile['extension']).parent), str(manifest.parent / 'node_modules')])
    save(root / 'artifacts/structural-editing.json', profile)
    return profile
