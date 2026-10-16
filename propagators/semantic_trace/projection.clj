(ns propagators.semantic-trace.projection
  "One-scope semantic graph. Clojure extracts evidence; Leapfrog decides policy."
  (:require [clojure.set :as set]
            [propagators.dataflow-projection :as normalization]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.bindings :as bindings]
            [propagators.semantic-trace.ports :as ports]
            [propagators.semantic-trace.directions :as directions]
            [propagators.semantic-trace.facts :as facts]
            [propagators.semantic-trace.logic :as logic]))

(defn- named? [label]
  (and (some? label) (not (ids/node-id? label))
       (not (contains? #{:cell/anonymous "cell"} label))))

(defn- renamed-app [aliases app]
  (-> (normalization/rename-application aliases app)
      (update :operator (partial normalization/canonical aliases))))

(defn- context [network operational]
  (when (= :mixed (:dataflow/scope operational))
    (throw (ex-info "A semantic projection requires one explicit scope" {})))
  (let [opened (:dataflow/scope operational)
        opened (if (= :root opened) nil opened)
        paths (cond-> (set (map first (keys (:nodes operational))))
                opened (conj (first opened)))
        paths (if (seq paths) paths #{[:outer]})
        discovered (mapcat #(facts/applications (observer/network-at-path network %) %) paths)
        ancestors (facts/enclosing-applications (net/net-relationship network) discovered
                                              (:dataflow/observer-outputs operational))
        enclosing (into (:dataflow/observer-applications operational #{})
                        (filter (set (map :id (filter :observable? discovered)))) ancestors)
        apps discovered
        records (mapcat #(facts/copies (observer/network-at-path network %) %) paths)
        scopes (facts/scope (net/net-relationship network) apps opened (map :id records))
        visible (set (map first (:visible scopes)))
        selected (filterv #(visible (:id %)) apps)]
    {:opened opened :scopes scopes :applications selected :observer-applications enclosing
     :copies (facts/visible-copies scopes records opened)}))

(defn- observation-context [graph operational applications seeds]
  (if (or (contains? operational :dataflow/observer-outputs)
          (contains? operational :dataflow/observer-applications))
    (assoc graph :dataflow/observer-applications applications :dataflow/seeds (set seeds))
    graph))

(defn- projection-facts [apps copies edge-function]
  (let [edges (mapcat (fn [app]
                        (map #(into [(:id app)] %) (remove (fn [[a b]] (= a b)) (edge-function app)))) apps)
        forward (mapcat (fn [app]
                          (if (contains? #{:forward :bidirectional} (:routing app))
                            (edge-function app) [])) apps)
        relays (concat forward (map (juxt :source :target) copies))]
    {:visible (set (map #(vector (:id %)) apps))
     :candidate-edge (set edges)
     :operator-use (set (map #(vector (:operator %) (:id %)) (filter #(= :application (:routing %)) apps)))
     :output (set (mapcat (fn [app] (map #(vector % (:id app)) (:outputs app))) apps))
     :copy-back (set (for [[source target] relays :when (not= source target)] [target source]))
     :contractible-copy (set (concat forward (map (juxt :source :target) (filter #(= :definition (:kind %)) copies))))
     :primary-result (set (map #(vector (:result %)) (filter #(= :application (:routing %)) apps)))}))

(defn- contraction [network evidence projected]
  (let [edges (:edge projected) outgoing (group-by first edges)
        nodes (set (mapcat identity edges)) labels (:nodes (observer/snapshot network nodes))
        source-cell? (fn [[path id]]
                       (contains? (get (net/network-dict-entry (observer/network-at-path network path)
                                                             patch/name-bindings-key)
                                       :compiler-2/source-cells {}) id))
        bound? (fn [[id label]] (and (not (source-cell? id)) (named? label)))
        classified (assoc (select-keys evidence [:contractible-copy :primary-result])
                          :named (set (for [entry labels :when (bound? entry)] [(key entry)]))
                          :unnamed (set (for [entry labels :when (not (bound? entry))] [(key entry)]))
                          :sole-successor (set (for [[source routes] outgoing :when (= 1 (count routes))]
                                                 [source (second (first routes))])))]
    (into {} (:alias (logic/run classified logic/contraction-rules)))))

(defn- select-component [edges seeds]
  (if (seq seeds)
    (let [facts {:edge (set edges) :seed (set (map vector seeds))
                 :adjacent (set (concat edges (map (comp vec reverse) edges)))}
          selected (logic/run facts logic/reachability-rules)]
      {:edges (vec (:selected-edge selected)) :nodes (set (map first (:reachable selected)))})
    {:edges edges :nodes (set (mapcat identity edges))}))

(defn explain
  "Executable evidence for a projection: EDB, derived relations, aliases, graph.
  Snapshot-local closed-world steps are limited to transparency, roots and degree."
  [network operational edge-function]
  (let [{:keys [opened scopes applications copies observer-applications]} (context network operational)
        initial-aliases (:dataflow/aliases operational)
        apps (mapv (partial renamed-app initial-aliases) applications)
        records (mapv (fn [copy] (-> copy (update :source (partial normalization/canonical initial-aliases))
                                               (update :target (partial normalization/canonical initial-aliases)))) copies)
        evidence (projection-facts apps records edge-function)
        derived (logic/run evidence logic/projection-rules)
        aliases (contraction network evidence derived)
        edges (normalization/rename-edges aliases (:edge derived))
        seeds (map #(normalization/canonical aliases (normalization/canonical initial-aliases %)) (:dataflow/seeds operational))
        selected (select-component edges seeds)
        operations (into {} (map (juxt :id identity)) (filter #(= :application (:routing %)) apps))
        call-labels (:nodes (observer/snapshot network (keys operations)))
        cells (set/difference (:nodes selected) (set (keys operations)))
        sampled (observer/snapshot network cells)
        labels (into {} (map (fn [[id label]]
                              [id (if (named? label) label
                                    (let [v (get (:values sampled) id)]
                                      (if (or (number? v) (string? v) (boolean? v)) (pr-str v) "cell")))])) (:nodes sampled))
        graph (trace/graph-union
               {:nodes (merge labels (select-keys call-labels (:nodes selected)))
                :node-kinds (merge (zipmap cells (repeat :cell))
                                   (zipmap (set/intersection (:nodes selected) (set (keys operations))) (repeat :propagator)))
                :values (select-keys (:values sampled) cells)
                :contents (select-keys (:contents sampled) cells) :edges (:edges selected)})]
    {:edb evidence :derived derived :ownership scopes :aliases aliases
     :graph (observation-context
             (assoc graph :dataflow/scope (or opened :root) :dataflow/projection :leapfrog)
             operational observer-applications seeds)}))

(defn project [network operational edge-function]
  (:graph (explain network operational edge-function)))

(defn- network-app-edges [app edge-function]
  (if (= :application (:routing app))
    (edge-function app)
    (vec (mapcat (fn [[source target]]
                   [[source (:id app)] [(:id app) target]])
                 (edge-function app)))))

(defn- network-evidence [apps copies edge-function]
  (let [base (projection-facts apps copies edge-function)
        boundaries (filter :boundary? copies)
        candidates (concat
                    (mapcat (fn [app]
                              (map #(into [(:id app)] %) (network-app-edges app edge-function))) apps)
                    (mapcat (fn [{:keys [id source target]}]
                              [[id source id] [id id target]]) boundaries))]
    (assoc base
           :visible (into (:visible base) (map (comp vector :id)) boundaries)
           :candidate-edge (set candidates)
           :copy-node (set (map (juxt :id :source :target) (remove :boundary? copies))))))

(defn explain-network
  "One-scope bipartite transformations: naming a private return adds no operation.
  Explicit routing and independently named cells retain their own identities."
  [network operational edge-function]
  (let [{:keys [opened scopes applications copies observer-applications]} (context network operational)
        returns (if opened
                  (facts/body-return-copies (observer/network-at-path network (first opened))
                                           (first opened) opened)
                  [])
        copies (vec (distinct (concat copies returns)))
         evidence (network-evidence applications copies edge-function)
         derived (logic/run evidence logic/network-rules)
         port-view (ports/explain applications copies (:edge derived))
         binding-view (bindings/explain network copies (:edges port-view))
         raw-aliases (merge (:aliases port-view) (:aliases binding-view))
         aliases (update-vals raw-aliases (partial normalization/canonical raw-aliases))
         seeds (map (partial normalization/canonical aliases) (:dataflow/seeds operational))
         selected (select-component (:edges binding-view) seeds)
        sampled (observer/snapshot network (:nodes selected))
        copy-labels (into {} (map (fn [{:keys [id kind]}]
                                    [id (case kind :definition '-> :return 'copy)])) copies)
        labels (into {} (map (fn [[id label]] [id (if (named? label) label "cell")])) (:nodes sampled))
        graph (trace/graph-union
                (assoc sampled :nodes (merge labels (select-keys copy-labels (:nodes selected)))
                               :node-aliases (into {} (for [[source target] aliases
                                                            :when (contains? (:nodes selected) target)]
                                                        [source #{target}]))
                               :edges (:edges selected)))]
    {:edb evidence :derived derived :ownership scopes :aliases aliases :bindings binding-view
     :ports port-view
     :graph (directions/annotate
             (observation-context
              (assoc graph :dataflow/scope (or opened :root)
                           :dataflow/projection :leapfrog :dataflow/representation :network)
              operational observer-applications seeds))}))

(defn project-network [network operational edge-function]
  (:graph (explain-network network operational edge-function)))
