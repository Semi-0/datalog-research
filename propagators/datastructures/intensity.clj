(ns propagators.datastructures.intensity
  "Intensity-tagged layered values.

  An intensity value is an ordinary layered/compound object with `:base` and
  `:intensity` layers. Merge content is either one intensity value or a vector
  of intensity values; strongest picks the highest intensity candidate."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.layered :as layered]))

(def base-layer :base)
(def intensity-layer :intensity)

(defn intensity-value
  [intensity payload]
  (obj/compound-object {base-layer payload
                        intensity-layer intensity}))

(defn intensity
  [v]
  (obj/slot-value v intensity-layer))

(defn base-value
  [v]
  (obj/slot-value v base-layer))

(defn intensity-value?
  [v]
  (let [i (intensity v)
        base (base-value v)]
    (and (number? i)
         (not (value/unusable? base)))))

(defn- intensity-candidates?
  [v]
  (and (sequential? v)
       (seq v)
       (every? intensity-value? v)))

(defn intensity-content?
  [v]
  (or (intensity-value? v)
      (intensity-candidates? v)))

(defn- candidates
  [content]
  (cond
    (value/nothing? content) []
    (intensity-value? content) [content]
    (intensity-candidates? content) (vec content)
    :else value/contradiction))

(defn- same-intensity-value?
  [a b]
  (and (= (intensity a) (intensity b))
       (= (base-value a) (base-value b))))

(defn merge-content
  [content update]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (empty? existing) update
      (some #(same-intensity-value? % update) existing) content
      :else (conj (vec existing) update))))

(defn strongest-value
  [content]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (empty? existing) value/nothing
      :else
      (let [max-intensity (apply max (map intensity existing))
            strongest (filter #(= max-intensity (intensity %)) existing)
            bases (set (map base-value strongest))]
        (if (= 1 (count bases))
          (first strongest)
          value/contradiction)))))

(defn p:with-intensity
  "Build a layered intensity value from an intensity cell and base-value cell."
  [intensity-id value-id out-id]
  (fn [n]
    (let [[base-prop n1] ((layered/p:base value-id out-id) n)
          [intensity-prop n2] ((layered/p:layer intensity-layer intensity-id out-id) n1)]
      [[base-prop intensity-prop] n2])))
