(ns propagators.datastructures.compound-object
  "Experimental object slots over named-network cell values."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.datastructures.named-network :as named]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib :as stdlib]))

(def slot-keys #{:car :cdr})

(defn- install-cell
  ([n id]
   (second ((cell/construct-cell id) n)))
  ([n id content strongest]
   (second ((cell/construct-cell id content strongest) n))))

(defn- put-dict [n k v]
  (net/net-with-dict n (assoc (net/net-dict-or-empty n) k v)))

(defn- update-dict [n k f & args]
  (net/net-with-dict n (apply update (net/net-dict-or-empty n) k f args)))

(defn empty-cons-net
  "A named network with stable `:car` and `:cdr` slot cells."
  []
  (let [car-id (ids/new-node-id)
        cdr-id (ids/new-node-id)]
    (-> net/empty-net
        (install-cell car-id)
        (install-cell cdr-id)
        (net/net-with-dict {:car car-id
                            :cdr cdr-id
                            :slot-index {:car #{}
                                         :cdr #{}}}))))

(defn ensure-cons-net
  [x]
  (cond
    (value/nothing? x) (empty-cons-net)
    (named/named-network? x) x
    (value/contradiction? x) value/contradiction
    :else value/contradiction))

(defn slot-id [collection-net slot-key]
  (get (net/net-dict-or-empty collection-net) slot-key))

(defn slot-parent-ids [collection-net slot-key]
  (get-in (net/net-dict-or-empty collection-net) [:slot-index slot-key] #{}))

(defn- ensure-slot-cell [collection-net slot-key]
  (if (slot-id collection-net slot-key)
    collection-net
    (let [id (ids/new-node-id)]
      (-> collection-net
          (install-cell id)
          (put-dict slot-key id)
          (update-dict :slot-index #(assoc (or % {}) slot-key #{}))))))

(defn- parent-cell [parent-net parent-id]
  (net/network-env-lookup parent-net parent-id))

(defn- merge-cell-entry [entry update network]
  (let [content' (merge/cell-merge (cell/cell-content entry) update network)
        strongest' (merge/strongest-value content' network)]
    (cell/cell content' strongest')))

(defn- sync-key [slot-key parent-id direction]
  [:slot-sync slot-key parent-id direction])

(defn- tap-key [parent-id]
  [:slot-tap parent-id])

(defn- slot-attached? [collection-net slot-key parent-id]
  (let [dict (net/net-dict-or-empty collection-net)]
    (and (contains? dict (sync-key slot-key parent-id :avatar->slot))
         (contains? dict (sync-key slot-key parent-id :slot->avatar)))))

(defn- attach-bi-sync [collection-net slot-key parent-id avatar-id slot-id]
  (if (slot-attached? collection-net slot-key parent-id)
    collection-net
    (let [[avatar->slot n] ((stdlib/p:id avatar-id slot-id) collection-net)
          [slot->avatar n] ((stdlib/p:id slot-id avatar-id) n)]
      (-> n
          (put-dict (sync-key slot-key parent-id :avatar->slot) avatar->slot)
          (put-dict (sync-key slot-key parent-id :slot->avatar) slot->avatar)))))

(defn- remove-prop-node [n prop-id]
  (let [g (net/net-graph n)
        node (get g prop-id)
        input-ids (if node (graph/node-input-ids node) #{})
        g' (reduce
            (fn [g input-id]
              (if-let [input-node (get g input-id)]
                (assoc g input-id
                       (graph/node (graph/node-input-ids input-node)
                                   (disj (graph/node-output-ids input-node) prop-id)))
                g))
            (dissoc g prop-id)
            input-ids)]
    (-> n
        (net/net-with-graph g')
        (net/net-with-env (dissoc (net/net-env n) prop-id)))))

(defn- remove-parent-tap [n parent-id]
  (let [k (tap-key parent-id)
        tap-id (get (net/net-dict-or-empty n) k)]
    (cond-> (net/net-with-dict n (dissoc (net/net-dict-or-empty n) k))
      tap-id (remove-prop-node tap-id))))

(defn- remove-slot-taps [n slot-key]
  (reduce remove-parent-tap n (slot-parent-ids n slot-key)))

(defn- install-parent-tap [n updated* parent-id]
  (let [avatar-id (get (net/net-dict-or-empty n) parent-id)
        [tap-id n'] (((stdlib/mark-updated-tap updated* parent-id) avatar-id) n)]
    (put-dict n' (tap-key parent-id) tap-id)))

(defn- hook-slot-output-taps [subnet slot-key updated*]
  (reduce
   (fn [n parent-id]
     (-> n
         (remove-parent-tap parent-id)
         (install-parent-tap updated* parent-id)))
   subnet
   (slot-parent-ids subnet slot-key)))

(defn- ensure-parent-avatar [collection-net slot-key parent-id parent-net]
  (let [dict (net/net-dict-or-empty collection-net)
        parent (parent-cell parent-net parent-id)
        parent-content (cell/cell-content parent)
        parent-strongest (cell/cell-strongest parent)]
    (if-let [avatar-id (get dict parent-id)]
      (let [avatar (get (net/net-env collection-net) avatar-id)
            avatar' (merge-cell-entry avatar parent-content parent-net)]
        (net/assoc-net-cell collection-net avatar-id avatar'))
      (let [avatar-id (ids/new-node-id)]
        (-> collection-net
            (install-cell avatar-id parent-content parent-strongest)
            (put-dict parent-id avatar-id)
            (update-dict :slot-index
                         #(update (or % {}) slot-key (fnil conj #{}) parent-id)))))))

(defn attach-slot-sync
  [collection-net slot-key parent-id parent-net]
  (let [n (ensure-slot-cell collection-net slot-key)
        n (ensure-parent-avatar n slot-key parent-id parent-net)
        dict (net/net-dict-or-empty n)
        avatar-id (get dict parent-id)
        slot-id (get dict slot-key)
        n (attach-bi-sync n slot-key parent-id avatar-id slot-id)]
    {:exec-net n
     :slot-id slot-id
     :avatar-id avatar-id}))

(defn- slot-effectful-tasks [subnet slot-key updated*]
  (let [parent-ids (vec (slot-parent-ids subnet slot-key))
        subnet' (hook-slot-output-taps subnet slot-key updated*)
        dict (net/net-dict-or-empty subnet')
        avatar-ids (keep #(get dict %) parent-ids)
        slot (slot-id subnet' slot-key)
        seed-ids (if slot (conj (vec avatar-ids) slot) (vec avatar-ids))]
    {:subnet subnet'
     :tasks (tq/into-queue (pop-inputs seed-ids (net/net-graph subnet')))}))

(defn- subnet-updated-parent-messages [after updated*]
  (let [dict (net/net-dict-or-empty after)]
    (mapv (fn [parent-id]
            (let [avatar-id (get dict parent-id)]
              (message parent-id (net/network-cell-strongest after avatar-id))))
          (sort-by pr-str @updated*))))

(defn run-slot-sync
  [collection-net slot-key parent-id parent-net]
  (let [{:keys [exec-net]} (attach-slot-sync collection-net slot-key parent-id parent-net)
        updated* (atom #{})
        {:keys [subnet tasks]} (slot-effectful-tasks exec-net slot-key updated*)
        collection-net' (run-tasks tasks subnet)
        parent-messages (subnet-updated-parent-messages collection-net' updated*)]
    {:collection-net (remove-slot-taps collection-net' slot-key)
     :parent-messages parent-messages}))

(defn p:slot*
  [slot-key parent-id collection-id]
  (when-not (contains? slot-keys slot-key)
    (throw (ex-info "unknown compound object slot" {:slot-key slot-key})))
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [collection-content (net/network-cell-content network collection-id)
           collection-net (ensure-cons-net (merge/strongest-value collection-content network))]
       (if (value/contradiction? collection-net)
         [(message collection-id value/contradiction)]
         (let [result (run-slot-sync collection-net slot-key parent-id network)]
           (into [(message collection-id (:collection-net result))]
                 (:parent-messages result))))))
   [parent-id collection-id]
   [parent-id collection-id]))

(defn p:car* [elem-id collection-id]
  (p:slot* :car elem-id collection-id))

(defn p:cdr* [elem-id collection-id]
  (p:slot* :cdr elem-id collection-id))

(defn p:cons*
  [head-id tail-id collection-id]
  (fn [network]
    (let [[car-prop n] ((p:car* head-id collection-id) network)
          [cdr-prop n] ((p:cdr* tail-id collection-id) n)]
      [[car-prop cdr-prop] n])))
