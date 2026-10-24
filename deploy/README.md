# GX10 editor CI/CD

GitHub is the source of deployment revisions. Both repositories run `.github/workflows/ci.yml` on main pushes and pull requests. GX10 polls once a minute and accepts only successful **push-to-main CI runs at both current main commit IDs**. Pending or failed CI leaves the running release unchanged.

The backend CI requires repository Actions secret `LAIN_READ_TOKEN`: a fine-grained token with read-only Contents access to both private repositories. Actions read access also permits checking CI runs. Set secrets privately with `gh secret set`; never put a token in a file or commit. The frontend's standalone CI does not need private cross-repository access.

## Install on GX10

Prerequisites: Java 21, Clojure CLI, Node 22, npm, Python 3, GitHub CLI and user systemd. Authenticate interactively on GX10:

```sh
gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git
```

After the backend source checkpoint is pushed, fetch it into a clean checkout and run:

```sh
python3 deploy/gx10_editor.py install
systemctl --user start gx10-lain-pull.service
```

The installer owns only the `gx10-lain-*` user services/timer and `~/.local/share/lain-editor-deploy`. It does not update development checkouts, touch benchmark services or start model inference. User services survive logout/reboot only when the user's systemd manager remains available; check `loginctl show-user "$USER" -p Linger` for unattended operation.

## Deployment and evidence

Each candidate has separate backend/frontend checkouts at exact SHAs. The backend consumes that candidate frontend's local contracts. GX10 builds the frontend, runs HTTP transport tests, backend editor integration tests, a real frontend-to-backend computation probe and browser acceptance before promotion. Candidate processes run in owned process groups and are reaped after verification. Main revisions and CI are checked again after preflight; a candidate superseded during the build is not activated.

Promotion atomically changes `current`, then restarts the owned services and checks the computation again. A failure restores the previous symlink and restarts the previous release. Earlier releases are retained. Failed candidate pairs are recorded and are not automatically retried at every timer tick; inspect the failure and explicitly run `python3 ~/.local/share/lain-editor-deploy/driver.py poll --retry` when appropriate.

The active pair and CI links are in `current/release.json`; the most recent attempted activation is in `last-attempt.json`. Inspect operation logs with:

```sh
systemctl --user status gx10-lain-pull.timer gx10-lain-backend gx10-lain-frontend
journalctl --user -u gx10-lain-pull.service -n 100 --no-pager
```

Frontend and backend bind to GX10 loopback ports **4782** and **4785**. Access the frontend from the Mac through SSH, choosing a free local port:

```sh
ssh -N -L 4792:127.0.0.1:4782 lain@gx10-ac8b.local
```

Then open `http://127.0.0.1:4792/`. Its `/api` proxy executes on GX10. Load `examples/lain/subagents/composition.lain` to inspect the three-actor sketch. Its missing Harbor/user/Pi adapters intentionally remain partial information; deployment does not make those adapters live or establish subagent execution guarantees.

## Verification

```sh
python3 -m unittest discover -s deploy -p 'test_*.py' -v
```

The tests cover exact-revision CI admission, rejection of fork/PR/failed runs, pending-CI behavior, promotion, rollback, failed first activation, failure receipts/retry suppression and cleanup of candidate processes. They do not claim a GitHub or GX10 deployment occurred; live deployment requires the actual CI and service evidence above.
