"""No-inference admission for the assisted cohort. A failed gate never starts workers."""
import argparse
import fcntl
import hashlib
import json
import shutil
import subprocess
import tempfile
import time
import uuid
from pathlib import Path


VERSIONS = {'pi': '1.3.0', 'pi-better-subagents': '0.19.1', 'harbor': '0.24.0'}


def save(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(argv, timeout=30):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return {'argv': argv, 'exit_code': result.returncode,
                'stdout': result.stdout, 'stderr': result.stderr}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'argv': argv, 'exit_code': None, 'stdout': '', 'stderr': repr(error)}


def probe(task, node, plugin, root, seccomp_base=None):
    """Preserve the task scaffold; optionally select the dedicated sandbox policies."""
    root.mkdir(parents=True, exist_ok=False)
    name = 'gx10-assisted-admission-' + uuid.uuid4().hex[:12]
    evidence = {'outcome': 'setup_declined', 'parent_model_requests': 0,
                'worker_model_requests': 0, 'container': name, 'expected_versions': VERSIONS,
                'started_monotonic_ns': time.monotonic_ns(), 'checks': {},
                'cleanup_verified': False, 'cohort_started': False}
    try:
        package = json.loads((plugin / 'package.json').read_text())
        if package['name'] != 'pi-better-subagents' or package['version'] != VERSIONS['pi-better-subagents']:
            raise ValueError('Pinned plugin identity mismatch')
        evidence['checked_plugin_version'] = package['version']
        source = Path(__file__).with_name('sandbox-probe.mjs')
        evidence['hashes'] = {'probe': sha256(source),
            'plugin_sandbox': sha256(plugin / 'sandbox.ts'),
            'plugin_core': sha256(plugin / 'shared-sandbox-core.ts'),
            'task_dockerfile': sha256(task / 'environment/Dockerfile')}
        with tempfile.TemporaryDirectory(prefix='gx10-assisted-build-') as staging:
            context = Path(staging)
            shutil.copytree(task / 'environment', context, dirs_exist_ok=True)
            bundle = context / '.pilot'
            bundle.mkdir()
            shutil.copytree(plugin, bundle / 'plugin')
            (bundle / 'node/bin').mkdir(parents=True)
            shutil.copy2(node / 'bin/node', bundle / 'node/bin/node')
            shutil.copy2(source, bundle / source.name)
            with (context / 'Dockerfile').open('a') as dockerfile:
                dockerfile.write('\nUSER root\nRUN apt-get update && apt-get install -y --no-install-recommends bubblewrap && rm -rf /var/lib/apt/lists/*\nCOPY .pilot/ /opt/pilot/\nRUN cp /opt/pilot/node/bin/node /usr/local/bin/pilot-node\n')
            build = command(['docker', 'build', '--label', 'gx10.assisted.admission=' + name,
                             '-t', name, str(context)], timeout=900)
            save(root / 'build.json', build)
            if build['exit_code'] != 0:
                raise RuntimeError('Task admission image build failed')
        security_options = []
        if seccomp_base is not None:
            import security as sandbox_security
            security_options = sandbox_security.options(seccomp_base, root)
            evidence['security'] = {'apparmor': sandbox_security.PROFILE,
                'seccomp_base_sha256': sha256(seccomp_base),
                'seccomp_sha256': sha256(root / 'seccomp.json')}
        launch = command(['docker', 'run', *security_options, '-d', '--name', name, '--network', 'host',
                          '--memory', '2g', '--cpus', '2', '--user', 'agent',
                          name, 'sleep', 'infinity'])
        save(root / 'launch.json', launch)
        if launch['exit_code'] != 0:
            raise RuntimeError('Admission container launch failed')
        if seccomp_base is not None:
            apparmor = command(['docker', 'exec', name, 'cat', '/proc/self/attr/current'])
            status = command(['docker', 'exec', name, 'cat', '/proc/self/status'])
            save(root / 'runtime-security.json', {'apparmor': apparmor, 'status': status})
            if apparmor['exit_code'] != 0 or status['exit_code'] != 0:
                raise RuntimeError('Could not inspect container security')
            sandbox_security.verify_container(apparmor['stdout'], status['stdout'])
        result = command(['docker', 'exec', '-e', 'HOME=/home/agent', name,
                          '/opt/pilot/node/bin/node', '/opt/pilot/sandbox-probe.mjs'], timeout=45)
        save(root / 'sandbox.json', result)
        sandbox = json.loads(result['stdout'])
        evidence['checks']['sandbox'] = sandbox
        if result['exit_code'] != 0 or sandbox.get('sandbox_passed') is not True:
            raise RuntimeError('Exact plugin sandbox failed inside the Harbor container')
        # Sandbox acceptance precedes trusted-tool provisioning and any inference.
        evidence['outcome'] = 'sandbox_passed'
    except BaseException as error:
        evidence['error'] = repr(error)
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            evidence['outcome'] = 'interrupted'
    finally:
        cleanup_started = time.monotonic_ns()
        removed = command(['docker', 'rm', '-f', name])
        save(root / 'container-removal.json', removed)
        remaining = command(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$'])
        image = command(['docker', 'image', 'rm', name])
        save(root / 'image-removal.json', image)
        image_remaining = command(['docker', 'image', 'ls', '-q', name])
        evidence['cleanup_verified'] = (remaining['exit_code'] == 0
            and not remaining['stdout'].strip() and image_remaining['exit_code'] == 0
            and not image_remaining['stdout'].strip())
        evidence['cleanup'] = {'remaining': remaining, 'image_remaining': image_remaining,
                               'duration_ns': time.monotonic_ns() - cleanup_started}
        if not evidence['cleanup_verified']:
            evidence['outcome'] = 'cleanup_uncertain'
        evidence['finished_monotonic_ns'] = time.monotonic_ns()
        save(root / 'result.json', evidence)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ['task', 'node', 'plugin', 'output']:
        parser.add_argument('--' + field, type=Path, required=True)
    parser.add_argument('--seccomp-base', type=Path,
                        help='Pinned Docker seccomp base; selects the dedicated AppArmor profile')
    args = parser.parse_args()
    lock = Path.home() / '.local/state/gx10-benchmark/experiment.lock'
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open('a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = probe(args.task, args.node, args.plugin, args.output, args.seccomp_base)
    print(json.dumps(result, indent=2))
    if result['outcome'] != 'sandbox_passed' or not result['cleanup_verified']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
