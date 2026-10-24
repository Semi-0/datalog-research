"""Build and admit the pinned Pi tools inside the actual task sandbox, without inference."""
import argparse
import fcntl
import json
import re
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

from admission import command, save, sha256
import security

HOME = Path('/home/lain')
EXPERIMENTS = HOME / 'experiments'
HERE = Path(__file__).resolve().parent


def native_binary(bundle, name, source):
    destination = bundle / 'native' / name
    destination.parent.mkdir(exist_ok=True)
    shutil.copy2(source, destination)
    listing = subprocess.run(['ldd', str(source)], capture_output=True, text=True, timeout=30)
    libraries = re.findall(r'(/[^\s()]+)', listing.stdout)
    for library in libraries:
        target = bundle / 'native/lib' / Path(library).name
        target.parent.mkdir(exist_ok=True)
        shutil.copy2(library, target)
    launcher = bundle / 'bin' / name
    if libraries:
        loader = '/usr/local/lib/pilot/native/lib/ld-linux-aarch64.so.1'
        launcher.write_text(f'#!/bin/sh\nexec {loader} --library-path /usr/local/lib/pilot/native/lib /usr/local/lib/pilot/native/{name} "$@"\n')
    else:
        launcher.write_text(f'#!/bin/sh\nexec /usr/local/lib/pilot/native/{name} "$@"\n')
    launcher.chmod(0o755)
    return sha256(source)


def stage_bundle(bundle):
    bundle.mkdir()
    (bundle / 'bin').mkdir()
    trees = {
        'node_modules': EXPERIMENTS / 'gx10-langgraph-install-1/packages/node_modules',
        'agent-tools/node_modules': EXPERIMENTS / 'gx10-agent-tools-1/node_modules',
        'web/node_modules': EXPERIMENTS / 'pi-gx10-capability-20261021/web-packages/node_modules',
        'cljform': EXPERIMENTS / 'gx10-structural-tools-1/cljform/extension',
        'cljform/node_modules': EXPERIMENTS / 'gx10-structural-tools-1/node_modules',
        'pi-gortex': EXPERIMENTS / 'gx10-pi-packages/pi-gortex-0.4.0',
    }
    for relative, source in trees.items():
        shutil.copytree(source, bundle / relative, symlinks=True)
    shutil.copy2(HOME / '.local/opt/node-v24.22.1-linux-arm64/bin/node', bundle / 'bin/node')
    hashes = {}
    for name, source in {
        'cljform': EXPERIMENTS / 'gx10-structural-tools-1/bin/cljform',
        'clojure-lsp': EXPERIMENTS / 'gx10-agent-tools-1/lsp/clojure-lsp',
        'clj-kondo': HOME / '.local/opt/clj-kondo-2026.10.20/clj-kondo',
        'gortex': HOME / '.local/opt/gortex-v0.64.8/gortex',
    }.items():
        hashes[name] = native_binary(bundle, name, source)
    clojure = bundle / 'bin/clojure'
    clojure.write_text('#!/bin/sh\nexec /usr/local/lib/pilot/clojure/launcher -Sdeps \'{:mvn/local-repo "/usr/local/lib/pilot/m2"}\' "$@"\n')
    clojure.chmod(0o755)
    shutil.copy2(HERE / 'runtime-bwrap.py', bundle / 'bin/bwrap')
    (bundle / 'bin/bwrap').chmod(0o755)
    for name in ['tool-probe.mjs', 'run-tool-probe.mjs', 'tool-environment.ts', 'companion.ts', 'parent.mjs', 'execution.mjs']:
        shutil.copy2(HERE / name, bundle / name)
    shutil.copy2(HERE.parent / 'gx10-langgraph/control.mjs', bundle / 'control.mjs')
    return hashes


