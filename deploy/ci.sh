#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in clojure node npm cargo python3; do
  if ! command -v "$tool" >/dev/null; then
    printf 'Missing CI prerequisite: %s\n' "$tool" >&2
    exit 1
  fi
done
if [[ -z ${LAIN_CI_TIMINGS:-} ]]; then
  mkdir -p target/ci
  LAIN_CI_TIMINGS=$(mktemp "$PWD/target/ci/timings.XXXXXXXX.jsonl")
fi
export LAIN_CI_TIMINGS
echo "CI timings: $LAIN_CI_TIMINGS"
stage() { python3 deploy/ci_timing.py "backend/$1" "${@:2}"; }
stage format clojure -M:format-check
stage lint clojure -M:lint
stage quality clojure -M:quality-test
stage production clojure -M:test production
stage protected-server clojure -M:test graph.lain-editor.protected-server-test
stage cljs-compile clojure -M:editor/cljs-test
stage cljs-tests node target/lain-editor/tests.js
stage rust cargo test --locked --manifest-path leapfrog-rs/Cargo.toml
stage deploy-controller python3 -m unittest discover -s deploy -p 'test_*.py' -v
stage session-sandbox python3 -m unittest discover -s deploy/session_sandbox -p 'test_*.py' -v
stage harbor-assisted python3 -m unittest discover -s benchmark/harbor-assisted -p 'test_*.py'
stage harbor-pilot python3 -m unittest discover -s benchmark/harbor-pilot -p 'test_*.py'
stage harbor-tasks python3 -m unittest discover -s benchmark/harbor-tasks -p 'test_*.py'
stage langgraph python3 -m unittest discover -s benchmark/gx10-langgraph -p test_outcomes.py
stage qwen-finish python3 -m unittest discover -s benchmark/qwen36-nvfp4 -p test_finish_events.py
stage node-benchmarks node --test benchmark/gx10-langgraph/test_control.mjs benchmark/harbor-assisted/test_execution.mjs benchmark/harbor-assisted/test_partial_launch.mjs
stage npm-install npm --prefix benchmark/pi-subagent-comparison ci --ignore-scripts --no-audit --no-fund
stage pi-fixtures npm --prefix benchmark/pi-subagent-comparison test
