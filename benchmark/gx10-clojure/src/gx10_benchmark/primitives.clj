(ns gx10-benchmark.primitives
  "Pure host primitives, imported with a digest through the session extension API."
  (:require [propagators.experimental.visualization.observation :as observation]))

(def gib (* 1024 1024 1024))

(defn memory-reserve [physical-bytes]
  (when-not (and (integer? physical-bytes) (pos? physical-bytes))
    (throw (ex-info "Physical RAM must be positive bytes" {:physical-bytes physical-bytes})))
  (max (* 16 gib) (long (Math/ceil (/ physical-bytes 5.0)))))

(defn primitive-bindings []
  [['memory-reserve
    (observation/value-operator
     ::memory-reserve 1 (fn [_ [physical]] (memory-reserve physical))) ]])
