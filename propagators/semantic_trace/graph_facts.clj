(ns propagators.semantic-trace.graph-facts
  "Observation-qualified graph facts. Every assertion uses the existing TTMS domain.
  Graph maps are immutable acquisition projections; modifiers never remove their facts."
  (:refer-clojure :exclude [derive])
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as ttms]))

(defn assertion-id [observation scope predicate tuple]
  [observation scope predicate (vec tuple)])

(defn assertions [graph] (::assertions graph {}))
(defn source-assertions [graph]
  (select-keys (assertions graph) (::source-ids graph #{})))
(defn premise-state [graph] (::states graph #{}))

(defn join-assertions [left right]
  (merge-with ttms/merge-content left right))

(defn assertion
  "An assertion payload with its existing TTMS justification and state."
  [base premises states]
  (ttms/content {:base base :support premises :premise-state states}))

(defn- fact [observation scope predicate tuple base premises]
  [(assertion-id observation scope predicate tuple)
   (assertion base (support/combine premises (datum/support-of base))
              (if (datum/layer-present? base :premise-state)
                (datum/layer-value base :premise-state) #{}))])

(defn from-graph
  "Acquire once. Repeated modifiers share the assertion table by identity.
  Legacy call-graph relations are included without flattening captured values."
  [observation scope graph premises]
  (if (contains? graph ::assertions)
    (do
      (when-not (= [observation scope] [(::observation graph) (::scope graph)])
        (throw (ex-info "Graph facts belong to another observation or scope" {})))
      graph)
    (let [entry (fn [predicate tuple base]
                  (fact observation scope predicate tuple base premises))
          claims (concat
                  [(entry :scope/available [scope] true)]
                  (map (fn [[id label]] (entry :node/label [id label] true)) (:nodes graph))
                  (map (fn [[id kind]] (entry :node/kind [id kind] true)) (:node-kinds graph))
                  (map (fn [[id _]] (entry :node/available [id] true)) (:values graph))
                  (map (fn [[id v]] (entry :node/value [id] v)) (:values graph))
                  (map (fn [[id v]] (entry :node/content [id] v)) (:contents graph))
                  (map #(entry :graph/edge % true) (:edges graph))
                  (mapcat (fn [[source targets]]
                            (map #(entry :node/alias [source %] true) targets))
                          (:node-aliases graph))
                  (mapcat (fn [[predicate tuples]]
                            (map #(entry predicate % true) tuples)) (:graph/facts graph)))]
      (let [table (reduce (fn [table [id content]]
                            (join-assertions table {id content}))
                          {} claims)]
        (assoc graph ::observation observation ::scope scope
                     ::assertions table ::source-ids (set (keys table)) ::states #{})))))

(defn fact-graph? [candidate]
  (and (map? candidate) (contains? candidate ::assertions)))

(defn observation [graph] (::observation graph))
(defn scope [graph] (::scope graph))

(defn source-projection [graph]
  (dissoc graph ::observation ::scope ::assertions ::source-ids ::states
          ::declarations ::derivations))

(defn join
  "Join one qualified scope. The caller supplies its established projection join."
  [left right join-projection]
  (when-not (= [(observation left) (scope left)]
               [(observation right) (scope right)])
    (throw (ex-info "Cannot join different graph-fact observations or scopes" {})))
  (let [projection (join-projection (source-projection left) (source-projection right))]
    (if (value/contradiction? projection)
      projection
      (assoc projection
             ::observation (observation left) ::scope (scope left)
             ::assertions (join-assertions (assertions left) (assertions right))
             ::source-ids (set/union (::source-ids left #{}) (::source-ids right #{}))
             ::states (support/join (premise-state left) (premise-state right))
             ::declarations (set/union (::declarations left #{}) (::declarations right #{}))
             ::derivations (merge-with set/union (::derivations left {}) (::derivations right {}))))))

(defn- merge-base [a b]
  (cond (= a b) a (value/nothing? a) b (value/nothing? b) a :else value/contradiction))

(defn- with-current-state [graph content]
  (ttms/merge-content content (ttms/content {:premise-state (premise-state graph)})))

(defn current [graph id]
  (when-let [content (get (assertions graph) id)]
    (ttms/strongest-value (with-current-state graph content) merge-base)))

(defn base-value [datum]
  (if (datum/layer-present? datum :base) (datum/layer-value datum :base) datum))

(defn- canonical-assertions [graph]
  (into {} (map (fn [[id content]] [id (with-current-state graph content)]))
        (assertions graph)))

(defn source-evidence
  "Acquisition input evidence, without the identity assigned to the observation.
  This is a reuse key, not cross-observation graph equivalence or an ordering."
  [graph]
  {:premise-state (premise-state graph)
   :assertions (into {} (map (fn [[id content]]
                              [(subvec id 1) (with-current-state graph content)]))
                     (source-assertions graph))
   :metadata (apply dissoc (source-projection graph)
                    [:nodes :edges :node-kinds :values :contents :node-aliases :graph/facts
                     :semantic-capture/nested-scopes :graph/modifiers])})

(defn equivalent? [left right]
  (and (= [(observation left) (scope left)] [(observation right) (scope right)])
       (= (premise-state left) (premise-state right))
       (= (canonical-assertions left) (canonical-assertions right))))

(defn subsumes?
  "Use TTMS join itself to define the information order, not visible tuple inclusion."
  [newer older]
  (and (= [(observation newer) (scope newer)] [(observation older) (scope older)])
       (= (premise-state newer) (support/join (premise-state older) (premise-state newer)))
       (= (canonical-assertions newer)
          (join-assertions (canonical-assertions older) (canonical-assertions newer)))))

(defn update-premise-state [graph states]
  (update graph ::states support/join states))

(defn claim
  "Add a qualified assertion. Independent supports remain independent TTMS observations.
  Adding membership to an explicitly complete predicate requires advancing or
  withdrawing its completeness evidence first."
  [graph predicate tuple base premises states]
  (let [id (assertion-id (observation graph) (scope graph) predicate tuple)
        updated (update-premise-state graph (support/join premises states))
        completeness (current updated (assertion-id (observation graph) (scope graph)
                                                    :scope/complete [predicate]))
        prior (current graph id)]
    (when (and completeness (true? (base-value completeness))
               (not (value/unusable? base))
               (or (nil? prior) (value/unusable? (base-value prior))))
      (throw (ex-info "Advance completeness before adding predicate membership"
                      {:predicate predicate :tuple tuple})))
    (-> updated
        (update ::assertions join-assertions {id (assertion base premises states)})
        (update ::source-ids (fnil conj #{}) id))))

(defn declarations [graph]
  (into #{}
        (keep (fn [[[_ _ predicate tuple :as id] _]]
                (when (and (= :view/declaration predicate)
                           (not (value/unusable? (base-value (current graph id)))))
                  (first tuple))))
        (assertions graph)))

(defn declare-view
  ([graph declaration] (declare-view graph declaration #{} #{}))
  ([graph declaration premises states]
   (-> graph
       (update ::declarations (fnil conj #{}) declaration)
       (claim :view/declaration [declaration] true premises states))))

(defn selected [graph]
  (into #{} (mapcat :selection) (filter #(= :select (:kind %)) (declarations graph))))

(defn enabled? [graph kind]
  (boolean (some #(= kind (:kind %)) (declarations graph))))

(defn relations
  "Project active assertions as an EDB. This does not discard their evidence."
  [graph]
  (reduce-kv
   (fn [result [_ _ predicate tuple] content]
     (let [v (ttms/strongest-value
              (ttms/merge-content content (ttms/content {:premise-state (premise-state graph)}))
                                 (fn [a b]
                                   (cond (= a b) a (value/nothing? a) b
                                         (value/nothing? b) a :else value/contradiction)))]
       (if (value/unusable? (if (datum/layer-present? v :base) (datum/layer-value v :base) v))
         result
         (update result predicate (fnil conj #{}) tuple))))
   {} (assertions graph)))

(defn source-relations [graph]
  (relations (assoc graph ::assertions (source-assertions graph))))

(defn current-graph
  "Interpret current facts as graph fields while retaining the complete fact table.
  Captured payloads are returned by reference, never recursively reconstructed."
  [graph]
  (if-not (fact-graph? graph)
    graph
    (let [edb (relations graph)
          mapping (fn [predicate]
                    (reduce (fn [result [id v]]
                              (update result id (fnil merge-base value/nothing) v))
                            {} (get edb predicate #{})))
          payloads (fn [predicate]
                     (reduce-kv
                      (fn [result [_ _ p [node] :as id] content]
                        (if (not= p predicate)
                          result
                          (let [v (current graph id) base (base-value v)]
                            (if (or (not (value/unusable? base))
                                    (value/contradiction? base)
                                    (seq (ttms/referenced-sources content))
                                    (contains? (get edb :node/available #{}) [node]))
                              (assoc result node
                                     (if (and (value/unusable? base)
                                              (or (value/contradiction? base)
                                                  (seq (ttms/referenced-sources content))))
                                       v base))
                              result))))
                      {} (assertions graph)))]
      (assoc graph
             :nodes (mapping :node/label) :node-kinds (mapping :node/kind)
             :values (payloads :node/value) :contents (payloads :node/content)
             :edges (vec (sort-by pr-str (get edb :graph/edge #{})))
             :node-aliases (reduce (fn [result [source target]]
                                    (update result source (fnil conj #{}) target))
                                  {} (get edb :node/alias #{}))
             :graph/facts edb))))

(defn- states-of [v]
  (if (datum/layer-present? v :premise-state)
    (datum/layer-value v :premise-state) #{}))

(defn- contributor [graph id]
  (when-not (and (vector? id) (= 4 (count id))
                 (= (observation graph) (first id)) (= (scope graph) (second id))
                 (contains? (assertions graph) id))
    (throw (ex-info "Unknown or foreign graph-fact contributor" {:contributor id})))
  (current graph id))

(defn- justifications [graph id]
  (let [v (contributor graph id)]
    (when (value/unusable? (base-value v))
      (throw (ex-info "Unavailable graph-fact contributor" {:contributor id})))
    (keep (fn [row]
            (let [candidate (ttms/strongest-value
                             (ttms/merge-content
                              (ttms/content row)
                              (ttms/content {:premise-state (states-of v)})) merge-base)]
              (when-not (value/unusable? (base-value candidate)) candidate)))
          (ttms/observations (get (assertions graph) id)))))

(defn contributor-evidence [graph id]
  (into #{} (keep (fn [[output contributors]] (when (= id output) contributors)))
        (get (relations graph) :fact/derivation #{})))

(defn- claim-derived [graph {:keys [predicate tuple base contributors]} premises states]
  (let [id (assertion-id (observation graph) (scope graph) predicate tuple)]
    (-> graph
        (claim predicate tuple base premises states)
        (claim :fact/derivation [id (set contributors)]
               (if (value/unusable? base) base true) premises states)
        (assoc ::source-ids (::source-ids graph)))))

(defn derive
  "Require explicit contributors and retain every independent justification.
  Captured values remain opaque; the adapter does not infer evidence from shape."
  [graph {:keys [contributors] :as receipt}]
  (when-not (seq contributors)
    (throw (ex-info "Derived graph fact requires contributor identities" {})))
  (let [choices (mapv #(justifications graph %) contributors)
        alternatives (reduce (fn [rows choices]
                               (for [row rows choice choices] (conj row choice)))
                             [[]] choices)]
    (reduce (fn [g inputs]
              (claim-derived g receipt
                             (apply support/combine (map datum/support-of inputs))
                             (apply support/join (map states-of inputs))))
            graph alternatives)))

(defn capture-value
  "Transport captured values, including meaningful supported bottom or conflict.
  Logical relationship derivation remains strict about usable contributors."
  [graph {:keys [predicate contributors] :as receipt}]
  (when-not (and (contains? #{:view/value :view/content} predicate) (seq contributors))
    (throw (ex-info "Captured values require a value predicate and contributors" {})))
  (let [inputs (mapv #(contributor graph %) contributors)
        unavailable (filter value/unusable? (map base-value inputs))]
    (if (empty? unavailable)
      (derive graph receipt)
      (let [conflicts (filter value/contradiction? unavailable)
            base (if (seq conflicts)
                   (value/contradiction-with-provenance
                    (into #{} (mapcat value/contradiction-provenance) conflicts))
                   value/nothing)]
        (claim-derived graph (assoc receipt :base base)
                       (apply support/combine (map datum/support-of inputs))
                       (apply support/join (map states-of inputs)))))))

(defn complete
  "Explicitly close a predicate at the supplied producer revisions."
  [graph predicate premises states]
  (claim graph :scope/complete [predicate] true premises states))

(defn derive-absence
  "Absence requires a current, explicitly supported completeness assertion.
  Positive rules never infer absence from a currently observed prefix."
  [graph {:keys [predicate tuple base absent completeness contributors] :or {base true}}]
  (let [[absent-predicate absent-tuple] absent
        expected (assertion-id (observation graph) (scope graph)
                               :scope/complete [absent-predicate])
        complete-value (when (= expected completeness) (current graph completeness))
        positive (current graph (assertion-id (observation graph) (scope graph)
                                              absent-predicate absent-tuple))]
    (when-not (and complete-value (true? (base-value complete-value)))
      (throw (ex-info "Absence requires current completeness evidence" {:absent absent})))
    (when (and positive (not (value/nothing? (base-value positive))))
      (throw (ex-info "Predicate membership is present or contradictory" {:absent absent})))
    (derive graph {:predicate predicate :tuple tuple :base base
                   :contributors (conj (set contributors) completeness)})))
