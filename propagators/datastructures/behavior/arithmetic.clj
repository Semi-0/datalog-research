(ns propagators.datastructures.behavior.arithmetic
  "Behavior-history arithmetic parallel to primitive arithmetic.

  These propagators read retained behavior history from input cell content and
  write a new behavior value to the output cell. They do not change core
  primitive arithmetic and do not own retention policy."
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.behavior-algebra :as hist]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def arithmetic-reducer-prefix :behavior.reducer/arithmetic)

(defn arithmetic-reducer-id
  [op]
  [arithmetic-reducer-prefix op])

(defn- input-view
  [network id]
  (behavior/strongest-history-view (net/network-cell-content network id)))

(defn- distributed-behavior-content
  [content]
  (if (tms/distributed-value? content)
    (tms/distributed-base-value (tms/strongest-distributed-value content))
    content))

(defn- valid-input-view?
  [v]
  (or (value/unusable? v)
      (behavior/behavior-value? v)))

(defn- tagged-source-keys
  [input-index view]
  (set (map (fn [source-key] [input-index source-key])
            (behavior/source-keys view))))

(defn- source-keys
  [views]
  (into #{}
        (mapcat (fn [[idx view]]
                  (tagged-source-keys idx view)))
        (map-indexed vector views)))

(defn- arithmetic-behavior-value
  [op history source-keys*]
  (behavior/behavior-value
   {:history history
    :source-keys source-keys*
    :reducer (arithmetic-reducer-id op)}))

(defn- safe-apply
  [f]
  (fn [& values]
    (if (some value/unusable? values)
      value/nothing
      (apply f values))))

(defn behavior-messages
  "Return output messages for one behavior operator activation."
  [op f input-ids out-id network]
  (let [views (mapv #(input-view network %) input-ids)]
    (cond
      (some value/nothing? views) []
      (some value/contradiction? views) [(message out-id value/contradiction)]
      (not (every? valid-input-view? views)) [(message out-id value/contradiction)]
      :else
      (let [result-history
            (hist/history-join-all
             (safe-apply f)
             (mapv behavior/history views))]
        (if (value/contradiction? result-history)
          [(message out-id value/contradiction)]
          [(message out-id
                    (arithmetic-behavior-value
                     op
                     result-history
                     (source-keys views)))])))))

(defn distributed-behavior-messages
  "TMS-composed behavior arithmetic. Plain behavior-messages stays unchanged."
  [op f input-ids out-id network]
  (let [contents (mapv #(net/network-cell-content network %) input-ids)
        views (mapv #(behavior/strongest-history-view
                       (distributed-behavior-content %))
                    contents)]
    (cond
      (some value/nothing? views)
      (if-let [update (tms/distributed-state-update contents)]
        [(message out-id update)]
        [])

      (some value/contradiction? views) [(message out-id value/contradiction)]
      (not (every? valid-input-view? views)) [(message out-id value/contradiction)]
      :else
      (let [result-history
            (hist/history-join-all
             (safe-apply f)
             (mapv behavior/history views))]
        (if (value/contradiction? result-history)
          [(message out-id value/contradiction)]
          [(message out-id
                    (let [result (arithmetic-behavior-value
                                  op
                                  result-history
                                  (source-keys views))]
                      (or (tms/distributed-result-update
                           [:behavior op out-id]
                           result
                          contents)
                         result)))])))))

(defn- existing-claim-value
  [content claim-id]
  (let [claim (get (tms/distributed-slots content)
                   (tms/claim-slot-key claim-id))]
    (when (tms/claim? claim)
      (tms/claim-value claim))))

(defn- same-existing-behavior-claim?
  [content claim-id result]
  (when-let [existing (existing-claim-value content claim-id)]
    (behavior/same-view? existing result)))

(defn- stable-distributed-result-update
  [claim-id result input-contents output-content]
  (if (same-existing-behavior-claim? output-content claim-id result)
    (tms/distributed-state-update input-contents)
    (tms/distributed-result-update claim-id result input-contents)))

(defn distributed-behavior-stable-messages
  "TMS behavior arithmetic that avoids re-emitting semantically identical claims."
  [op f input-ids out-id network]
  (let [contents (mapv #(net/network-cell-content network %) input-ids)
        views (mapv #(behavior/strongest-history-view
                       (distributed-behavior-content %))
                    contents)]
    (cond
      (some value/nothing? views)
      (if-let [update (tms/distributed-state-update contents)]
        [(message out-id update)]
        [])

      (some value/contradiction? views) [(message out-id value/contradiction)]
      (not (every? valid-input-view? views)) [(message out-id value/contradiction)]
      :else
      (let [result-history
            (hist/history-join-all
             (safe-apply f)
             (mapv behavior/history views))]
        (if (value/contradiction? result-history)
          [(message out-id value/contradiction)]
          [(message out-id
                    (let [result (arithmetic-behavior-value
                                  op
                                  result-history
                                  (source-keys views))
                          claim-id [:behavior op out-id]]
                      (or (stable-distributed-result-update
                           claim-id
                           result
                           contents
                           (net/network-cell-content network out-id))
                          result)))])))))

(defn behavior-propagator
  "Build a behavior-history propagator installer.

  The returned installer is variadic like primitive propagators: all node ids
  except the last are behavior inputs, and the last node id is the output. On
  activation it joins every input's retained history over shared temporal
  overlap and applies `f` to the joined payload values."
  [op f]
  (fn [& node-ids]
    (let [input-ids (vec (butlast node-ids))
          out-id (last node-ids)]
      (when (empty? input-ids)
        (throw (ex-info "behavior propagator requires at least one input"
                        {:op op :node-ids node-ids})))
      (prop/construct-propagator
       (fn [_inputs _outputs network]
         (behavior-messages op f input-ids out-id network))
       input-ids
       [out-id]))))

(defn distributed-behavior-propagator
  [op f]
  (fn [& node-ids]
    (let [input-ids (vec (butlast node-ids))
          out-id (last node-ids)]
      (when (empty? input-ids)
        (throw (ex-info "distributed behavior propagator requires at least one input"
                        {:op op :node-ids node-ids})))
      (prop/construct-propagator
       (fn [_inputs _outputs network]
         (distributed-behavior-messages op f input-ids out-id network))
       input-ids
       [out-id]))))

(defn distributed-behavior-stable-propagator
  [op f]
  (fn [& node-ids]
    (let [input-ids (vec (butlast node-ids))
          out-id (last node-ids)]
      (when (empty? input-ids)
        (throw (ex-info "stable distributed behavior propagator requires at least one input"
                        {:op op :node-ids node-ids})))
      (prop/construct-propagator
       (fn [_inputs _outputs network]
         (distributed-behavior-stable-messages op f input-ids out-id network))
       input-ids
       [out-id]))))

(def +
  (behavior-propagator :+ core/+))

(def *
  (behavior-propagator :* core/*))

(def /
  (behavior-propagator :/ core//))

(def negate
  (behavior-propagator :negate core/-))

(defn -
  ([input-id out-id]
   (negate input-id out-id))
  ([left-id right-id out-id]
   ((behavior-propagator :- core/-) left-id right-id out-id))
  ([left-id right-id third-id & more-ids]
   (apply (behavior-propagator :- core/-)
          left-id
          right-id
          third-id
          more-ids)))
