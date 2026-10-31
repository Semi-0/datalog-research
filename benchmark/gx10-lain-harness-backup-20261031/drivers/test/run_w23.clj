;; W23 STRICT runner. Exit 0 ONLY if every test passed with zero failures/errors and a nonzero
;; number of assertions actually ran. Any failure, error, exception, timeout (enforced by the
;; invoking shell's `timeout`) or zero-assertion run exits nonzero.
;; Usage (from drivers/): clojure -M -e "(load-file \"test/run_w23.clj\")"
(ns run-w23
  (:require [clojure.test :as t]))

(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/test/w23_contract_test.clj")

(let [expected-tests 3
      res (try (t/run-tests 'lain.w23-contract-test)
               (catch Throwable e (.printStackTrace e) {:fatal (.toString e)}))]
  (println :summary res)
  (let [ok (and (every? #(contains? res %) [:test :pass :fail :error])
                (zero? (:fail res)) (zero? (:error res))
                (pos? (:pass res))                          ; zero assertions => strict FAIL
                (= expected-tests (:test res)))]
    (println (if ok :W23-TESTS-PASS :W23-TESTS-FAIL))
    (flush)
    (System/exit (if ok 0 1))))
