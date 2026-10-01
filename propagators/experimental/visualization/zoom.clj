(ns propagators.experimental.visualization.zoom
  "Semantic selection and source traversal as collection data, without XR."
  (:require [clojure.set :as set]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.datastructures.dependency :as dependency]
            [propagators.experimental.visualization.collections :as collections]
            [propagators.experimental.visualization.data :as data]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-patch :as patch]))

(def focus
  (operator/propagator-operator
   {:name ::focus
    :activate
    (fn [network [source control :as inputs] [output] _]
      (when-not (= 2 (count inputs))
        (throw (ex-info "focus expects collection and selection cell" {:inputs inputs})))
      (let [collection (data/payload (net/network-cell-strongest network source))]
        (if (data/collection? collection)
          [(message output
                    (data/fragment
                     (into {:collection/id output
                            :collection/type (data/field collection :collection/type)
                            :collection/sources #{(data/reference source)}
                            :collection/graph-source (data/field collection :collection/graph-source)}
                           (map (fn [entry]
                                  [[:element (:identity entry)]
                                   (update entry :selections (fnil conj [])
                                           {:control control :candidate (:identity entry)
                                            :sources (:sources entry)})])
                                (data/entries collection)))))]
          [])))}))

(defn- source-patches [network output entry]
  (let [current (net/network-cell-strongest network (:value entry))
        refs (set/union (:sources entry)
                        (dependency/sources (data/evidence-value current)))]
    (into []
          (mapcat
           (fn [ref]
             (let [identity [(:identity entry) ref]
                   cell (collections/stable-id output identity :source)]
               [(patch/declare-cell cell)
                (message cell (data/supported [::source cell] ref #{ref} []))
                (message output (data/fragment
                                 {[:element identity]
                                  (assoc entry :identity identity :value cell :sources #{ref})}))]))
           (sort-by pr-str refs)))))

(def sources-of
  (operator/propagator-operator
   {:name ::sources-of
    :activate
    (fn [network inputs [output] _]
      (when-not (= 1 (count inputs))
        (throw (ex-info "sources-of expects one collection" {:inputs inputs})))
      (let [source (first inputs)
            collection (data/payload (net/network-cell-strongest network source))]
        (if (data/collection? collection)
          (into [(message output (data/fragment
                                  {:collection/id output :collection/type :list
                                   :collection/sources #{(data/reference source)}
                                   :collection/graph-source (data/field collection :collection/graph-source)}))]
                (map (fn [entry]
                       (patch/declare-propagator
                        (collections/stable-id output (:identity entry) :sources) ::sources
                        (data/entry-inputs entry) [output]
                        (fn [_ _ current] (source-patches current output entry))))
                     (data/entries collection)))
          [])))}))
