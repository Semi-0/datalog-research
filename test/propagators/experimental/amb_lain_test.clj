(ns propagators.experimental.amb-lain-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [examples.lain.amb.experiment :as example]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(deftest declaration-finds-a-source-linked-triple-and-reruns-idempotently
  (let [run (example/run example/source)
        network (get-in run [:result :network])
        records (example/choice-records network)
        replay (nb/run-propagators network (get-in run [:compiled :props]))]
    (is (= :completed (get-in run [:result :status])))
    (is (= [3 4 5] (:solution run)))
    (is (= 3 (count records)))
    (is (= 3 (count (set (map :source records)))))
    (is (every? #(= :active (:status %)) records))
    (is (= [3 4 5] (sort (map #(nth (:domain %) (:index %)) records))))
    (is (every? #(contains? (net/net-env network) (:source %)) records))
    (is (= (net/net-env network) (net/net-env replay)))
    (is (= (net/net-graph network) (net/net-graph replay)))
    (is (pos? (count (get-in run [:trace :nodes]))))
    (is (pos? (count (get-in run [:trace :edges]))))))

(deftest lain-exhaustion-retains-an-explicit-unsatisfiable-result
  (let [run (example/run (example/source-for 4))
        network (get-in run [:result :network])
        bases (example/cell-bases network)]
    (is (= :completed (get-in run [:result :status])))
    (is (nil? (:solution run)))
    (is (some #(= {:action :unsatisfiable} %) bases))
    (is (some #(= #{{:literals #{} :context #{}}} %) bases))))

(deftest first-choice-does-not-wait-for-feedback
  (let [compiled (example/compile-source "(let-cell [r s] (amb (list 1 2) r s))")
        result (runner/run-network (:props compiled) (:net compiled))
        records (example/choice-records (:network result))]
    (is (= :completed (:status result)))
    (is (= 1 (observation/cell-base (:network result) (:cell compiled))))
    (is (= 1 (count records)))
    (is (= 1 (:timestamp (first records))))))

(deftest two-applications-have-independent-choice-occurrences
  (let [source (str/replace example/source "(pythagorean (list 1 2 3 4 5))"
                            "(list (pythagorean (list 1 2 3 4 5))
                                   (pythagorean (list 1 2 3 4 5)))")
        run (example/run source)
        records (example/choice-records (get-in run [:result :network]))]
    (is (= :completed (get-in run [:result :status])))
    (is (= 2 (count (:solution run))))
    (is (= 6 (count records)))
    (is (= 6 (count (set (map :source records)))))
    (is (= [3 3 4 4 5 5] (sort (map #(nth (:domain %) (:index %)) records))))))

(deftest original-whole-compound-gating-is-a-reproduced-runtime-gap
  ;; Characterization only: this is explicitly NOT solver acceptance evidence.
  (let [original (str/replace example/source
                              "(list (switch a valid) (switch b valid) (switch c valid))"
                              "(switch (list a b c) valid)")
        run (example/run original)]
    (is (= :failed (get-in run [:result :status])))
    (is (= "Expected supported collection content" (ex-message (get-in run [:result :error]))))
    (is (set? (:content (ex-data (get-in run [:result :error])))))))
