(ns propagators.experimental.amb-enumeration-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.checkpoint-enumeration :as checkpoint]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.enumeration-data :as data]
            [examples.lain.amb.network-search :as first-answer]
            [examples.lain.amb.repair-enumeration :as repair]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(deftest checkpoints-extend-not-replace-the-first-answer-network
  (let [q (question/build 5) original (first-answer/build q)
        extended (checkpoint/extend-all-answers original)
        first-result (runner/run-network (:tasks original) (:network original))
        result (checkpoint/evaluate extended (select-keys extended [:network :tasks]))]
    (is (= [3 4 5] (:solution (first-answer/answer original (:network first-result)))))
    (is (= [[3 4 5]] (:answers result)))
    (is (= :completed (:status result)))
    (doseq [[id entry] (net/net-env (:network original)) :when (prop/prop? entry)]
      (is (identical? entry (net/network-env-lookup (:network extended) id))))
    (is (= 4 (count (filter prop/prop? (vals (net/net-env (:network extended)))))))
    (is (= :running (:status (observation/cell-base (:network original) (:frontier original)))))
    (is (= (:network result)
           (:network (checkpoint/evaluate extended {:network (:network result) :tasks (:tasks extended)}))))))

(deftest all-answer-sets-match-exhaustive-oracle
  (let [pool (driver/pool 4)]
    (try
      (doseq [n [1 4 5 12]]
        (let [q (question/build n) template (checkpoint/template q)
              expected (vec (sort (oracle/exhaustive n)))
              sequential (checkpoint/run template)
              partitioned (checkpoint/run-partitions template 4 pool)
              repaired (repair/run (repair/extend-question q))]
          (is (= expected (:answers sequential) (:answers partitioned) (:answers repaired)))
          (is (= :completed (:status sequential) (:status partitioned) (:status repaired)))
          (is (= (count expected) (reduce + (map #(count (:answers %)) (:branches partitioned)))))
          (is (= (count expected) (count (:answer-exclusions repaired))))
          (is (= (* n n n) (get-in sequential [:frontier :leaves])))))
      (finally (driver/close! pool)))))

(deftest repaired-answers-are-not-logical-contradictions
  (let [q (question/build 5) built (repair/extend-question q) result (repair/run built)
        cells (:enumeration-cells built)
        context (observation/context (:network result) #{(:request cells)})
        assignment (set (map (fn [source v] [source (dec v)]) (:sources q) [3 4 5]))]
    (is (= 1 (count context)))
    (is (not (learning/blocked? (:logical-nogoods result) assignment #{})))
    (is (learning/blocked? (:answer-exclusions result) assignment context))
    (is (not (learning/blocked? (:answer-exclusions result) assignment #{})))
    (is (some #(and (empty? (:literals %)) (= context (:context %))) (:search-exclusions result)))
    (doseq [[id entry] (net/net-env (:network q)) :when (prop/prop? entry)]
      (is (identical? entry (net/network-env-lookup (:network built) id))))
    (is (= (:network result) (:network (repair/run (assoc built :network (:network result))))))))

(deftest all-true-question-enumerates-without-inventing-failures
  (let [q (question/build 2 (fn [_ _ _] true))
        expected (vec (for [a [1 2] b [1 2] c [1 2]] [a b c]))
        a (checkpoint/run (checkpoint/template q)) b (repair/run (repair/extend-question q))]
    (is (= expected (:answers a) (:answers b)))
    (is (= #{} (:logical-nogoods b)))
    (is (= 8 (count (:answer-exclusions b))))
    (is (every? seq (map :context (:answer-exclusions b))))))

(deftest delayed-old-answer-is-not-collected-as-current
  (let [q (question/build 2 (fn [_ _ _] true)) built (repair/extend-question q)
        result (repair/run built) network (:network result)
        cells (:enumeration-cells built)
        emitted (publication/source-message network (first (:sources q)) value/nothing :retracted)
        [_ delayed] (core/eval-cell (first (:sources q))
                                   (update emitted :value collection/content) network)]
    (is (some? (repair/collectable-answer network q cells)))
    ;; The old tuple is still in the output, but its source version was withdrawn.
    ;; Observe before draining downstream tasks to exercise stale delivery.
    (is (= (observation/cell-base network (get-in q [:cells :output]))
           (observation/cell-base delayed (get-in q [:cells :output]))))
    (is (nil? (repair/collectable-answer delayed q cells)))))

(deftest resume-preserves-unexplored-alternatives-and-counters
  (let [frontier {:status :solution :solution [3 4 5] :accepted :snapshot
                  :pending [{:id 7}] :last-result 6 :decisions 20 :leaves 15}
        resumed (checkpoint/resume-frontier frontier)]
    (is (= :running (:status resumed)))
    (is (= (select-keys frontier [:pending :last-result :decisions :leaves])
           (select-keys resumed [:pending :last-result :decisions :leaves])))
    (is (not (contains? resumed :accepted)))
    (is (= :exhausted (:status (checkpoint/resume-frontier (assoc frontier :pending [])))))
    (is (thrown? clojure.lang.ExceptionInfo (checkpoint/resume-frontier {:status :running})))))

(deftest callback-errors-and-unresolved-values-remain-errors
  (doseq [predicate [(fn [_ _ _] (throw (ex-info "constraint failed" {})))
                     (fn [_ _ _] value/nothing)]]
    (let [q (question/build 1 predicate)]
      (is (thrown? clojure.lang.ExceptionInfo (checkpoint/run (checkpoint/template q))))
      (is (thrown? clojure.lang.ExceptionInfo (repair/run (repair/extend-question q))))))
  (let [id (ids/new-node-id)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (data/retained-set (data/seed-snapshot (nb/install-cells [id]) id 3) id)))))
