(ns propagators.compiler-2.runtime.retained-network
  "Consumer-owned retained callback state. No scheduler or patch extensions."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.one-time-network :as once]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.graph :as graph]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.observer :as observers]
            [propagators.runner :as runner]))

(def storage-scope [::storage])

(defn stored [strongest]
  (if (value/nothing? strongest)
    nil
    (let [base (datum/layer-value strongest :base)]
      (if (evidence/evidence-set? base) (evidence/strongest base) base))))

(defn publish
  ([parent id base] (publish parent id base :active))
  ([parent id base status]
   (message id (collection/content
                 (publication/next-source-datum
                   id (net/network-cell-strongest parent id) base status)))))

(defn snapshot-view
  "Remove every retained observer's storage values before freezing or capturing."
  [parent]
  (let [parent (declarations/discard parent)
        ids (vals (get (net/network-dict-entry parent gur/name-bindings-key) storage-scope))]
    (-> (reduce (fn [network id]
                  (if-let [entry (get (net/net-env network) id)]
                    (net/assoc-net-cell network id (assoc entry :content value/nothing :strongest value/nothing))
                    network)) parent ids)
        (net/net-with-dict (dissoc (net/net-dict-or-empty parent) observers/observers-key)))))

(defn isolated-base [parent]
  (let [parent (snapshot-view parent)
        cells (into {} (filter (fn [[_ entry]] (cell/cell? entry))) (net/net-env parent))]
    (gur/vm-net (net/net (zipmap (keys cells) (repeat (graph/blank-node)))
                         cells (net/net-dict-or-empty parent)))))

(defn compilation-key [parent callback state]
  (let [callable (net/network-cell-strongest parent callback)
        captures (or (obj/slot-value state :capture/ids) #{})]
    {:declaration (application/callable-declaration callable)
     :lexical-env (get callable application/captured-environment-key)
     :captures (into {} (map (fn [id]
                              [id (application/callable-declaration
                                    (net/network-cell-strongest parent id))])) captures)}))

(defn reusable? [state key]
  (and state (= (obj/slot-value state :compilation/hash) (hash key))
       (= (obj/slot-value state :compilation/key) key)))

(defn advance [network updates]
  (let [[tasks prepared] (core/eval-activation-result updates network)]
    (runner/completed-network (runner/run-network tasks prepared))))

(defn- refresh-captures [private parent captured]
  (let [updates (for [id captured
                      :let [entry (get (net/net-env parent) id)]
                      :when (and (cell/cell? entry)
                                 (not= (cell/cell-content entry)
                                       (net/network-cell-content private id)))]
                  (message id (cell/cell-content entry)))]
    (advance private updates)))

(defn- projected-result [private result-id]
  ;; This is the explicit finite observation boundary, not computation dispatch.
  (let [view (net/net-with-env private
               (into {} (map (fn [[id entry]]
                               [id (if (cell/cell? entry)
                                     (assoc entry :strongest (data/payload (cell/cell-strongest entry)))
                                     entry)])) (net/net-env private)))]
    (once/project-trace (once/compound-projection) view result-id)))

(defn step
  "Return the next layered state and sample. Existing state is never mutated."
  [parent state callback context root arguments owner request]
  (let [key (compilation-key parent callback state)
        reuse (reusable? state key)
        id #(gur/stable-node-id [::private owner %])
        snapshot-id (id :snapshot) root-id (id :root) result-id (id :result)
        captured (if reuse (obj/slot-value state :parent/ids) (set (keys (net/net-env (isolated-base parent)))))
        base (if reuse
               (refresh-captures (obj/slot-value state :base) (snapshot-view parent) captured)
               (-> (isolated-base parent)
                   (builder/ensure-cell snapshot-id)
                   (builder/install-cell root-id root root)
                   (builder/ensure-cell result-id)))
        frozen (observation/frozen-snapshot (snapshot-view parent) {:output owner})
        updates [(publish base snapshot-id frozen)]
        effects (if reuse updates
                  (into [(gur/apply-closure-effect callback
                           (into [context snapshot-id root-id] arguments) result-id)] updates))
        completed (advance base effects)
        callable-captures (into #{} (filter (fn [id]
                                            (and (seq (:outputs (get (net/net-graph completed) id)))
                                                 (application/compiler-callable?
                                                   (net/network-cell-strongest parent id))))) captured)
        next-key (compilation-key parent callback
                   (obj/compound-object {:capture/ids callable-captures}))]
    {:value (projected-result completed result-id)
     :state (obj/compound-object
              {:base completed :compilation/hash (hash next-key) :compilation/key next-key
               :parent/ids captured :capture/ids callable-captures :observation/request request})
     :compiled? (not reuse)}))
