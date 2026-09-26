(ns propagators.experimental.visualization.presentation
  "Nested declarative arrangement and optional selection controls. No I/O."
  (:require [propagators.compiler-2.model.operator-value :as operator]
            [propagators.cells.value :as value]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.selection :as selection]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.visualizer :as visualizer]))

(def juxtapose
  (operator/propagator-operator
   {:name ::juxtapose
    :activate (fn [_ inputs [out] _]
                [(message out (visualizer/juxtapose-declaration out inputs))])}))

(def selectable
  (operator/propagator-operator
   {:name ::selectable
    :activate (fn [network inputs [out] _]
                (when-not (= 2 (count inputs))
                  (throw (ex-info "selectable expects collection and control cell" {:inputs inputs})))
                (let [[source control] inputs
                      collection (data/payload (net/network-cell-strongest network source))
                      initial (message control (selection/initial control))]
                  (cond
                    (value/unusable? collection) [initial]
                    (data/collection? collection)
                    [initial
                     (message out
                              (data/fragment
                               (into {:collection/id out
                                      :collection/type (data/field collection :collection/type)
                                      :collection/sources #{(data/reference source)}
                                      :collection/selection-cell control
                                      :collection/graph-source (data/field collection :collection/graph-source)}
                                     (map (fn [entry] [[:element (:identity entry)] entry])
                                          (data/entries collection)))))]
                    :else (throw (ex-info "selectable expects a collection" {})))))}))
