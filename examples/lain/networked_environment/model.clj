(ns examples.lain.networked-environment.model
  "Peer declarations are indexed in the existing immutable Net dictionary."
  (:require [propagators.cell-evaluator :as evaluator]
            [propagators.cells.value :as value]
            [propagators.cells.merge :as merge]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.runner :as runner]
            [propagators.stdlib.premise-state :as state]))

(def ports-scope ::ports)
(def connections-scope ::connections)
(def context-key ::context)
(def identity-key ::identity)

(defn indexed [network scope]
  (get (net/network-dict-entry network patch/name-bindings-key) scope {}))

(defn apply-inputs [network messages]
  (let [[tasks updated] (evaluator/evaluate-all messages network)]
    (runner/completed-network (runner/run-network tasks updated))))

(defn current [network id]
  (net/network-cell-strongest network id))

(defn payload [x]
  (cond
    (collection/content? x) (payload (merge/strongest-value x net/empty-net))
    (datum/layer-present? x :base) (datum/layer-value x :base)
    :else x))

(defn supported [x]
  (cond
    (collection/content? x) x
    (datum/layer-present? x :support) (collection/content x)
    :else (collection/content {:base x :support #{}})))

(defn qualify
  "Conjoin process support without concealing incompatible source versions."
  [x premise]
  (let [x (if (collection/content? x)
            (merge/strongest-value x net/empty-net) x)
        base (payload x)
        supports (support/combine (datum/support-of x) (datum/support-of premise))
        states (support/join (state/states-of x) (state/states-of premise))
        observation {:base base :support supports :premise-state states}]
    ;; Keep contradictory observations as evidence as well as carrying states.
    (collection/content observation)))

(defn next-source [source previous base status]
  (let [next (publication/next-source-datum source previous base status)]
    (assoc next :premise-state (:support next))))
