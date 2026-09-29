(ns propagators.datastructures.layered-value
  "Readiness of layered datums, independent of event classification.
  Registration is owned here; cells.value has no dependency on compound data."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.datastructures.support :as support]
            [propagators.network :as net]))

(defn layer-present? [x layer]
  (cond
    (net/net? x) (contains? (net/net-dict x) layer)
    (map? x) (contains? x layer)
    :else false))

(defn layer-value [x layer]
  (cond
    (net/net? x) (obj/slot-value x layer)
    (map? x) (get x layer)
    :else nil))

(defn support-of
  "An absent layer means a constant, not a missing source update."
  [x]
  (if (layer-present? x :support) (layer-value x :support) #{}))

(defn- support-layers [x]
  (loop [datum x layers []]
    (let [layers (conj layers (support-of datum))]
      (if (layer-present? datum :base)
        (recur (layer-value datum :base) layers)
        layers))))

(defmethod value/unusable? clojure.lang.IPersistentMap [x]
  (boolean
   (or (and (layer-present? x :base)
            (value/unusable? (layer-value x :base)))
       (support/unusable? (support-of x))
       (not (apply support/compatible? (support-layers x))))))

(defmethod value/any-unusable-values? :compound [& values]
  (or (boolean (some value/unusable? values))
      (not (apply support/compatible? (mapcat support-layers values)))))
