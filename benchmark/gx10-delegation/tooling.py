"""Isolated profiles: a lightweight Flash controller and coding implementors."""
import hashlib
import json
from pathlib import Path

from lifecycle import Failure, save
from gortex_profile import prepare_gortex, verify_gortex

HERE = Path(__file__).resolve().parent
TOOLS = Path.home() / 'experiments/gx10-agent-tools-1'
WEB = Path.home() / 'experiments/pi-gx10-capability-20261021/web-packages/node_modules/pi-web-access'
KONDO = Path.home() / '.local/opt/clj-kondo-2026.10.20/clj-kondo'


IMPLEMENTOR_TOOLS = ['clj_kondo', 'lens_diagnostics', 'lsp_navigation', 'symbol_search',
                     'read_symbol', 'module_report', 'effective_config', 'pi_lens_activate_tools']


def required_tools(role):
    if role not in ['controller', 'implementor']:
        raise Failure('setup_declined', 'Unknown tool role')
    names = {'web_search', 'fetch_content'}
    if role == 'implementor':
        names.update({'clj_kondo', 'lens_diagnostics', 'lsp_navigation'})
    return names


def require_ready(root):
    from anti_repeat import require_ready as require_stream_guard
    require_stream_guard(root)
    """Admission gate checked by the host proxy before any generation request."""
    profile = json.loads((root / 'artifacts/tool-profile.json').read_text())
    rows = [json.loads(line) for line in (root / 'artifacts/tooling.jsonl').read_text().splitlines()]
    ready = [row for row in rows if row.get('kind') == 'ready']
    sampling = [row for row in rows if row.get('kind') == 'sampling']
    required = required_tools(profile['role']) | set(profile.get('structural_tools', []))
    if not ready or not required.issubset(set(ready[-1]['tools'])):
        raise Failure('setup_declined', 'Required tools are not active; inference refused')
    if not sampling or sampling[-1].get('temperature') != 1.0:
        raise Failure('setup_declined', 'Effective temperature was not verified; inference refused')
    if profile['role'] == 'implementor':
        verify_gortex(root)


def configure(root, checkout, env, protocol):
    role = env.get('GX10_TOOL_ROLE', 'implementor')
    if role not in ['controller', 'implementor']:
        raise Failure('setup_declined', 'Unknown tool role')
    config = json.loads((root / 'agent/models.json').read_text())
    for model in config['providers']['local-benchmark']['models']:
        model['samplingParams']['temperature'] = protocol['sampling']['temperature']
    save(root / 'agent/models.json', config)
    policy = dict(protocol['coding'])
    if role == 'implementor':
        from gortex_profile import GRAPH_TOOLS
        policy['gortex_tools'] = list(GRAPH_TOOLS)
        policy['allowed_tools'] = list(dict.fromkeys(policy['allowed_tools'] + IMPLEMENTOR_TOOLS))
    from structural_editing import configure as configure_structural
    structural = configure_structural(root, env, protocol['coding'].get('structural_editing'))
    structural_tools = []
    if structural is not None:
        structural_tools = structural['tools']
        policy['structural_tools'] = structural_tools
        policy['allowed_tools'] = list(dict.fromkeys(policy['allowed_tools'] + structural_tools))
    save(root / 'agent/benchmark-policy.json', policy)
    env.update(GX10_PUBLIC_WEB='1', GX10_TOOL_ROLE=role, GX10_AGENT_ROOT=str(root))
    entries = [WEB / 'dist/index.js']
    binaries = []
    if role == 'implementor':
        for name in ['lens', 'gortex']:
            (root / name).mkdir(exist_ok=True)
        excludes = checkout / '.git/info/exclude'
        with excludes.open('a') as stream:
            stream.write('\n/.lsp/.cache/\n/.clj-kondo/.cache/\n/.cpcache/\n')
        env.update(PI_LENS_HOME=str(root / 'lens'), PILENS_DATA_DIR=str(root / 'lens/data'),
                   PI_LENS_CONFIG_PATH=str(root / 'agent/pi-lens.json'),
                   PI_LENS_DISABLE_LSP_INSTALL='1', PI_LENS_DISABLE_TOOL_INSTALL='1',
                   PI_LINT_ENABLE='unbounded-loop')
        env['PATH'] = str(TOOLS / 'lsp') + ':' + str(KONDO.parent) + ':' + env['PATH']
        save(root / 'agent/pi-lens.json', {
            'format': {'enabled': False}, 'autofix': {'enabled': False},
            'tests': {'enabled': False}, 'tools': {'lazy': False},
            'startup': {'mode': 'quick', 'scans': {'enabled': False}}})
        graph_entry = root / 'agent/extensions/gortex-entry.ts'
        if not graph_entry.exists():
            graph_entry = prepare_gortex(root, checkout, env)
        entries += [TOOLS / 'node_modules/pi-lens/dist/index.js',
                    TOOLS / 'node_modules/pi-linter/index.ts', graph_entry]
        binaries = [KONDO, TOOLS / 'lsp/clojure-lsp']
    if structural is not None:
        entries.append(Path(structural['extension']))
        binaries.append(Path(structural['binary']))
    for path in entries + binaries:
        if not path.is_file():
            raise Failure('setup_declined', 'Required tool is missing: ' + str(path))
    save(root / 'artifacts/tool-profile.json', {
        'role': role, 'temperature': protocol['sampling']['temperature'], 'web': 'public-http-https',
        'structural_tools': structural_tools,
        'gortex': role == 'implementor', 'pi_linter': 'implementor: loaded; RPC editor checks are a no-op',
        'extensions': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in entries},
        'binaries': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in binaries},
        'package_lock_sha256': hashlib.sha256((TOOLS / 'package-lock.json').read_bytes()).hexdigest()})
    return [str(p) for p in entries]


def verify(root):
    profile = json.loads((root / 'artifacts/tool-profile.json').read_text())
    required = required_tools(profile['role'])
    if profile['role'] == 'implementor':
        verify_gortex(root)
    rows = [json.loads(line) for line in (root / 'artifacts/tooling.jsonl').read_text().splitlines()]
    ready = [r for r in rows if r['kind'] == 'ready']
    if not ready or ready[-1]['temperature'] != 1.0 or not required.issubset(set(ready[-1]['tools'])):
        raise Failure('setup_declined', 'Required tool registration or temperature verification missing')
    activity = [json.loads(line) for line in (root / 'artifacts/activity.jsonl').read_text().splitlines()]
    succeeded = {r['tool'] for r in activity if r.get('type') == 'tool_end' and not r.get('failed')}
    if not required.issubset(succeeded):
        raise Failure('task_failed', 'Agent did not exercise required tools: ' + str(sorted(required - succeeded)))
    if profile['role'] == 'implementor' and not succeeded.intersection({'workspace_info', 'workspace', 'read_file', 'gortex_read'}):
        raise Failure('task_failed', 'Implementor did not query its private Gortex graph')
    if profile['role'] == 'implementor':
        probes = [r for r in rows if r.get('kind') == 'lsp_probe']
        if not any(r.get('ready') for r in probes):
            raise Failure('setup_declined', 'No successful active Clojure LSP diagnostic probe')
    save(root / 'artifacts/tooling-verification.json', {'passed': True, 'ready': ready[-1], 'successful_tools': sorted(succeeded)})
