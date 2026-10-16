(ns propagators.semantic-trace.directions
  "Reciprocal connections are annotations over lossless directed tracer edges."
  (:require [propagators.semantic-trace.logic :as logic]))

(defn annotate [graph]
  (let [edges (set (:edges graph))
        evidence {:edge edges :reverse-edge (set (map (comp vec reverse) edges))}
        derived (logic/run evidence logic/reciprocal-edge-rules)]
    (assoc graph :bidirectional-edges (set (:reciprocal derived)))))

(defn connections [graph]
  (let [reciprocal (set (:bidirectional-edges graph))]
    (vec (keep (fn [[source target :as edge]]
                 (if (and (not= source target) (reciprocal edge))
                   (if (neg? (compare (pr-str source) (pr-str target)))
                     {:from source :to target :bidirectional true}
                     nil)
                   {:from source :to target}))
               (:edges graph)))))
