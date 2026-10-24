(ns graph.lain-editor.inspection.semantic-scene
  "Pure CPS compilation from captured semantics to renderer-neutral scenes."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-common.cps :as cps]
            [propagators.semantic-trace.capture :as capture]))

(defn scope-expression?
  [expression]
  (= :semantic-scene/scope (:semantic-scene/type expression)))

(defn node-expression?
  [expression]
  (= :semantic-scene/node (:semantic-scene/type expression)))

(defn- diagnostic
  [candidate]
  (cond
    (value/nothing? candidate) {:kind :missing :message "Semantic information is unavailable"}
    (value/contradiction? candidate) {:kind :conflict
                                      :message "Semantic information conflicts"
                                      :provenance (value/contradiction-provenance candidate)}
    :else nil))

(defn compile-node
  [_compile-k state expression k]
  (let [{:keys [id label kind value nested-scope]} expression
        visible (cond-> {:id id :label label :kind kind}
                  (contains? expression :value) (assoc :value value)
                  nested-scope (assoc :nested-scope nested-scope)
                  (diagnostic value) (assoc :diagnostic (diagnostic value)))]
    (cps/continue k state visible)))

(defn compile-scope
  [compile-k state expression k]
  (let [{:keys [id graph nested-scopes partial?]} expression
        nodes (mapv (fn [[node-id label]]
                      {:semantic-scene/type :semantic-scene/node
                       :id node-id
                       :label label
                       :kind (get (:node-kinds graph) node-id :cell)
                       :value (get (:values graph) node-id value/nothing)
                       :nested-scope (get nested-scopes node-id)})
                    (sort-by (comp pr-str key) (:nodes graph)))]
    (cps/compile-args
     compile-k state nodes
     (fn [next-state compiled-nodes]
       (cps/continue
        k next-state
        {:scene/type :semantic-scene/scene
         :scene/scope id
         :scene/nodes compiled-nodes
         :scene/edges (vec (:edges graph))
         :scene/partial? (boolean partial?)
         :scene/diagnostics (vec (:semantic-capture/diagnostics graph))})))))

(defn make-scene-compiler
  [scope? compile-scope compile-node]
  (cps/make-compiler
   (cps/compose-rules
    (cps/on scope? compile-scope)
    compile-node)))

(def scene-compiler
  (make-scene-compiler scope-expression? compile-scope compile-node))

(def preview-size [200 120])

(defn layout-request
  "Canonical layout-provider input shared by detail and preview renderers."
  [scene measured-sizes]
  {:scope (:scene/scope scene)
   :nodes (mapv (fn [{:keys [id label]}]
                  {:id id :label label :size (get measured-sizes id [44 44])})
                (:scene/nodes scene))
   :edges (:scene/edges scene)})

(defn scale-preview
  "Scale canonical detail coordinates into a compact viewport without changing topology."
  [positions]
  (if (empty? positions)
    {}
    (let [[width height] preview-size
          xs (map (comp first val) positions)
          ys (map (comp second val) positions)
          left (apply min xs)
          right (apply max xs)
          top (apply min ys)
          bottom (apply max ys)
          span-x (max 1 (- right left))
          span-y (max 1 (- bottom top))
          scale (min (/ (- width 16) span-x) (/ (- height 16) span-y))]
      (into {}
            (map (fn [[id [x y]]]
                   [id [(+ 8 (* scale (- x left)))
                        (+ 8 (* scale (- y top)))]]))
            positions))))

(defn- graph-scope
  [graph scope-id partial?]
  {:semantic-scene/type :semantic-scene/scope
   :id scope-id
   :graph graph
   :nested-scopes (or (:semantic-capture/nested-scopes graph) {})
   :partial? partial?})

(defn compile-scene
  "Compile one semantic scope. Legacy graphs are explicit partial scenes."
  ([semantic] (compile-scene semantic :root))
  ([semantic scope-id]
   (let [captured? (capture/fragment? semantic)
         graph (if captured?
                 (capture/scope semantic scope-id)
                 (when (= :root scope-id) semantic))]
     (if graph
       (second (scene-compiler {} (graph-scope graph scope-id
                                             (or (not captured?)
                                                 (some #(= :missing (:kind %))
                                                       (:semantic-capture/diagnostics graph))))))
       {:scene/type :semantic-scene/scene
        :scene/scope scope-id
        :scene/nodes []
        :scene/edges []
        :scene/partial? true
        :scene/diagnostics [{:kind :missing
                             :message "This semantic scope was not captured"}]}))))
