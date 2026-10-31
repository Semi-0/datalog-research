#!/bin/bash
# Gated MAIN single-child bring-up, 2026-10-30. Run ONCE, only after:
#   BATTERY-V2 GREEN-ON-TRACER-TREE && audit GO (done) && RAM reserve_ok && no live bridge/driver.
# One child, one JVM, no relaunch. Never re-run this file.
set -u
ROOT=/home/lain/experiments/lain-harness-flash-once-20261027
OBS=$ROOT/observations/recovery-20261030
LOG=$ROOT/logs

fail() { echo "PRE-FLIGHT-FAIL: $1" | tee -a "$OBS/launch-preflight.log"; exit 1; }

# pre-flight
avail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
total=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)
[ $((avail*100)) -ge $((total*20)) ] || fail "RAM avail ${avail}MB < 20% of ${total}MB"
grep -q '"reserve_ok": true' "$OBS/resource-status.json" || fail "watch.py guard reserve_ok false"
curl -s -m3 http://127.0.0.1:18310/health >/dev/null || fail "model 18310 unhealthy"
for p in $(pgrep -x java); do tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "lain.flash-once\|flash_once.clj" && fail "a flash-once JVM already running (pid $p)"; done
for p in $(pgrep -x node); do tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "pi_bridge.mjs --serve" && fail "a pi_bridge process already running (pid $p)"; done
test -s "$ROOT/logs/flash-once.jsonl" && fail "flash-once.jsonl not fresh (must be 0B rotated)"
test -e "$ROOT/stage/app/answer.txt" && fail "stage/app/answer.txt exists (stale grading evidence)"
[ "$(python3 -c "import json;print(json.load(open('$ROOT/container-logs/trial-a1/bridge-owned.json'))['children'])")" = "[]" ] \
  || fail "bridge-owned.json children not empty"

# container start (start ONLY; sleep-7200 self-reap; 20-min driver guard fits)
sg docker -c "docker start lain-trial-1" >/dev/null || fail "docker start failed"
sleep 2
state=$(sg docker -c "docker inspect lain-trial-1 --format '{{.State.Status}} {{.State.StartedAt}}'") || fail "inspect failed"
echo "container: $state" | tee -a "$OBS/launch-preflight.log"
case "$state" in running*) ;; *) fail "container not running: $state";; esac

# in-container freshness (read-only)
ins=$(sg docker -c "docker exec lain-trial-1 sh -c 'cat /app/task.txt; test -e /app/answer.txt && echo DIRTY-ANSWER; ls /logs/agent/trial-a1/control'") || fail "in-container probe failed"
echo "in-container: $ins" | tee -a "$OBS/launch-preflight.log"
case "$ins" in *DIRTY-ANSWER*) fail "stale /app/answer.txt in container";; esac
case "$ins" in *launch*) fail "launch receipts already in control/ - reconcile, do NOT launch";; esac
flock -n "$ROOT/.gate-jvm.lock" -c true || fail ".gate-jvm.lock busy (sibling gate running) - wait, this is not a stall"

# THE launch — exactly once
cd "$ROOT/drivers" || fail "no drivers dir"
echo "launch-at $(date -Is)" >> "$OBS/launch-preflight.log"
timeout 1500 flock "$ROOT/.gate-jvm.lock" clojure -J-Xmx2g -M -e "(load-file \"$ROOT/drivers/flash_once.clj\")" -m lain.flash-once \
  > "$LOG/driver-trial-recovery20261030.log" 2>&1 &
DRV=$!
echo "driver-shell-pid $DRV $(date -Is)" | tee -a "$OBS/launch-preflight.log"
sleep 25
JPID=""
for p in $(pgrep -x java); do tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "flash_once.clj" && JPID=$p; done
echo "driver-jvm-pid ${JPID:-unknown} $(date -Is)" | tee -a "$OBS/launch-preflight.log"

# first-submit gate: watch for early progress, NOT a blind 20-min loop
for i in $(seq 1 24); do
  if ! kill -0 $DRV 2>/dev/null; then wait $DRV; rc=$?; echo "driver exited early rc=$rc"; exit $rc; fi
  ev=$(grep -o '"event":"[a-z.:]*"' "$ROOT/logs/flash-once.jsonl" 2>/dev/null | sort -u | tr '\n' ' ')
  case "$ev" in *pi.call*|*harbor.prepare*|*finish*|*assessed*) echo "FIRST-PROGRESS events: $ev"; exit 0;; esac
  sleep 10
done
echo "STALL: no submit/launch events after ~4min — killing driver ONCE, NO relaunch" | tee -a "$OBS/launch-preflight.log"
[ -n "$JPID" ] && kill -TERM "$JPID" 2>/dev/null
pkill -TERM -P $DRV 2>/dev/null; kill $DRV 2>/dev/null
exit 99
