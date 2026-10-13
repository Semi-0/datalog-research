(ns propagators.experimental.amb-checkpoint-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.checkpoint-search :as search]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.observation :as observation]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(deftest restoration-modes-follow-the-same-search
  (doseq [n [1 4 5 8]]
    (let [built (question/build n)
          original (:network built)
          checkpoint (search/run built :checkpoint)
          withdrawn (search/run built :withdrawal)
          expected (:solution (oracle/chronological n))]
      (is (= expected (:solution checkpoint) (:solution withdrawn)))
      (is (= (:status checkpoint) (:status withdrawn)))
      (is (= (select-keys (:metrics checkpoint) [:decisions :leaves :max-depth])
             (select-keys (:metrics withdrawn) [:decisions :leaves :max-depth])))
      (is (= 0 (get-in checkpoint [:metrics :withdrawals])))
      (is (= 0 (get-in withdrawn [:metrics :restores])))
      (is (= (get-in checkpoint [:metrics :restores])
             (get-in withdrawn [:metrics :withdrawals])))
      (is (identical? original (:network built)))
      (if (some? expected)
        (is (contains? (set (oracle/exhaustive n)) (:solution checkpoint)))
        (is (empty? (oracle/exhaustive n)))))))

(deftest checkpoints-isolate-sibling-branches-and-retain-dependencies
  (let [built (question/build 5)
        root (search/settle (select-keys built [:network :tasks]))
        [a b c] (:sources built)
        prefix (-> root (search/tell b 4 :active) (search/tell c 5 :active))
        rejected (search/tell prefix a 1 :active)
        accepted (search/tell prefix a 3 :active)
        output (get-in built [:cells :output])
        failed (observation/cell-base (:network rejected) (get-in built [:cells :failure]))
        supported (net/network-cell-strongest (:network accepted) output)]
    (is (= :failed (:status failed)))
    (is (= #{a b c} (set (map :source (:support failed)))))
    (is (= [3 4 5] (observation/base supported)))
    (is (= #{a b c} (set (map :source (datum/support-of supported)))))
    (is (every? #(= :active (:premises-status %)) (datum/support-of supported)))
    (is (value/nothing? (net/network-cell-strongest (:network prefix) a)))
    (is (value/nothing? (net/network-cell-strongest (:network root) b)))
    (is (= 1 (observation/cell-base (:network rejected) a)))
    (is (= 3 (observation/cell-base (:network accepted) a)))
    (is (tq/queue-empty? (:tasks root)))
    (is (tq/queue-empty? (:tasks accepted)))
    (is (= (:network accepted)
           (runner/completed-network (runner/run-network [] (:network accepted)))))))

(deftest partial-information-is-pending-not-failure
  (let [built (question/build 5)
        root (search/settle (select-keys built [:network :tasks]))
        partial (search/tell root (first (:sources built)) 3 :active)]
    (is (= :pending (:status (observation/cell-base (:network partial)
                                                   (get-in built [:cells :failure])))))
    (is (value/unusable? (net/network-cell-strongest (:network partial)
                                                   (get-in built [:cells :output]))))))

(deftest withdrawal-delivers-state-before-reactivation
  (let [built (question/build 5)
        root (search/settle (select-keys built [:network :tasks]))
        [a b c] (:sources built)
        found (-> root (search/tell a 3 :active) (search/tell b 4 :active)
                  (search/tell c 5 :active))
        withdrawn (search/tell found a value/nothing :retracted)
        repaired (search/tell withdrawn a 1 :active)]
    (is (= [3 4 5] (observation/cell-base (:network found) (get-in built [:cells :output]))))
    (is (value/unusable? (net/network-cell-strongest (:network withdrawn)
                                                   (get-in built [:cells :output]))))
    (is (= :pending (:status (observation/cell-base (:network withdrawn)
                                                   (get-in built [:cells :failure])))))
    (is (= :failed (:status (observation/cell-base (:network repaired)
                                                  (get-in built [:cells :failure])))))
    (is (= 1 (observation/cell-base (:network repaired) a)))))

(deftest invalid-input-and-evaluator-errors-are-not-unsatisfiability
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive finite domain" (question/build 0)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown restoration mode"
                       (search/run (question/build 1) :bfs)))
  (doseq [mode [:checkpoint :withdrawal]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"branch evaluation failed"
                         (search/run (question/build 1 (fn [_ _ _] :not-a-boolean)) mode)))))
