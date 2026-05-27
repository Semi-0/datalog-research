(ns propagators.datastructures.compound_subnet
  "Compound linked-list subnet state and merge (no propagators, no scheduler)."
  (:require [propagators.cells.avatar :as avatar]
            [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_update :as update]
            [propagators.ids :as id]
            [propagators.network :as net]
            [propagators.stdlib :refer [hook-output-taps]]))

(defn subnet-outer-ids
  "All outer node-id dict keys in `subnet`."
  [subnet]
  (let [dict (net/net-dict-or-empty subnet)]
    (vec (filter id/node-id? (keys dict)))))

(defn subnet-effectful-tasks
  "Tasks to run avatar outputs on `subnet` for `outer-ids`, after hooking taps."
  [subnet outer-ids updated*]
  (let [subnet' (hook-output-taps subnet outer-ids updated*)
        dict (net/net-dict-or-empty subnet')
        avatar-ids (mapv #(get dict %) (vec outer-ids))]
    {:subnet subnet'
     :tasks (pop-inputs avatar-ids (net/net-graph subnet'))}))

(defn update-internal-network [subnet outer-net name update]
  (let [dict (net/net-dict-or-empty subnet)
        id (update/compound-id update)
        strongest (net/network-cell-strongest outer-net id)
        content (net/network-cell-content outer-net id)
        spawn (fn [n key] (second (avatar/spawn-avatar-cell n key strongest content)))]
    (if (contains? dict name)
      (if (contains? dict id)
        (avatar/sync-avatar-cell subnet outer-net id)
        (-> subnet
            (spawn id)
            (avatar/link-avatars id name)))
      (-> subnet
          (spawn name)
          (spawn id)
          (avatar/link-avatars id name)))))

(defn merge-compound-data
  "Merge compound update into state; extend avatars and accumulate output ids.
  Does not run the internal network."
  [compound-state update network]
  (let [subnet (state/state-subnet compound-state)
        out-ids (state/state-out-ids compound-state)
        subnet' (cond
                  (update/complete-compound-data? update) (-> subnet
                                                              (update-internal-network network :tail (update/take-tail update))
                                                              (update-internal-network network :head (update/take-head update)))
                  (update/compound-data-tail? update) (update-internal-network subnet network :tail update)
                  (update/compound-data-head? update) (update-internal-network subnet network :head update)
                  :else subnet)
        out-ids' (into (or out-ids #{}) (update/compound-data-ids update))]
    (state/compound-state subnet' out-ids')))

(defn dispatch-target?
  "Only dispatch to element cells; skip nested collection cells (car/cdr own those)."
  [network outer-id]
  (let [content (cell/cell-content (net/network-env-lookup network outer-id))]
    (not (state/compound-subnet-state? content))))

