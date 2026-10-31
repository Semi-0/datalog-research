# APPARMOR-LOAD.md — gx10-harbor-bwrap-v1 missing after 13:26 boot

Investigation: read-only, no mutations. Host gx10 (gx10-ac8b), Ubuntu 24.04 arm64,
kernel 7.0.0-1019-nvidia, docker 29.6.2. Timestamp of investigation: 2026-10-30 18:15–18:37 CET.

## VERDICT: **ROOT-REQUIRED-ONE-SHOT**

No unprivileged and no already-granted route exists to load `gx10-harbor-bwrap-v1` into the
kernel. Loading AppArmor policy is a `CAP_MAC_ADMIN` operation
("Commands that require the MAC_ADMIN capability within the affected AppArmor namespace to
load policy into the kernel", `man 8 apparmor_parser`, *Privileged commands*; `-a/--add` is
documented under **Privileged profile commands**). Our credential set is
`uid=1000(lain)`, `CapEff=0000000000000000`, empty bounding set, `NoNewPrivs=1`, and none of
the escalation affordances on this host (sudo drop-ins, polkit actions, setuid helpers, file
capabilities, systemd units) expose a policy-load path. One root command fixes it; the
container config needs no change and the launch script is rerunnable unchanged.

---

## 0. Evidence-quality caveat (important for reuse)

The investigating shell is itself sandboxed (bwrap-style: non-init user+mount namespace,
masked `/proc`, `no_new_privs=1`). Three probes are therefore **inconclusive as evidence about
the host** and are flagged below rather than counted as proof:

| Probe | Observed in sandbox | Status |
|---|---|---|
| `sudo -n -l` | `sudo: The "no new privileges" flag is set, which prevents sudo from running as root.` | Sandbox artifact. Host verdict comes from `/etc/sudoers.d/` inventory instead (§2). |
| `unshare -Ur true` | `cannot open /proc/self/uid_map: Read-only file system` | Sandbox artifact, not a host userns verdict. |
| `aa-exec -p gx10-harbor-bwrap-v1 -- true` | `ERROR: Read-only file system` | Sandbox artifact (masked `/proc/self/attr/...`). |

