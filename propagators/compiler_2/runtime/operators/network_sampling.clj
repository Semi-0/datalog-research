(ns propagators.compiler-2.runtime.operators.network-sampling
  "Reactive sampling with edge-free observers and disposable Lain callback topology."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.one-time-network :as once]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.network-patch :as patch]
            [propagators.observer :as observers]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as roots]))

(defn- sampling-function [root callback arguments context output]
  (fn [network]
    (let [callable (data/payload (net/network-cell-strongest network callback))]
     (cond
      (value/nothing? callable) value/nothing
      (value/contradiction? callable) callable
      (not (gur/recursive-closure? callable))
      (throw (ex-info "observe-network callback must be a network" {:callback callable}))
      :else
      (let [snapshot (observation/frozen-snapshot network {:output output})
            root-value (gur/stable-node-id [::root output])
            _ (when (contains? (net/net-env network) root-value)
                (throw (ex-info "Observation argument identity collision" {:id root-value})))
            private-net (builder/install-cell network root-value root root)]
        (data/payload (:value (once/run-flat-gur-once private-net context callback
                                       (into [root-value] arguments)
                                       (once/compound-projection) output snapshot))))))))

(defn- sample-activation [observer-id output]
  (fn [_inputs _outputs network]
    (let [sample (observers/sample-observer network observer-id)]
      (if (value/nothing? sample)
        []
        [(message output
                  (collection/content
                   (publication/next-source-datum
                    output (net/network-cell-strongest network output) sample :active)))]))))

(defn- observe-install [network [root callback & arguments] output context]
  (let [sample-id (gur/stable-node-id [::observer output])
        sample (sampling-function root callback arguments context output)
        [_ registered] (declarations/eval-activation-result
                         [(patch/declare-observer sample-id 'observe-network sample)] network)
        before (set (keys (net/net-env registered)))
        [initial watched] ((roots/p:observe-roots [root] output) registered)
        introduced (set/difference (set (keys (net/net-env watched))) before)
        watches (conj (set (filter #(prop/prop? (net/network-env-lookup watched %)) introduced)) initial)
        activate (sample-activation sample-id output)
        decorated (reduce (fn [current id]
                            (net/assoc-net-prop current id
                              (assoc (net/network-env-lookup current id) :activate activate)))
                          watched watches)
        [_ installed] (declarations/eval-activation-result
                       [(gur/bind-name [:relationship/root-observers]
                                       [[root] output output] output)] decorated)
        control-id (gur/stable-node-id [::control output])
        [control installed] ((prop/construct-propagator
                              control-id ::control activate (into [callback] arguments) [output]) installed)]
    [installed (conj (vec watches) control) output]))

(defn observe-operator []
  (operator/operator-closure
   {:name 'observe-network
    :input-selector (fn [arguments _fallback _context]
                      (when (< (count arguments) 2)
                        (throw (ex-info "observe-network expects a root cell, callback, and optional arguments" {})))
                      (vec arguments))
    :application-installer
    (fn [network arguments output context]
      (when (< (count arguments) 2)
        (throw (ex-info "observe-network expects a root cell, callback, and optional arguments" {})))
      (observe-install (reduce builder/ensure-cell network (conj (vec arguments) output))
                       arguments output context))}))
