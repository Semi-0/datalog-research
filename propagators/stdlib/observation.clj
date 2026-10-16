(ns propagators.stdlib.observation
  "Identity publication: project locally, never copy TTMS retained history."
  (:refer-clojure :exclude [identity])
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.premise-publication :as publication]))

(defn- current-conflict-content
  "Forward the current causes of a conflict, not a synthetic joint observation.
  The latter can subsume an independent cause required after later withdrawal."
  [content strongest]
  (let [states (state/states-of strongest)
        current? (fn [observation]
                   (let [candidate (collection/content
                                    (assoc observation :premise-state states))
                         projected (collection/strongest-value candidate
                                     (fn [_base update] update))]
                     (= (:support observation) (datum/support-of projected))))]
    {:support/observations (set (filter current? (:support/observations content)))
     :support/states states}))

(defn- supported-update [content strongest]
  (cond
    (membership/content? content) (membership/current-content content)
    (datum/layer-present? strongest :membership) (membership/content strongest)
    (or (collection/content? content)
          (datum/layer-present? strongest :support))
    (cond
      (value/nothing? strongest) (collection/content {:premise-state #{}})
      (value/contradiction? (datum/layer-value strongest :base))
      (if (and (collection/content? content)
               (datum/layer-present? strongest :premise-state))
        (current-conflict-content content strongest)
        (collection/content strongest))
      (datum/layer-present? strongest :premise-state) (publication/content strongest)
      :else (collection/content strongest))
    :else nil))

(defn sync-update
  "Publish current data, including contradictions and their original support."
  [network id]
  (let [content (net/network-cell-content network id)
        strongest (net/network-cell-strongest network id)
        supported (supported-update content strongest)]
    (cond
      (some? supported) supported
      (tms/distributed-value? content) (tms/distributed-forward-update content)
      (behavior/behavior-value? content) content
      (not (value/nothing? strongest)) strongest
      :else nil)))

(defn- current-activation [[from] [to] network]
  (let [content (net/network-cell-content network from)
        strongest (net/network-cell-strongest network from)
        supported (supported-update content strongest)]
    (cond
      (some? supported) [(message/message to supported)]
      (value/nothing? content) []
      :else [(message/message to content)])))

(def activation
  (prop/compose-activation current-activation publication/transport-states))

(defn identity [name source target]
  (prop/construct-propagator name activation [source] [target]))
