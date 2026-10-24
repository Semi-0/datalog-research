#!/bin/bash
set -eu
mkdir -p /logs/verifier
printf '0\n' > /logs/verifier/reward.txt
if timeout 120 clojure /tests/check.clj > /logs/verifier/clojure.log 2>&1; then
    printf '1\n' > /logs/verifier/reward.txt
fi
cat /logs/verifier/clojure.log
