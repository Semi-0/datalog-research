(ns propagators.datastructures.value-projection
  "Read supported payloads without depending on source references or observers."
  (:require [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope]
            [propagators.datastructures.tms.core :as tms]
            [propagators.experimental.visualization.ttms :as ttms]))

(defn evidence-value [v]
  (let [v (ttms/payload (scope/unwrap v))]
    (tms/distributed-base-value
     (if (tms/distributed-content? v)
       (tms/strongest-distributed-value v)
       v))))

(defn payload [v] (dependency/unwrap (evidence-value v)))
