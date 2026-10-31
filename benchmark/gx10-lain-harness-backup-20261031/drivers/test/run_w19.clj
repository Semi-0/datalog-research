;; W19 STRICT test runner. Exit 0 ONLY if every test in BOTH suites ran with zero failures/errors
;; and a nonzero number of assertions actually ran. Any failure, error, exception, timeout
;; (enforced by the invoking shell's `timeout`), missing suite or wrong test count exits nonzero.
;; Usage (from drivers/):
;;   timeout 300 flock <abs>/.gate-jvm.lock clojure -M -e "(load-file \"test/run_w19.clj\")"
(ns run-w19
  (:require [clojure.test :as t]))

(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/test/w17_contract_test.clj")
(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/test/w19_classpath_test.clj")

(def expected-tests 19)                     ; 7 W17 contract tests + 12 W19 classpath/bridge/harbor tests

(let [res (try
            (t/run-tests 'lain.w17-contract-test 'lain.w19-classpath-test)
            (catch Throwable e
              (.printStackTrace e)
              {:fatal (.toString e)}))]
  (println :summary res)
  (let [ok (and (every? #(contains? res %) [:test :pass :fail :error])
                (zero? (:fail res)) (zero? (:error res))
                (pos? (:pass res))                       ; zero assertions => strict FAIL
                (= expected-tests (:test res)))]         ; a missing/renamed suite => strict FAIL
    (println (if ok :W19-TESTS-PASS :W19-TESTS-FAIL))
    (flush)
    (System/exit (if ok 0 1))))
