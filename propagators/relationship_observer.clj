(ns propagators.relationship-observer
  "Composable propagators that sample structural relationships from a Net."
  (:require [propagators.cells.cell :as cell]
            [propagators.datastructures.compound-object.patch :as compound-patch]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.semantic-trace :as semantic-trace]))

(def observer-name :relationship/observer)

(defn stable-node-id
  [& parts]
  (ids/->NodeId
   (java.util.UUID/nameUUIDFromBytes
    (.getBytes (pr-str parts) java.nio.charset.StandardCharsets/UTF_8))))

(defn- nested-network
  [network [_kind cell-id]]
  (let [entry (net/network-env-lookup network cell-id)]
    (when (cell/cell? entry)
      (let [strongest (cell/cell-strongest entry)]
        (when (net/net? strongest)
          strongest)))))

(defn network-at-path
  "Resolve a relationship path such as `[:outer [:cell id]]`."
  [network path]
  (when (= :outer (first path))
    (reduce (fn [current segment]
              (when current
                (nested-network current segment)))
            network
            (rest path))))

(defn node-entry
  [network [path node-id]]
  (when-let [owner (network-at-path network path)]
    (net/network-env-lookup owner node-id)))

(defn observer-node?
  [network node-key]
  (let [entry (node-entry network node-key)]
    (and (prop/prop? entry)
         (= observer-name (prop/prop-name entry)))))

