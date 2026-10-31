#!/usr/bin/env bash
# Strict final battery V2 2026-10-30: re-gate on the TRACER tree (candidate HEAD
# c1ed279b095d87efc65c7fd5a60f6d34197444a7, parent 1596f7e) with the uncommitted
# harness production edits still in the working tree.
#
# Copy of final-gate-20261030.sh with exactly three changes:
#   * HEAD expectation 1596f7e... -> c1ed279... (both at baseline and in drift check)
#   * manifest baseline regenerated into *-v2.txt (fresh before-snapshot) plus an
#     after-snapshot *-v2-after.txt; drift = before vs after + HEAD check
#   * every clojure call: timeout 900, <= -J-Xmx3g, serialized under $LOCK, and a
#     resource guard that pauses 60s while resource-status.json reserve_ok is false
#
# Exit 0 only if EVERY gate is strictly green with the known expected totals.
set -u
ROOT=/home/lain/experiments/lain-harness-flash-once-20261027
OBS="$ROOT/observations/recovery-20261030"
LOCK="$ROOT/.gate-jvm.lock"
HEAD_EXPECT=c1ed279b095d87efc65c7fd5a60f6d34197444a7
MAN="$OBS/stable-tree-manifest-20261030-v2.txt"
MAN_AFTER="$OBS/stable-tree-manifest-20261030-v2-after.txt"
SUM="$OBS/final-battery-summary-20261030-v2.txt"
RES="$OBS/resource-status.json"
FAIL=0

ram_ok() { awk '/MemTotal/{t=$2} /MemAvailable/{a=$2} END{exit !(a*5>=t)}' /proc/meminfo; }
reserve_ok() { grep -qE '"reserve_ok"[[:space:]]*:[[:space:]]*true' "$RES" 2>/dev/null; }
note() { echo "$@" | tee -a "$SUM"; }

# pause 60s whenever the resource guard trips; give up after 10 pauses
wait_ram() { local tries=0
  while ! ram_ok || ! reserve_ok; do
    tries=$((tries + 1))
    [ "$tries" -le 10 ] || { note "# RAM GUARD: still not ok after 10 pauses, refusing JVM"; return 1; }
    note "# RAM GUARD: reserve/physical not ok (memavail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)MB), pausing 60s (wait $tries)"
    sleep 60
  done; }

# one JVM at a time, cwd fixed per suite, <=3g heap, hard 900s timeout
run_clojure() { local t="$1" d="$2"; shift 2
  wait_ram || return 91
  flock -x "$LOCK" timeout --foreground "$t" env -C "$d" clojure "$@"
}

write_manifest() { # path
  {
    echo "# stable tree manifest $(date -u +%FT%TZ)"
    echo "# candidate HEAD: $(git -C "$ROOT/candidate" rev-parse HEAD)"
    ( cd "$ROOT/candidate" && git status --porcelain )
    ( cd "$ROOT/candidate" && { git ls-files -m; git ls-files --others; } \
        | grep -v '^\.cpcache/' | sort -u | xargs -r sha256sum )
    find "$ROOT/drivers" -path '*/SUPERSEDED*' -prune -o -type f \
      \( -name '*.clj' -o -name '*.mjs' -o -name 'deps.edn' \) -print | sort | xargs -r sha256sum
  } > "$1" 2>&1
}

: > "$SUM"

# ---- 0. stable tree manifest (fresh baseline) + HEAD gate ---------------------
write_manifest "$MAN"
note "# manifest(baseline): $MAN"
HEAD1=$(grep -m1 'candidate HEAD' "$MAN" | awk '{print $NF}')
note "# baseline HEAD: $HEAD1"
[ "$HEAD1" = "$HEAD_EXPECT" ] || { note "head-gate: HEAD is $HEAD1, expected $HEAD_EXPECT"; FAIL=1; }

