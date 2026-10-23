"""Read-only attribution for the owned benchmark service groups."""
from pathlib import Path


def owned_memory(units):
    units = set(units)
    groups = {}
    processes = []
    if not Path('/proc').is_dir():
        return {'groups': groups, 'processes': processes, 'unsupported': 'Linux procfs unavailable'}
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit():
            continue
        try:
            membership = (directory / 'cgroup').read_text()
            group = next((line.split(':', 2)[-1] for line in membership.splitlines()
                          if any(unit in line.split('/') for unit in units)), None)
            if group is None:
                continue
            status = {}
            for line in (directory / 'status').read_text().splitlines():
                key, value = line.split(':', 1)
                if key in ['VmRSS', 'VmHWM', 'RssAnon', 'RssFile', 'RssShmem']:
                    status[key + '_bytes'] = int(value.split()[0]) * 1024
            processes.append({'pid': int(directory.name), 'cgroup': group,
                              'command': (directory / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace'),
                              **status})
            if group not in groups:
                cgroup = Path('/sys/fs/cgroup') / group.lstrip('/')
                groups[group] = {'memory_current_bytes': int((cgroup / 'memory.current').read_text()),
                                 'memory_stat': (cgroup / 'memory.stat').read_text()}
        except (FileNotFoundError, ProcessLookupError):
            # A process can exit between reading membership and its status.
            pass
        except (PermissionError, ValueError, OSError) as error:
            processes.append({'pid': int(directory.name), 'diagnostic_error': repr(error)})
    return {'groups': groups, 'processes': processes}
