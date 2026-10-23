# Background jobs that survive SSH logout

On a Linux host with systemd, run from the directory the job should use:

```sh
bash /path/to/KIROSHI/scripts/run-background.sh my-job -- \
  /absolute/path/to/program argument1 argument2
```

The launcher checks the user manager, refuses an existing service name, enables
lingering for the current account if necessary, and verifies `Linger=yes` before
starting anything. If policy denies the change it exits without launching; it
does not invoke sudo or prompt for a password. Enabling lingering persists for
this account and keeps its user manager alive after the last login ends.

It launches a detached transient user service, preserving the current working
directory and literal command arguments. Use absolute program paths and supply
required environment/configuration explicitly: this is not a login shell and
does not copy the calling shell's entire environment. Startup uses `Type=exec`
so an executable-start failure is reported. The job has no runtime deadline or
automatic retries/restarts. Explicit stops include descendant process groups.

Successful jobs retain their unit and exit status (`active (exited)`); that does
not mean a computation is still running. Inspect `SubState` and `ExecMainStatus`
and the application's own final result files:

```sh
systemctl --user show my-job.service \
  -p LoadState -p ActiveState -p SubState -p Result -p ExecMainStatus
journalctl --user -u my-job.service -f
systemctl --user stop my-job.service
```

The launcher prints status/log/stop commands. Use a new name for another job, or
inspect and stop the retained unit before reusing its name. A successful launch
means the executable started, not that the whole computation succeeded.

SSH logout is covered. Reboots, shutdowns, explicit stops and resource failures
can still interrupt the job. Transient jobs do not automatically resume after a
reboot; resumability belongs to the application. This launcher does not impose
RAM limits or detect infinite code: the GX10 suite supplies those guards.

For GX10, from `~/projects/KIROSHI`, use this launcher for both the suite and its
recorder (choose a new output directory for every continuation):

```sh
RUN_ROOT="$HOME/experiments/gx10-coding-context-$(date +%Y%m%d-%H%M%S)"
bash scripts/run-background.sh gx10-coding-context-suite -- \
  /usr/bin/python3 "$PWD/benchmark/gx10-context/suite.py" run \
  --output "$RUN_ROOT" --continue-from /absolute/path/to/interrupted-run \
  --validation /absolute/path/to/passed-live-validation/validation.json
bash scripts/run-background.sh gx10-live-record -- \
  /usr/bin/python3 "$PWD/benchmark/gx10-context/record_live.py" \
  --run-root "$RUN_ROOT"
```

Protocol-v3 continuation supports interruptions during coding and context work.
It verifies cleanup before retaining durable results, waits for surviving guarded
workers, and retries interrupted cells in fresh Pi environments. A passed live
validation receipt bound to current harness hashes is required before scoring.
Starting an existing active suite is deliberately refused. See
[the protocol](../benchmark/gx10-context/EXPERIMENT.md) and
[validation/rollout evidence](../benchmark/gx10-context/VALIDATION.md).

Verification: `python3 scripts/test_run_background.py` exercises enabling,
verification failure, permission denial, existing-unit preservation, literal
arguments, already-enabled persistence, and startup error propagation.

References: [loginctl lingering](https://www.freedesktop.org/software/systemd/man/latest/loginctl.html),
[systemd-run services](https://www.freedesktop.org/software/systemd/man/latest/systemd-run.html).
