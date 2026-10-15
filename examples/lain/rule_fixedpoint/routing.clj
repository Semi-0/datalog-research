(ns examples.lain.rule-fixedpoint.routing
  "Immutable source-to-proof reverse index and current premise-state fanout."
  (:require [clojure.set :as sets]
            [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.rule-fixedpoint.channels :as channels]
            [examples.lain.rule-fixedpoint.evidence :as evidence]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn discover [network targets previous]
  (reduce
   (fn [index target]
     (reduce-kv
      (fn [index proof content]
        (let [key [target proof]]
          (if (contains? (:known index) key)
            index
            (reduce (fn [current source] (update-in current [:routes source] (fnil conj #{}) key))
                    (-> index (update :known (fnil conj #{}) key) (update :new (fnil conj #{}) key))
                    (evidence/retained-sources content)))))
      index (:experiment/proof-evidence (net/network-cell-content network target))))
   (assoc previous :new #{}) targets))

(defn updates [network keys]
  (into []
        (keep (fn [[target proofs]]
                (let [all (evidence/state-updates network target (map second proofs))]
                  (if (seq all) (message/message target (relation/relation all)) nil))))
        (group-by first keys)))

(defn installer [sources targets index-cell]
  (prop/construct-propagator
   ::premise-routing
   (fn [_ _ network]
     (let [old (channels/base network index-cell) old (if (map? old) old {})
           index (discover network targets old)
           current (evidence/source-states network sources)
           values (into {} (map #(vector % (channels/base network %))) sources)
           changed-values (into #{} (keep (fn [[source v]]
                                            (if (= v (get-in old [:values source] ::missing)) nil source))) values)
           changed (into changed-values (map :source) (sets/difference current (or (:states old) #{})))
           affected (apply sets/union (:new index) (map #(get-in index [:routes %] #{}) changed))
           next-index (-> index (dissoc :new) (assoc :states current :values values))]
       (into (updates network affected) (channels/snapshot network index-cell next-index))))
   (into (vec sources) targets) (conj targets index-cell)))
