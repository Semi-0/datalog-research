(ns propagators.compiler-2.experimental.completed-when-pruning
  "Experimental retirement of completed flat-GUR availability controls.
  No cell IDs or surviving executable ports change. Persistent when markers
  must remain present; deleting those markers invalidates this optimization."
  (:require [propagators.gur.flat :as flat]
            [propagators.network-facts.export :as export]
            [propagators.network-facts.import :as importer]))

(defn completed-controls [network receipts]
  (let [expected-class (class (:activate (flat/when-effect :probe nil (constantly []))))
        by-id (group-by :id receipts)
        markers (get-in network [:dict flat/name-bindings-key flat/when-scope])]
    (into #{}
          (keep (fn [[key condition]]
                  (let [id (flat/stable-node-id [:when key :prop])
                        activation (get-in network [:env id :activate])
                        node (get-in network [:graph id])]
                    (when (and activation (= #{condition} (:inputs node))
                               (empty? (:outputs node))
                               (some #(and (= expected-class (:source-class %))
                                           (identical? activation (:installed-activation %))
                                           (= #{condition} (:inputs %))
                                           (empty? (:outputs %))) (get by-id id)))
                      id)))) markers)))

(defn- retire-facts [facts removed]
  (let [index (export/index-facts facts)
        entities (set (mapcat (fn [id] [[:env id] [:graph id]
                                      [:relationship [[:outer] id]]]) removed))
        fields (set (mapcat #(export/values-at index % :record/field) entities))
        retired (into entities fields)
        references #{:net/env-entry :net/graph-node :net/relationship-node
                     :graph/input :graph/output :relationship/parent :relationship/child}
        ;; Reach surviving owners through retired ownership boundaries.
        parents (fn parents [entity visited]
                  (if (contains? visited entity)
                    (throw (ex-info "Cyclic retired ownership" {:entity entity}))
                    (mapcat (fn [parent]
                              (if (contains? retired parent)
                                (parents parent (conj visited entity))
                                [parent]))
                            (export/values-at index entity :relationship/parent))))
        additions (into #{}
                        (mapcat (fn [entity]
                                  (for [child (export/values-at index entity :relationship/child)
                                        :when (not (contains? retired child))
                                        parent (parents entity #{})
                                        fact [[parent :relationship/child child]
                                              [child :relationship/parent parent]]]
                                    fact)))
                        (filter #(= :relationship (first %)) entities))]
    (into additions
          (remove (fn [[entity attribute value]]
                    (or (contains? retired entity)
                        (and (contains? references attribute)
                             (contains? retired value))))) facts)))

(defn optimize [network receipts]
  (let [removed (completed-controls network receipts)
        source (export/net->facts network)
        facts (retire-facts source removed)
        _ (importer/validate-facts facts)
        rebuilt (importer/facts->net facts)]
    (when-not (and (= (apply dissoc (:env network) removed) (:env rebuilt))
                   (= (:dict network) (:dict rebuilt))
                   (every? (fn [[id entry]]
                             (if (:activate entry)
                               (identical? (:activate entry) (get-in rebuilt [:env id :activate]))
                               true)) (:env rebuilt)))
      (throw (ex-info "Pruning altered surviving executable evidence" {})))
    {:net rebuilt :removed removed
     :validation-path :trusted-completed-when-experiment
     :marker-policy :preserve-completion-markers}))