Everything else below was read through host-visible kernel/daemon state
(`/sys/kernel/security`, `/proc/1`, `journalctl -k`, `systemctl`, `docker` daemon) and is
direct evidence about gx10. `docker ps` works from this shell (kernel-level docker-group access
works; only `sg docker`'s `setgroups()` is blocked by the sandbox user namespace):
`setgroups: Operation not permitted / setgid: Invalid argument`.

---

## 1. Loaded profile enumeration

`aa-status` is **unusable unprivileged** on this kernel (that is a root-only tool):

```
$ aa-status
apparmor module is loaded.
You do not have enough privilege to read the profile set.        (rc=4)
```

`/sys/kernel/security/apparmor/policy/names` **does not exist** on this kernel
(`ls: cannot access ...: No such file or directory`) — this kernel exposes enumeration through
`policy/profiles/<name>.<id>/{name,mode}`, which is world-readable (`-r--r--r--`). Use that:

```sh
for d in /sys/kernel/security/apparmor/policy/profiles/*/; do
  printf '%-40s %s\n' "$(cat "$d/name")" "$(cat "$d/mode")"
done | sort
```

Result (this boot, boot id `3791d1aa…`, started 13:26:58): **156 profiles loaded** —
56 enforce, 8 complain, 92 unconfined. Relevant subset:

| Profile | Loaded? | Mode | Loaded at | Source |
|---|---|---|---|---|
| `gx10-harbor-bwrap-v1` | **ABSENT** | — | — | not in `/etc/apparmor.d` |
| `gx10-delegation-bwrap` | present | **unconfined** (`flags=(unconfined)`) | 13:26 (apparmor.service) | `/etc/apparmor.d/gx10-delegation-bwrap` |
| `docker-default` | present | enforce | 13:27 (loaded by dockerd itself) | no `/etc/apparmor.d/docker-default` file |
| `runc` | present | unconfined (allow-all stub) | 13:26 | `/etc/apparmor.d/runc` |

`/sys/kernel/security/apparmor/policy/namespaces/` is empty (single root AppArmor namespace —
the profile is not hiding in another namespace).

Confirmation the container asks for a label that the kernel has never seen this boot:

```
$ docker inspect lain-trial-1 --format '{{json .HostConfig.SecurityOpt}}'
["apparmor=gx10-harbor-bwrap-v1","no-new-privileges=true"]

$ docker inspect lain-trial-1 --format '{{.State.Error}}'
... unable to apply apparmor profile: apparmor failed to apply profile:
write fsmount:fscontext:proc/thread-self/attr/apparmor/exec: no such file or directory
State: exited (127)
```

```
kernel audit, this boot:
Oct 30 18:15:36 apparmor="DENIED" operation="change_onexec" class="file" info="label not found"
                error=-2 profile="unconfined" name="gx10-harbor-bwrap-v1" pid=25665 comm="runc:[2:INIT]"
Oct 30 18:16:31 (identical, pid=25791)
```

`error=-2` is `ENOENT` — the *target label does not exist*. It is **not** a permission denial
(runc itself runs under the allow-all `runc` stub / `profile="unconfined"`), so nothing about
runc's own confinement is at fault. Only the profile is missing.

Previous boot (`-1`, 2026-10-27 10:54 CET → 2026-10-30 13:26 CET) proves the load was ad-hoc:

```
Oct 27 22:17:13 apparmor="STATUS" operation="profile_load" profile="unconfined"
                name="gx10-harbor-bwrap-v1" pid=41387 comm="apparmor_parser"     <- manual root load
Oct 27 12:54:17 / 19:56:16 / 19:56:57 / 21:22:38
                apparmor="DENIED" operation="change_onexec" ... name="gx10-harbor-bwrap-v1"
                comm="runc:[2:INIT]" and comm="aa-exec"                          <- before that load
```

This boot has **no** `profile_load` event for that name. Cause: the profile lives only in
`/home/lain/...`, and `apparmor.service` (enabled, `active (exited)` since 13:26:59) executes
`/lib/apparmor/apparmor.systemd reload` → `rc.apparmor.functions: __parse_profiles_dir` over
`/etc/apparmor.d` only. `grep -rl gx10-harbor /etc` → **no matches**.

Profile file integrity (4 identical copies, matches the recorded `5de40657…`):

```
5de40657fb4b50b21151187ebc920f554a59ac4e61eab4d74ecd609d7fe12ea8
  /home/lain/experiments/gx10-assisted-admission-code-1/gx10-harbor-bwrap-v1.apparmor   (canonical, SANDBOX-FIX.md)
  /home/lain/projects/datalog-research/benchmark/harbor-assisted/gx10-harbor-bwrap-v1.apparmor
  .../lain-harness-flash-once-20261027/candidate/benchmark/harbor-assisted/...
  .../gx10-flash-autoround-concurrency-20261027/code/benchmark/harbor-assisted/...
```

---

## 2. Route-by-route elimination (all routes checked, all closed)

| # | Route | Result | Evidence |
|---|---|---|---|
| 1 | Passwordless sudo | **No.** No NOPASSWD drop-ins exist. | `ls -a /etc/sudoers.d/` → `. .. README` only (dir is `0755`, listing is authoritative even though the files are `0440 root` and unreadable: `grep: /etc/sudoers: Permission denied`). `lain` **is** in `sudo:x:27:lain`, i.e. standard password-authenticated `%sudo` — consistent with "sudo requires a password we don't have". `sudo -n` itself is untestable here (§0). |
| 2 | polkit / pkexec | **No AppArmor action exists.** | `pkaction` list: no `*apparmor*` action; `/usr/share/polkit-1/actions/` has no apparmor `.policy`; no apparmor rule in `/usr/share/polkit-1/rules.d/` (13 files, all desktop/fwupd/packagekit/etc.). `/etc/polkit-1/rules.d/` is `0750 root` → **unreadable, unverified** (limitation). `/usr/bin/pkexec` is setuid root but has no action to call. |
| 3 | setuid/setgid helper | **None.** | `find / -xdev -perm -4000` → 19 hits (`su`,`sudo`,`mount`,`pkexec`,`nvidia-modprobe`,…); `… | grep -iE 'apparmor|aa-|harbor|gx10'` → **empty**. `apparmor_parser` is `-rwxr-xr-x root`, no setuid bit. `sg`→`newgrp` is setgid but blocked here by the sandbox userns and gains only group membership (which never grants `CAP_MAC_ADMIN`). |
| 4 | File capabilities | **None grant `cap_mac_admin`.** | `getcap -r /usr` → only `cap_net_raw`/`cap_net_bind_service`/`cap_net_admin`,`cap_sys_nice` (snap-confine). Explicit xattr probe of `apparmor_parser`, `pkexec`, `su`, `newgrp`, `docker`, `nsenter`, `unshare` → all `-`. |
| 5 | systemd reload of apparmor | **Would not help even with root.** | `apparmor.service` (`/lib/systemd/system/apparmor.service`, enabled, ran 13:26:59, PID 1306 exited 0) → `ExecStart/ExecReload=/lib/apparmor/apparmor.systemd reload` → `rc.apparmor.functions` parses `APPARMOR_DIR=/etc/apparmor.d` only. `gx10-harbor-bwrap-v1` is not there (§1). No unit anywhere references `apparmor_parser` (`grep -rl apparmor_parser /lib/systemd/system /etc/systemd/system` → empty); no unit/timer/path/cron references `/home/lain` (`grep -rl /home/lain …` → empty). `apparmor.service` `ExecStop=/bin/true` on purpose, so restart is also not a load path. |
| 6 | `/etc/apparmor.d` drop-in | **Not writable by us.** | `drwxr-xr-x root root /etc/apparmor.d` (also `/etc/apparmor.d/local`, `.../disable`) — root-owned 755; `gx10` grep under `/etc` finds only `gx10-delegation-bwrap`. Writing there is itself the *root* action (see persistence note). |
| 7 | Docker daemon auto-load | **Daemon loads only `docker-default`.** | `docker info` → `SecurityOptions: ["name=apparmor","name=seccomp,profile=builtin","name=cgroupns"]`; `docker-default.169` appeared in the kernel profile set at 13:27 (daemon start, one minute after apparmor.service) and no `/etc/apparmor.d/docker-default` exists → the daemon's own loader handles only that one name. `/var/lib/docker/apparmor*` is `Permission denied`, so absence of a daemon-side profile store there is **unverified**; the empirical proof stands (daemon did not create the label it then failed to apply). `/etc/docker/daemon.json` does not exist. |
| 8 | containerd config | **Nothing relevant.** | `/etc/containerd/config.toml` (886 B, 0644): no apparmor/runtime/snapshotter overrides (`grep -iE 'apparmor|runtime|snapshotter'` → no hits). runc asks the kernel for the label; it does not load policy. |
| 9 | `aa-load` (precompiled cache) | **Root-only, cache unreadable.** | `/usr/sbin/aa-load` is `-rwxr-xr-x root`, no caps; loads "Precompiled AppArmor policy from a cache location". `/var/cache/apparmor/{0bb6850a.0,f6da0254.0}` are `drwx------ root` (`ls: cannot open directory: Permission denied`, `find -type f | wc -l` → 0 visible). And SANDBOX-FIX.md's canonical command uses `--skip-cache`, so no cached blob for this profile is expected. |
| 10 | `aa-complain` / `aa-audit` / `aa-enforce` | **Not installed, and useless anyway.** | `ls /usr/sbin/aa-* /usr/bin/aa-*` → only `aa-enabled aa-exec aa-features-abi aa-load aa-remove-unknown aa-status aa-teardown`. There is no `aa-complain`/`aa-audit`/`aa-logprof`/`aa-genprof`. These tools only change the mode of an **already loaded** profile, so they could not conjure the label; `aa-exec` likewise only *transitions to* a loaded label — and the Oct 27 journal shows `comm="aa-exec"` hitting the same `label not found` before the root load. |
| 11 | Unprivileged user-namespace trickery | **Closed by design and by host config.** | `sysctl kernel.apparmor_restrict_unprivileged_userns = 1` (the reason `gx10-delegation-bwrap` exists — it grants `userns` to `/usr/bin/bwrap` only). A user namespace grants no authority over the host's AppArmor policy: policy load needs `CAP_MAC_ADMIN` *within the affected AppArmor namespace*, and this host has exactly one (empty `policy/namespaces/`). Sandbox artifact for `unshare -Ur` noted in §0. |
| 12 | Write the kernel interface directly | **DAC looks permissive; the capability gate stops it.** See §3. | stat-only, no writes attempted. |

---

## 3. Would writing `/sys/kernel/security/apparmor/.load` work unprivileged? — **No**

Stat-only inspection (no write attempted, per instructions):

```
/sys/kernel/security/apparmor/.load       -rw-rw-rw- nobody nogroup     (0666!)
/sys/kernel/security/apparmor/.replace    -rw-rw-rw- nobody nogroup
/sys/kernel/security/apparmor/.remove     -rw-rw-rw- nobody nogroup
/sys/kernel/security/apparmor/policy/.load   -rw-r----- nobody nogroup  (0640)
/sys/kernel/security/apparmor/policy/names   -> No such file or directory (not on this kernel)
```

Two independent reasons this is not a route:

1. **DAC is not the gate.** The legacy `/.load` is mode 0666, i.e. world-writable by permission
   bits alone; the kernel still refuses on the LSM/capability check. `apparmor_parser(8)`:
   privileged commands "require the MAC_ADMIN capability within the affected AppArmor namespace
   to load policy into the kernel". Our `CapEff`/`CapBnd` are `0000000000000000`.
2. **The `nobody nogroup` display is a namespace artifact, not a group grant.** Host `uid 0`
   and every gid except `1000` are *unmapped* in the investigating shell's user namespace, so
   kernel-exposed `root:root`/`nogroup` objects render as `nobody:nogroup`
   (proof: `/etc/shadow` is `-rw-r----- nobody nogroup` and is **not** readable → no real
   privilege over root-owned files). `securityfs` inodes are owned by the global root uid, so
   the `0640 nogroup` on `policy/.load` is not an invitation — we are not a member of group
   nogroup on the host.

Corroborating host facts: `/sys/kernel/security/lsm` = `lockdown,capability,landlock,yama,apparmor,ima,evm`;
`lockdown` = `[integrity] confidentiality`; AppArmor features include `policy/{set_load,compressed_load,diff_encode}`.
Nothing here exposes an unprivileged load interface.

---

## 4. Could `docker start` take a one-shot override instead? — **No**

```
$ docker start --help
Usage:  docker start [OPTIONS] CONTAINER [CONTAINER...]
Options:
  -a, --attach               Attach STDOUT/STDERR and forward signals
      --detach-keys string   Override the key sequence for detaching
  -i, --interactive          Attach container's STDIN
```

No `--security-opt`, and no equivalent on `docker update` (its flags are limited to
cpu/shares/cpuset/memory/pids-limit/restart). `--security-opt` exists only on `docker run`/
`docker create`, which would mean **creating a new container** — that changes container config
and is out of scope, and dropping the profile would weaken the confinement that the pilot
profile exists to provide (`gx10-harbor-bwrap-v1` is an *enforcing*, non-`flags=(unconfined)`
profile; the already-loaded `gx10-delegation-bwrap` is `flags=(unconfined)` and is not an
equivalent substitute, nor is it the requested label).

---

## 5. Recovery runbook (one root human, ~20 seconds)

Container `lain-trial-1` is `exited (127)`, so nothing is running under the label; `--add`
(correct for "not loaded"; it errors if a same-named profile already exists) is the right verb.
Run on gx10 from a normal host shell (not from an agent sandbox; `sg docker` fails inside a
sandbox with `setgroups: Operation not permitted`):

```sh
# 0) integrity gate — MUST print 5de40657fb4b50b21151187ebc920f554a59ac4e61eab4d74ecd609d7fe12ea8
sha256sum /home/lain/experiments/gx10-assisted-admission-code-1/gx10-harbor-bwrap-v1.apparmor

# 1) load it (canonical command from SANDBOX-FIX.md)
sudo apparmor_parser --add --skip-cache \
  /home/lain/experiments/gx10-assisted-admission-code-1/gx10-harbor-bwrap-v1.apparmor
#    only if step 1 reports the name already exists: use --replace instead of --add

# 2) confirm before starting anything
sudo aa-status | grep -i gx10-harbor          # expect: gx10-harbor-bwrap-v1  (in enforce)

# 3) start the existing container UNCHANGED (no config edits)
sg docker -c "docker start lain-trial-1"      # from a normal host shell
#  docker start lain-trial-1                 # equivalent where docker-group access is direct
```

Then rerun `observations/recovery-20261030/launch-main-20261030.sh` as-is — it is idempotent up
to the preflight it aborted in, and no launch budget was consumed.

**Verification — profile is in the kernel (runnable unprivileged, no sudo needed):**

```sh
# aa-status substitute for non-root (this kernel has no policy/names file):
for d in /sys/kernel/security/apparmor/policy/profiles/*/; do
  printf '%-40s %s\n' "$(cat "$d/name")" "$(cat "$d/mode")"; done | grep gx10
#   expect:  gx10-harbor-bwrap-v1      enforce
#            gx10-delegation-bwrap     unconfined
sudo dmesg | grep 'profile_load' | grep gx10-harbor     # STATUS line, or:
journalctl -k --since today | grep -F 'name="gx10-harbor-bwrap-v1"'
```

**Verification — container is up:**

```sh
docker inspect lain-trial-1 --format '{{.State.Status}} {{.State.ExitCode}} {{.State.Error}}'
#   expect:  running 0  (empty Error).  Note: .State.Status prints "running", not "Up".
docker exec lain-trial-1 cat /proc/self/attr/current      # label should name gx10-harbor-bwrap-v1
```

**Post-start freshness checks** (both bind-mounted, so checkable from the host too):

```sh
# /app  <- stage/app ; /logs/agent <- container-logs
docker exec lain-trial-1 test ! -e /app/answer.txt && echo "answer.txt ABSENT (fresh)"
# host equivalent: test ! -e stage/app/answer.txt   (verified absent at 18:33, mtime dir Oct 28 16:37)

docker exec lain-trial-1 ls -la /logs/agent/trial-a1/control
# host equivalent: ls -la container-logs/trial-a1/control
#   currently: bridge-inventory.json, bridge-workers.json  (pre-existing control state)

docker exec lain-trial-1 cat /app/task.txt        # expect exactly: 3 4
# host equivalent: cat stage/app/task.txt          (verified: "3 4", 4 bytes)
```

(Paths relative to `/home/lain/experiments/lain-harness-flash-once-20261027/`; mounts read from
`docker inspect lain-trial-1 --format '{{json .Mounts}}'`:
`stage/app -> /app`, `container-logs -> /logs/agent`, both RW, rprivate.)

If `docker start` still fails after a successful load, the only remaining classes of cause are
(a) the profile loaded but in complain/unconfined mode — re-check `mode` above, or (b) a second,
unrelated runc failure — the `label not found`/`ENOENT` signature in the kernel audit must be
gone; if it is gone and the error text changed, that is a new incident.

## 6. Persistence (out of scope now, but this recurs on every boot)

The profile is ad-hoc kernel state: it survived nothing, and the Oct 30 13:26 power-cycle
dropped it exactly as the Oct 27 load was only ever valid for that boot. To make it survive
reboots, a root human must install it where `apparmor.service` looks (`/etc/apparmor.d`, root
owned — mirroring the existing `gx10-delegation-bwrap` pattern used by
`benchmark/gx10-delegation/README.md`):

```sh
sudo install -m 644 /home/lain/experiments/gx10-assisted-admission-code-1/gx10-harbor-bwrap-v1.apparmor \
     /etc/apparmor.d/gx10-harbor-bwrap-v1
sudo apparmor_parser -r /etc/apparmor.d/gx10-harbor-bwrap-v1
```

Not done here, and not recommended to bundle into the recovery: it widens the host's permanent
attack surface beyond the pilot, whereas `--add --skip-cache` is a single boot-scoped action.

## 7. Cleanup / teardown of the loaded label (after its containers stop)

```sh
sudo apparmor_parser --remove --skip-cache \
  /home/lain/experiments/gx10-assisted-admission-code-1/gx10-harbor-bwrap-v1.apparmor
```

## 8. Unverified / limitations

- `/etc/sudoers` and `/etc/sudoers.d/README` contents unreadable (`0440 root`); verdict rests on
  the directory inventory (no drop-ins besides `README`) + user report that sudo needs a password.
- `/etc/polkit-1/rules.d/` (`0750 root`) unreadable → a site-local polkit rule granting an
  apparmor-adjacent action cannot be fully excluded; no corresponding polkit *action* exists,
  so such a rule would have nothing to authorize.
- `/var/lib/docker/**` and `/var/cache/apparmor/*/**` unreadable (`Permission denied`) →
  daemon-side profile store and parser cache contents not inspected; conclusions drawn from
  kernel-side profile-set timestamps and the daemon's own failure text instead.
- Kernel writes to `securityfs` interfaces were **not** attempted (stat-only, per instructions);
  the §3 verdict rests on the documented `CAP_MAC_ADMIN` requirement plus our zero-capability set.
- `sudo`/`unshare`/`aa-exec` probes are sandbox artifacts (§0), not host evidence.
