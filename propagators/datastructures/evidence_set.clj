(ns propagators.datastructures.evidence-set
  "Evidence sets keep incomparable named networks as raw cell content.

  The set is maintained as an antichain: stronger incoming named networks replace
  weaker old evidence; updates already subsumed by old evidence are ignored; truly
  incomparable updates are kept alongside existing evidence."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.named-network :as named]))

(defn evidence-set? [x]
  (and (set? x)
       (seq x)
       (every? named/named-network? x)))

(defn evidence-networks [x]
  (cond
    (evidence-set? x) x
    (named/named-network? x) [x]
    (value/nothing? x) []
    :else []))

(defn evidence-set [& values]
  (set (mapcat evidence-networks values)))

(defn- subsumes? [a b]
  (= true (named/named-network->= a b)))

(defn- add-evidence [evidence update]
  (if (some #(subsumes? % update) evidence)
    evidence
    (conj (set (remove #(subsumes? update %) evidence)) update)))

(defn merge-evidence
  "Merge named-network evidence without forcing a strongest join."
  [content update]
  (reduce add-evidence
          (evidence-set content)
          (evidence-networks update)))

(defn strongest
  "Strongest named-network view for an evidence set."
  [evidence]
  (let [networks (vec (evidence-networks evidence))]
    (cond
      (empty? networks)
      value/nothing

      (= 1 (count networks))
      (first networks)

      :else
      (reduce
       (fn [acc n]
         (if (value/contradiction? acc)
           value/contradiction
           (named/join acc n)))
       (first networks)
       (rest networks)))))
