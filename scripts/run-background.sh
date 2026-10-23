#!/usr/bin/env bash
# Linux/systemd user jobs that must survive the last SSH session closing.
set -euo pipefail

if [[ $# -lt 3 || "$2" != -- ]]; then
    echo "Usage: $0 UNIT -- COMMAND [ARG ...]" >&2
    exit 2
fi
unit="${1%.service}.service"
if [[ ! "$unit" =~ ^[[:alnum:]_][[:alnum:]_.@-]*\.service$ ]]; then
    echo "Invalid service name: $unit" >&2
    exit 2
fi
shift 2
for tool in loginctl systemctl systemd-run id; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required Linux/systemd command is missing: $tool" >&2
        exit 1
    fi
done
systemctl --user show-environment >/dev/null
state="$(systemctl --user show "$unit" --property=LoadState --value)"
if [[ "$state" != not-found ]]; then
    echo "Refusing to replace existing unit $unit (LoadState=$state). Choose a new name or inspect and stop the old unit." >&2
    exit 1
fi
user="$(id -un)"
linger="$(loginctl show-user "$user" --property=Linger --value)"
if [[ "$linger" != yes ]]; then
    echo "Enabling logout persistence for $user."
    if ! loginctl --no-ask-password enable-linger "$user"; then
        echo "Could not enable lingering. Job was not started. Enable it with an authorized loginctl enable-linger $user, then retry." >&2
        exit 1
    fi
fi
if [[ "$(loginctl show-user "$user" --property=Linger --value)" != yes ]]; then
    echo "Logout persistence verification failed. Job was not started." >&2
    exit 1
fi
# Keep argv literal, detach from SSH, retain successful exit status for inspection,
# and include child groups when explicitly stopping this job. No runtime deadline.
systemd-run --user --unit="$unit" --property=Type=exec \
    --property=KillMode=control-group --remain-after-exit \
    --expand-environment=no --working-directory="$PWD" -- "$@"
printf 'Started %s with verified Linger=yes.\n' "$unit"
printf 'Status: systemctl --user status %q\n' "$unit"
printf 'Logs:   journalctl --user -u %q -f\n' "$unit"
printf 'Stop:   systemctl --user stop %q\n' "$unit"