def build_image(task, image, root):
    with tempfile.TemporaryDirectory(prefix='gx10-assisted-tools-') as temporary:
        context = Path(temporary)
        shutil.copytree(task / 'environment', context, dirs_exist_ok=True)
        hashes = stage_bundle(context / '.pilot-tools')
        with (context / 'Dockerfile').open('a') as dockerfile:
            dockerfile.write('''
USER root
RUN apt-get update && apt-get install -y --no-install-recommends bubblewrap && rm -rf /var/lib/apt/lists/*
COPY .pilot-tools/ /usr/local/lib/pilot/
RUN mv /usr/bin/bwrap /usr/local/lib/pilot/bwrap-real && cp /usr/local/lib/pilot/bin/bwrap /usr/bin/bwrap
RUN cp -a /opt/java/openjdk /usr/local/lib/pilot/java && cp -a /opt/clojure /usr/local/lib/pilot/clojure && cp -a /opt/m2 /usr/local/lib/pilot/m2
RUN cp /usr/local/bin/clojure /usr/local/lib/pilot/clojure/launcher && sed -i 's|/opt/clojure|/usr/local/lib/pilot/clojure|g' /usr/local/lib/pilot/clojure/launcher
ENV PATH=/usr/local/lib/pilot/bin:/usr/local/lib/pilot/node_modules/.bin:/usr/local/lib/pilot/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ENV JAVA_HOME=/usr/local/lib/pilot/java
ENV CLJFORM_BIN=/usr/local/lib/pilot/bin/cljform
ENV GORTEX_BIN=/usr/local/lib/pilot/bin/gortex
ENV CI=1
ENV NO_COLOR=1
WORKDIR /app
''')
        result = command(['docker', 'build', '-t', image, str(context)], timeout=900)
        save(root / 'build.json', result)
        if result['exit_code'] != 0:
            raise RuntimeError('Tool image failed to build')
        save(root / 'tool-binary-hashes.json', hashes)


def admit(task, base, root):
    root.mkdir(parents=True, exist_ok=False)
    name = 'gx10-assisted-tools-' + uuid.uuid4().hex[:12]
    receipt = {'outcome': 'setup_declined', 'image': name, 'model_requests': 0,
               'cleanup_verified': False, 'cohort_started': False}
    try:
        build_image(task, name, root)
        options = security.options(base, root)
        launch = command(['docker', 'run', '-d', '--name', name, *options,
                          '--network', 'host', '--memory', '2g', '--cpus', '2',
                          '--user', 'agent', name, 'sleep', 'infinity'])
        save(root / 'launch.json', launch)
        if launch['exit_code'] != 0:
            raise RuntimeError('Tool container failed to launch')
        apparmor = command(['docker', 'exec', name, 'cat', '/proc/self/attr/current'])
        status = command(['docker', 'exec', name, 'cat', '/proc/self/status'])
        security.verify_container(apparmor['stdout'], status['stdout'])
        result = command(['docker', 'exec', name, 'node',
                          '/usr/local/lib/pilot/run-tool-probe.mjs'], timeout=240)
        save(root / 'tool-probe.json', result)
        if result['exit_code'] != 0:
            raise RuntimeError('Required-tool sandbox probe failed; inspect tool-probe.json')
        receipt['outcome'] = 'tools_verified'
    except BaseException as error:
        receipt['error'] = repr(error)
    finally:
        removal = command(['docker', 'rm', '-f', name])
        remaining = command(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$'])
        save(root / 'cleanup.json', {'removal': removal, 'remaining': remaining})
        receipt['cleanup_verified'] = remaining['exit_code'] == 0 and not remaining['stdout'].strip()
        if not receipt['cleanup_verified']:
            receipt['outcome'] = 'cleanup_uncertain'
        # The image is an intentional reusable build artifact, not a running resource.
        save(root / 'result.json', receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['task', 'seccomp-base', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    lock = HOME / '.local/state/gx10-benchmark/experiment.lock'
    with lock.open('a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = admit(args.task, args.seccomp_base, args.output)
    print(json.dumps(result, indent=2))
    if result['outcome'] != 'tools_verified' or not result['cleanup_verified']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
