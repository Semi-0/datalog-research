;; W17 STRICT test runner. Exit 0 ONLY if every test passed with zero failures/errors
;; and a nonzero number of assertions actually ran. Any failure, error, exception,
;; timeout (enforced by the invoking shell's `timeout`), or zero-assertion run exits nonzero.
;; Usage (from drivers/):
;;   clojure -Sdeps '<cp>' -M -e "(load-file \"test/run_w17.clj\")"
(ns run-w17
  (:require [clojure.test :as t]))

(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/test/w17_contract_test.clj")

(let [expected-tests 7
      res (try
            (t/run-tests 'lain.w17-contract-test)
            (catch Throwable e
              (.printStackTrace e)
              {:fatal (.toString e)}))]
  (println :summary res)
  (let [ok (and (every? #(contains? res %) [:test :pass :fail :error])
                (zero? (:fail res)) (zero? (:error res))
                (pos? (:pass res))                       ; zero assertions => strict FAIL
                (= expected-tests (:test res)))]
    (println (if ok :W17-TESTS-PASS :W17-TESTS-FAIL))
    (flush)
    (System/exit (if ok 0 1))))
