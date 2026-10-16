(ns graph.lain-editor.inspection
  "Explicit projections of real cells and the existing relationship dataflow graph."
  (:require [clojure.string :as str]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.call-inspection :as calls]
            [graph.lain-editor.retained-inspection :as retained-inspection]
            [graph.lain-editor.value-labels :as value-labels]
            [graph.lain-editor.value-grid :as value-grid]
            [graph.lain-editor.structural-value :as structure]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.one-time-network :as projection]
            [propagators.datastructures.compound-object :as compound]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.network-history :as history]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.directions :as directions]
            [propagators.semantic-trace.modifiers :as modifiers]))

(defn printed [x]
  (let [text (binding [*print-length* 30 *print-level* 5] (pr-str x))]
    (if (> (count text) 12000) (str (subs text 0 12000) "…") text)))

(defn render-value
  "Shared direct/replay value renderer. No reads from a live network."
  [candidate]
  (let [v (data/payload candidate)]
    (cond
      (history/replay? v) (str (count (history/field v :replay/frames)) " recorded observations")
      (trace/semantic-trace-graph? v) (str "semantic topology · " (count (:nodes v)) " nodes")
      (and (net/net? v) (not (compound/accessor-network? v))
           (empty? (compound/public-slot-keys v))) (str "network · " (count (net/net-env v)) " nodes")
      :else (value-labels/format-value v))))

(defn- display-candidate [network id]
  (let [v (data/payload (net/network-cell-strongest network id))]
    (if (and (compound/accessor-network? v) (not (datum/layer-present? v :base)))
       (projection/project-trace (projection/compound-projection) network id)
       v)))

(defn display-value [network id]
  (render-value (display-candidate network id)))

(defn- inspection-rows [v reader]
  (let [payload (data/payload v)]
    (if (or (history/replay? payload) (trace/semantic-trace-graph? payload))
      [{:key "" :value (render-value payload) :depth 0}]
      (value-grid/rows payload reader))))

(defn describe [network [path id :as key]]
  (let [owner (observer/network-at-path network path)
        entry (observer/node-entry network key)
        node (get (net/net-graph owner) id)
        relations (net/net-relationship network)]
    (when-not entry (throw (ex-info "Node no longer exists" {})))
    (cond-> {:id (pr-str key) :kind "propagator" :name (str (prop/prop-name entry))
             :inputs (mapv pr-str (:inputs node)) :outputs (mapv pr-str (:outputs node))
             :parents (mapv pr-str (relationship/parents relations key))
             :children (mapv pr-str (relationship/children relations key))}
      (cell/cell? entry)
      (assoc :kind "cell" :name (str (cell/cell-name entry))
             :strongest (printed (cell/cell-strongest entry))
             :summary (render-value (cell/cell-strongest entry))
             :valueGrid (inspection-rows (cell/cell-strongest entry) (structure/network-reader owner))
             :contentGrid (inspection-rows (cell/cell-content entry) (structure/network-reader owner))
             :content (printed (cell/cell-content entry))))))

