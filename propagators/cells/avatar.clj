(ns propagators.cells.avatar
  "Avatar cell spawn/register (network only; no scheduler)."
  (:require [propagators.ids :as id]
            [propagators.network :as net]))

(defn spawn-avatar-cell
  "Create an avatar cell and register it in the subnet dict under `key`.
  Returns `[inner-id net-with-avatar-cell]`."
  [network key strongest content]
  (let [dict (net/net-dict-or-empty network)]
    (if (contains? dict key)
      [(get dict key) network]
      (let [inner-id (id/new-node-id)
            [_ network*] ((net/construct-cell inner-id content strongest) network)
            dict* (net/net-dict-or-empty network*)]
        [inner-id (net/net-with-dict network* (assoc dict* key inner-id))]))))
