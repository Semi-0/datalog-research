(ns propagators.datastructures.compound_subnet
  "Compound linked-list subnet state and merge (no propagators, no scheduler)."
  (:require [meander.epsilon :as m]
            [propagators.cells.avatar :as avatar]
            [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.ids :as id]
            [propagators.network :as net]
            [propagators.stdlib :refer [p:id p:tap bi-sync]]))

(declare complete-compound-data?)
(declare compound-data-head? compound-data-tail?)

(defrecord CompoundUpdate [head tail])
(defrecord CompoundSubnetState [subnet out-ids])
(defrecord CompoundStrongestResult [subnet updated*])

(defn compound-update
  [{:keys [head tail]}]
  (->CompoundUpdate head tail))

(defn update-head [update]
  (:head update))

(defn update-tail [update]
  (:tail update))

(defn compound-state [subnet out-ids]
  (->CompoundSubnetState subnet out-ids))

(defn state-subnet [state]
  (:subnet state))

(defn state-out-ids [state]
  (:out-ids state))

(defn strongest-result [subnet updated*]
  (->CompoundStrongestResult subnet updated*))

(defn strongest-subnet [result]
  (:subnet result))

(defn strongest-updated* [result]
  (:updated* result))

(defn take-head [update]
  (when-let [h (update-head update)]
    (compound-update {:head h})))

(defn take-tail [update]
  (when-let [t (update-tail update)]
    (compound-update {:tail t})))

(defn compound-id
  "Extract the single node-id referenced by a head-only or tail-only update map."
  [update]
  (or (update-head update)
      (update-tail update)))

(defn compound-data-ids
  "All node-ids referenced by a compound-data update."
  [update]
  (vec (keep identity [(update-head update) (update-tail update)])))

(defn link-avatars
  "Wire the avatar cells referenced by `id-key` and `name-key` in the subnet dict."
  [subnet id-key name-key _bi-sync]
  (let [dict (net/net-dict-or-empty subnet)
        a (get dict id-key)
        b (get dict name-key)]
    (if (and a b)
      (-> subnet
          (net/install-net (p:id a b))
          (net/install-net (p:id b a)))
      subnet)))

(defn subnet-outer-ids
  "All outer node-id dict keys in `subnet`."
  [subnet]
  (let [dict (net/net-dict-or-empty subnet)]
    (vec (filter id/node-id? (keys dict)))))

(defn mark-updated-tap
  "Build an effectful tap that records `outer-node-id` into `updated*`."
  [updated* outer-node-id]
  (p:tap
   (fn [_inputs]
     (swap! updated* conj outer-node-id))))

(defn hook-output-taps
  "Install taps on avatar cells whose dict key is in `outer-ids` (once per key)."
  [subnet outer-ids updated*]
  (reduce
   (fn [n outer-id]
     (let [dict (net/net-dict-or-empty n)
           hooked (or (:tap-hooked dict) #{})]
       (if (contains? hooked outer-id)
         n
         (if-let [avatar-id (get dict outer-id)]
           (let [n' (net/install-net n ((mark-updated-tap updated* outer-id) avatar-id))
                 dict' (net/net-dict-or-empty n')]
             (net/net-with-dict n' (assoc dict' :tap-hooked (conj hooked outer-id))))
           n))))
   subnet
   (vec outer-ids)))

