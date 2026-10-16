(ns propagators.semantic-trace.facts
  "Read-only semantic EDB adapter. Identities stay path-qualified; values are opaque."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
            [propagators.compiler-2.runtime.topology-effects :as topology]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.semantic-trace.logic :as logic]))

(def instrumentation #{'xr:io 'io:xr 'display 'relationship:dataflow 'relationship:roots 'relationship:semantic 'relationship:roots-semantic 'relationship:children 'relationship:with-values 'relationship:caller-view
                       'observe-network 'observe-network-retained 'semantic-snapshot 'traverse
                       'history:record 'history:between 'history:empty 'history:append})

(defn routing [network app]
  (let [callable (application/callable-declaration
                  (net/network-cell-strongest network (:operator-id app)))]
    (case (operator/operator-name callable)
      -> :forward
      <-> :bidirectional
      :application)))

(defn applications [network path]
  (let [topology-results (topology/topology-result-ids network)]
    (mapv (fn [app]
            (let [interface (ports/application-ports network app)
                  role (routing network app)
                  operator-value (net/network-cell-strongest network (:operator-id app))
                  name (operator/operator-name (application/callable-declaration operator-value))
                  observable? (not (or (instrumentation name)
                                       (and (= :forward role) (contains? topology-results (first (:argument-ids app))))))
                  qualify #(vector path %)]
              {:id (qualify (gur/stable-node-id [(:application-id app) :apply-prop]))
               :application-id (:application-id app) :routing role
               :observable? observable?
               :eligible? (and observable? (not (value/unusable? operator-value)))
               :inputs (mapv qualify (:inputs interface)) :outputs (mapv qualify (:outputs interface))
               :operator (qualify (:operator-id app)) :arguments (mapv qualify (:argument-ids app))
               :result (qualify (:result-id app))}))
          (application/application-topologies network))))

(defn copies [network path]
  (vec (mapcat
        (fn [[id node]]
          (let [entry (net/network-env-lookup network id)
                name (when (prop/prop? entry) (prop/prop-name entry))]
            (cond
              (and (vector? name) (= :compiler-2/definition (first name)))
              (for [source (:inputs node) target (:outputs node) :when (not= source target)]
                {:kind :definition :id [path id] :source [path source] :target [path target]})
              (and (vector? name) (= :compiler-2/boundary (first name))
                   (vector? (second name)) (contains? #{:caller :defined-input} (second (second name))))
              (for [source (:inputs node) target (:outputs node) :when (not= source target)]
                {:kind :return :app [path (gur/stable-node-id [(first (second name)) :apply-prop])]
                 :id [path id] :source [path source] :target [path target]})
              :else [])))
        (net/net-graph network))))

(defn root-seeds
  "Resolve seeds through declared input and definition copies to caller cells."
  [network seed-ids]
  (let [definitions (map (juxt (comp second :target) (comp second :source))
                         (filter #(= :definition (:kind %)) (copies network [:outer])))
        inbound (for [[id node] (net/net-graph network)
                      :let [entry (net/network-env-lookup network id)
                            name (when (prop/prop? entry) (prop/prop-name entry))]
                      :when (and (vector? name) (= 4 (count name))
                                 (= :compiler-2/application (first name))
                                 (= :inbound (nth name 2)))
                      source (:inputs node) target (:outputs node)]
                  [target source])
        relationships (net/net-relationship network)
        predecessors (into #{} (filter (fn [[target _source]]
                                         (seq (:inputs (get relationships [[:outer] target])))))
                           (concat definitions inbound))
        derived (logic/run {:return (set (map vector seed-ids)) :predecessor predecessors}
                           logic/return-rules)
        copied (set (map first predecessors))
        reached (set (map first (:return-reachable derived)))
        origins (set/difference reached copied)]
    (if (seq origins) origins reached)))

(defn- relevant-parents [relationships targets]
  ;; Lossless restriction for owner queries on targets: retain every ancestor,
  ;; without stopping at applications or otherwise deciding semantic visibility.
  (loop [pending (vec targets) seen #{} edges #{}]
    (if-let [node (peek pending)]
      (if (seen node)
        (recur (pop pending) seen edges)
        (let [parents (:inputs (get relationships node))]
          (recur (into (pop pending) parents) (conj seen node)
                 (into edges (map #(vector node %) parents)))))
      edges)))

(defn enclosing-applications [relationships apps targets]
  (let [targets (set targets)
        producers (set (map :id (filter #(some targets (:outputs %)) apps)))
        ancestors (map second (relevant-parents relationships (into targets producers)))]
    (set/intersection (set (map :id apps)) (into producers ancestors))))

(defn scope
  "Stratum 1: closest enclosing application through transparent ownership only.
  Root classification is an explicit closed-world anti-join over this snapshot."
  [relationships apps opened targets]
  (let [app-ids (set (map :id apps)) barriers (cond-> app-ids opened (conj opened))
        parents (relevant-parents relationships (into barriers targets))
        domain (into barriers (mapcat identity) parents)
        facts {:parent parents :application (set (map vector barriers))
               :transparent (set (map vector (set/difference domain barriers)))}
        owned (logic/run facts logic/ownership-rules)
        owned-apps (set (map first (:owner owned)))
        eligible (set (map :id (filter :eligible? apps)))
        classified (assoc owned :eligible (set (map vector eligible))
                                :root-call (set (map vector (set/difference eligible owned-apps)))
                                :root-view (if opened #{} #{[:root]})
                                :opened (if opened #{[opened]} #{}))]
    (logic/run classified logic/visibility-rules)))

(defn visible-copies [scopes records opened]
  (let [visible (set (map first (:visible scopes)))
        owners (reduce (fn [index [node app]] (update index node (fnil conj #{}) app)) {} (:owner scopes))]
    (filterv (fn [record]
               (case (:kind record)
                 :return (contains? visible (:app record))
                 :definition (let [enclosing (get owners (:id record) #{})]
                               (if opened (contains? enclosing opened) (empty? enclosing)))
                 (throw (ex-info "Unknown semantic copy role" {:copy record})))) records)))

(defn body-return-copies [network path parent]
  (let [app (first (filter #(= parent (:id %)) (applications network path)))]
    (if app
      (vec (for [[id node] (net/net-graph network)
                 :let [entry (net/network-env-lookup network id)
                       name (when (prop/prop? entry) (prop/prop-name entry))]
                 :when (and (vector? name) (= 4 (count name))
                            (= :compiler-2/application (first name))
                            (= (:application-id app) (second name))
                            (= :outbound (nth name 2)))
                 source (:inputs node) target (:outputs node)
                 :when (not= source target)]
             {:kind :return :boundary? true :id [path id]
              :source [path source] :target [path target]}))
      [])))
