(ns propagators.semantic-trace.tracer-modifiers
  "Commutative declarations, not an ordered pipeline of destructive transforms."
  (:require [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]))

(defn declaration [kind]
  (when-not (contains? #{:high-level :with-values :caller} kind)
    (throw (ex-info "Unknown tracer modifier" {:kind kind})))
  {:kind kind})

(defn selection [selectors]
  {:kind :select :selection (set selectors)})

(defn declaration? [candidate]
  (and (map? candidate)
       (or (and (contains? #{:high-level :with-values :caller} (:kind candidate))
                (= #{:kind} (set (keys candidate))))
           (and (= :select (:kind candidate)) (set? (:selection candidate))
                (= #{:kind :selection} (set (keys candidate)))))))

(defn normalize [modifiers]
  (doseq [modifier modifiers]
    (when-not (declaration? modifier)
      (throw (ex-info "Expected a tracer modifier declaration" {:modifier modifier}))))
  (let [selected (filter #(= :select (:kind %)) modifiers)
        others (remove #(= :select (:kind %)) modifiers)]
    (if (seq selected)
      (into (conj (set others) (selection #{}))
            (map #(selection #{%})) (mapcat :selection selected))
      (set others))))

(defn apply-modifiers [graph modifiers]
  (let [modifiers (normalize modifiers)
        modify (fn [scope]
                 (reduce facts/declare-view scope modifiers))]
    (if (capture/fragment? graph)
      (capture/update-scope graph :root modify)
      (modify (facts/from-graph ::legacy :root graph #{})))))
