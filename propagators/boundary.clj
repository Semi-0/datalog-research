(ns propagators.boundary
  (:require [propagators.cells.avatar :as avatar]
            [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.core :refer [run-tasks]]
            [propagators.ids :as id]
            [propagators.network :as net :refer [net-graph]]
            [propagators.stdlib :as stdlib]))

(defn spawn-avatar-cell
  "Create an avatar cell cloned from the outer cell value/content.

  Arities:
  - (spawn-avatar-cell network outer-id)
  - (spawn-avatar-cell network outer-id inner-id)
  - (spawn-avatar-cell network key strongest content)
  - (spawn-avatar-cell network _outer-id inner-id strongest content)

  Returns `[inner-id net-with-avatar-cell]`."
  ([network outer-id]
   ((cell/construct-cell
     (id/new-node-id)
     (net/network-cell-strongest network outer-id)
     (net/network-cell-content network outer-id))
    network))
  ([network outer-id inner-id]
   ((cell/construct-cell
     inner-id
     (net/network-cell-strongest network outer-id)
     (net/network-cell-content network outer-id))
    network))
  ([network key strongest content]
   (avatar/spawn-avatar-cell network key strongest content))
  ([network _outer-id inner-id strongest content]
   ((cell/construct-cell inner-id content strongest) network)))

(defn create-and-register-avatar-link
  "Intention-focused helper:
  1) spawn avatar cell, 2) wire via nothing link, 3) register outer->inner in dict."
  [network outer-id link-fn record-fn]
  (let [[inner-id net*] (spawn-avatar-cell network outer-id)
        net** (link-fn net* outer-id inner-id)]
    (record-fn net** outer-id inner-id)))

(defn create-boundary-outputs
  "Spawn avatar per external output; assoc into dict :avatars-out; return net."
  [network outer-ids]
  (reduce (fn [net* ext]
            (create-and-register-avatar-link net* ext stdlib/nothing-out-link net/assoc-avatar-out))
          network
          (vec outer-ids)))

(defn create-boundary-inputs
  "Spawn avatar per external input; assoc into dict :avatars-in; return net."
  [network outer-ids]
  (reduce (fn [net* ext]
            (create-and-register-avatar-link net* ext stdlib/nothing-in-link net/assoc-avatar-in))
          network
          (vec outer-ids)))

(defn run-internal-network
  "Resolve inner ids for `external-inputs` from dict :avatars-in; inner fixpoint."
  [external-inputs network]
  (let [inner-ids (mapv #(net/lookup-inner-in network %) (vec external-inputs))]
    (run-tasks (pop-inputs inner-ids (net-graph network)) network)))
