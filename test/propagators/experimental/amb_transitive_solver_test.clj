(ns propagators.experimental.amb-transitive-solver-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.amb.knowledge :as knowledge]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.solver-operator :as operator]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.transitive-solver :as experiment]
            [examples.lain.amb.worker-driver :as driver]
            [examples.lain.relational-datalog.runtime :as relation]
            [propagators.cells.value :as value]
            [propagators.network :as net]
            [propagators.network-cache :as cache]))

(deftest network-inference-agrees-with-independent-bfs
  (doseq [[domain edges] [[[1 2 3] #{}]
                         [[1 2 3 4] #{[1 2] [2 3] [3 4]}]
                         [[1 2] #{[1 2] [2 1]}]
                         [[1 2 3 4] #{[1 2] [3 4]}]
                         [[1 2 3 4] #{[1 2] [1 3] [2 4] [3 4]}]]]
    (let [result (knowledge/infer domain edges)]
      (is (= :completed (:status result)))
      (is (= (knowledge/closure-oracle domain edges) (:reach result)))
      (is (= (:network result) (:network (relation/run (:network result) [])))))))

(deftest late-bridge-duplicate-facts-and-fixed-topology
  (let [before (knowledge/infer [1 2 3 4] #{[1 2] [3 4]})
        bridged (knowledge/add-edges before #{[2 3]})
        duplicate (knowledge/add-edges bridged #{[1 2] [2 3] [3 4]})]
    (is (= #{[1 2] [3 4]} (:reach before)))
    (is (contains? (:reach bridged) [1 4]))
    (is (= (knowledge/closure-oracle [1 2 3 4] #{[1 2] [2 3] [3 4]}) (:reach bridged)))
    (is (= (:graph (:network before)) (:graph (:network bridged))))
    (is (= (set (keys (:env (:network before)))) (set (keys (:env (:network bridged))))))
    (is (= (:network bridged) (:network duplicate)))
    (is (zero? (:steps duplicate)))))

(deftest runtime-inference-never-consults-the-host-oracle
  (with-redefs [knowledge/closure-oracle (fn [& _] (throw (ex-info "Oracle must not drive inference" {})))]
    (is (= #{[1 2] [2 3] [1 3]}
           (:reach (knowledge/infer [1 2 3] #{[1 2] [2 3]}))))))

(deftest dataflow-facts-are-derived-from-the-installed-network
  (let [fixture (knowledge/dataflow-fixture)]
    (is (= [3 4 6 10 11] (:values fixture)))
    (is (= #{[1 2] [1 3] [2 4] [3 4] [4 5]} (:edges fixture)))
    (is (= (:edges fixture) (knowledge/dataflow-edges (:subject-network fixture) (:subject-cells fixture))))
    (is (= #{[1 2] [1 3] [2 4] [3 4] [4 5] [1 4] [1 5] [2 5] [3 5]}
           (knowledge/closure-oracle (:domain fixture) (:edges fixture))))
    (is (= 7 (count (knowledge/answers-oracle (:domain fixture) (:edges fixture)))))))

(deftest one-compiled-question-swaps-all-three-solvers-without-source-writes
  (let [fixture (experiment/prepare (knowledge/scenario :graph-chain))
        compiled (:compiled fixture) initial (get-in fixture [:state :network])
        calls (atom []) pool (driver/pool 4)]
    (try
      (binding [strategy/*executor* pool cache/*cache* nil
                operator/*observe-solve* #(swap! calls conj %)]
        (let [final
              (reduce (fn [network choice]
                        (let [result (lain/select-and-run compiled network choice)
                              question (:question (peek @calls))]
                          (is (= :completed (:status result)))
                          (is (= [[1 2 3] [1 2 4] [1 3 4] [2 3 4]] (:answers result)))
                          (is (= (:graph initial) (:graph (:network result))))
                          (is (every? #(value/nothing? (net/network-cell-strongest (:network result) %))
                                      (:sources question)))
                          (:network result))) initial experiment/modes)
              duplicate (lain/select-and-run compiled final :concurrent)]
          (is (= 3 (count @calls)))
          (is (every? #(identical? (:question (first @calls)) (:question %)) @calls))
          (is (= (:env final) (:env (:network duplicate))))))
      (finally (driver/close! pool)))))

(deftest cycles-dataflow-and-no-answer-share-the-same-contract
  (let [pool (driver/pool 4)]
    (try
      (doseq [scenario [{:id :tiny-cycle :domain [1 2] :edges #{[1 2] [2 1]}}
                        {:id :empty :domain [1 2] :edges #{}}
                        (knowledge/scenario :dataflow-fork-join)]]
        (let [fixture (experiment/prepare scenario)]
          (doseq [choice experiment/modes]
            (testing (str (:id scenario) " " choice)
              (let [row (experiment/sample fixture choice pool)]
                (is (= :completed (:status row)))
                (is (= (:expected fixture) (:answers row)))
                (is (= 1 (:solver-calls row)))
                (if (= :concurrent choice)
                  (is (= (min 4 (count (:domain fixture))) (get-in row [:metrics :execution-threads])))
                  (is (= 1 (get-in row [:metrics :execution-threads])))))))))
      (finally (driver/close! pool)))))

(deftest invalid-knowledge-and-scenarios-fail-explicitly
  (doseq [[domain edges] [[[1 1] #{}] [[] #{}] [[1 2] #{[1 3]}] [[1 2] #{[1]}]]]
    (is (thrown? clojure.lang.ExceptionInfo (knowledge/infer domain edges))))
  (is (thrown? clojure.lang.ExceptionInfo (knowledge/scenario :unknown)))
  (is (thrown? clojure.lang.ExceptionInfo (knowledge/reachable? 7 1 2))))
