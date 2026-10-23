"""Verified Linux backend transitions. Task semantics do not belong here."""
from contextlib import contextmanager
from collections import deque
import fcntl
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.request

GIB = 1024**3


class Failure(RuntimeError):
    def __init__(self, outcome, message):
        super().__init__(message)
        self.outcome = outcome


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_suffix(path.suffix + '.tmp').open('w') as output:
        json.dump(value, output, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())
    path.with_suffix(path.suffix + '.tmp').replace(path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def memory():
    fields = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    counters = dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())
    total = int(fields['MemTotal'].split()[0]) * 1024
    available = int(fields['MemAvailable'].split()[0]) * 1024
    return {'total': total, 'available': available, 'used': total - available,
            'reserve': max(16 * GIB, total // 5),
            'swap_used': (int(fields['SwapTotal'].split()[0]) - int(fields['SwapFree'].split()[0])) * 1024,
            'swap_in': int(counters['pswpin']), 'swap_out': int(counters['pswpout'])}


def http(url, body=None, timeout=10):
    data = None
    if body is not None:
        data = json.dumps(body).encode()
    headers = {'Content-Type': 'application/json'}
    if url.startswith('http://127.0.0.1:8001/'):
        headers['Authorization'] = 'Bearer local-benchmark'
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=timeout) as response:
        return json.load(response)


