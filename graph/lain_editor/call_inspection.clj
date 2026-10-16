(ns graph.lain-editor.call-inspection
  "Bounded, read-only call occurrence summaries from actual runtime topology."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.semantic-trace.facts :as semantic-facts]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]))

(defn callable-inputs [v]
  (let [declaration (application/callable-declaration (data/payload v))]
    (when (closure/closure-info? declaration) (closure/closure-inputs declaration))))

(defn value-text [v]
  (let [v (data/payload v) parameters (callable-inputs v)]
    (cond
      (value/nothing? v) "waiting"
      (value/contradiction? v) "contradiction"
      parameters (str "network " parameters)
      :else (let [s (binding [*print-length* 6 *print-level* 2] (pr-str v))]
              (if (> (count s) 90) (str (subs s 0 89) "…") s)))))

(defn- read-cell [network id]
  (value-text (net/network-cell-strongest network id)))

(defn- callable [network app]
  (data/payload (net/network-cell-strongest network (:operator-id app))))

(defn- callable-key [network app]
  (select-keys (callable network app) [application/declaration-id-key application/captured-environment-key]))

(defn- closure-call? [network app]
  (closure/closure-info? (application/callable-declaration (callable network app))))

(defn- children-by-call [network path relations]
  (let [apps (semantic-facts/applications network path)
        eligible (set (map :id (filter :eligible? apps)))
        scopes (semantic-facts/scope relations apps nil [])]
    (reduce (fn [index [node parent]]
              (if (eligible node)
                (update index (second parent) (fnil conj #{}) (second node)) index))
            {} (:owner scopes))))

(defn- call-label [network path id app]
  (let [label (get-in (observer/snapshot network [[[:outer] id]]) [:nodes [[:outer] id]])]
    (if (= :propagator/anonymous label)
      "selected branch"
      (str label))))

(defn- selection [network app]
  (let [declaration (application/callable-declaration (callable network app))]
    (when (= 'if (operator/operator-name declaration))
      (let [condition (data/payload (net/network-cell-strongest network (first (:argument-ids app))))]
        {:condition (value-text condition)
         :branch (cond
                   (value/contradiction? condition) "contradiction"
                   (value/unusable? condition) "pending"
                   condition "then"
                   :else "else")}))))

(defn- returned-input [network app names]
  (let [sources (:sources (dataflow/return-flow (dataflow/copy-predecessors network (:application-id app)) (:result-id app)))]
    (some (fn [name]
            (let [id (env/resolve-binding-id network (:frame-id app) name)]
              (when (= #{id} sources)
                {:name (str name) :value (read-cell network id)}))) names)))

(defn- summary [network path id app depth]
  (let [declaration (application/callable-declaration (callable network app))
        names (closure/closure-inputs declaration)
        interface (ports/application-ports network app)]
    {:id (pr-str [path id]) :label (call-label network path id app) :depth depth
     :arguments (mapv (fn [position input]
                        {:name (str (nth names position (symbol (str "arg" (inc position)))))
                         :value (read-cell network input)})
                      (range) (:inputs interface))
     :return (read-cell network (:return-id interface))
     :members (mapv #(read-cell network %) (:member-outputs interface))
     :returnsInput (when (closure/closure-info? declaration) (returned-input network app names))}))

(defn inspect-call
  "Follow structural ownership, stopping at each child application. Rows describe
  nesting, never scheduler order. Limits are reported explicitly. No Net writes."
  ([network root] (inspect-call network root false))
  ([network [path root] chase?]
  (let [owner (observer/network-at-path network path)
        index (into {} (map (fn [app] [(gur/stable-node-id [(:application-id app) :apply-prop]) app]))
                    (application/application-topologies owner))
        root-app (get index root)
        children (children-by-call owner path (net/net-relationship network))]
    (when root-app
      (let [callee (callable-key owner root-app)]
        (loop [pending [[root 0]] seen #{} rows []]
          (if (and (seq pending) (< (count rows) 64))
            (let [[id depth] (peek pending) remaining (pop pending) app (get index id)]
              (if (seen id)
                (recur remaining seen rows)
                (let [child-ids (sort-by pr-str (get children id #{}))
                      nested (filter #(closure-call? owner (get index %)) child-ids)
                      operations (if chase? (mapv (fn [child]
                                         (let [child-app (get index child)]
                                           (assoc (summary owner path child child-app (inc depth))
                                                  :selection (selection owner child-app))))
                                       (remove (set nested) child-ids)) [])
                      row (assoc (summary owner path id app depth)
                                 :sameCallable (= callee (callable-key owner app))
                                 :operations operations :hasChildren (boolean (seq child-ids)))]
                  (recur (if (and chase? (< depth 32)) (into remaining (map #(vector % (inc depth)) (reverse nested))) remaining)
                         (conj seen id) (conj rows (assoc row :depthLimited (boolean (and chase? (>= depth 32) (seq nested)))))))))
            {:occurrences rows :chased (boolean chase?)
             :truncated (boolean (or (seq pending) (some :depthLimited rows)))})))))))
