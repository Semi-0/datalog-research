(ns propagators.experimental.ttms-reducer-test
  (:require [clojure.test :refer [deftest is]]
            [experiments.ttms-compound.demo :as demo]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-reducer :as history]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.semantic-trace :as trace]))

(defn- premise [source timestamp status]
  {:source source :timestamp timestamp :premises-status status})

(defn- observed [source timestamp base]
  (let [p (premise source timestamp :active)]
    (collection/content {:base base :support #{p} :premise-state #{p}})))

(defn- publish [network target content]
  (let [[tasks patched] (core/eval-cell target (message/message target content) network)]
    (nb/run-propagators patched tasks)))

(defn- setup []
  (let [[input records archive output] (repeatedly 4 ids/new-node-id)
        [tasks installed] ((history/p:collect input records archive output)
                           (nb/install-cells [input records archive output]))]
    {:input input :records records :archive archive :output output :tasks tasks
     :network (nb/run-propagators installed tasks)}))

(defn- base [network id]
  (datum/layer-value (net/network-cell-strongest network id) :base))

(deftest retained-history-and-current-support-have-distinct-meanings
  (let [{:keys [input records archive output tasks network]} (setup)
        after-first (publish network input (observed input 1 7))
        after-second (publish after-first input (observed input 2 8))
        p3 (premise input 3 :retracted)
        withdrawn (publish after-second input (collection/content {:base value/nothing
                                                             :support #{p3} :premise-state #{p3}}))
        restored (publish withdrawn input (observed input 4 9))
        result (base restored output)
        projected (net/network-cell-strongest restored records)]
    (is (= [7 8 value/nothing 9] (:history result)))
    (is (= [9] (:current result)))
    (is (= [false false false true] (mapv :current? (:records result))))
    (is (= #{(premise input 4 :active)} (:frontier result)))
    (is (= #{(premise input 4 :active)} (reducer/reduced-dependence projected)))
    (is (= (:frontier result) (reducer/reduced-epoch projected)))
    (is (= #{archive} (set (map :source (datum/support-of
                                         (net/network-cell-strongest restored output))))))
    (is (= #{} (:support (base withdrawn output))))
    (is (= [] (:current (base withdrawn output))))
    (is (not (value/unusable? (net/network-cell-strongest withdrawn output))))
    (is (= restored (publish restored input (observed input 4 9))))
    (is (= restored (publish restored input (observed input 1 7))))
    (is (= restored (nb/run-propagators restored tasks)))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env restored)))))))

(deftest semantic-graphs-and-network-history-keep-their-original-data
  (let [{:keys [input output network]} (setup)
        graph-a (trace/graph-union {:nodes {:x "x"} :node-kinds {:x :cell}
                                   :values {:x 7} :edges []})
        graph-b (assoc-in graph-a [:values :x] 8)
        after-first (publish network input (observed input 1 graph-a))
        after-second (publish after-first input (observed input 2 graph-b))
        old (obj/compound-object {:x 10 :y 20})
        fresh (obj/compound-object {:x 7})
        third (publish after-second input (observed input 3 old))
        fourth (publish third input (observed input 4 fresh))
        result (base fourth output)]
    (is (= [graph-a graph-b old fresh] (:history result)))
    (is (= [fresh] (:current result)))
    (is (= #{:x} (set (obj/public-slot-keys (first (:current result))))))
    (is (= #{:x :y} (set (obj/public-slot-keys (nth (:history result) 2)))))
    (is (= [1 2 3 4] (mapv #(-> % :support first :timestamp) (:records result))))))

(deftest history-merge-is-associative-commutative-and-idempotent
  (let [source (ids/new-node-id)
        updates (mapv (fn [n] {:history/observations #{{:base n :support #{(premise source n :active)}}}
                               :history/states #{(premise source n :active)}}) [1 2 3])]
    (doseq [a updates]
      (is (= a (history/merge-history a a)))
      (doseq [b updates]
        (is (= (history/merge-history a b) (history/merge-history b a)))
        (doseq [c updates]
          (is (= (history/merge-history (history/merge-history a b) c)
                 (history/merge-history a (history/merge-history b c)))))))))

(deftest out-of-order-records-are-ordered-by-source-time
  (let [source (ids/new-node-id)
        a {:history/observations #{{:base 8 :support #{(premise source 2 :active)}}}
           :history/states #{(premise source 2 :active)}}
        b {:history/observations #{{:base 7 :support #{(premise source 1 :active)}}}
           :history/states #{(premise source 1 :active)}}
        result (history/project-history (history/merge-history a b))]
    (is (= [7 8] (:history result)))
    (is (= [8] (:current result)))
    (is (= #{(premise source 2 :active)} (:support result)))))

(deftest contradiction-provenance-is-recorded-without-being-relabelled
  (let [{:keys [input output network]} (setup)
        problem (value/contradiction-with-provenance #{:measurement})
        after-first (publish network input (observed input 1 problem))
        after (publish after-first input (observed input 2 42))
        record (first (:records (base after output)))]
    (is (= problem (:base record)))
    (is (= #{(premise input 1 :active)} (:support record)))
    (is (= [42] (:current (base after output))))))

(deftest recorder-requires-distinct-owned-cells
  (let [id (ids/new-node-id)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"four distinct cells"
                         (history/p:collect id id id id)))))

(deftest native-ttms-dependency-shrink-counterexample-is-explicit
  ;; This records an existing limitation, not an acceptance test for its repair.
  (let [result (demo/dependency-shrink-counterexample)]
    (is (= [8] (:expected-current result)))
    (is (= 'the-nothing (:actual-current result)))
    (is (false? (:new-result-survived result)))))
