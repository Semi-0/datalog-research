(ns propagators.experimental.visualization.observation
  "Small observational operators; references confer no mutation authority."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.experimental.visualization.collections :as collections]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur.flat :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as observer]))

(defn application-propagator [a]
  (:id (gur/apply-closure-effect (:operator-id a)
                                 (into [(:context-id a)] (:argument-ids a))
                                 (:result-id a))))

(defn- declared-inputs [network reference]
  (let [path (:source/path reference)
        owner (observer/network-at-path network path)
        selected (:source/cell reference)
        matches (filter #(= selected (application-propagator %))
                        (application/application-topologies owner))]
    (when-not (= 1 (count matches))
      (throw (ex-info "Expected one declared network occurrence" {:reference reference})))
    (let [a (first matches)
          callable (data/payload (net/network-cell-strongest owner (:operator-id a)))
          declaration (application/callable-declaration callable)]
      (when-not (closure/closure-info? declaration)
        (throw (ex-info "Selected occurrence is not a network definition" {:reference reference})))
      (let [n (count (closure/closure-inputs declaration))
            ports (take n (:argument-ids a))]
        (set (map #(data/reference path % []) ports))))))

(defn- graph-outputs [network reference]
  (let [path (:source/path reference)
        owner (observer/network-at-path network path)
        selected (:source/cell reference)]
    (if (prop/prop? (get (net/net-env owner) selected))
      (set (map #(data/reference path % [])
                (:outputs (get (net/net-graph owner) selected))))
      (throw (ex-info "Output interface requires a propagator reference"
                      {:reference reference})))))

(defn declared-interface [network reference direction]
  (case direction
    :inputs (declared-inputs network reference)
    :outputs (graph-outputs network reference)
    (throw (ex-info "Unknown interface direction" {:direction direction}))))

(defn value-operator [name arity compute]
  (operator/propagator-operator
   {:name name
    :activate
    (fn [network inputs [out] _]
      (when-not (= arity (count inputs))
        (throw (ex-info "Invalid observation arity" {:operator name :inputs inputs})))
      (if (prop/concrete-inputs? network inputs)
        (let [contents (mapv #(net/network-cell-content network %) inputs)
              values (mapv #(data/payload (net/network-cell-strongest network %)) inputs)
              result (compute network values)]
          (if (value/unusable? result)
            []
            [(message out (data/supported [name out] result #{} contents))]))
        []))}))

(def inputs-of
  (value-operator ::inputs-of 1 #(declared-interface %1 (first %2) :inputs)))
(def outputs-of
  (value-operator ::outputs-of 1 #(declared-interface %1 (first %2) :outputs)))
(def member?
  (value-operator ::member? 2
                  (fn [_ [item references]]
                    (when-not (set? references)
                      (throw (ex-info "member? expects a reference set" {:references references})))
                    (contains? references item))))

(def occurrence-of
  (operator/propagator-operator
   {:name ::occurrence-of
    :activate
    (fn [network [definition & ports :as inputs] [out] _]
      (when (< (count inputs) 2)
        (throw (ex-info "occurrence-of expects a definition and explicit application ports" {})))
      (let [callable (data/payload (net/network-cell-strongest network definition))
            matches (if (value/unusable? callable)
                      []
                      (filter #(and (= (vec ports) (:argument-ids %))
                                    (= (application/callable-declaration callable)
                                       (application/callable-declaration
                                        (data/payload (net/network-cell-strongest network (:operator-id %))))))
                              (application/application-topologies network)))]
        (case (count matches)
          0 []
          1 [(message out (data/reference (application-propagator (first matches))))]
          (throw (ex-info "Ambiguous declared network occurrence" {:ports ports})))))}))

(def strongest-of
  (operator/propagator-operator
   {:name ::strongest-of
    :activate
    (fn [network inputs [out] _]
      (when-not (= 1 (count inputs))
        (throw (ex-info "strongest-of expects one source reference" {:inputs inputs})))
      (if (prop/concrete-inputs? network inputs)
        (let [reference (data/payload (net/network-cell-strongest network (first inputs)))]
          (when-not (data/reference? reference)
            (throw (ex-info "strongest-of expects a source reference" {:value reference})))
          [(patch/declare-propagator
            (collections/stable-id out reference (data/watch-ids network reference)) ::read
            (into (vec inputs) (data/watch-ids network reference)) [out]
            (fn [_ _ current]
              (let [content (data/read-source current reference)
                    result (data/payload (data/read-strongest current reference))]
                (if (value/unusable? result)
                  []
                  [(message out (data/supported [::read out] result #{reference}
                                               (into [content] (map #(net/network-cell-content current %) inputs))))]))))])
        []))}))
