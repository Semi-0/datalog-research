(ns propagators.visualizer
  "Port-neutral declarations and pure resolution for inspectable views."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.event :as event]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as relationship-observer])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def view-types #{:cell-window :cell-history :hierarchy :juxtapose})
(def directions #{:inputs :outputs})

(defn stable-id
  [& parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str parts) StandardCharsets/UTF_8))))

(defn view-declaration?
  [candidate]
  (and (map? candidate)
       (contains? view-types (:view/type candidate))
       (some? (:view/id candidate))))

(defn reference-set?
  [candidate]
  (and (map? candidate)
       (= :propagator-reference-set (:structure/type candidate))
       (contains? directions (:direction candidate))
       (some? (:cell-id candidate))))

(defn cell-window-declaration
  [view-id source-id]
  {:view/type :cell-window
   :view/id view-id
   :view/source-cell source-id})

(defn cell-history-declaration
  [view-id source-id history-id]
  {:view/type :cell-history
   :view/id view-id
   :view/source-cell source-id
   :view/history-cell history-id})

(defn reference-set
  [cell-id direction]
  (when-not (contains? directions direction)
    (throw (ex-info "propagator reference direction must be :inputs or :outputs"
                    {:direction direction})))
  {:structure/type :propagator-reference-set
   :cell-id cell-id
   :direction direction})

(defn hierarchy-declaration
  [view-id reference-cell-id]
  {:view/type :hierarchy
   :view/id view-id
   :reference-cell reference-cell-id})

(defn juxtapose-declaration
  [view-id child-cell-ids]
  (when (< (count child-cell-ids) 2)
    (throw (ex-info "juxtapose requires at least two views"
                    {:children (vec child-cell-ids)})))
  {:view/type :juxtapose
   :view/id view-id
   :view/children (vec child-cell-ids)
   :view/layout {:axis :horizontal}})

(defn- program-time
  [network]
  (let [dict (net/net-dict-or-empty network)]
    {:sample/epoch (long (or (:program/epoch dict) 0))
     :sample/tick (long (or (:runtime/commit-tick dict) 0))}))

(defn- history-sample
  [network source-id]
  (let [source (net/network-env-lookup network source-id)]
    (merge (program-time network)
           {:sample/content (cell/cell-content source)
            :sample/strongest (cell/cell-strongest source)})))

(defn- history-event
  [network source-id history-id]
  (let [{:sample/keys [epoch tick] :as sample}
        (history-sample network source-id)
        timestamp (+ (* epoch 1000000000) tick)
        source [:cell-history source-id (hash sample)]]
    (event/active-event history-id source timestamp sample)))

(defn- install-view-propagator
  [network id name inputs outputs activate]
  (let [prepared (reduce nb/ensure-cell network (concat inputs outputs))]
    (if (contains? (net/net-env prepared) id)
      [id prepared]
      (nb/install-propagator
       prepared
       (prop/construct-propagator id name activate inputs outputs)))))

(defn p:cell-window
  [source-id port-id]
  (fn [network]
    (let [prop-id (stable-id :visualizer :cell-window source-id port-id)
          declaration (cell-window-declaration prop-id source-id)]
      (install-view-propagator
       network prop-id :visualizer/cell-window [source-id] [port-id]
       (fn [_inputs _outputs _network]
         [(message port-id declaration)])))))

(defn p:cell-history
  [source-id port-id]
  (fn [network]
    (let [prop-id (stable-id :visualizer :cell-history source-id port-id)
          history-id (stable-id :visualizer :cell-history source-id port-id :history)
          declaration (cell-history-declaration prop-id source-id history-id)]
      (install-view-propagator
       (nb/ensure-cell network history-id)
       prop-id :visualizer/cell-history [source-id] [history-id port-id]
       (fn [_inputs _outputs current]
         [(message history-id (history-event current source-id history-id))
          (message port-id declaration)])))))

(defn p:propagator-references
  [cell-id direction port-id]
  (fn [network]
    (let [prop-id (stable-id :visualizer :propagator-references
                             cell-id direction port-id)
          declaration (reference-set cell-id direction)]
      (install-view-propagator
       network prop-id :visualizer/propagator-references [cell-id] [port-id]
       (fn [_inputs _outputs _network]
         [(message port-id declaration)])))))