(defn root-node-keys
  "Return outer nodes without structural parents, excluding observer machinery."
  ([network] (root-node-keys network #{}))
  ([network excluded-node-ids]
   (let [relationships (net/net-relationship network)]
     (into #{}
           (comp
            (map #(relationship/node-key [:outer] %))
            (remove #(seq (relationship/parents relationships %)))
            (remove #(contains? excluded-node-ids (second %)))
            (remove #(observer-node? network %)))
           (keys (net/net-env network))))))

(defn connected-root-node-keys
  "Top-level nodes connected to explicit outer seed cells."
  [seed-ids]
  (fn [network]
    (let [allowed (root-node-keys network)
          g (net/net-graph network)
          seeds (into #{}
                      (map #(relationship/node-key [:outer] %))
                      seed-ids)]
      (loop [frontier (vec (filter allowed seeds))
             seen #{}]
        (if-let [[_path node-id :as current] (first frontier)]
          (if (contains? seen current)
            (recur (subvec frontier 1) seen)
            (let [node (get g node-id)
                  neighbors (if node
                              (into (graph/node-input-ids node)
                                    (graph/node-output-ids node))
                              #{})
                  next-keys (->> neighbors
                                 (map #(relationship/node-key [:outer] %))
                                 (filter allowed)
                                 (remove seen))]
              (recur (into (subvec frontier 1) next-keys)
                     (conj seen current))))
          seen)))))

(defn child-node-keys
  "Return a selector for `parent` and its immediate structural children."
  [parent]
  (fn [network]
    (conj (relationship/children (net/net-relationship network) parent)
          parent)))

(defn- node-label
  [network node-key]
  (let [entry (node-entry network node-key)]
    (cond
      (prop/prop? entry) (prop/prop-name entry)
      (cell/cell? entry) (or (:name entry) "cell")
      :else (pr-str (second node-key)))))

(defn- node-value
  [network node-key]
  (let [entry (node-entry network node-key)]
    (when (cell/cell? entry)
      (cell/cell-strongest entry))))

(defn- selected-network-edges
  [network selected]
  (mapcat
   (fn [[path node-id :as from]]
     (let [owner (network-at-path network path)
           node (when owner (get (net/net-graph owner) node-id))]
       (for [to-id (if node (graph/node-output-ids node) #{})
             :let [to (relationship/node-key path to-id)]
             :when (contains? selected to)]
         [from to])))
   selected))

(defn- selected-relationship-edges
  [network selected]
  (mapcat
   (fn [parent]
     (for [child (relationship/children (net/net-relationship network) parent)
           :when (contains? selected child)]
       [parent child]))
   selected))

(defn snapshot
  "Project selected path-qualified nodes from the current immutable Net."
  [network node-keys]
  (let [selected (set (filter #(node-entry network %) node-keys))]
    (semantic-trace/graph-union
     {:nodes (into {} (map (fn [key] [key (node-label network key)])) selected)
      :node-aliases {}
      :values (into {}
                    (keep (fn [key]
                            (let [entry (node-entry network key)]
                              (when (cell/cell? entry)
                                [key (node-value network key)]))))
                    selected)
      :node-ui {}
      :expansions {}
      :edges (vec (distinct
                   (concat (selected-network-edges network selected)
                           (selected-relationship-edges network selected))))})))

(defn snapshot-of
  "Compose a `Net -> node keys` selector into a `Net -> semantic graph` sample."
  [select-nodes]
  (fn [network]
    (snapshot network (select-nodes network))))

(defn p:observe-network
  "Install a propagator that samples the current Net when `trigger-id` changes.

  A nil trigger installs a zero-input sampler suitable for one initial run."
  [id trigger-id output-id sample]
  (prop/construct-propagator
   id
   observer-name
   (fn [_inputs _outputs network]
     [(message output-id (sample network))])
   (if trigger-id [trigger-id] [])
   [output-id]))

(defn observer-id
  [output-id role trigger-id]
  (stable-node-id :relationship-observer output-id role trigger-id))

(defn- install-observer
  [network output-id role trigger-id sample]
  (let [id (observer-id output-id role trigger-id)]
    (if (contains? (net/net-env network) id)
      [id network]
      (nb/install-propagator
       network
       (p:observe-network id trigger-id output-id sample)))))

(defn- cell-node-keys
  [network node-keys]
  (filter #(cell/cell? (node-entry network %)) node-keys))

(defn p:observe-roots
  "Install ordinary observer propagators for current top-level cells.

  Returns `[initial-observer-id updated-network]`.  The caller schedules newly
  installed observer ids in the same way as other installed propagators."
  ([output-id]
   (p:observe-roots nil output-id))
  ([observed-cell-ids output-id]
   (fn [network]
    (let [prepared (nb/ensure-cell network output-id)
          select (if (seq observed-cell-ids)
                   (connected-root-node-keys observed-cell-ids)
                   #(root-node-keys % #{output-id}))
          sample (snapshot-of select)
          roots (cell-node-keys prepared (select prepared))
          [_ watched]
          (reduce (fn [[ids current] [_path cell-id]]
                    (let [[id next-network]
                          (install-observer current output-id :root cell-id sample)]
                      [(conj ids id) next-network]))
                  [[] prepared]
                  roots)]
      (install-observer watched output-id :initial nil sample)))))

(defn- observer-declaration
  [output-id role trigger-id sample]
  (let [id (observer-id output-id role trigger-id)]
    (patch/declare-propagator
     id observer-name
     (if trigger-id [trigger-id] [])
     [output-id]
     (fn [_inputs _outputs network]
       [(message output-id (sample network))]))))

(defn- declaration-observers
  [activation-result output-id sample]
  (let [{:keys [messages effects]}
        (patch/normalize-activation-return activation-result)
        patches (concat effects messages)]
    (->> patches
         (keep (fn [declaration]
                 (case (:op declaration)
                   :network/declare-cell
                   (observer-declaration output-id :child (:id declaration) sample)

                   :network/declare-propagator
                   (observer-declaration output-id :topology nil sample)

                   :compound/accessor-declaration
                   (observer-declaration output-id :compound-child
                                         (:id declaration) sample)

                   nil)))
         distinct
         vec)))

(defn observe-declarations
  "Activation-result transform that observes topology declared by one parent."
  [output-id sample]
  (fn [activation-result _inputs _outputs _network]
    (let [{:keys [messages effects]}
          (patch/normalize-activation-return activation-result)]
      {:effects (into (vec effects)
                      (declaration-observers activation-result output-id sample))
       :messages (vec messages)})))

(defn- decorate-parent
  [network parent-key output-id sample]
  (let [[path parent-id] parent-key]
    (if (not= [:outer] path)
      (throw (ex-info "relationship observer can decorate only an outer parent"
                      {:parent parent-key}))
      (let [parent (net/network-lookup-propagator network parent-id)]
        (if-not (prop/prop? parent)
          (throw (ex-info "relationship observer parent is not a propagator"
                          {:parent parent-key}))
          (net/assoc-net-prop
           network parent-id
           (prop/prop
            (prop/prop-name parent)
            (prop/compose-activation
             (prop/prop-f parent)
             (observe-declarations output-id sample)))))))))

(defn p:observe-children
  "Decorate one outer parent and observe its current immediate child cells."
  [parent-key output-id]
  (fn [network]
    (let [prepared (nb/ensure-cell network output-id)
          select (child-node-keys parent-key)
          sample (snapshot-of select)
          decorated (decorate-parent prepared parent-key output-id sample)
          children (disj (select decorated) parent-key)
          [_ watched]
          (reduce (fn [[ids current] [path cell-id :as child-key]]
                    (if (and (= [:outer] path)
                             (cell/cell? (node-entry current child-key)))
                      (let [[id next-network]
                            (install-observer current output-id :child cell-id sample)]
                        [(conj ids id) next-network])
                      [ids current]))
                  [[] decorated]
                  children)]
      (install-observer watched output-id [:initial parent-key] nil sample))))
