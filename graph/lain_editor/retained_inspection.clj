(ns graph.lain-editor.retained-inspection
  "Pane-owned child observations. The observed program Net remains immutable."
  (:require [propagators.compiler-2.runtime.operators.retained-relationship :as relationship]
            [propagators.compiler-2.runtime.retained-network :as retained]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.network-builder :as builder]))

(def storage-key ::observations)

(defn child-graph [session parent]
  (locking session
    (let [network (:program/net @session)
          storage (or (get @session storage-key)
                      (let [storage (atom {})]
                        (swap! session assoc storage-key storage)
                        storage))
          previous (get @storage parent)]
      (if (identical? network (:parent previous))
        (:value previous)
        (let [id #(gur/stable-node-id [::child parent %])
              root (id :root) callback-id (id :callback) output (id :output)
              reference (data/reference (first parent) (second parent) [])
              callable (if previous (:callable previous) (relationship/callback :children [root]))
              prepared (-> network
                           (builder/install-cell root reference reference)
                           (builder/install-cell callback-id callable callable))
              epoch (inc (get previous :epoch 0))
              result (retained/step prepared (:state previous) callback-id nil root [] output [epoch])]
          (swap! storage assoc parent
                 (assoc result :parent network :callable callable :epoch epoch))
          (:value result))))))
