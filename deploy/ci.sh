#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in clojure node npm cargo python3; do
  if ! command -v "$tool" >/dev/null; then
    printf 'Missing CI prerequisite: %s\n' "$tool" >&2
    exit 1
  fi
done
clojure -M:test
clojure -M:test graph.lain-editor.protected-server-test
clojure -M:editor/cljs-test
node target/lain-editor/tests.js
node --test examples/lain/subagents/pi_bridge_test.mjs
cargo test --locked --manifest-path leapfrog-rs/Cargo.toml
python3 -m unittest discover -s deploy -p 'test_*.py' -v
python3 -m unittest discover -s benchmark/harbor-assisted -p 'test_*.py'
python3 -m unittest discover -s benchmark/harbor-pilot -p 'test_*.py'
python3 -m unittest discover -s benchmark/harbor-tasks -p 'test_*.py'
python3 -m unittest discover -s benchmark/gx10-langgraph -p test_outcomes.py
python3 -m unittest discover -s benchmark/qwen36-nvfp4 -p test_finish_events.py
node --test benchmark/gx10-langgraph/test_control.mjs benchmark/harbor-assisted/test_execution.mjs benchmark/harbor-assisted/test_partial_launch.mjs
npm --prefix benchmark/pi-subagent-comparison ci --ignore-scripts --no-audit --no-fund
npm --prefix benchmark/pi-subagent-comparison test
