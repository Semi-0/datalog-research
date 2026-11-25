(ns propagators.compiler-2.language.quotation
  "The data boundary between Lain source forms and connected car/cdr objects."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.network :as net]))

(def empty-list-value :compiler-2/list-empty)
(defrecord LexicalEnvironment [id])

(defn base [x]
  (if (datum/layer-present? x :base) (datum/layer-value x :base) x))

(defn- agree [left right]
  (cond
    (value/nothing? left) right
    (value/nothing? right) left
    (= left right) left
    :else value/contradiction))

(defn read-expression
  "Read compound data including live car/cdr endpoints. Return the cells
  whose later publications can complete or contradict this form."
  [network root]
  (let [readings (volatile! {})]
    (letfn [(read-cell [id path]
              (when (contains? path id)
                (throw (ex-info "eval cannot read a cyclic expression" {:cell id})))
              (if-let [entry (find @readings id)]
                (val entry)
                (let [form (decode (base (net/network-cell-strongest network id))
                                   (conj path id))]
                  (vswap! readings assoc id form)
                  form)))
            (member [pair k path]
              (let [parents (filter #(contains? (net/net-env network) %)
                                    (obj/accessor-parent-ids pair k))
                    source (if (obj/accessor-source-slot-present? pair k)
                             (decode (base (obj/accessor-source-slot-value pair k)) path)
                             value/nothing)]
                (reduce agree source (map #(read-cell % path) parents))))
            (decode [x path]
              (cond
                (value/unusable? x) x
                (= empty-list-value x) '()
                (net/net? x)
                (let [head (member x :car path)
                      tail (member x :cdr path)]
                  (cond
                    (or (value/contradiction? head) (value/contradiction? tail)) value/contradiction
                    (or (value/nothing? head) (value/nothing? tail)) value/nothing
                    (seq? tail) (cons head tail)
                    :else (throw (ex-info "eval expects a proper list" {:tail tail}))))
                (vector? x) (let [items (mapv #(decode % path) x)]
                              (or (some #(when (value/unusable? %) %) items) items))
                :else x))]
      (let [form (read-cell root #{})]
        {:form form :members (disj (set (keys @readings)) root)}))))
