(ns propagators.compiler-2.runtime.operators.network-sampling
  "Reactive sampling with edge-free observers and disposable Lain callback topology."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.one-time-network :as once]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.retention :as retention]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as roots]))

(defn- sampling-function [root callback arguments context output]
  (fn [network]
    (let [callable (data/payload (net/network-cell-strongest network callback))]
      (cond
        (value/nothing? callable) {:value value/nothing}
        (value/contradiction? callable) {:value callable}
        (not (gur/recursive-closure? callable))
        (throw (ex-info "observe-network callback must be a network" {:callback callable}))
        :else
        (let [observation-id (random-uuid)
              snapshot (observation/frozen-snapshot
                        network {:output output :observation/id observation-id})
              root-value (gur/stable-node-id [::root output])
              _ (when (contains? (net/net-env network) root-value)
                  (throw (ex-info "Observation argument identity collision" {:id root-value})))
              private-net (builder/install-cell network root-value root root)
              projected (data/payload (:value (once/run-flat-gur-once private-net context callback
                                             (into [root-value] arguments)
                                             (once/compound-projection) output snapshot)))
              captured (capture/freeze-result snapshot projected)]
          {:value captured
           :source (when (and (capture/fragment? captured)
                              (= observation-id (capture/observation-id captured)))
                     snapshot)})))))

(defn- sample-activation [sample output]
  (fn [_inputs _outputs network]
    (let [{:keys [value source]} (sample network)
          previous (data/payload (net/network-cell-strongest network output))]
      (cond
        (value/nothing? value) []
        (capture/same-source? previous value) []
        :else
        (retention/publication
         source value output
         (publication/next-source-datum
          output (net/network-cell-strongest network output) value :active))))))

(defn- observe-install [network [root callback & arguments] output context]
  (let [sample-id (gur/stable-node-id [::observer output])
        sample (sampling-function root callback arguments context output)
        [_ registered] (declarations/eval-activation-result
                         [(patch/declare-observer sample-id 'observe-network
                                                  (fn [current] (:value (sample current))))] network)
        before (set (keys (net/net-env registered)))
        [initial watched] ((roots/p:observe-roots [root] output) registered)
        introduced (set/difference (set (keys (net/net-env watched))) before)
        watches (conj (set (filter #(prop/prop? (net/network-env-lookup watched %)) introduced)) initial)
        activate (sample-activation sample output)
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
