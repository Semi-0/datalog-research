(ns examples.lain.rule-fixedpoint.channels
  "Ordinary messages for immutable experiment state; no private executor/cache."
  (:require [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.publication :as publication]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.network :as net]))

(defn base [network id]
  (datum/layer-value (net/network-cell-strongest network id) :base))

(defn snapshot [network id value]
  (if (= value (base network id))
    []
    [(update (publication/snapshot-message network id value) :value collection/content)]))

(defn tell [world id content]
  (let [[tasks network] (core/eval-cell id (message/message id content) (:network world))]
    (assoc world :network network :tasks (tq/merge-queues (:tasks world) tasks))))
