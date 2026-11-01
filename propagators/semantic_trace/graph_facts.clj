(ns propagators.semantic-trace.graph-facts
  "Observation-qualified graph facts. Every assertion uses the existing TTMS domain.
  Graph maps are immutable acquisition projections; modifiers never remove their facts."
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
    graph
    (let [entry (fn [predicate tuple base]
                  (fact observation scope predicate tuple base premises))
          claims (concat
                  (map (fn [[id label]] (entry :node/label [id label] true)) (:nodes graph))
                  (map (fn [[id kind]] (entry :node/kind [id kind] true)) (:node-kinds graph))
                  (map (fn [[id v]] (entry :node/value [id] v)) (:values graph))
                  (map (fn [[id v]] (entry :node/content [id] v)) (:contents graph))
                  (map #(entry :graph/edge % true) (:edges graph))
                  (mapcat (fn [[predicate tuples]]
                            (map #(entry predicate % true) tuples)) (:graph/facts graph)))]
      (let [table (into {} claims)]
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

(defn claim
  "Add a qualified assertion. Independent supports remain independent TTMS observations."
  [graph predicate tuple base premises states]
  (let [id (assertion-id (::observation graph) (::scope graph) predicate tuple)]
    (-> graph
        (update ::assertions join-assertions {id (assertion base premises states)})
        (update ::source-ids (fnil conj #{}) id)
        (update ::states support/join states))))

(declare relations)

(defn declarations [graph]
  (into #{} (map first) (get (relations graph) :view/declaration #{})))

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

(defn- states-of [v]
  (if (datum/layer-present? v :premise-state)
    (datum/layer-value v :premise-state) #{}))

(defn- justifications [graph id]
  (when-not (and (= (observation graph) (first id)) (contains? (assertions graph) id))
    (throw (ex-info "Unknown or foreign graph-fact contributor" {:contributor id})))
  (let [v (current graph id)]
    (when (value/unusable? (base-value v))
      (throw (ex-info "Unavailable graph-fact contributor" {:contributor id})))
    (keep (fn [row]
            (let [candidate (ttms/strongest-value
                             (ttms/merge-content
                              (ttms/content row)
                              (ttms/content {:premise-state (states-of v)})) merge-base)]
              (when-not (value/unusable? (base-value candidate)) candidate)))
          (ttms/observations (get (assertions graph) id)))))

(defn contributor-evidence [graph id] (get (::derivations graph {}) id #{}))

(defn derive
  "Require explicit contributors and retain every independent justification.
  Captured values remain opaque; the adapter does not infer evidence from shape."
  [graph {:keys [predicate tuple base contributors]}]
  (when-not (seq contributors)
    (throw (ex-info "Derived graph fact requires contributor identities" {})))
  (let [choices (mapv #(justifications graph %) contributors)
        alternatives (reduce (fn [rows choices]
                               (for [row rows choice choices] (conj row choice)))
                             [[]] choices)
        id (assertion-id (observation graph) (scope graph) predicate tuple)
        result (reduce (fn [g inputs]
                         (claim g predicate tuple base
                                (apply support/combine (map datum/support-of inputs))
                                (apply support/join (map states-of inputs))))
                       graph alternatives)]
    (-> result
        (assoc ::source-ids (::source-ids graph))
        (update-in [::derivations id] (fnil conj #{}) (set contributors)))))
