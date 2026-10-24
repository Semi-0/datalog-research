#!/usr/bin/python3
"""Expose read-only runtime metadata inside the plugin's write-confined namespace.

The enclosing Harbor container supplies the PID namespace: /proc contains only
this task's parent, worker and tools. No host PID namespace or host path is mounted.
"""
import os
import sys
from pathlib import Path

RUNTIME_READS = ('/proc', '/etc/resolv.conf', '/etc/hosts', '/etc/nsswitch.conf', '/etc/ssl/certs')


def arguments(argv, exists=Path.exists):
    if '--' not in argv:
        return argv
    boundary = argv.index('--')
    mounts = []
    for path in RUNTIME_READS:
        if exists(Path(path)):
            mounts.extend(['--ro-bind', path, path])
    return argv[:boundary] + mounts + argv[boundary:]


if __name__ == '__main__':
    binary = '/usr/local/lib/pilot/bwrap-real'
    os.execv(binary, [binary, *arguments(sys.argv[1:])])
