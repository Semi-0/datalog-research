(ns propagators.compiler-2.runtime.operators.relationship-observer
  "Ordinary callable installers for raw and semantic relationship observation."
  (:require [clojure.set :as set]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.declaration-effects :as nb]
            [propagators.gur.flat :as gur]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]))

(defn- propagator-ids [network]
  (into #{} (keep (fn [[id entry]] (when (prop/prop? entry) id))) (net/net-env network)))

(defn- observer-ports [name arg-ids fallback-id]
  (when (empty? arg-ids)
    (throw (ex-info (str name " expects a seed cell and optional output cell")
                    {:argument-ids arg-ids})))
  (if (= 1 (count arg-ids))
    [(vec arg-ids) fallback-id]
    [(vec (butlast arg-ids)) (last arg-ids)]))

(defn- install-roots
  ([network observed-ids output-id] (install-roots network observed-ids output-id output-id))
  ([network observed-ids output-id owner-id]
  (let [prepared (reduce nb/ensure-cell network (conj observed-ids output-id))
        before (propagator-ids prepared)
        [initial installed] ((observer/p:observe-roots observed-ids output-id owner-id) prepared)
        [_ registered] (nb/eval-activation-result
                        [(gur/bind-name [:relationship/root-observers]
                                        [observed-ids output-id owner-id] output-id)] installed)
        introduced (set/difference (propagator-ids registered) before)]
    [registered (vec (distinct (cons initial (sort-by pr-str introduced)))) output-id])))

(defn- observer-operator [name install]
  (operator-value/operator-closure
   {:name name
    :input-selector (fn [arg-ids fallback-id _context-id]
                      (first (observer-ports name arg-ids fallback-id)))
    :output-selector (fn [arg-ids fallback-id]
                       [(second (observer-ports name arg-ids fallback-id))])
    :install (fn [network arg-ids fallback-id]
               (let [[observed-ids output-id] (observer-ports name arg-ids fallback-id)]
                 (install network observed-ids output-id)))}))

(defn roots-operator []
  (observer-operator 'relationship:roots install-roots))

(defn- install-semantic [network observed-ids output-id]
  (let [raw-id (gur/stable-node-id [:relationship/semantic output-id :raw])
        [observed observers _] (install-roots network observed-ids raw-id output-id)
        prepared (nb/ensure-cell observed output-id)
        [projector installed] ((dataflow/p:semantic-network raw-id output-id) prepared)]
    [installed (conj observers projector) output-id]))

(defn roots-semantic-operator []
  (observer-operator 'relationship:roots-semantic install-semantic))

(defn semantic-operator []
  (roots-semantic-operator))

(defn- dataflow-ports [arg-ids fallback-id]
  (when-not (contains? #{1 2} (count arg-ids))
    (throw (ex-info "relationship:dataflow expects a graph and optional output cell"
                    {:argument-ids arg-ids})))
  [(first arg-ids) (if (= 2 (count arg-ids)) (second arg-ids) fallback-id)])

(defn dataflow-operator []
  (operator-value/operator-closure
   {:name 'relationship:dataflow
    :input-selector (fn [arg-ids fallback-id _context-id]
                      [(first (dataflow-ports arg-ids fallback-id))])
    :output-selector (fn [arg-ids fallback-id]
                       [(second (dataflow-ports arg-ids fallback-id))])
    :install (fn [network arg-ids fallback-id]
               (let [[input-id output-id] (dataflow-ports arg-ids fallback-id)
                     prepared (reduce nb/ensure-cell network [input-id output-id])
                     [id installed] ((dataflow/p:dataflow input-id output-id) prepared)]
                 [installed [id] output-id]))}))
