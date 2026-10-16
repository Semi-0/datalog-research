(ns propagators.semantic-trace.bindings
  "Separate binding syntax from transformations without merging named cells."
  (:require [propagators.dataflow-projection :as normalization]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace.logic :as logic]))

(defn- source-cell? [network [path id]]
  (contains? (get (net/network-dict-entry (observer/network-at-path network path)
                                        patch/name-bindings-key)
                  :compiler-2/source-cells {}) id))

(defn explain [network copies edges]
  (let [definitions (filter #(= :definition (:kind %)) copies)
        outgoing (group-by first (set edges))
        evidence {:definition (set (map (juxt :source :id :target) definitions))
                  :source-cell (set (for [{:keys [source]} definitions
                                         :when (source-cell? network source)] [source]))
                  :sole-consumer (set (for [[source routes] outgoing :when (= 1 (count routes))]
                                        [source (second (first routes))]))}
        derived (logic/run evidence logic/binding-rules)
        raw (into {} (:binding-alias derived))
        aliases (update-vals raw (partial normalization/canonical raw))
        hidden (set (for [{:keys [id source]} definitions :when (contains? aliases source)] id))]
    {:edb evidence :derived derived :aliases aliases
     :node-aliases (update-vals aliases hash-set)
     :edges (normalization/rename-edges aliases (remove #(some hidden %) edges))}))
