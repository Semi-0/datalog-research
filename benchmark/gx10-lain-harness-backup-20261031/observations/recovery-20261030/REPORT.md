# GX10 recovery — 2026-10-30

## Outage evidence
- Previous boot continued writing journal entries until 2026-10-30 13:26 CET, immediately before the user's power cycle.
- NetworkManager at03:57:28 CET: Wi-Fi four-way authentication handshake -> disconnected -> need-auth (supplicant-disconnect).
- At03:57:29: need-auth -> failed (no-secrets), then disconnected/inactive.
- NordVPN subsequently reported Network unreachable; Meshnet could not reach relay servers or its API.
- No host OOM-killer, kernel panic, NVIDIA Xid or thermal-throttling log match was found after2026-10-29 19:00 local time. Older container-limited OOMs exist onOct28 and do not explain this incident.
- This establishes loss of Wi-Fi connectivity; it does not establish whether the initial handshake failure originated in the access point, adapter or another cause. no-secrets does not by itself prove a wrong password.
- After reboot Wi-Fi and Meshnet connected. Saved profile autoconnect=yes, autoconnect-retries=0, psk-flags=0.

## Work recovery
- Reboot removed all tmux/agent processes. Candidate and driver source edits/checkpoints survived.
- Working source backups: pre-resume/ and candidate-before.patch.
- W23 had proven the live stall was cwd-relative .lain loading plus a discarded load result. Its partial production fix and 91tests/648assertion green log survived.
- W23 driver regression tests were incomplete. No real Flash-child success or final artifacts.json was present at recovery.
- Only the existing gx10-flash-autoround-team container is being restored, with its original configuration. All alternative model containers remain stopped.
- A new independent watch.py enforces20percent physical RAM reserve. A readiness-gated launcher starts a fresh focused on-device Pi coordinator only after health200.
- The coordinator receives TASK.md containing exact remaining work, production-faithful bridge tests, native effect/editor ownership constraints and final acceptance gates. Previous dead jobs must not be replayed or awaited.
- No commit, push, deployment, credential change or VPN/firewall change performed during recovery.

Raw evidence: network.log, kernel-critical.log, model-start.json, resource-status.json and resources.jsonl.

## Verified resumption
Original model health200 at12:42 UTC; fresh coordinator b82b07d4-567f-417b-a075-af2dc8170ad4 in tmux gx10-harness-recovery pane%0 read TASK.md and fresh test evidence. Actual batch batch_mvuys2n7_1 launched w23-tests sa_mvuys2na_1 and contract-review sa_mvuys2ol_2. Each hard30min, soft25/grace5, same AutoRoundmedium. Fresh W23 test failed and timed out at300sec (exit124); no orphan supervisor JVM left. No real child or harness success claimed.

## Recovery-policy follow-up
Live inspection confirms autoconnect=yes and autoconnect-retries=0 (forever); psk-flags=0, auth-retries=-1. No enabled dedicated Wi-Fi recovery service/timer was found. The decisive03:57:29 message is not merely no-secrets: it is 'The connection was modified since activation'. Audit history shows the saved profile was updated onOct27 11:08:05 CET for connection.autoconnect-retries and connection.timestamp while that boot's connection was active. No other connection-update is logged before the failure. This is consistent with a saved-vs-applied profile mismatch preventing secret retrieval; it does not prove which property caused the mismatch or why the initial four-way handshake failed. NetworkManager1.46 source explicitly rejects secret retrieval for a modified applied connection. Infinite retry count alone did not recover this state. No live network settings changed during diagnosis.
