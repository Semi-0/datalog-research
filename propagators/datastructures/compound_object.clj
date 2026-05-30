(ns propagators.datastructures.compound-object
  "Experimental object slots over named-network cell values."
  (:require [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.named-network :as named]
            [propagators.effectful-execution :as effect]
            [propagators.effectful-sync :as sync]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def slot-keys #{:car :cdr})

(def slot-sync-key :slot-sync)

(defn empty-cons-net
  "A named network with stable `:car` and `:cdr` slot cells."
  []
  (let [car-id (ids/new-node-id)
        cdr-id (ids/new-node-id)]
    (-> net/empty-net
        (nb/install-cell car-id)
        (nb/install-cell cdr-id)
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

(defn slot-cell-id [collection-net slot-key]
  (net/network-dict-entry collection-net slot-key))

(defn slot-strongest [collection-net slot-key]
  (net/network-cell-strongest collection-net (slot-cell-id collection-net slot-key)))

(defn slot-content [collection-net slot-key]
  (net/network-cell-content collection-net (slot-cell-id collection-net slot-key)))

(defn- ensure-slot-cell [collection-net slot-key]
  (if (slot-cell-id collection-net slot-key)
    collection-net
    (let [id (ids/new-node-id)]
      (-> collection-net
          (nb/install-cell id)
          (net/assoc-net-dict-entry slot-key id)
          (net/update-net-dict-entry :slot-index #(assoc (or % {}) slot-key #{}))))))

(defn attach-slot-sync
  [collection-net slot-key parent-id parent-net]
  (let [n (-> collection-net
              (ensure-slot-cell slot-key)
              (sync/ensure-parent-avatar :slot-index slot-key parent-id parent-net))
        dict (net/net-dict-or-empty n)
        avatar-id (get dict parent-id)
        slot-id (get dict slot-key)]
    (sync/attach-indexed-bi-sync n slot-sync-key slot-key parent-id avatar-id slot-id)))

(defn- slot-seed-ids [subnet slot-key]
  (sync/indexed-seed-ids subnet
                         :slot-index
                         slot-key
                         (net/network-dict-entry subnet slot-key)))

(defn- stable-slot-cell-ids [exec-net slot-key]
  (sync/indexed-stable-cell-ids exec-net
                                :slot-index
                                slot-key
                                (net/network-dict-entry exec-net slot-key)))

(defn- slot-output-taps [subnet slot-key updated*]
  (effect/hook-output-taps subnet
                           (net/network-indexed-ids subnet :slot-index slot-key)
                           updated*))

(defn- slot-sync-needed?
  "True when parent avatar and slot strongest values still need propagation."
  [exec-net slot-key parent-id parent-net]
  (let [dict (net/net-dict-or-empty exec-net)
        slot-id (get dict slot-key)
        avatar-id (get dict parent-id)]
    (if-not (and slot-id avatar-id)
      true
      (let [parent-v (net/network-cell-strongest parent-net parent-id)
            avatar-v (net/network-cell-strongest exec-net avatar-id)
            slot-v (net/network-cell-strongest exec-net slot-id)]
        (or (merge/cell-updated? parent-v avatar-v parent-net)
            (merge/cell-updated? avatar-v slot-v exec-net))))))

(defn- stable-collection-net [exec-net slot-key]
  (effect/project-stable-cells exec-net
                               exec-net
                               (stable-slot-cell-ids exec-net slot-key)))

(defn- execute-slot-subnet
  [exec-net slot-key]
  (effect/execute-subnet exec-net
                         #(slot-output-taps %1 slot-key %2)
                         #(slot-seed-ids % slot-key)))

(defn sync-slot-messages
  [collection-id slot-key [exec-net collection-net' updated*]]
  (let [collection-message (message collection-id
                                    (effect/project-stable-cells
                                     exec-net
                                     collection-net'
                                     (stable-slot-cell-ids exec-net slot-key)))
        parent-messages (sync/updated-parent-messages collection-net' updated*)]
    (into [collection-message] parent-messages)))

(defn p:slot
  [slot-key parent-id collection-id]
  (when-not (contains? slot-keys slot-key)
    (throw (ex-info "unknown compound object slot" {:slot-key slot-key})))
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [collection-net (-> network
                              (net/network-cell-strongest collection-id)
                              ensure-cons-net)]
       (if (value/contradiction? collection-net)
         [(message collection-id value/contradiction)]
         (let [exec-net (attach-slot-sync collection-net slot-key parent-id network)]
           (if (slot-sync-needed? exec-net slot-key parent-id network)
             (->> (execute-slot-subnet exec-net slot-key)
                  (sync-slot-messages collection-id slot-key))
             (let [stable (stable-collection-net exec-net slot-key)]
               (if (merge/cell-updated? stable collection-net network)
                 [(message collection-id stable)]
                 [])))))))
   [parent-id collection-id]
   [parent-id collection-id]))

(defn p:car [elem-id collection-id]
  (p:slot :car elem-id collection-id))

(defn p:cdr [elem-id collection-id]
  (p:slot :cdr elem-id collection-id))

(defn p:cons
  [head-id tail-id collection-id]
  (fn [network]
    (let [[car-prop n] (nb/install-propagator network (p:car head-id collection-id))
          [cdr-prop n] (nb/install-propagator n (p:cdr tail-id collection-id))]
      [[car-prop cdr-prop] n])))
