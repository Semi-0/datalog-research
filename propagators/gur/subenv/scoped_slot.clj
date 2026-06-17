(ns propagators.gur.subenv.scoped-slot
  "Register child-frame slot accessors as scoped compound-object participants."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.datastructures.compound-object :as obj]
            [propagators.effectful-sync :as sync]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.scoped-address :as scoped]))

(defn- cell-strongest-value
  [entry]
  (when (cell/cell? entry)
    (cell/cell-strongest entry)))

(defn- accessor-value?
  [v]
  (and (net/net? v)
       (obj/accessor-network? v)))

(defn- source-cell-value
  [source-net id]
  (when (and (net/net? source-net)
             (ids/node-id? id)
             (contains? (net/net-env source-net) id))
    (cell-strongest-value (get (net/net-env source-net) id))))

(defn accessor-parent-cell-ids
  ([v] (accessor-parent-cell-ids nil #{} v))
  ([source-net v] (accessor-parent-cell-ids source-net #{} v))
  ([source-net seen v]
   (if (or (not (accessor-value? v)) (contains? seen v))
     #{}
     (let [seen* (conj seen v)
           direct (->> (obj/accessor-slot-keys v)
                       (mapcat #(obj/accessor-parent-ids v %))
                       (filter ids/node-id?)
                       set)
           indirect (->> direct
                         (map #(source-cell-value source-net %))
                         (mapcat #(accessor-parent-cell-ids source-net seen* %))
                         set)
           nested (->> (vals (obj/accessor-source-slots v))
                       (mapcat #(accessor-parent-cell-ids source-net seen* %))
                       set)]
       (into direct (concat indirect nested))))))

(defn- copy-or-merge-cell
  [target-net source-net id]
  (let [source-cell (net/network-lookup-cell source-net id)]
    (if (contains? (net/net-env target-net) id)
      (let [target-cell (net/network-lookup-cell target-net id)]
        (net/assoc-net-cell target-net
                            id
                            (merge/merge-cell-entry target-cell
                                                    (cell/cell-content source-cell)
                                                    source-net)))
      (nb/install-cell target-net
                       id
                       (cell/cell-content source-cell)
                       (cell/cell-strongest source-cell)))))

(defn import-accessor-parent-cells
  "Seed accessor parent cells from `source-net` into `target-net` for `v`."
  [target-net source-net v]
  (reduce (fn [n id]
            (if (contains? (net/net-env source-net) id)
              (copy-or-merge-cell n source-net id)
              n))
          target-net
          (accessor-parent-cell-ids source-net v)))

(defn- child-owned-id?
  [parent-net child-net id]
  (and (ids/node-id? id)
       (contains? (net/net-env child-net) id)
       (not (contains? (net/net-env parent-net) id))))

(defn- parent-owned-id?
  [parent-net id]
  (and (ids/node-id? id)
       (contains? (net/net-env parent-net) id)))

(defn- live-scoped-target?
  [child-net target]
  (let [route (net/network-dict-entry child-net target)
        owner-id (when (and (vector? route)
                            (contains? #{:dispatch/subenv
                                         :dispatch/subenv-ref}
                                       (first route)))
                   (second route))]
    (or (and (vector? route)
             (= :dispatch/local (first route))
             (contains? (net/net-env child-net) (second route)))
        (and owner-id
             (net/net? (cell-strongest-value
                        (get (net/net-env child-net) owner-id)))))))

(defn- boundary-outer-id?
  [child-net id]
  (let [dict (net/net-dict-or-empty child-net)]
    (or (contains? (get dict :avatars-in {}) id)
        (contains? (get dict :avatars-out {}) id))))

(defn- child-accessor-values
  [child-net]
  (keep (fn [[id entry]]
          (let [v (cell-strongest-value entry)]
            (when (accessor-value? v)
              [id v])))
        (net/net-env child-net)))

(defn- subenv-net?
  [v]
  (and (net/net? v)
       (some? (net/network-dict-entry v [:env/scope]))))

(defn- nested-child-nets
  [child-net]
  (keep (fn [[_id entry]]
          (let [v (cell-strongest-value entry)]
            (when (subenv-net? v)
              v)))
        (net/net-env child-net)))

(defn- accessor-slot-exports
  [parent-net child-net scope cell-id accessor-value slot-key]
  (let [parent-ids (obj/accessor-parent-ids accessor-value slot-key)
        parent-owned (filter #(parent-owned-id? parent-net %) parent-ids)
        child-owned (filter #(child-owned-id? parent-net child-net %) parent-ids)
        targets (concat (map #(scoped/cell-ref scope %) child-owned)
                        (filter #(and (parent-owned-id? parent-net cell-id)
                                      (not (boundary-outer-id? child-net cell-id))
                                      (scoped/address? %)
                                      (not= scope (scoped/address-scope %))
                                      (live-scoped-target? child-net %))
                                parent-ids))]
    (for [parent-id parent-owned
          child-ref targets]
      {:slot-key slot-key
       :parent-id parent-id
       :child-ref child-ref})))

(defn- publisher-slot-exports
  [parent-net child-net scope accessor-value slot-key]
  (let [parent-ids (obj/accessor-parent-ids accessor-value slot-key)
        parent-owned (filter #(parent-owned-id? parent-net %) parent-ids)
        child-owned (filter #(child-owned-id? parent-net child-net %) parent-ids)
        targets (concat (map #(scoped/cell-ref scope %) child-owned)
                        (filter #(and (scoped/address? %)
                                      (not= scope (scoped/address-scope %))
                                      (live-scoped-target? child-net %))
                                parent-ids))]
    (for [parent-id parent-owned
          child-ref targets]
      {:slot-key slot-key
       :parent-id parent-id
       :child-ref child-ref})))

(defn- direct-child-accessor-exports
  [parent-net child-net scope]
  (mapcat
   (fn [[_cell-id accessor-value]]
     (mapcat #(publisher-slot-exports parent-net child-net scope accessor-value %)
             (obj/accessor-slot-keys accessor-value)))
   (child-accessor-values child-net)))

(defn- recursive-direct-child-accessor-exports
  [parent-net child-net scope]
  (mapcat
   (fn [[cell-id accessor-value]]
     (mapcat #(accessor-slot-exports parent-net child-net scope cell-id accessor-value %)
             (obj/accessor-slot-keys accessor-value)))
   (child-accessor-values child-net)))

(defn- child-accessor-exports
  [parent-net child-net scope]
  (concat
   (recursive-direct-child-accessor-exports parent-net child-net scope)
   (mapcat (fn [nested]
             (let [nested-scope (net/network-dict-entry nested [:env/scope])]
               (child-accessor-exports parent-net nested nested-scope)))
           (nested-child-nets child-net))))

(defn- collection-cell-value
  [parent-net collection-id]
  (cell-strongest-value (get (net/net-env parent-net) collection-id)))

(defn- collection-cell-ids-for-parent
  [parent-net slot-key parent-id]
  (keep (fn [[collection-id entry]]
          (let [v (cell-strongest-value entry)]
            (when (and (accessor-value? v)
                       (contains? (obj/accessor-parent-ids v slot-key) parent-id))
              collection-id)))
        (net/net-env parent-net)))

(defn- update-collection-accessor
  [parent-net collection-id slot-key child-ref]
  (let [current (collection-cell-value parent-net collection-id)
        updated (obj/register-accessor-parent current slot-key child-ref)]
    (if (= current updated)
      parent-net
      (net/assoc-net-cell parent-net collection-id (cell/cell updated updated)))))

(defn- register-export
  [parent-net {:keys [slot-key parent-id child-ref]}]
  (reduce (fn [n collection-id]
            (update-collection-accessor n collection-id slot-key child-ref))
          parent-net
          (collection-cell-ids-for-parent parent-net slot-key parent-id)))

(defn- accumulate-export-update
  [parent-net updates {:keys [slot-key parent-id child-ref]}]
  (reduce (fn [acc collection-id]
            (let [current (get acc collection-id
                               (collection-cell-value parent-net collection-id))]
              (assoc acc collection-id
                     (obj/register-accessor-parent current slot-key child-ref))))
          updates
          (collection-cell-ids-for-parent parent-net slot-key parent-id)))

(defn- accumulated-export-messages
  [parent-net exports]
  (->> (reduce #(accumulate-export-update parent-net %1 %2) {} exports)
       (keep (fn [[collection-id updated]]
               (when-not (sync/strongest-equivalent?
                          (collection-cell-value parent-net collection-id)
                          updated
                          parent-net)
                 (message collection-id updated))))))

(defn direct-child-accessor-messages
  "Return parent-cell messages that publish direct child scoped accessors.

  Unlike `register-child-accessors`, this does not recurse into nested sub-envs
  and does not mutate the parent network directly. Nested frames publish through
  their own immediate owner cells.
  "
  [parent-net child-net]
  (if-let [scope (and (net/net? child-net)
                      (net/network-dict-entry child-net [:env/scope]))]
    (vec (accumulated-export-messages
          parent-net
          (direct-child-accessor-exports parent-net child-net scope)))
    []))

(defn p:publish-child-accessors
  "Publish direct child accessor exports as ordinary parent-cell messages."
  [owner-id]
  (prop/construct-propagator
   (fn [_inputs _outputs parent-net]
     (direct-child-accessor-messages
      parent-net
      (collection-cell-value parent-net owner-id)))
   [owner-id]
   []))

(defn register-child-accessors
  "Add child-scoped slot addresses to matching parent collection accessors."
  [parent-net _owner-id scope child-net]
  (reduce register-export
          parent-net
          (child-accessor-exports parent-net child-net scope)))
