(ns gx10-benchmark.process-test
  (:require [clojure.test :refer [deftest is]]
            [gx10-benchmark.controller-test :as fixture]
            [gx10-benchmark.process :as process]
            [gx10-benchmark.telemetry :as telemetry]))

(deftest real-command-output-and-ownership
  (let [sentinel (.start (ProcessBuilder. ^java.util.List ["/bin/sleep" "30"]))]
    (try
      (let [result (process/run! ["/bin/echo" "fixture"] (fixture/temporary) {:timeout-ms 2000})]
        (is (= 0 (:exit-code result)))
        (is (= "fixture\n" (slurp (:stdout result)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timed out"
                            (process/run! ["/bin/sleep" "10"] (fixture/temporary) {:timeout-ms 50})))
      (is (.isAlive sentinel))
      (finally (.destroyForcibly sentinel)))))

(deftest reserve-crossing-stops-owned-work
  (let [samples (atom [100 0])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"RAM reserve crossed"
                          (process/run! ["/bin/sleep" "10"] (fixture/temporary)
                                        {:sample! #(let [v (first @samples)]
                                                     (swap! samples (fn [s] (vec (rest s))))
                                                     {:available-bytes v})
                                         :reserve-bytes 50 :timeout-ms 1000})))))

(deftest no-invented-generation-or-prefill-rates
  (let [r (telemetry/request-metrics {:id 1 :finish :stop :input-tokens 1000
                                     :cached-input-tokens 800 :output-tokens 100
                                     :prefill-ms 100 :decode-ms 2000})]
    (is (= 50.0 (:decode-tokens-per-second r)))
    (is (= 10000.0 (:prefill-tokens-per-second r)))
    (is (= :includes-cache-hits (:prefill-basis r))))
  (is (nil? (:decode-tokens-per-second (telemetry/request-metrics {:finish :aborted :output-tokens 100 :decode-ms 2000}))))
  (is (nil? (:prefill-tokens-per-second (telemetry/request-metrics {:finish :stop :input-tokens 1000})))) )
