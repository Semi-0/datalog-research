"""Pinned read-only Pi graph access, isolated to one owned candidate checkout."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil

from lifecycle import save

HERE = Path(__file__).resolve().parent
GRAPH_TOOLS = ['workspace_info', 'read_file', 'search_symbols', 'search_text',
               'get_symbol_source', 'get_callers', 'get_dependencies', 'find_usages', 'explore', 'analyze']


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def prepare_gortex(root, checkout, environment, package=None, home=None):
    """Only copy/configure here; daemon startup/indexing runs under the Pi watchdog."""
    if home is None:
        home = Path.home()
    home = Path(home)
    if package is None:
        package = home / 'experiments/gx10-pi-packages/pi-gortex-0.4.0'
    package = Path(package)
    lock = json.loads((HERE / 'pi-gortex-lock.json').read_text())
    actual = {str(path.relative_to(package)) for path in package.rglob('*') if path.is_file()}
    if actual != set(lock['files']) or any(path.is_symlink() for path in package.rglob('*')):
        raise RuntimeError('Pinned pi-gortex file inventory differs')
    for name, expected in lock['files'].items():
        if sha256(package / name) != expected:
            raise RuntimeError('Pinned pi-gortex file differs: ' + name)
    binaries = {}
    for name, entry in lock['runtime'].items():
        binary = home / entry['path']
        if sha256(binary) != entry['sha256']:
            raise RuntimeError('Pinned runtime differs: ' + name)
        binaries[name] = binary
    extension = root / 'agent/extensions/pi-gortex'
    extension.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(package, extension)
    private = root / 'gortex'
    for name in ['home', 'config', 'data', 'cache']:
        (private / name).mkdir(parents=True)
    socket = '/tmp/gx10-gortex-' + hashlib.sha256(str(root).encode()).hexdigest()[:16] + '.sock'
    wrapper = private / 'gortex'
    exports = {'HOME': str(private / 'home'), 'XDG_CONFIG_HOME': str(private / 'config'),
               'XDG_DATA_HOME': str(private / 'data'), 'XDG_CACHE_HOME': str(private / 'cache'),
               'GORTEX_DAEMON_SOCKET': socket, 'GORTEX_DAEMON_PIDFILE': str(private / 'daemon.pid'),
               'GORTEX_DAEMON_LOGFILE': str(private / 'daemon.log'), 'DO_NOT_TRACK': '1'}
    lines = ['#!/bin/bash', 'set -euo pipefail']
    lines += ['export ' + key + '=' + shlex.quote(value) for key, value in exports.items()]
    binary = shlex.quote(str(binaries['gortex']))
    log = shlex.quote(str(private / 'startup.log'))
    lines += ['if [[ "${1:-}" == daemon && "${2:-}" == start ]]; then exit 0; fi',
              'if [[ "${1:-}" == mcp ]]; then',
              '  ' + binary + ' daemon start --detach >>' + log + ' 2>&1',
              '  ' + binary + ' track --wait --wait-timeout 110s ' + shlex.quote(str(checkout)) + ' >>' + log + ' 2>&1',
              '  touch ' + shlex.quote(str(private / 'index-ready')),
              'fi', 'exec ' + binary + ' "$@"']
    wrapper.write_text('\n'.join(lines) + '\n')
    wrapper.chmod(0o700)
    save(root / 'agent/extensions/gortex.json', {'bin': str(wrapper), 'native_mcp': False,
         'hook_mode': 'enrich', 'enforce': False, 'tools_preset': 'readonly'})
    entry = root / 'agent/extensions/gortex-entry.ts'
    entry.write_text("import gortex from './pi-gortex/index.ts';\n"
                     "import { appendFileSync } from 'node:fs';\n"
                     "import { join } from 'node:path';\n"
                     "export default function(pi) {\n"
                     "  const audit = (row) => appendFileSync(join(process.env.PI_CODING_AGENT_DIR, '../artifacts/gortex-registration.jsonl'), JSON.stringify(row) + '\\n');\n"
                     "  const wrapped = new Proxy(pi, {get(target, name) {\n"

                     "    if (name === 'registerTool') { return (tool) => {\n"
                     "      try { const result = target.registerTool(tool); audit({name:tool.name, registered:true}); return result; }\n"
                     "      catch(error) { audit({name:tool.name, registered:false, error:String(error)}); throw error; }\n"
                     "    }; }\n"
                     "    const value = Reflect.get(target, name);\n"
                     "    if (typeof value === 'function') { return value.bind(target); }\n"
                     "    return value;\n"
                     "  }});\n"
                     "  return gortex(wrapped);\n"
                     "}\n")
    policy_path = root / 'agent/benchmark-policy.json'
    if policy_path.exists():
        policy = json.loads(policy_path.read_text())
        policy['gortex_tools'] = GRAPH_TOOLS
        save(policy_path, policy)
    environment['PATH'] = str(binaries['node'].parent) + os.pathsep + environment['PATH']
    environment['GX10_GORTEX_PROFILE'] = '1'
    environment['GORTEX_TOOLS'] = 'readonly'
    save(root / 'artifacts/gortex-profile.json', {'package': lock['package'], 'version': lock['version'],
         'lock_sha256': sha256(HERE / 'pi-gortex-lock.json'), 'socket': socket,
         'candidate_checkout': str(checkout), 'private_state': str(private),
         'runtime': lock['runtime'], 'tools_preset': 'readonly', 'native_mcp': False,
         'ownership': 'attempt systemd cgroup; no shared daemon or global configuration'})
    return entry


def verify_gortex(root):
    if not (root / 'gortex/index-ready').exists():
        raise RuntimeError('Private candidate graph did not finish indexing')
    audit = root / 'artifacts/pi-runtime.jsonl'
    rows = [json.loads(line) for line in audit.read_text().splitlines()]
    ready = [row for row in rows if row['kind'] == 'gortex_ready']
    if not ready:
        raise RuntimeError('Pi did not expose the required read-only graph tools')
    save(root / 'artifacts/gortex-verification.json', {'passed': True, 'tools': ready[-1]['tools'],
         'index_ready': True, 'audit_sha256': sha256(audit)})
