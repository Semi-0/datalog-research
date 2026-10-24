(ns propagators.experimental.amb-lain-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [examples.lain.amb.experiment :as example]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(deftest dependency-directed-repair-eliminates-an-entire-independent-subtree
  (let [run (example/run
             "(let-cell [repairs ca cb cc]
                (define a (amb (list 1 2) repairs ca))
                (define b (amb (list 1 2) repairs cb))
                (define c (amb (list 1 2) repairs cc))
                (define valid (= a 2))
                (define failure (require valid))
                (define known (learn failure (list ca cb cc)))
                (-> (backtrack (list ca cb cc) known) repairs)
                (list (switch a valid) (switch b valid) (switch c valid)))")
        network (get-in run [:result :network])
        records (example/choice-records network)
        changed (filter #(= 1 (:index %)) records)
        a (:source (first changed))
        independent (remove #(= a (:source %)) records)
        known #{{:literals #{[a 0]} :context #{}}}]
    (is (= :completed (get-in run [:result :status])))
    (is (= [2 1 1] (:solution run)))
    (is (= 3 (count records)))
    (is (= 1 (count changed)))
    (is (= 3 (:timestamp (first changed))) "one withdraw/choose handshake")
    (is (= 2 (count independent)))
    (is (every? #(and (= 0 (:index %)) (= 1 (:timestamp %))) independent)
        "repair jumps over the two later independent choices")
    (is (some #{known} (example/cell-bases network))
        "the real learned no-good contains only the failing dependency")
    (let [[b c] (map :source independent)]
      (doseq [bi (range 2) ci (range 2)]
        (is (learning/blocked? known #{[a 0] [b bi] [c ci]} #{}))
        (is (not (learning/blocked? known #{[a 1] [b bi] [c ci]} #{})))))))

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
  ;; Characterization only: quiescence with an unresolved output is NOT solver
  ;; acceptance evidence. The former collection exception is no longer emitted.
  (let [original (str/replace example/source
                              "(list (switch a valid) (switch b valid) (switch c valid))"
                              "(switch (list a b c) valid)")
        run (example/run original)
        network (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        snapshot (observation/list-snapshot network root)]
    (is (= :completed (get-in run [:result :status])))
    (is (nil? (get-in run [:result :error])))
    (is (false? (:ready? snapshot)))
    (is (empty? (:items snapshot)))
    (is (= :bool4/nothing (observation/cell-base network root)))
    (is (nil? (:solution run)))))
