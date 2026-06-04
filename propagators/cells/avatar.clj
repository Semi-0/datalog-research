(ns propagators.cells.avatar
  "Avatar cell spawn/register (network only; no scheduler)."
  (:require [propagators.cells.cell :as cell]
            [propagators.ids :as id]
            [propagators.network :as net]
            [propagators.stdlib.prop :refer [id]]))

(defn spawn-avatar-cell
  "Create an avatar cell and register it in the subnet dict under `key`.
  Returns `[inner-id net-with-avatar-cell]`."
  [network key strongest content]
  (let [dict (net/net-dict-or-empty network)]
    (if (contains? dict key)
      [(get dict key) network]
      (let [inner-id (id/new-node-id)
            [_ network*] ((cell/construct-cell inner-id content strongest) network)
            dict* (net/net-dict-or-empty network*)]
        [inner-id (net/net-with-dict network* (assoc dict* key inner-id))]))))

(defn avatar-strongest
  "Strongest value of the avatar registered under `outer-id` in `subnet`."
  [subnet outer-id]
  (when-let [avatar-id (get (net/net-dict-or-empty subnet) outer-id)]
    (net/network-cell-strongest subnet avatar-id)))

(defn sync-avatar-cell
  "Sync avatar for `outer-id` from parent-net into internal-net."
  [internal-net parent-net outer-id]
  (if-let [avatar-id (get (net/net-dict-or-empty internal-net) outer-id)]
    (let [content (net/network-cell-content parent-net outer-id)
          strongest (net/network-cell-strongest parent-net outer-id)]
      (net/assoc-net-cell internal-net avatar-id (cell/cell content strongest)))
    internal-net))

(defn link-avatars
  "Wire the avatar cells referenced by `id-key` and `name-key` in the subnet dict."
  [subnet id-key name-key]
  (let [dict (net/net-dict-or-empty subnet)
        a (get dict id-key)
        b (get dict name-key)]
    (if (and a b)
      (-> subnet
          (net/install-net (id a b))
          (net/install-net (id b a)))
      subnet)))
