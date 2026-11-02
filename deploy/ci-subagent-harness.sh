#!/usr/bin/env bash
# Experimental integration lane. Deliberately opt-in; failures still fail this lane.
set -euo pipefail
cd "$(dirname "$0")/.."
clojure -M:test subagent-harness
node --test examples/lain/subagents/pi_bridge_test.mjs
