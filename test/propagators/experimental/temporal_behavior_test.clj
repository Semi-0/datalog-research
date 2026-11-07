(ns propagators.experimental.temporal-behavior-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.temporal-behavior :as temporal]
            [propagators.gur :as gur]))

(defn p [id revision status]
  {:source (gur/stable-node-id [:temporal-test id]) :timestamp revision :premises-status status})
(defn event [id time revision payload]
  (collection/content {:base (temporal/occurrence id time revision payload)
                       :support #{(p id 0 :active)} :premise-state #{(p id 0 :active)}}))
(defn state [update]
  (temporal/merge-source {} {:evidence update}))

(deftest source-evidence-is-an-aci-join
  (let [states (mapv state [(event :a 1 0 1) (event :b 2 0 2)
                            (event :b 2 1 4) (event :b 2 1 5)
                            (collection/content {:premise-state #{(p :a 1 :retracted)}})])
        join temporal/merge-source]
    (doseq [a states]
      (is (= a (join a a)))
      (doseq [b states]
        (is (= (join a b) (join b a)))
        (doseq [c states]
          (is (= (join (join a b) c) (join a (join b c)))))))))

(deftest bounded-join-does-not-resurrect-pruned-payloads
  (let [updates (mapv #(temporal/merge-source {} {:window 2 :evidence (event % % 0 %)}) (range 1 5))
        join temporal/merge-source
        forward (reduce join {} updates)
        reverse-order (reduce join {} (reverse updates))]
    (is (= forward reverse-order))
    (is (= forward (join forward (first updates))))
    (is (= [3 4] (mapv :value (:records (temporal/project forward)))))
    (is (= 2 (count (get-in forward [:evidence :support/observations]))))
    (is (= #{1 2} (:unavailable-ids forward)))))

(deftest conflicting-beliefs-are-inspectable-and-recoverable
  (let [initial (state (event :a 1 0 3))
        conflict (temporal/merge-source initial
                                        {:evidence (collection/content {:premise-state #{(p :a 0 :retracted)}})})
        resolved (temporal/merge-source conflict
                                        {:evidence (collection/content {:premise-state #{(p :a 1 :active)}})})]
    (is (value/contradiction? (get-in (temporal/project conflict) [:records 0 :value])))
    (is (= 2 (count (get-in conflict [:evidence :support/states]))))
    (is (= 3 (get-in (temporal/project resolved) [:records 0 :value])))))

(deftest source-adapter-reuses-generic-reducer-without-strongest-reduction
  (let [id (gur/stable-node-id [:source])
        networks (temporal/source-networks)
        empty (temporal/update-value id networks {:window 2})
        initial (reducer/merge-content value/nothing empty nil nil)
        added (reducer/merge-content initial (event :a 1 0 3) nil nil)
        count (atom {})]
    (binding [temporal/*instrument* count]
      (dotimes [_ 5]
        (is (= 3 (get-in (reducer/reduced-result (reducer/strongest added)) [:records 0 :value])))))
    (is (empty? @count))
    (is (= added (reducer/merge-content added (event :a 1 0 3) nil nil)))))

(deftest conflicting-prefix-remains-a-conflict
  (let [input (temporal/join-evidence (event :a 1 0 1) (event :b 2 0 2))
        conflicted (temporal/join-evidence input
                                           (collection/content {:premise-state #{(p :a 0 :retracted)}}))
        result (temporal/calculate :scan {0 conflicted} {} + [])]
    (is (every? value/contradiction?
                (map :value (:records (temporal/project {:result result})))))))