def identities():
    result = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
            command = (entry / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace')
            result.append({'pid': int(entry.name), 'start_ticks': stat[19], 'ppid': int(stat[1]),
                           'group': int(stat[2]), 'command': command,
                           'cgroup': (entry / 'cgroup').read_text().strip()})
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            pass
    return result


def ollama_runners(processes):
    """Installed Ollama versions use either a subcommand or a llama-server binary."""
    runners = []
    for process in processes:
        parts = process['command'].split()
        if not parts:
            continue
        executable = Path(parts[0]).name
        native = executable in ['llama-server', 'ollama_llama_server']
        subcommand = executable == 'ollama' and len(parts) >= 2 and parts[1] == 'runner'
        if native or subcommand:
            runners.append(process)
    return runners


def settle(observe, event, baseline=None, timeout=180, now=time.monotonic, sleep=time.sleep,
           stable_seconds=10, variation_bytes=256 * 1024**2, shortfall_bytes=2 * GIB):
    """Ten clean one-second observations; absence alone never establishes settlement."""
    deadline = now() + timeout
    window = deque(maxlen=stable_seconds + 1)
    while now() < deadline:
        state = observe()
        event('settlement_sample', state)
        if state['models'] or state['old_processes'] or state['endpoint_alive']:
            window.clear()
        else:
            window.append(state['memory']['available'])
            if len(window) == stable_seconds + 1 and max(window) - min(window) <= variation_bytes:
                available = min(window)
                if baseline is not None and baseline - available > shortfall_bytes:
                    event('baseline_shortfall', {'baseline': baseline, 'available': available})
                else:
                    return available
        sleep(1)
    raise Failure('transition_failed', 'Unload/settlement deadline expired; inspect transition samples')


@contextmanager
def experiment_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise Failure('setup_declined', 'Another benchmark controller holds the host lock') from error
        yield


class Backend:
    def __init__(self, root, manifest, state_dir=None):
        self.root = root
        self.manifest = manifest
        self.state_dir = state_dir or Path.home() / '.local/state/gx10-benchmark'
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.state_file = self.state_dir / 'ownership.json'
        self.state = {'units': [], 'runners': [], 'baseline': None, 'aliases': [], 'groups': {}, 'attempt_results': {}}
        if self.state_file.exists():
            self.state.update(json.loads(self.state_file.read_text()))
        self.unit = None
        self.attempt_unit = None
        self.breached = threading.Event()

    def event(self, operation, details):
        with (self.root / 'lifecycle.jsonl').open('a') as log:
            log.write(json.dumps({'epoch': time.time(), 'operation': operation, 'details': details}) + '\n')
            log.flush()
            os.fsync(log.fileno())

    def persist(self):
        save(self.state_file, self.state)

    def system(self, *args, check=True):
        result = subprocess.run(['systemctl', '--user', *args], text=True, capture_output=True, timeout=30)
        self.event('systemctl', {'args': args, 'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
        if check and result.returncode != 0:
            raise Failure('transition_failed', 'systemctl failed: ' + repr(args))
        return result.stdout.strip()

    def group_processes(self, unit):
        group = self.system('show', unit, '-p', 'ControlGroup', '--value', check=False)
        if group and self.state['groups'].get(unit) != group:
            self.state['groups'][unit] = group
            self.persist()
        if not group:
            group = self.state['groups'].get(unit)
        if not group:
            return []
        return [p for p in identities() if any(line.split(':', 2)[-1] == group or
                line.split(':', 2)[-1].startswith(group + '/') for line in p['cgroup'].splitlines())]

    def stop(self, unit):
        self.event('stop_owned_service', {'unit': unit, 'processes': self.group_processes(unit)})
        load_state = self.system('show', unit, '-p', 'LoadState', '--value')
        if load_state == 'not-found':
            if self.group_processes(unit):
                raise Failure('transition_failed', 'Removed service still owns processes: ' + unit)
            self.event('owned_service_already_absent', {'unit': unit})
            return
        try:
            self.system('stop', '--no-block', unit)
        except Failure:
            if self.system('show', unit, '-p', 'LoadState', '--value') == 'not-found' and not self.group_processes(unit):
                return
            raise
        started = time.monotonic()
        forced = False
        while self.group_processes(unit):
            elapsed = time.monotonic() - started
            if elapsed >= 5 and not forced:
                self.event('force_owned_service_exit', {'unit': unit, 'processes': self.group_processes(unit)})
                self.system('kill', '--kill-whom=all', '--signal=SIGKILL', unit, check=False)
                forced = True
            if elapsed >= 20:
                raise Failure('transition_failed', 'Owned service still contains processes: ' + unit)
            time.sleep(.2)

    def endpoint(self, port):
        with socket.socket() as connection:
            connection.settimeout(.2)
            return connection.connect_ex(('127.0.0.1', port)) == 0

    def inventory(self):
        if not self.endpoint(11434):
            return []
        response = http('http://127.0.0.1:11434/api/ps')
        self.event('ollama_inventory', response)
        return response['models']

    def wait_previous_attempts(self):
        # Separately guarded coding/context workers may outlive an interrupted controller.
        # Their execution has no inherited controller or transition wall-clock cutoff.
        for unit in self.state['units']:
            if 'attempt' not in unit:
                continue
            waiting = False
            while self.system('show', unit, '-p', 'SubState', '--value') in ['running', 'start', 'start-pre', 'start-post']:
                if not waiting:
                    self.event('wait_previous_attempt', {'unit': unit, 'record': self.state['attempt_results'].get(unit)})
                    waiting = True
                time.sleep(.2)
            if waiting:
                self.event('previous_attempt_finished', {'unit': unit})

    def cleanup(self):
        started = time.monotonic()
        policy = self.manifest['transition']
        try:
            self.wait_previous_attempts()
            started = time.monotonic()
            owned_pids = {p['pid'] for unit in self.state['units'] for p in self.group_processes(unit)}
            servers = []
            for process in identities():
                parts = process['command'].split()
                ollama_server = len(parts) >= 2 and Path(parts[0]).name == 'ollama' and parts[1] == 'serve'
                vllm_server = len(parts) >= 3 and parts[1:3] == ['-m', 'vllm.entrypoints.openai.api_server']
                if (ollama_server or vllm_server) and process['pid'] not in owned_pids:
                    servers.append(process)
            if servers:
                self.event('unowned_inference_conflict', {'processes': servers})
                raise Failure('transition_failed', 'Unowned inference server; refusing to unload or stop it')
            loaded = self.inventory()
            sources = {m['source'] for m in self.manifest['models']}
            allowed = sources | set(self.state['aliases'])
            if any(m['name'] not in allowed for m in loaded):
                raise Failure('transition_failed', 'Unrelated resident model; refusing to unload it')
            old = ollama_runners(identities())
            known_runners = {(r['pid'], r['start_ticks']) for r in self.state['runners']}
            if any(p['pid'] not in owned_pids and (p['pid'], p['start_ticks']) not in known_runners for p in old):
                raise Failure('transition_failed', 'Unowned inference runner; refusing to stop it')
            # Only claim runners of the known experiment server, never an arbitrary server.
            self.state['runners'].extend({'pid': p['pid'], 'start_ticks': p['start_ticks']} for p in old)
            self.persist()
            for unit in self.state['units']:
                if 'attempt' in unit:
                    self.stop(unit)
            for model in loaded:
                response = http('http://127.0.0.1:11434/api/generate', {'model': model['name'], 'keep_alive': 0})
                self.event('unload_acknowledgement', {'model': model['name'], 'response': response})
            # Poll inventory and original runner identity BEFORE stopping our Ollama server.
            unload_deadline = started + policy['timeout_seconds']
            while loaded or any((p['pid'], p['start_ticks']) in
                  {(r['pid'], r['start_ticks']) for r in self.state['runners']} for p in identities()):
                self.event('unloading', {'models': loaded, 'processes': identities(), 'memory': memory()})
                if time.monotonic() >= unload_deadline:
                    raise Failure('transition_failed', 'Ollama runner failed to exit after unload')
                time.sleep(1)
                loaded = self.inventory()
            for unit in self.state['units']:
                if 'attempt' not in unit:
                    self.stop(unit)
            def observe():
                processes = identities()
                runners = {(p['pid'], p['start_ticks']) for p in self.state['runners']}
                old_processes = [p for p in processes if (p['pid'], p['start_ticks']) in runners]
                for unit in self.state['units']:
                    old_processes.extend(self.group_processes(unit))
                # Any backend outside the ownership registry is a conflict, including an empty Ollama server.
                conflicts = [p for p in processes if '/ollama serve' in p['command'] or
                             ' -m vllm.entrypoints.openai.api_server ' in p['command']]
                return {'models': self.inventory(), 'old_processes': old_processes + conflicts,
                        'endpoint_alive': self.endpoint(8001) or self.endpoint(11434), 'memory': memory()}
            available = settle(observe, self.event, self.state['baseline'],
                timeout=max(0, policy['timeout_seconds'] - (time.monotonic() - started)),
                stable_seconds=policy['stable_seconds'], variation_bytes=policy['variation_bytes'],
                shortfall_bytes=policy['baseline_shortfall_bytes'])
            self.state.update(units=[], runners=[], groups={}, attempt_results={}, baseline=available)
            self.persist()
            self.event('transition_verified', {'available': available, 'duration_s': time.monotonic() - started})
        except BaseException as error:
            self.event('cleanup_failed', {'error': repr(error), 'processes': identities(), 'memory': memory()})
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                raise
            raise Failure('transition_failed', repr(error)) from error

    def launch(self, unit, command, environment=None):
        if self.system('show', unit, '-p', 'LoadState', '--value') != 'not-found':
            raise Failure('setup_declined', 'Service name conflict; unrelated unit preserved: ' + unit)
        # Persist intent before launch, so an interruption can clean up partially started units.
        self.state['units'].append(unit)
        self.persist()
        launcher = Path(__file__).resolve().parents[2] / 'scripts/run-background.sh'
        argv = ['bash', str(launcher), unit, '--']
        if environment:
            argv += ['/usr/bin/env'] + [key + '=' + value for key, value in environment.items()]
        argv += command
        result = subprocess.run(argv, text=True, capture_output=True, timeout=30)
        self.event('launch', {'unit': unit, 'command': command, 'exit': result.returncode,
                              'stdout': result.stdout, 'stderr': result.stderr})
        if result.returncode:
            raise Failure('runtime_error', 'Service startup failed: ' + unit)

    def stop_owned_inference(self):
        for unit in [self.attempt_unit, self.unit]:
            if unit:
                try:
                    self.stop(unit)
                except BaseException as error:
                    self.event('resource_cleanup_failure', {'unit': unit, 'error': repr(error)})

    @contextmanager
    def guard(self, name):
        initial = memory()
        if initial['available'] < initial['reserve']:
            self.breached.set()
            self.event('reserve_breach', {'memory': initial, 'processes': identities()})
            self.stop_owned_inference()
            raise Failure('resource_stopped', 'Physical RAM reserve unavailable before operation')
        done = threading.Event()
        def watch():
            from resource_snapshot import owned_memory
            next_snapshot = 0
            with (self.root / (name + '-memory.jsonl')).open('a') as log:
                while not done.is_set():
                    sample = {'epoch': time.time(), **memory()}
                    if time.monotonic() >= next_snapshot:
                        try:
                            self.event('owned_memory', owned_memory(self.state['units']))
                        except Exception as error:
                            self.event('owned_memory_diagnostic_error', {'error': repr(error)})
                        next_snapshot = time.monotonic() + 1
                    log.write(json.dumps(sample) + '\n')
                    log.flush()
                    if sample['available'] < sample['reserve']:
                        self.breached.set()
                        self.event('reserve_breach', {'memory': sample, 'processes': identities()})
                        self.stop_owned_inference()
                        return
                    done.wait(.2)
        watcher = threading.Thread(target=watch, daemon=True)
        watcher.start()
        try:
            yield
            if self.breached.is_set():
                raise Failure('resource_stopped', 'Physical RAM reserve crossed')
        except BaseException as error:
            if self.breached.is_set():
                raise Failure('resource_stopped', 'Physical RAM reserve crossed; underlying error: ' + repr(error)) from error
            raise
        finally:
            done.set()
            watcher.join(timeout=35)

    def wait_ready(self, url):
        deadline = time.monotonic() + 1800
        while time.monotonic() < deadline:
            if self.breached.is_set():
                raise Failure('resource_stopped', 'RAM reserve crossed during startup')
            try:
                return http(url)
            except (OSError, ValueError):
                state = self.system('show', self.unit, '-p', 'SubState', '--value')
                if state in ['failed', 'dead', 'exited']:
                    raise Failure('runtime_error', 'Backend exited before readiness')
                time.sleep(1)
        raise Failure('runtime_error', '30-minute backend readiness timeout')
