(ns propagators.compiler-2.legacy
  "Legacy compiler-2 operators kept for compatibility."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.legacy :as tms]
            [propagators.gur :as gur]
            [propagators.gur.accumulating.core :as gur-core]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- retained-closure
  [callable]
  (let [callable* (-> callable scope-source/unwrap dependency/unwrap)]
    (cond
      (gur-core/recursive-closure? callable*)
      callable*

      :else
      nil)))

(defn- retained-closure-info
  [closure]
  (cond
    (gur-core/recursive-closure? closure)
    (let [declaration (get closure gur-core/declaration-key)]
      (cond
        (closure-value/closure-info? declaration)
        declaration

      :else
        nil))

    :else
    nil))

(defn- retained-closure-boundary
  [closure-id closure storage-id]
  (cond
    (gur-core/recursive-closure? closure)
    (vec (distinct (concat [closure-id storage-id]
                           (gur-core/captured-cell-ids closure))))

    :else
    [closure-id storage-id]))

(defn- closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- single-output-id
  [closure-info arg-ids out-id]
  (let [outputs (closure-output-symbols
                 (closure-value/closure-output closure-info))]
    (if (= 1 (count outputs))
      (peek (vec arg-ids))
      out-id)))

(defn- legacy-premise-storage-messages
  [premise storage-id premise-out-id network]
  (let [output-value (h/strongest-or-nothing network premise-out-id)]
    (cond-> []
      (and (not (value/unusable? output-value))
           (not (value/unusable? premise)))
      (conj (message storage-id
                     (tms/premise-update tms/reducer-id premise 0 true))
            (message storage-id
                     (tms/claim-update
                      (tms/claim [:premise-closure premise premise-out-id]
                                 :answer
                                 output-value
                                 [(tms/support premise
                                               [:compiler-2/premise-closure
                                                premise-out-id]
                                               :premise-closure)])))))))

(defn- install-legacy-premise-call
  [closure-id closure-info premise storage-id network arg-ids out-id]
  (let [premise-out-id (single-output-id closure-info arg-ids out-id)
        [apply-props applied]
        ((gur/p:apply-closure closure-id arg-ids out-id) network)
        [storage-prop declared]
        ((prop/construct-propagator
          (h/stable-node-id :compiler-2/legacy-premise-storage
                            closure-id premise-out-id storage-id)
          (fn [_inputs _outputs current]
            (legacy-premise-storage-messages premise
                                             storage-id
                                             premise-out-id
                                             current))
          [premise-out-id]
          [storage-id])
         applied)]
    [declared (conj (vec apply-props) storage-prop) premise-out-id]))

(defn- legacy-premise-closure-value
  [closure-id closure closure-value premise storage-id]
  (operator-value/operator-closure
   {:name "legacy-premise-closure-value"
    :boundary-cell-ids (fn [_network _arg-ids]
                         (retained-closure-boundary closure-id
                                                    closure
                                                    storage-id))
    :output-selector (fn [arg-ids out-id]
                       [(single-output-id closure-value arg-ids out-id)])
    :install (fn [network arg-ids out-id]
               (install-legacy-premise-call closure-id
                                            closure-value
                                            premise
                                            storage-id
                                            network
                                            arg-ids
                                            out-id))}))

(defn legacy-premise-closure-operator []
  (operator-value/operator-closure
   {:name "legacy-premise-closure"
    :activate (fn [network _context-id arg-ids out-id]
                (let [[closure-id premise-id storage-id] (vec arg-ids)]
                  (cond
                    (not (and closure-id premise-id storage-id
                              (= 3 (count arg-ids))))
                    (throw (ex-info
                            "premise-closure expects closure, premise, and tms storage"
                            {:arg-ids arg-ids}))

                    :else
                    (let [callable (h/strongest-or-nothing network closure-id)
                          closure (retained-closure callable)
                          closure-info (retained-closure-info closure)
                          premise (h/strongest-or-nothing network premise-id)]
                      (cond
                        (or (nil? closure-info)
                            (value/unusable? premise))
                        []

                        :else
                        [(message out-id
                                  (legacy-premise-closure-value closure-id
                                                                closure
                                                                closure-info
                                                                premise
                                                                storage-id))])))))}))

(defn bind-legacy-central-tms-operators
  [compiler-env]
  (env/bind-at compiler-env 'premise-closure (legacy-premise-closure-operator) 0))

(defn legacy-central-tms-env []
  (bind-legacy-central-tms-operators (h/default-env)))
