(ns gx10-benchmark.test-runner
  (:require [clojure.test :as test]
            [gx10-benchmark.outcome-test]
            [gx10-benchmark.transition-test]
            [gx10-benchmark.controller-test]
            [gx10-benchmark.process-test]))

(defn -main [& _]
  (let [result (test/run-tests 'gx10-benchmark.outcome-test 'gx10-benchmark.transition-test
                               'gx10-benchmark.controller-test 'gx10-benchmark.process-test)]
    (shutdown-agents)
    (System/exit (+ (:fail result) (:error result)))))
