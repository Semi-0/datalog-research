(ns propagators.stdlib.observation
  "Identity publication: project locally, never copy TTMS retained history."
  (:refer-clojure :exclude [identity])
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-publication :as publication]))

(defn- supported-update [content strongest]
  (if (or (collection/content? content)
          (datum/layer-present? strongest :support))
    (cond
      (value/nothing? strongest) (collection/content {:premise-state #{}})
      (datum/layer-present? strongest :premise-state) (publication/content strongest)
      :else (collection/content strongest))
    nil))

(defn sync-update
  "Keep ordinary sync policy; supported identities publish only current data."
  [network id]
  (let [content (net/network-cell-content network id)
        strongest (net/network-cell-strongest network id)
        supported (supported-update content strongest)]
    (cond
      (some? supported) supported
      (tms/distributed-value? content) (tms/distributed-forward-update content)
      (behavior/behavior-value? content) content
      (not (value/unusable? strongest)) strongest
      :else nil)))

(defn- current-activation [[from] [to] network]
  (let [content (net/network-cell-content network from)
        strongest (net/network-cell-strongest network from)
        supported (supported-update content strongest)]
    (cond
      (some? supported) [(message/message to supported)]
      (value/unusable? content) []
      :else [(message/message to content)])))

(def activation
  (prop/compose-activation current-activation publication/transport-states))

(defn identity [name source target]
  (prop/construct-propagator name activation [source] [target]))
