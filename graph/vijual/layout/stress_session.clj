(ns graph.vijual.layout.stress-session
  "Persistent layout sessions. Internal buffers are opaque and must not be edited."
  (:require [graph.vijual.layout.node-model :as model]
            [graph.vijual.layout.stress-numeric :as numeric]
            [graph.vijual.layout.stress-preferences :as preferences]
            [graph.vijual.layout.stress-topology :as topology]))

(defn- metrics [sources]
  (assoc numeric/empty-metrics :distance-sources-recomputed sources :changed-positions 0))

(defn- publish [before after]
  (let [old (into {} (map (juxt :id :position) (:normalized before)))
        changed (count (remove (fn [{:keys [id position]}]
                                 (= position (get old id))) (:normalized after)))]
    (assoc after :revision (inc (:revision before))
           :deltas (model/node-deltas (:nodes before) (:nodes after) (get-in after [:opts :id-fn]))
           :metrics (assoc (:metrics after) :changed-positions changed))))

(defn create-layout
  "Prepare an opaque, persistent session without relaxation. Public outputs are
  :nodes, :deltas ([[node -1|1] ...]), :revision and :metrics. Never edit internals.
  Nodes use the same adapters and units as layout-nodes. :pinned-ids fixes centers."
  ([nodes edges] (create-layout nodes edges {}))
  ([nodes edges opts]
   (let [opts (model/options opts)
         nodes (vec nodes)
         normalized (model/normalize nodes opts)
         ids (mapv :id normalized)
         _ (model/validate-edges! ids edges)
         _ (model/validate-pins! ids (:pinned-ids opts))
         edges (model/canonical-edges ids edges)
         prepared (topology/prepare ids edges opts nil)]
     {:nodes nodes :normalized normalized :opts opts :edges edges
      :pinned-ids (set (:pinned-ids opts)) :topology prepared
      :numeric (numeric/initial normalized) :revision 0
      :deltas (mapv #(vector % 1) nodes)
      :metrics (assoc (metrics (count ids)) :changed-positions (count ids))})))

(defn update-layout
  "Atomically apply :upsert-nodes, :remove-node-ids, :add-edges, :remove-edges,
  :pin-ids and :unpin-ids. Upserts replace complete nodes, including seed positions.
  Removal also removes incident edges and pins. Conflicts/invalid endpoints throw;
  the input session remains usable. No relaxation runs during an update."
  [state changes]
  (let [edited (model/apply-changes state changes)
        ids (mapv :id (:normalized edited))
        topology-changed? (or (not= ids (mapv :id (:normalized state)))
                              (not= (:edges edited) (:edges state)))
        prepared (if topology-changed?
                   (topology/prepare ids (:edges edited) (:opts state) (:topology state))
                   (:topology state))
        sources (if topology-changed? (get-in prepared [:paths :sources-recomputed]) 0)
        next-state (merge state edited
                          {:topology prepared :metrics (metrics sources)
                           :numeric (numeric/edit (:numeric state) (:normalized edited)
                                                  topology-changed?)})]
    (publish state next-state)))

(defn- materialize [state result]
  (let [positions (numeric/positions (:geometry result))
        with-position (get-in state [:opts :with-position])
        nodes (mapv (fn [node normalized [x y :as point]]
                      (let [[old-x old-y] (:position normalized)]
                        (if (and (== x old-x) (== y old-y))
                          node
                          (with-position node point))))
                    (:nodes state) (:normalized state) positions)
        normalized (model/normalize nodes (:opts state))]
    (doseq [[old new [x y]] (map vector (:normalized state) normalized positions)]
      (model/require-valid!
       (and (= (:id old) (:id new)) (= (:size old) (:size new))
            (== x (first (:position new))) (== y (second (:position new))))
       "with-position must preserve id/size and write the requested center" {:id (:id old)}))
    {:nodes nodes :normalized normalized}))

(defn advance-layout
  "Relax up to :iterations (session default 100), stopping when maximum center
  displacement <= :tolerance (default 0). Return a new session; earlier snapshots
  remain unchanged. Metrics count pair visits, including old/new sparse visits.
  Sizes remain soft constraints; pinned nodes influence free nodes but never move."
  ([state] (advance-layout state {}))
  ([state opts]
   (model/require-valid! (every? #{:iterations :tolerance} (keys opts))
                         "Unknown relaxation option" {:options opts})
   (let [budget (merge (select-keys (:opts state) [:iterations :tolerance]) opts)
         _ (model/validate-budget! budget)
         indices (zipmap (map :id (:normalized state)) (range))
         packed (assoc (get-in state [:topology :packed]) :preferences
                       (preferences/prepare (:normalized state) (:edges state) (:opts state)))
         [result work] (numeric/advance (:numeric state) packed
                                        (map indices (:pinned-ids state)) budget)
         next-state (merge state (materialize state result)
                           {:numeric result :metrics (merge (metrics 0) work)})]
     (publish state next-state))))
