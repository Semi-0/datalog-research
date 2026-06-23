(ns propagators.gur.subenv.scoped-slot
  "Register child-frame slot accessors as scoped compound-object participants."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv.env :as env]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.scoped-address :as scoped])
  (:import [java.util Collections IdentityHashMap]))

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
  ([v] (accessor-parent-cell-ids nil v))
  ([source-net v]
   ;; ponytail: identity cycle guard; equal list nodes are still distinct tails.
   (let [seen (Collections/newSetFromMap (IdentityHashMap.))]
     (letfn [(walk [value]
               (if (or (not (accessor-value? value)) (.contains seen value))
                 #{}
                 (do
                   (.add seen value)
                   (let [direct (->> (obj/accessor-slot-keys value)
                                     (mapcat #(obj/accessor-parent-ids value %))
                                     (filter ids/node-id?)
                                     set)
                         indirect (->> direct
                                       (map #(source-cell-value source-net %))
                                       (mapcat walk)
                                       set)
                         nested (->> (vals (obj/accessor-source-slots value))
                                     (mapcat walk)
                                     set)]
                     (into direct (concat indirect nested))))))]
       (walk v)))))

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

(defn- scoped-local-ids
  [child-net scope]
  (->> (env/scoped-bindings child-net)
       (keep (fn [[binding-scope _name local-id]]
               (when (= scope binding-scope)
                 local-id)))
       set))

(defn- scoped-local-id-index
  [child-net]
  (reduce (fn [index [scope _name local-id]]
            (update index scope (fnil conj #{}) local-id))
          {}
          (env/scoped-bindings child-net)))

(defn- local-scope-index
  [child-net]
  (or (net/network-dict-entry child-net env/local-scopes-key) {}))

(defn- external-output-cell?
  [parent-net child-net cell-id]
  (and (parent-owned-id? parent-net cell-id)
       (boundary-outer-id? child-net cell-id)))

(defn- publisher-slot-exports
  ([parent-net child-net scope cell-id accessor-value slot-key]
   (publisher-slot-exports parent-net
                           child-net
                           scope
                           (scoped-local-ids child-net scope)
                           cell-id
                           accessor-value
                           slot-key))
  ([parent-net child-net scope scope-locals cell-id accessor-value slot-key]
  (let [parent-ids (sort-by pr-str (obj/accessor-parent-ids accessor-value slot-key))
        parent-owned (filter #(parent-owned-id? parent-net %) parent-ids)
        child-owned (filter #(child-owned-id? parent-net child-net %) parent-ids)
        targets (concat (map #(scoped/cell-ref scope %)
                             (filter scope-locals child-owned))
                        (filter #(and (scoped/address? %)
                                      (not= scope (scoped/address-scope %))
                                      (live-scoped-target? child-net %))
                                parent-ids))
        parent-exports
        (for [parent-id parent-owned
              child-ref targets]
          {:slot-key slot-key
           :parent-id parent-id
           :child-ref child-ref})
        output-exports
        (when (external-output-cell? parent-net child-net cell-id)
          (for [child-ref targets]
            {:slot-key slot-key
             :collection-id cell-id
             :child-ref child-ref}))]
    (concat parent-exports output-exports))))

(defn- direct-child-accessor-exports
  ([parent-net child-net scope]
   (direct-child-accessor-exports parent-net
                                  child-net
                                  scope
                                  (child-accessor-values child-net)
                                  {scope (scoped-local-ids child-net scope)}))
  ([parent-net child-net scope accessor-values scope-local-index]
   (let [scope-locals (get scope-local-index scope #{})]
     (mapcat
      (fn [[cell-id accessor-value]]
        (mapcat #(publisher-slot-exports parent-net
                                         child-net
                                         scope
                                         scope-locals
                                         cell-id
                                         accessor-value
                                         %)
                (sort-by pr-str (obj/accessor-slot-keys accessor-value))))
      accessor-values))))

(defn- neighbor-slot-exports
  [parent-net child-net local-scopes cell-id accessor-value slot-key]
  (let [parent-ids (sort-by pr-str (obj/accessor-parent-ids accessor-value slot-key))
        parent-owned (filter #(parent-owned-id? parent-net %) parent-ids)
        scoped-parents (filter #(and (scoped/address? %)
                                     (contains? (net/net-dict-or-empty parent-net) %))
                               parent-ids)
        child-owned (filter #(child-owned-id? parent-net child-net %) parent-ids)
        child-targets (for [local-id child-owned
                            scope (sort-by pr-str (get local-scopes local-id))]
                        (scoped/cell-ref scope local-id))
        scoped-targets (filter #(and (scoped/address? %)
                                     (live-scoped-target? child-net %))
                               parent-ids)
        targets (distinct (concat child-targets scoped-targets))
        parent-exports
        (concat
         (for [parent-id parent-owned
               child-ref targets
               :when (not= parent-id child-ref)]
           {:slot-key slot-key
            :parent-id parent-id
            :child-ref child-ref})
         (for [parent-id scoped-parents
               child-ref targets
               :when (not= parent-id child-ref)]
           {:slot-key slot-key
            :parent-id parent-id
            :child-ref child-ref
            :value-only? true}))
        output-exports
        (when (external-output-cell? parent-net child-net cell-id)
          (for [child-ref targets]
            {:slot-key slot-key
             :collection-id cell-id
             :child-ref child-ref}))]
    (concat parent-exports output-exports)))

(defn- neighbor-child-accessor-exports
  [parent-net child-net]
  (let [accessor-values (vec (child-accessor-values child-net))
        local-scopes (local-scope-index child-net)]
    (mapcat
     (fn [[cell-id accessor-value]]
       (mapcat #(neighbor-slot-exports parent-net
                                       child-net
                                       local-scopes
                                       cell-id
                                       accessor-value
                                       %)
               (sort-by pr-str (obj/accessor-slot-keys accessor-value))))
     accessor-values)))

(defn- collection-cell-value
  [parent-net collection-id]
  (cell-strongest-value (get (net/net-env parent-net) collection-id)))

(defn- child-ref-value
  [child-net child-ref]
  (cond
    (ids/node-id? child-ref)
    (cell-strongest-value (get (net/net-env child-net) child-ref))

    (scoped/address? child-ref)
    (let [route (net/network-dict-entry child-net child-ref)]
      (or
       (case (first route)
         :dispatch/local
         (cell-strongest-value (get (net/net-env child-net) (second route)))

         :dispatch/subenv
         (let [[_ owner-id local-id] route
               owner-net (cell-strongest-value (get (net/net-env child-net)
                                                    owner-id))]
           (when (net/net? owner-net)
             (cell-strongest-value (get (net/net-env owner-net) local-id))))

         nil)
       (when (and (vector? child-ref)
                  (= :env/cell-ref (first child-ref)))
         (cell-strongest-value (get (net/net-env child-net)
                                    (nth child-ref 2))))))

    :else
    nil))

(defn- parent-already-has?
  [parent-net parent-id v]
  (= v (cell-strongest-value (get (net/net-env parent-net) parent-id))))

(defn- parent-messageable?
  [parent-net parent-id]
  (or (parent-owned-id? parent-net parent-id)
      (and (scoped/address? parent-id)
           (contains? (net/net-dict-or-empty parent-net) parent-id))))

(defn- collection-cell-ids-for-parent
  [parent-net slot-key parent-id]
  (keep (fn [[collection-id entry]]
          (let [v (cell-strongest-value entry)]
            (when (and (accessor-value? v)
                       (contains? (obj/accessor-parent-ids v slot-key) parent-id))
              collection-id)))
        (net/net-env parent-net)))

(defn- export-collection-ids
  [parent-net {:keys [collection-id slot-key parent-id]}]
  (if collection-id
    [collection-id]
    (collection-cell-ids-for-parent parent-net slot-key parent-id)))

(defn- declaration-missing?
  [parent-net collection-id slot-key child-ref]
  (let [current (collection-cell-value parent-net collection-id)]
    (not (and (accessor-value? current)
              (contains? (obj/accessor-parent-ids current slot-key)
                         child-ref)))))

(defn- accumulated-export-messages
  [parent-net exports]
  (->> exports
       (remove :value-only?)
       (mapcat (fn [{:keys [slot-key child-ref] :as export}]
                 (for [collection-id (export-collection-ids parent-net export)
                       :when (declaration-missing? parent-net
                                                   collection-id
                                                   slot-key
                                                   child-ref)]
                   [collection-id slot-key child-ref])))
       distinct
       (mapv (fn [[collection-id slot-key child-ref]]
               (message collection-id
                        (obj/accessor-declaration slot-key child-ref))))))

(defn- accumulated-export-value-messages
  [parent-net child-net exports]
  (->> exports
       (keep (fn [{:keys [parent-id child-ref]}]
               (when (parent-messageable? parent-net parent-id)
                 (let [v (child-ref-value child-net child-ref)]
                   (when-not (or (nil? v)
                                 (and (parent-owned-id? parent-net parent-id)
                                      (parent-already-has? parent-net parent-id v)))
                     (message parent-id v))))))
       distinct
       vec))

(defn direct-child-accessor-messages
  "Return parent-cell messages that publish direct child scoped accessors.

  This emits accessor declarations only; cell merge owns topology refinement.
  Nested frames publish through their own immediate owner cells.
  "
  [parent-net child-net]
  (if-let [scope (and (net/net? child-net)
                      (net/network-dict-entry child-net [:env/scope]))]
    (vec (accumulated-export-messages
          parent-net
          (direct-child-accessor-exports parent-net child-net scope)))
    []))

(defn direct-child-accessor-messages-for-scopes
  [parent-net child-net scopes]
  (let [accessor-values (vec (child-accessor-values child-net))
        scope-local-index (scoped-local-id-index child-net)]
    (vec
     (mapcat (fn [scope]
               (accumulated-export-messages
                parent-net
                (direct-child-accessor-exports parent-net
                                               child-net
                                               scope
                                               accessor-values
                                               scope-local-index)))
             (sort-by pr-str scopes)))))

(defn direct-child-accessor-messages-for-neighbors
  [parent-net child-net]
  (let [exports (vec (neighbor-child-accessor-exports parent-net child-net))]
    (vec
     (concat
      (accumulated-export-messages parent-net exports)
      (accumulated-export-value-messages parent-net child-net exports)))))

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
