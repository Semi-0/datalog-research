(ns examples.lain.rule-fixedpoint.evidence
  "TTMS proof transport. Only actual grounding cells confer authority."
  (:require [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.rule-fixedpoint.model :as model]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.gur.flat :as gur]
            [propagators.network :as net]
            [propagators.stdlib.premise-state :as state]))

(defn observation [source epoch status base]
  (let [premises #{{:source source :timestamp epoch :premises-status status}}]
    (collection/content {:base base :support premises :premise-state premises})))

(defn live [network id]
  (into {} (filter #(= :active (:status (val %)))) (:proofs (relation/view network id))))

(defn source-states [network sources]
  (apply support/join (map #(state/states-of (net/network-cell-strongest network %)) sources)))

(defn current-version? [network premises]
  (let [current (source-states network (set (map :source premises)))]
    (and (every? #(= :active (:premises-status %)) premises) (= premises current))))

(defn current? [network premises]
  (and (current-version? network premises)
       (every? #(not (value/unusable? (net/network-cell-strongest network (:source %)))) premises)))

(defn source-key [premises]
  ;; stable-node-id hashes printed data. Canonical insertion also handles UUID
  ;; hash collisions in equal source sets, without changing the shared helper.
  (into #{} (sort-by :uuid (set (map :source premises)))))

(defn proof-id [rule binding premises]
  (let [variables (model/variables rule)
        ordered (zipmap variables (map binding variables))]
    (gur/stable-node-id [::proof (:id rule) ordered (source-key premises)])))

(defn grounded [network authority proofs]
  (let [authorization (net/network-cell-strongest network authority)
        premises (apply support/combine
                        (datum/support-of authorization)
                        (map :support proofs))]
    (if (and (not (value/unusable? authorization)) (seq (datum/support-of authorization))
             (seq premises) (current? network premises)) premises nil)))

(defn derived [tuple premises]
  (collection/content {:base tuple :support premises :premise-state premises}))

(defn retained-sources [content]
  (into #{} (map :source)
        (concat (:support/states content)
                (mapcat :support (:support/observations content)))))

(defn conflict-observations [network content]
  (into #{}
        (keep (fn [{:keys [support]}]
                (if (and (current-version? network support)
                         (some #(value/contradiction?
                                 (datum/layer-value (net/network-cell-strongest network (:source %)) :base)) support))
                  {:base value/contradiction :support support} nil)))
        (:support/observations content)))

(defn state-updates [network id proof-ids]
  (into {}
        (keep (fn [[key content]]
                (let [sources (retained-sources content)
                      current (source-states network sources)
                      old (apply support/join (:support/states content)
                                 (map :support (:support/observations content)))
                      invalid (conflict-observations network content)]
                  (cond
                    (seq invalid) [key {:support/observations invalid :support/states current}]
                    (not= old current) [key (collection/content {:premise-state current})]
                    :else nil))))
        (select-keys (:experiment/proof-evidence (net/network-cell-content network id)) proof-ids)))

(defn signatures [proofs]
  (into {} (map (fn [[id proof]] [id (select-keys proof [:status :base :support :premise-state])])) proofs))

(defn changed-tuples [previous current]
  (into #{} (keep (fn [[id proof]]
                   (if (and (= :active (:status proof))
                            (not= proof (get previous id))) (:base proof) nil))) current))
