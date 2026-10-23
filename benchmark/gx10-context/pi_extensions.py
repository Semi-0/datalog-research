"""Prepare a pinned extension inside each attempt, without global Pi installation."""
import hashlib
import json
from pathlib import Path
import shutil

from lifecycle import save

HERE = Path(__file__).resolve().parent


def prepare_vigilant(root, protocol, source=None, overrides=None):
    policy = protocol['pi_extensions']['vigilant']
    lock = json.loads((HERE / policy['lock_file']).read_text())
    if source is None:
        source = Path.home() / 'experiments/gx10-pi-packages' / ('pi-vigilant-' + lock['version'])
    for name, expected in lock['files'].items():
        path = source / name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Pinned pi-vigilant file differs: ' + name)
    destination = root / 'agent/extensions/pi-vigilant'
    shutil.copytree(source, destination)
    settings = dict(policy['settings'])
    if overrides is not None:
        settings.update(overrides)
    save(destination / 'pi-vigilant.json', settings)
    save(root / 'artifacts/pi-vigilant.json', {'lock': lock, 'settings': settings,
         'entry': str(destination / 'index.ts'),
         'settings_sha256': hashlib.sha256((destination / 'pi-vigilant.json').read_bytes()).hexdigest()})
    anti_repeat = protocol['pi_extensions'].get('anti_repeat')
    if anti_repeat is not None:
        from anti_repeat import prepare
        return prepare(root, anti_repeat)
    return destination / 'index.ts'
