(ns propagators.semantic-trace.fact-queries
  "Provenance adapters around the existing positive Leapfrog evaluator.
  Witness relations are evaluated by Leapfrog too; this is not a second rule engine."
  (:require [clojure.set :as set]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.semantic-trace.logic :as logic]))

(defn transform
  "Apply a pure transformation (including a Meander rewrite) returning derivation
  receipts {:predicate :tuple :base :contributors}. Each alternative is a receipt."
  [graph rewrite]
  (reduce facts/derive graph (rewrite (facts/relations graph))))

(defn- witnesses [rules]
  (mapv (fn [i {:keys [head body] :as rule}]
          (when-not (and (vector? head) (= 2 (count head))
                         (seq body) (every? #(and (vector? %) (= 2 (count %))) body))
            (throw (ex-info "Only positive rules with explicit tuple bodies are admitted" {:rule rule})))
          {:predicate (keyword "graph-facts.witness" (str i))
           :head head :body body
           :widths (mapv (comp count second) body)
           :rule {:head [(keyword "graph-facts.witness" (str i))
                         (vec (mapcat second body))]
                  :body body}})
        (range) rules))

(defn- partition-tuple [widths tuple]
  (loop [widths widths tuple (vec tuple) result []]
    (if-let [width (first widths)]
      (recur (rest widths) (subvec tuple width) (conj result (subvec tuple 0 width)))
      result)))

(defn- alternatives [choices]
  (reduce (fn [result options]
            (for [a result b options] (set/union a b)))
          [#{}] choices))

(defn- add-proof [proofs key proof]
  (let [known (get proofs key #{})]
    ;; A cyclic detour adds no evidence when a smaller witness already proves it.
    (if (some #(set/subset? % proof) known)
      proofs
      (assoc proofs key (conj (set (remove #(set/subset? proof %) known)) proof)))))

(defn query
  "Positive query over one immutable graph-fact scope. Derivations retain finite
  alternative sets of source-fact identities. Recompute from current EDB after
  withdrawal so recursive conclusions cannot justify their own existence.
  The proof budget fails explicitly rather than returning an incomplete success."
  ([graph rules] (query graph rules 10000))
  ([graph rules budget]
   (when-not (pos-int? budget) (throw (ex-info "Positive query budget required" {})))
   (let [edb (facts/source-relations graph)
         specs (witnesses rules)
         result (logic/run edb (into (vec rules) (map :rule specs)))
         ids (into {}
                   (keep (fn [[id _]]
                           (let [[_ _ predicate tuple] id]
                             (when (contains? (get edb predicate #{}) tuple)
                               [[predicate tuple] #{#{id}}]))))
                   (facts/source-assertions graph))
         instances
         (vec (mapcat
               (fn [{:keys [predicate body widths head]}]
                 (for [witness (get result predicate)
                       :let [tuples (partition-tuple widths witness)
                             bindings (into {} (mapcat (fn [[_ variables] tuple] (map vector variables tuple))
                                                       body tuples))]]
                   {:output [(first head) (mapv bindings (second head))]
                    :inputs (mapv (fn [[predicate _] tuple] [predicate tuple]) body tuples)}))
               specs))
         proofs (loop [proofs ids steps 0]
                  (let [next (reduce (fn [current {:keys [output inputs]}]
                                       (reduce #(add-proof %1 output %2) current
                                               (alternatives (map #(get proofs % #{}) inputs))))
                                     proofs instances)
                        size (reduce + 0 (map count (vals next)))]
                    (when (> (+ steps size) budget)
                      (throw (ex-info "Graph query proof budget exhausted" {:budget budget})))
                    (if (= next proofs) next (recur next (+ steps size)))))]
     (reduce-kv
      (fn [view [predicate tuple] proofs]
        (if (contains? (get edb predicate #{}) tuple)
          view
          (reduce (fn [view contributors]
                    (facts/derive view {:predicate predicate :tuple tuple :base true
                                        :contributors contributors}))
                  view proofs)))
      graph proofs))))