(defn subnet-effectful-tasks
  "Tasks to run avatar outputs on `subnet` for `outer-ids`, after hooking taps."
  [subnet outer-ids updated*]
  (let [subnet' (hook-output-taps subnet outer-ids updated*)
        dict (net/net-dict-or-empty subnet')
        avatar-ids (mapv #(get dict %) (vec outer-ids))]
    {:subnet subnet'
     :tasks (pop-inputs avatar-ids (net/net-graph subnet'))}))

(defn compound-subnet-state?
  "Cell content shape with accumulated outer output ids."
  [x]
  (and (map? x)
       (contains? x :subnet)
       (contains? x :out-ids)
       (net/network? (state-subnet x))
       (set? (state-out-ids x))))

(defn empty-compound-subnet
  "Initial compound cell content before any merge."
  []
  (compound-state net/empty-net #{}))

(defn compound-strongest-result?
  "Strongest slot shape after effectful run."
  [x]
  (and (map? x)
       (contains? x :subnet)
       (contains? x :updated*)
       (net/network? (strongest-subnet x))
       (instance? clojure.lang.Atom (strongest-updated* x))))

(defn avatar-strongest
  "Strongest value of the avatar registered under `outer-id` in `subnet`."
  [subnet outer-id]
  (when-let [avatar-id (get (net/net-dict-or-empty subnet) outer-id)]
    (net/network-cell-strongest subnet avatar-id)))

(defn sync-avatar-cell
  "Sync avatar for `outer-id` from parent. `network` is `[internal-subnet parent-net]`."
  [[internal-net parent-net] outer-id]
  (if-let [avatar-id (get (net/net-dict-or-empty internal-net) outer-id)]
    (let [content (net/network-cell-content parent-net outer-id)
          strongest (net/network-cell-strongest parent-net outer-id)]
      (net/assoc-net-cell internal-net avatar-id (cell/cell content strongest)))
    internal-net))

(defn update-internal-network [subnet outer-net name update]
  (let [dict (net/net-dict-or-empty subnet)
        id (compound-id update)
        strongest (net/network-cell-strongest outer-net id)
        content (net/network-cell-content outer-net id)
        spawn (fn [n key] (second (avatar/spawn-avatar-cell n key strongest content)))]
    (if (contains? dict name)
      (if (contains? dict id)
        (sync-avatar-cell [subnet outer-net] id)
        (-> subnet
            (spawn id)
            (link-avatars id name bi-sync)))
      (-> subnet
          (spawn name)
          (spawn id)
          (link-avatars id name bi-sync)))))

(defn merge-compound-data
  "Merge compound update into state; extend avatars and accumulate output ids.
  Does not run the internal network."
  [state update network]
  (let [subnet (state-subnet state)
        out-ids (state-out-ids state)
        subnet' (cond
                  (complete-compound-data? update) (-> subnet
                                                       (update-internal-network network :tail (take-tail update))
                                                       (update-internal-network network :head (take-head update)))
                  (compound-data-tail? update) (update-internal-network subnet network :tail update)
                  (compound-data-head? update) (update-internal-network subnet network :head update)
                  :else subnet)
        out-ids' (into (or out-ids #{}) (compound-data-ids update))]
    (compound-state subnet' out-ids')))

(defn complete-compound-data?
  "Both head and tail node ids are present."
  [x]
  (boolean
   (m/match x
     {:head (m/pred id/node-id?) :tail (m/pred id/node-id?)}
     true
     :else
     false)))

(defn compound-data-head?
  "Head-only compound update."
  [x]
  (and (not (contains? x :tail))
       (boolean
        (m/match x
          {:head (m/pred id/node-id?)}
          true
          :else
          false))))

(defn compound-data-tail?
  "Tail-only compound update."
  [x]
  (and (not (contains? x :head))
       (boolean
        (m/match x
          {:tail (m/pred id/node-id?)}
          true
          :else
          false))))

(defn partial-compound-data?
  "Head-only or tail-only compound slot (not complete)."
  [x]
  (or (compound-data-head? x) (compound-data-tail? x)))

(defn compound-data?
  [x]
  (or (complete-compound-data? x)
      (partial-compound-data? x)
      (compound-data-head? x)
      (compound-data-tail? x)))

(defn dispatch-target?
  "Only dispatch to element cells; skip nested collection cells (car/cdr own those)."
  [network outer-id]
  (let [content (cell/cell-content (net/network-env-lookup network outer-id))]
    (not (compound-subnet-state? content))))

