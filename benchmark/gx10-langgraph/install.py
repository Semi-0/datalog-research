#!/usr/bin/env python3
"""Install the pinned local UI/API on GX10; never start a model or assessment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.request

HERE = Path(__file__).resolve().parent
HOME = Path.home()
PREFIX = HOME / 'experiments/gx10-langgraph-install-1'
NODE = HOME / '.local/opt/node-v24.22.1-linux-arm64/bin/node'
VENV = HOME / '.local/opt/langgraph-pi'
LGVIEW_COMMIT = 'cb06b8e657fa03c30a9da6544bf0903e0ef97485'


def command(args, cwd=None, timeout=120):
    return subprocess.run(list(map(str, args)), cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout).stdout


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def unit(description, executable, arguments, environment):
    env = ''.join('Environment="' + key + '=' + value + '"\n' for key, value in environment.items())
    invocation = ' '.join('"' + str(value) + '"' for value in [executable, *arguments])
    return ('[Unit]\nDescription=' + description + '\nAfter=network.target\n\n[Service]\n'
            'Type=simple\nWorkingDirectory=' + str(HERE) + '\n' + env +
            'ExecStart=' + invocation + '\nRestart=on-failure\nRestartSec=3\n'
            'KillMode=control-group\nTimeoutStopSec=30\nUMask=0077\n'
            '\n[Install]\nWantedBy=default.target\n')


def health():
    with urllib.request.urlopen('http://127.0.0.1:2024/ok', timeout=10) as response:
        api = json.load(response)
    request = urllib.request.Request('http://127.0.0.1:2024/assistants/search',
                                     data=b'{}', headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=10) as response:
        assistants = json.load(response)
    if not any(row['graph_id'] == 'gx10_delegation' for row in assistants):
        raise RuntimeError('Delegation graph is not discoverable')
    with urllib.request.urlopen('http://127.0.0.1:4141/', timeout=10) as response:
        if b'<html' not in response.read().lower():
            raise RuntimeError('lgview did not serve HTML')
    return {'api': api, 'graphs': [row['graph_id'] for row in assistants], 'ui': True}


def install():
    PREFIX.mkdir(parents=True, exist_ok=True)
    evidence = PREFIX / 'evidence'
    evidence.mkdir(exist_ok=True)
    versions = command([VENV / 'bin/python', '-c',
        'import importlib.metadata as m,json; print(json.dumps({n:m.version(n) for n in ["langgraph","langgraph-api","langgraph-cli"]}))'])
    if json.loads(versions)['langgraph'] != '1.2.16':
        raise RuntimeError('Installed LangGraph drifted from 1.2.16')
    harbor = command([HOME / '.local/opt/harbor-eval/bin/harbor', '--version'])
    if '0.24.0' not in harbor:
        raise RuntimeError('Harbor must be 0.24.0')
    packages = PREFIX / 'packages'
    if not (packages / 'package-lock.json').is_file():
        raise RuntimeError('Install pinned package.json and preserve npm package-lock.json first')
    for name, expected in [('@earendil-works/pi-coding-agent', '1.3.0'), ('pi-better-subagents', '0.19.1')]:
        actual = json.loads((packages / 'node_modules' / name / 'package.json').read_text())['version']
        if actual != expected:
            raise RuntimeError('Package drift: ' + name)
    link = HERE / 'node_modules'
    if not link.exists():
        link.symlink_to(packages / 'node_modules', target_is_directory=True)
    if link.resolve() != (packages / 'node_modules').resolve():
        raise RuntimeError('Unexpected delegation node_modules target')
    lgview = PREFIX / 'lgview'
    if command(['git', 'rev-parse', 'HEAD'], cwd=lgview).strip() != LGVIEW_COMMIT:
        raise RuntimeError('lgview source commit drifted')
    if not (lgview / 'dist/cli.js').is_file():
        raise RuntimeError('Build lgview with npm ci and npm run build first')
    config = {'dependencies': ['langgraph==1.2.16'], 'graphs': {'gx10_delegation': str(HERE / 'graph.py') + ':graph'},
              'env': {'LANGSMITH_TRACING': 'false', 'LANGCHAIN_TRACING_V2': 'false',
                      'LANGGRAPH_CLI_NO_ANALYTICS': '1', 'OTEL_SDK_DISABLED': 'true'}}
    save(HERE / 'langgraph.json', config)
    environment = {'PATH': str(NODE.parent) + ':' + str(VENV / 'bin') + ':/usr/bin:/bin',
        'PYTHONPATH': str(HERE), 'LANGSMITH_TRACING': 'false', 'LANGCHAIN_TRACING_V2': 'false',
        'LANGSMITH_API_KEY': '', 'LANGCHAIN_API_KEY': '', 'LANGGRAPH_CLI_NO_ANALYTICS': '1',
        'LANGGRAPH_API_NO_USAGE_STATS': 'true', 'OTEL_SDK_DISABLED': 'true', 'DO_NOT_TRACK': '1',
        'HARBOR_TELEMETRY': '0', 'PI_TELEMETRY': '0', 'ANONYMIZED_TELEMETRY': 'false'}
    services = {
        'gx10-langgraph-api': unit('GX10 local LangGraph experimental API', VENV / 'bin/langgraph',
            ['dev', '--config', HERE / 'langgraph.json', '--host', '127.0.0.1', '--port', '2024',
             '--no-reload', '--no-browser', '--n-jobs-per-worker', '1', '--allow-blocking'], environment),
        'gx10-lgview': unit('GX10 local lgview', NODE,
            [lgview / 'dist/cli.js', '--server', 'http://127.0.0.1:2024', '--port', '4141', '--no-open'], environment)}
    service_dir = HOME / '.config/systemd/user'
    service_dir.mkdir(parents=True, exist_ok=True)
    for name, content in services.items():
        target = service_dir / (name + '.service')
        if target.exists() and target.read_text() != content:
            raise RuntimeError('Refusing to replace a different service: ' + str(target))
        target.write_text(content)
    command(['systemctl', '--user', 'daemon-reload'])
    for name in services:
        command(['systemctl', '--user', 'enable', '--now', name + '.service'])
    error = None
    for _ in range(30):
        try:
            result = health()
            break
        except Exception as failure:
            error = failure
            time.sleep(1)
    else:
        raise RuntimeError('Services failed health checks') from error
    (evidence / 'requirements.lock').write_text(command([VENV / 'bin/pip', 'freeze']))
    (evidence / 'harbor-requirements.lock').write_text(command([HOME / '.local/opt/harbor-eval/bin/pip', 'freeze']))
    (evidence / 'package-lock.json').write_bytes((packages / 'package-lock.json').read_bytes())
    (evidence / 'lgview-package-lock.json').write_bytes((lgview / 'package-lock.json').read_bytes())
    hashes = {str(path.relative_to(HERE)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in HERE.iterdir() if path.is_file()}
    save(evidence / 'deployment.json', {'epoch': time.time(), 'source_hashes': hashes,
        'lgview_commit': LGVIEW_COMMIT, 'lgview_bundle_sha256': hashlib.sha256((lgview / 'dist/cli.js').read_bytes()).hexdigest(),
        'versions': json.loads(versions), 'harbor': harbor.strip(), 'health': result,
        'services': services, 'models_loaded_by_install': False})
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--health', action='store_true')
    args = parser.parse_args()
    if args.health:
        print(json.dumps(health()))
    else:
        install()
