(ns examples.lain.relational-datalog.semi-naive
  "Insertion-only differential operators. Progress is explicit immutable Net data."
  (:require [clojure.set :as sets]
            [examples.lain.relational-datalog.operators :as operators]
            [examples.lain.relational-datalog.runtime :as relation]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.gur.flat :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(defn new-facts [old current]
  (if (sets/subset? old current)
    (sets/difference current old)
    (throw (ex-info "Semi-naive relations must grow monotonically"
                    {:previous old :current current}))))

(defn delta-pairs [old-left old-right left right]
  (let [delta-left (new-facts old-left left)
        delta-right (new-facts old-right right)]
    (sets/union
     (set (for [a delta-left b right] [a b]))
     (set (for [a old-left b delta-right] [a b])))))

(defn seen-facts [network id]
  (let [datum (net/network-cell-strongest network id)]
    (if (value/nothing? datum)
      #{}
      (relation/facts datum))))

(defn progress-outputs [kind arguments output]
  (let [key [::progress kind (vec arguments) output]]
    (case kind
      :product [output (gur/stable-node-id [key :left]) (gur/stable-node-id [key :right])]
      :map [output (gur/stable-node-id [key :seen])]
      :filter [output (gur/stable-node-id [key :seen])]
      (throw (ex-info "Unknown differential operator" {:kind kind})))))

(defn product-messages [strategy network {:keys [inputs outputs]}]
  (if (= 2 (count inputs))
    (let [[left right] (mapv #(net/network-cell-strongest network %) inputs)
          [output seen-left seen-right] outputs]
      (if (or (value/unusable? left) (value/unusable? right))
        []
        (case strategy
          :naive [(message output (operators/product left right))]
          :semi-naive
          (let [old-left (seen-facts network seen-left)
                old-right (seen-facts network seen-right)
                left (relation/facts left) right (relation/facts right)]
            [(message output (relation/relation (delta-pairs old-left old-right left right)))
             (message seen-left (relation/relation (new-facts old-left left)))
             (message seen-right (relation/relation (new-facts old-right right)))])
          (throw (ex-info "Unknown product strategy" {:strategy strategy})))))
    (throw (ex-info "product requires two relations" {:inputs inputs}))))

(defn product-operator [strategy]
  (case strategy
    :naive (operator/propagator-operator
            {:name ::naive-product
             :messages (fn [network call] (product-messages :naive network call))})
    :semi-naive (operator/propagator-operator
                 {:name ::product
                  :output-selector (fn [arguments output] (progress-outputs :product arguments output))
                  :messages (fn [network call] (product-messages :semi-naive network call))})
    (throw (ex-info "Unknown product strategy" {:strategy strategy}))))

(defn collection-messages [mode network {:keys [outputs] :as call}]
  (let [descriptor (operators/collection-call mode network call)]
    (if (nil? descriptor)
      []
      (let [[output seen] outputs
            delta (new-facts (seen-facts network seen) (:facts descriptor))]
        (into [(message output relation/empty-relation)
               (message seen (relation/relation delta))]
              (mapcat #(operators/entry-patches descriptor %) (sort-by pr-str delta)))))))

(defn collection-operator [mode]
  (if (contains? #{:map :filter} mode)
    (operator/propagator-operator
     {:name [::collection mode]
      :output-selector (fn [arguments output] (progress-outputs mode arguments output))
      :messages (fn [network call] (collection-messages mode network call))})
    (throw (ex-info "Unknown collection mode" {:mode mode}))))

(defn bindings [strategy]
  (case strategy
    :naive (into (operators/bindings) {'product (product-operator :naive)})
    :semi-naive (into (operators/bindings)
                      {'product (product-operator :semi-naive)
                       'map (collection-operator :map)
                       'filter (collection-operator :filter)})
    (throw (ex-info "Unknown relation strategy" {:strategy strategy}))))