(defn- node-label [_graph _id label]
  (if (or (ids/node-id? label) (contains? #{nil :cell/anonymous "cell"} label))
    "cell"
    (str label)))

(defn- reference-token [id]
  (let [cell-id (if (vector? id) (last id) id)]
    (if (ids/node-id? cell-id)
      (str "cell/" (:uuid cell-id))
      nil)))

(defn- reference-tokens [graph id]
  (vec (distinct (keep reference-token
                       (cons id (for [[source targets] (:node-aliases graph)
                                      :when (contains? targets id)] source))))))

(defn graph-json
  ([graph] (graph-json graph nil))
  ([graph network]
  {:nodes (mapv (fn [[id label]]
                  (let [label (node-label graph id label)
                        captured (get (:values graph) id value/nothing)
                        rendered (render-value captured)
                        path (if (vector? id) (first id) [:outer])
                        reader (if network
                                 (structure/network-reader (observer/network-at-path network path))
                                 (structure/snapshot-reader graph path))]
                    (cond-> {:id (pr-str id) :label label
                             :kind (name (get (:node-kinds graph) id :cell))
                             :value (if (contains? (:values graph) id) rendered "")
                             :referenceTokens (reference-tokens graph id)}
                      (and (= :cell (get (:node-kinds graph) id :cell))
                           (contains? (:graph/modifiers graph) :cell-values))
                      (assoc :valueLines (vec (str/split-lines rendered))
                             :valueSections (value-labels/sections (data/payload captured))
                             :valueGrid (value-grid/rows (data/payload captured) reader))
                      (and (= :cell (get (:node-kinds graph) id :cell))
                           (contains? (:contents graph) id))
                      (assoc :contentGrid (value-grid/rows (data/payload (get (:contents graph) id)) reader)))))
                (sort-by (comp pr-str key) (:nodes graph)))
   :edges (mapv #(-> % (update :from pr-str) (update :to pr-str))
                (directions/connections graph))
   :aliases (into {} (map (fn [[source targets]]
                           [(pr-str source) (mapv pr-str (sort-by pr-str targets))])) (:node-aliases graph))
   :projection (some-> (:dataflow/projection graph) name)
   :representation (some-> (:dataflow/representation graph) name)
   :scope (let [scope (:dataflow/scope graph)]
            (cond (= :root scope) {:kind "root"}
                  (= :mixed scope) {:kind "mixed"}
                  (vector? scope) {:kind "child" :parent (pr-str scope)}
                  :else nil))}))

(defn semantic-graph [network id]
  (let [payload (data/payload (net/network-cell-strongest network id))]
    (if (trace/semantic-trace-graph? payload)
      payload
      (let [operational (assoc (observer/snapshot network ((observer/connected-root-node-keys [id]) network))
                               :dataflow/seeds #{[[:outer] id]})]
         (dataflow/dataflow-graph network operational)))))

(defn replay-json [payload]
  {:frames (mapv (fn [frame]
                   {:sequence (:sequence frame) :time (:time frame) :elapsedMs (:elapsedMs frame)
                    :graph (graph-json (:graph frame))
                    :provenance (printed (:source-support frame))})
                 (history/field payload :replay/frames))})

(defn resolve-node [network requested]
  ;; Match known runtime identities; never parse user-supplied tagged EDN.
  (or (some (fn [id]
              (let [key [[:outer] id]] (when (= requested (pr-str key)) key)))
            (keys (net/net-env network)))
      (throw (ex-info "Unknown node in this pane" {}))))

(defn inspect [editors {:keys [pane generation display parent node chase]}]
  (when-not (contains? #{nil true false} chase)
    (throw (ex-info "chase must be a boolean" {:chase chase})))
  (let [editor (editor/require-pane editors pane)
        _ (editor/require-generation editor generation)
        state @(:session editor)
        network (:program/net state)
        binding (symbol (if (= "@display" (:cell display)) "result" (or (:cell display) "result")))
        id (if (and (= "@display" (:cell display)) (display/mode network))
             display/cell-id
             (env/resolve-binding-id network (:program/env state) binding))]
    (when-not id (throw (ex-info (str "No binding named " binding) {})))
    (let [key [[:outer] id]
          result {:generation generation :cell (describe network key)
                  :value (display-value network id)
                  :status (cond
                            (value/nothing? (net/network-cell-strongest network id)) "waiting"
                            (value/contradiction? (net/network-cell-strongest network id)) "contradiction"
                            :else "ready")
                  :runtimeNodes (count (net/net-env network))}]
       (cond
         (history/replay? (data/payload (net/network-cell-strongest network id)))
         (let [timeline (replay-json (data/payload (net/network-cell-strongest network id)))]
           (assoc result :value (str (count (:frames timeline)) " recorded observations")
                         :replay timeline))
         node (let [selected (resolve-node network node)]
               (assoc result :selected (describe network selected)
                             :call (calls/inspect-call network selected (true? chase))))
        parent (assoc result :graph
                      (graph-json (modifiers/inherit
                                   (if (= :network (:dataflow/representation (semantic-graph network id)))
                                     (retained-inspection/child-graph (:session editor) (resolve-node network parent))
                                     (dataflow/child-dataflow-graph network (resolve-node network parent)))
                                   (semantic-graph network id)) network))
        (= "graph" (:mode display))
        (let [direction (keyword (:direction display))
              graph (semantic-graph network id)
              graph-value? (trace/semantic-trace-graph?
                            (data/payload (net/network-cell-strongest network id)))]
          (when-not (contains? #{:upstream :downstream :both} direction)
            (throw (ex-info "Unknown trace direction" {})))
          (assoc result :graph
                 (graph-json (if graph-value?
                               graph
                               (trace/trace-graph graph {:node key :direction direction})) network)))
        :else (assoc result :valueGrid
                     (value-grid/rows (data/payload (net/network-cell-strongest network id))
                                      (structure/network-reader network)))))))
