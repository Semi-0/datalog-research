"""Container-only Bubblewrap exceptions; retain Docker's other seccomp restrictions."""
import copy
import hashlib
import json
from pathlib import Path


PROFILE = 'gx10-harbor-bwrap-v1'
BASE_REVISION = '4b8214fc4971e06172938a1f1554ccff6647ee80'
BASE_URL = f'https://raw.githubusercontent.com/moby/profiles/{BASE_REVISION}/seccomp/default.json'
BASE_SHA256 = '6416b47770785a41ac59073cdc77d9fe98517df2799dc83ef207e622de3053f6'
# Linux clone namespace bits. Permit NEWUSER and NEWNS only; preserve all other denials.
NAMESPACE_MASK = 0x7E020000
SANDBOX_NAMESPACES = 0x10020000


def seccomp_profile(raw):
    if hashlib.sha256(raw).hexdigest() != BASE_SHA256:
        raise ValueError('Docker seccomp base hash mismatch')
    profile = copy.deepcopy(json.loads(raw))
    if profile['defaultAction'] != 'SCMP_ACT_ERRNO':
        raise ValueError('Docker seccomp base must deny by default')
    profile['syscalls'].extend([
        {'names': ['clone'], 'action': 'SCMP_ACT_ALLOW',
         'args': [{'index': 0, 'value': NAMESPACE_MASK & ~SANDBOX_NAMESPACES,
                   'valueTwo': 0, 'op': 'SCMP_CMP_MASKED_EQ'}]},
        {'names': ['unshare'], 'action': 'SCMP_ACT_ALLOW',
         'args': [{'index': 0, 'value': 0xFFFFFFFF & ~SANDBOX_NAMESPACES,
                   'valueTwo': 0, 'op': 'SCMP_CMP_MASKED_EQ'}]},
        {'names': ['mount', 'umount2', 'pivot_root'], 'action': 'SCMP_ACT_ALLOW'},
    ])
    return profile


def options(base, root):
    profile = root / 'seccomp.json'
    profile.write_text(json.dumps(seccomp_profile(Path(base).read_bytes()), indent=2) + '\n')
    return ['--security-opt', 'seccomp=' + str(profile.resolve()),
            '--security-opt', 'apparmor=' + PROFILE,
            '--security-opt', 'no-new-privileges=true', '--cap-drop', 'ALL']


def verify_container(apparmor, status):
    if apparmor.strip() != PROFILE + ' (enforce)':
        raise ValueError('Expected the dedicated enforcing AppArmor profile')
    fields = {}
    for line in status.splitlines():
        if ':' in line:
            name, value = line.split(':', 1)
            fields[name] = value.strip()
    if fields.get('NoNewPrivs') != '1' or int(fields.get('CapEff', '-1'), 16) != 0:
        raise ValueError('Container must have no-new-privileges and zero effective capabilities')
