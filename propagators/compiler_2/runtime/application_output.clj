(ns propagators.compiler-2.runtime.application-output
  "Export transient activation values across the closure boundary.

  Closure environments remap avatar addresses; accessor networks materialize
  their slots; explicit scope values retain provenance around exported bases."
  (:require [propagators.cells.merge :as cell-merge]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(defn- cell-content-or-nothing
  [network id]
  (if (contains? (net/net-env network) id)
    (net/network-cell-content network id)
    value/nothing))

(defn- inner->outer-boundary-map
  [network]
  (into {}
        (map (fn [[outer inner]] [inner outer]))
        (merge (get (net/net-dict-or-empty network) :avatars-in {})
               (get (net/net-dict-or-empty network) :avatars-out {}))))

(defn- externalize-closure-value
  [v network]
  (let [closure-env (closure-value/closure-env v)]
    (if (value/unusable? closure-env)
      v
      (obj/compound-object
       (assoc (closure-value/slot-map v)
              closure-value/closure-env-slot
              (env/externalize-env closure-env
                                   (inner->outer-boundary-map network)))))))

(defn- activation-cell-value
  [network id]
  (let [content (cell-content-or-nothing network id)]
    (if (value/unusable? content)
      (h/strongest-or-nothing network id)
      content)))

(defn- single-slot-value
  [values]
  (reduce (fn [acc v]
            (cond
              (value/unusable? acc) v
              (= acc v) acc
              :else (reduced value/contradiction)))
          value/nothing
          values))

(defn- accessor-slot-value
  [v network slot-key]
  (let [parent-values
        (->> (obj/accessor-parent-ids v slot-key)
             (filter #(contains? (net/net-env network) %))
             (map #(activation-cell-value network %))
             (remove value/unusable?))]
    (cond
      (seq parent-values)
      (single-slot-value parent-values)

      (obj/accessor-source-slot-present? v slot-key)
      (obj/accessor-source-slot-value v slot-key)

      :else
      value/nothing)))

(defn- externalize-accessor-value
  ([v network]
   (externalize-accessor-value v network #{}))
  ([v network seen]
   (cond
     (not (obj/accessor-network? v))
     v

     (contains? seen (System/identityHashCode v))
     value/nothing

     :else
     (let [seen' (conj seen (System/identityHashCode v))
           source-slots
           (into {}
                 (keep (fn [slot-key]
                         (let [slot-value
                               (externalize-accessor-value
                                (accessor-slot-value v network slot-key)
                                network
                                seen')]
                           (if (value/unusable? slot-value)
                             nil
                             [slot-key slot-value]))))
                 (obj/accessor-slot-keys v))]
       (if (seq source-slots)
         (obj/as-accessor-network source-slots)
         value/nothing)))))

(defn- externalize-output-value
  [v network]
  (cond
    (scope-source/scope-value? v)
    (scope-source/map-base v #(externalize-output-value % network))

    (closure-value/closure-info? v)
    (externalize-closure-value v network)

    (obj/accessor-network? v)
    (externalize-accessor-value v network)

    :else
    v))

(defn externalized-cell-value
  [network id]
  (let [content (cell-content-or-nothing network id)
        strongest (h/strongest-or-nothing network id)
        content-value (if (value/unusable? content)
                        nil
                        (externalize-output-value content network))]
    (if (and content-value
             (not (value/unusable? content-value)))
      content-value
      (externalize-output-value strongest network))))

(defn- external-output-candidate-ids
  [network ext]
  (vec (distinct
        (keep identity
              [(net/lookup-inner-out network ext)
               (if (contains? (net/net-env network) ext)
                 ext
                 nil)]))))

(defn- externalized-output-message
  [network-from network-to ext]
  (let [outer-content (cell-content-or-nothing network-to ext)
        outer-value (if (value/unusable? outer-content)
                      (h/strongest-or-nothing network-to ext)
                      outer-content)]
    (some (fn [candidate-id]
            (let [output-value (externalized-cell-value network-from candidate-id)]
              (if (and (not (value/unusable? output-value))
                         (cell-merge/cell-updated? output-value
                                                   outer-value
                                                   network-to))
                (message ext output-value)
                nil)))
          (external-output-candidate-ids network-from ext))))

(defn externalized-output-messages
  [network-from network-to external-outputs]
  (keep identity
        (map #(externalized-output-message network-from network-to %)
             (vec external-outputs))))
