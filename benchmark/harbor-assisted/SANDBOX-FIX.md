# Container-specific Bubblewrap policy

The original default-container probe failed at namespace creation. A no-inference diagnostic with only seccomp removed got past namespace creation and failed at `bwrap: Failed to make / slave: Permission denied`. The host's unconfined `unshare` probe separately hit AppArmor's `unprivileged_userns` restriction. No host-wide sysctl or Docker daemon setting was changed.

The fix selects a dedicated **enforcing** AppArmor profile and a hash-pinned Docker seccomp policy for each pilot container. It drops all container capabilities and enables `no-new-privileges`. Bubblewrap creates its own user and mount namespaces and confines the child to `/app`; the profile does not grant host `CAP_SYS_ADMIN`.

The seccomp policy retains the pinned Moby base and adds only clone/unshare requests restricted to user/mount namespace bits, plus mount, umount2 and pivot_root. Other namespace bits and Docker's clone3 restriction remain intact. AppArmor retains the container proc/sys protections and adds user namespaces, mounts and pivot_root. These exceptions increase kernel namespace/mount exposure inside these containers, which is necessary for Bubblewrap; the actual worker remains constrained by its filesystem namespace. Required trusted tools still need their own confinement validation.

Sources: [Docker seccomp documentation](https://docs.docker.com/engine/security/seccomp/), [pinned Moby seccomp base](https://github.com/moby/profiles/blob/4b8214fc4971e06172938a1f1554ccff6647ee80/seccomp/default.json), [pinned Moby AppArmor template](https://github.com/moby/profiles/blob/4b8214fc4971e06172938a1f1554ccff6647ee80/apparmor/template.go).

## Apply the prepared profile on GX10

Files are deployed at `/home/lain/experiments/gx10-assisted-admission-code-1/`. The profile passed offline compilation with AppArmor parser 4.0.1. The 13 focused tests passed both locally and on GX10. Loading a new kernel AppArmor profile requires sudo; the SSH session has no passwordless sudo.

Review [gx10-harbor-bwrap-v1.apparmor](gx10-harbor-bwrap-v1.apparmor), then run on GX10:

```sh
sudo apparmor_parser --add --skip-cache /home/lain/experiments/gx10-assisted-admission-code-1/gx10-harbor-bwrap-v1.apparmor
```

This loads a newly named profile for explicit container selection. It does not replace `docker-default`, persist a profile under `/etc`, or change existing containers. If this name is already loaded, inspect the existing profile before replacing it.

After loading, the admission check is:

```sh
python3 /home/lain/experiments/gx10-assisted-admission-code-1/admission.py \
  --task /home/lain/experiments/gx10-harbor-tasks-v1-protected/distinct \
  --node /home/lain/.local/opt/node-v24.22.1-linux-arm64 \
  --plugin /home/lain/experiments/gx10-langgraph-install-1/packages/node_modules/pi-better-subagents \
  --seccomp-base /home/lain/experiments/gx10-assisted-admission-code-1/docker-seccomp-base.json \
  --output /home/lain/experiments/gx10-assisted-policy-admission-NEW
```

Use a fresh output directory. The runner refuses a mismatched base hash, missing AppArmor profile, a non-enforcing runtime label, effective capabilities, failed confinement, or uncertain cleanup. A passing sandbox alone does not admit the full cohort. The strengthened probe checks inside write/rename/delete and rejection of absolute, relative, symlink and shell writes outside `/app`.

The base file is pinned by SHA-256 `6416b47770785a41ac59073cdc77d9fe98517df2799dc83ef207e622de3053f6`; [security.py](security.py) defines the exact upstream revision and verifies the file before use. The historical default-security probe remains available by omitting `--seccomp-base`.

To unload the named profile after its containers have stopped:

```sh
sudo apparmor_parser --remove --skip-cache /home/lain/experiments/gx10-assisted-admission-code-1/gx10-harbor-bwrap-v1.apparmor
```

The narrowed seccomp policy passed the strengthened exact-plugin confinement probe in a diagnostic container using the already loaded `gx10-delegation-bwrap` profile, which has `flags=(unconfined)`. That diagnostic deliberately accepted this existing label; production code still requires the new enforcing profile. It used zero effective capabilities and no-new-privileges, rejected all four outside-write routes, issued no model requests and verified cleanup. See the [diagnostic evidence excerpt](evidence/namespace-diagnostic-2-summary.json); full raw records are at `/home/lain/experiments/gx10-assisted-namespace-diagnostic-2/`.

The enforcing-profile live admission **passed** after the user loaded the prepared profile. `/home/lain/experiments/gx10-assisted-policy-admission-1/` contains the full result, launch, runtime-security, sandbox and cleanup receipts; a [local evidence excerpt](evidence/enforcing-admission-1-summary.json) preserves the key fields. The observed label was `gx10-harbor-bwrap-v1 (enforce)`, UID 1000, all capability sets zero, `NoNewPrivs: 1`, and `Seccomp: 2`. Inside write/rename/delete succeeded; absolute, relative, symlink and shell writes outside `/app` were rejected. The container and image were removed and absence was independently checked. No model request or cohort ran. The sandbox prerequisite is resolved; required-tool checks and the remaining pilot implementation are separate acceptance steps.