# ---- 1. integrated 12-suite gate (one JVM) -----------------------------------
IG="$OBS/logs-v2-integrated.log"
cd "$ROOT/candidate" || exit 90
CP="$(clojure -Spath):test:editor/test"
{
  echo "# integrated 12-suite gate $(date -u +%FT%TZ)"
  echo "# memavail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)MB"
} > "$IG"
SUITES='propagators.compiler-2-harness-session-test
propagators.compiler-2-harness-effect-boundary-test
propagators.compiler-2-subagent-harness-test
propagators.compiler-2-harness-primitives-test
propagators.compiler-2-pi-extension-test
propagators.compiler-2-pi-transport-test
propagators.compiler-2-harbor-extension-test
propagators.compiler-2-harbor-grading-contract-test
propagators.compiler-2-socket-extension-test
propagators.compiler-2.runtime.session-extension-test
propagators.compiler-2.runtime.functional-network-effects-test
editor.harness-glue-attach-detach-test'
REQ=""; VEC=""
for ns in $SUITES; do REQ="$REQ (require (quote $ns))"; VEC="$VEC '$ns"; done
VEC="${VEC} ]"; VEC="[${VEC# }"
{
run_clojure 900 "$ROOT/candidate" -J-Xmx3g -Scp "$CP" -M -e "
(defn sum1 [x] (if (map? x) x (first (filter #(and (map? %) (= :summary (:type %))) (flatten x)))))
(do $REQ
    (def results (map (fn [n] [n (sum1 (clojure.test/run-tests n))]) $VEC))
    (doseq [[n r] results] (println :SUITE n :r r))
    (let [sum (apply merge-with + (map #(dissoc % :type) (remove nil? (map second results))))]
      (println :RESULT sum)
      (when (or (> (+ (:fail sum 0) (:error sum 0)) 0)
                (zero? (+ (:pass sum 0) (:fail sum 0) (:error sum 0))))
        (println :GATE-FAIL)
        (System/exit 1))))"
  echo "# clojure exit $?"
} >> "$IG" 2>&1
code=0
grep -q "# clojure exit 0" "$IG" || code=1
if grep -qE 'Execution error|Exception in thread|^:GATE-FAIL|Full report at' "$IG"; then
  echo "# integrated log marker failure"; code=1
fi
R=$(grep -m1 ':RESULT' "$IG" || true)
for pat in ':test 91[,}]' ':pass 648[,}]' ':fail 0[,}]' ':error 0[,}]'; do
  echo "$R" | grep -qE "$pat" || { echo "# integrated expectation miss: $pat"; code=1; }
done
[ "$code" -eq 0 ] || FAIL=1
note "integrated-12-suite: exit=$code $(echo "$R" | head -c 120)"

# ---- 2. driver suites ---------------------------------------------------------
expect_suite() { # name logfile exitcode "Ran N tests containing M assertions"
  local name="$1" log="$2" code="$3" expect="$4"
  if grep -qF "$expect" "$log" && grep -qE '^0 failures, 0 errors\.$' "$log" && [ "$code" -eq 0 ]; then
    note "$name: exit=0 [$expect]"
  else
    note "$name: FAIL exit=$code grep=$(grep -c 'Ran .* tests' "$log") $(tail -1 "$log")"
    FAIL=1
  fi
}
for s in w17 w19 w23; do
  LOG="$OBS/logs-v2-driver-$s.log"
  { echo "# driver $s $(date -u +%FT%TZ)"
    echo "# memavail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)MB"
    run_clojure 900 "$ROOT/drivers" -J-Xmx2g -M -e "(load-file \"test/run_$s.clj\")"
    echo "# clojure exit $?"
  } >> "$LOG" 2>&1
  c=$?
  case $s in
    w17) e="Ran 7 tests";;
    w19) e="Ran 19 tests containing 182 assertions.";;
    w23) e="Ran 3 tests containing 26 assertions.";;
  esac
  grep -q "# clojure exit 0" "$LOG" || c=1
  expect_suite "driver-$s" "$LOG" "$c" "$e"
done

# ---- 3. node suite ------------------------------------------------------------
NL="$OBS/logs-v2-node.log"
{ echo "# node suite $(date -u +%FT%TZ)"
  timeout 180 node --test examples/lain/subagents/pi_bridge_test.mjs
} > "$NL" 2>&1
nc=$?
if [ "$nc" -eq 0 ] && grep -q "^# pass 9$" "$NL" && grep -q "^# fail 0$" "$NL"; then
  note "node-suite: exit=0 [pass 9 fail 0]"
else
  note "node-suite: FAIL exit=$nc $(grep -E '^# (pass|fail)' "$NL" | tr '\n' ' ')"
  FAIL=1
fi

# ---- 4. tree drift check (before vs after + HEAD) -----------------------------
write_manifest "$MAN_AFTER"
HEAD2=$(git -C "$ROOT/candidate" rev-parse HEAD)
[ "$HEAD2" = "$HEAD_EXPECT" ] || { note "head-gate: HEAD moved to $HEAD2 during run"; FAIL=1; }
[ "$HEAD1" = "$HEAD2" ] || { note "tree-drift: HEAD changed $HEAD1 -> $HEAD2"; FAIL=1; }
if diff <(grep -v '^# stable tree manifest' "$MAN") <(grep -v '^# stable tree manifest' "$MAN_AFTER") > "$OBS/logs-v2-drift.diff" 2>&1; then
  note "tree-drift: none (baseline == after, HEAD $HEAD2)"
else
  note "tree-drift: DETECTED, see $OBS/logs-v2-drift.diff"
  sed -n '1,20p' "$OBS/logs-v2-drift.diff" | tee -a "$SUM"
  FAIL=1
fi

note "BATTERY-V2 exit=$FAIL $(date -u +%FT%TZ)"
exit $FAIL
