(ns propagators.generic-procedure.methods
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.generic-procedure.constants :as constants]
            [propagators.network :as net]))

(defn make-method-spec
  [{:keys [method-key branch-value]}]
  {:method-key method-key
   :predicates (obj/slot-value branch-value :method/predicates)
   :matcher (obj/slot-value branch-value :method/matcher)
   :handler (obj/slot-value branch-value :method/handler)})

(defn compound-slot-present?
  [compound-value slot-key]
  (let [compound-net (obj/compound-object compound-value)]
    (and (not (value/contradiction? compound-net))
         (some? (net/network-dict-entry compound-net slot-key)))))

(defn complete-method-spec?
  [{:keys [predicates matcher handler]}]
  (and (vector? predicates)
       (not (apply value/any-unusable-values? predicates))
       (some? matcher)
       (not (value/unusable? matcher))
       (some? handler)
       (not (value/unusable? handler))))

(defn generic-methods
  [generic-value]
  (->> (net/net-dict-or-empty generic-value)
       (keep (fn [[slot-key slot-id]]
               (when (constants/method-slot? slot-key)
                 {:method-key (second slot-key)
                  :branch-value (net/network-cell-strongest generic-value slot-id)})))
       (sort-by (comp pr-str :method-key))
       (mapv make-method-spec)
       (filter complete-method-spec?)
       vec))

(defn normalize-generic-value
  [v]
  (obj/compound-object v))

(defn spec-method-key [method-spec]
  (:method-key method-spec))

(defn spec-predicates [method-spec]
  (:predicates method-spec))

(defn spec-matcher [method-spec]
  (:matcher method-spec))

(defn spec-handler [method-spec]
  (:handler method-spec))