(defn p:hierarchy
  [reference-cell-id port-id]
  (fn [network]
    (let [prop-id (stable-id :visualizer :hierarchy reference-cell-id port-id)
          declaration (hierarchy-declaration prop-id reference-cell-id)]
      (install-view-propagator
       network prop-id :visualizer/hierarchy [reference-cell-id] [port-id]
       (fn [_inputs _outputs _network]
         [(message port-id declaration)])))))

(defn p:juxtapose
  [child-cell-ids port-id]
  (fn [network]
    (let [child-cell-ids (vec child-cell-ids)
          prop-id (stable-id :visualizer :juxtapose child-cell-ids port-id)
          declaration (juxtapose-declaration prop-id child-cell-ids)]
      (install-view-propagator
       network prop-id :visualizer/juxtapose child-cell-ids [port-id]
       (fn [_inputs _outputs _network]
         [(message port-id declaration)])))))

(defn- propagator-reference
  [path propagator-id]
  {:structure/type :propagator-reference
   :network-path path
   :propagator-id propagator-id})

(defn resolve-reference-set
  [network declaration]
  (when-not (reference-set? declaration)
    (throw (ex-info "not a propagator reference set"
                    {:declaration declaration})))
  (let [cell-id (:cell-id declaration)
        node (graph/get-node (net/net-graph network) cell-id)
        adjacent (case (:direction declaration)
                   :inputs (graph/node-input-ids node)
                   :outputs (graph/node-output-ids node)
                   (throw (ex-info "unknown propagator reference direction"
                                   {:declaration declaration})))]
    (->> adjacent
         (filter #(prop/prop? (net/network-env-lookup network %)))
         (map #(propagator-reference [:outer] %))
         (sort-by (comp pr-str :propagator-id))
         vec)))

(defn- relationship-descendants
  [relationships roots]
  (loop [frontier (vec roots)
         visited #{}]
    (if-let [current (first frontier)]
      (if (contains? visited current)
        (recur (subvec frontier 1) visited)
        (recur (into (subvec frontier 1)
                     (sort-by pr-str (relationship/children relationships current)))
               (conj visited current)))
      visited)))

(defn- history-samples
  [history]
  (if (event/event-content? history)
    (->> (event/active-facts history)
         (map (fn [fact]
                (event/event-value fact)))
         (sort-by (juxt :sample/epoch :sample/tick hash))
         vec)
    []))

(declare resolve-view*)

(defn- resolve-view-cell
  [network cell-id visited]
  (when (contains? visited cell-id)
    (throw (ex-info "cyclic declarative view"
                    {:cell-id cell-id :visited visited})))
  (let [declaration (net/network-cell-strongest network cell-id)]
    (when (value/unusable? declaration)
      (throw (ex-info "view declaration cell is not ready"
                      {:cell-id cell-id})))
    (resolve-view* network declaration (conj visited cell-id))))

(defn- resolve-view*
  [network declaration visited]
  (when-not (view-declaration? declaration)
    (throw (ex-info "not a declarative view" {:declaration declaration})))
  (case (:view/type declaration)
    :cell-window
    (let [source-id (:view/source-cell declaration)
          source (net/network-env-lookup network source-id)]
      (when-not (cell/cell? source)
        (throw (ex-info "cell-window source is not a cell"
                        {:source-id source-id})))
      (assoc declaration
             :view/content (cell/cell-content source)
             :view/strongest (cell/cell-strongest source)))

    :cell-history
    (let [history-id (:view/history-cell declaration)
          history (net/network-cell-content network history-id)]
      (assoc declaration :view/samples (history-samples history)))

    :hierarchy
    (let [reference-id (:reference-cell declaration)
          reference-set-value (net/network-cell-strongest network reference-id)
          references (resolve-reference-set network reference-set-value)
          roots (mapv (fn [{:keys [network-path propagator-id]}]
                        (relationship/node-key network-path propagator-id))
                      references)
          selected (relationship-descendants (net/net-relationship network) roots)]
      (assoc declaration
             :view/roots roots
             :view/graph (relationship-observer/snapshot network selected)))

    :juxtapose
    (assoc declaration
           :view/resolved-children
           (mapv #(resolve-view-cell network % visited)
                 (:view/children declaration)))

    (throw (ex-info "unknown declarative view type"
                    {:declaration declaration}))))

(defn resolve-view
  [network declaration]
  (resolve-view* network declaration #{}))
