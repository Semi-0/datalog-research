(ns examples.lain.relational-datalog.proof-relation
  "An experiment-local OR projection over separately conjunctive TTMS proofs."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.runner :as runner]
            [propagators.stdlib.premise-state :as state]))

(defn relation? [v]
  (and (map? v) (= #{:experiment/proof-evidence} (set (keys v)))
       (map? (:experiment/proof-evidence v))))

(def empty-relation {:experiment/proof-evidence {}})

(defn relation [proofs]
  (if (and (map? proofs) (every? ids/node-id? (keys proofs)))
    {:experiment/proof-evidence
     (update-vals proofs #(collection/merge-content value/nothing %))}
    (throw (ex-info "Proof relation requires proof-cell IDs and TTMS evidence"
                    {:proofs proofs}))))

(defn merge-content [old update]
  (let [old (if (value/nothing? old) empty-relation old)
        update (if (value/nothing? update) empty-relation update)]
    (if (and (relation? old) (relation? update))
      (relation (merge-with collection/merge-content
                            (:experiment/proof-evidence old)
                            (:experiment/proof-evidence update)))
      (throw (ex-info "Expected experimental proof relations"
                      {:content old :update update})))))

(defn proof-state [projected]
  (let [base (datum/layer-value projected :base)
        states (state/states-of projected)
        status (cond
                 (value/contradiction? base) :contradictory
                 (some #(= :retracted (:premises-status %)) states) :withdrawn
                 (value/unusable? projected) :pending
                 :else :active)]
    {:status status :base base :support (datum/support-of projected)
     :premise-state states}))

(defn strongest [content network]
  (let [project #(collection/strongest-value
                 % (fn [a b] (merge/cell-merge a b network)))
        proofs (update-vals (:experiment/proof-evidence content)
                            #(proof-state (project %)))
        live (filter #(= :active (:status (val %))) proofs)]
    (doseq [[id proof] live]
      (if (and (vector? (:base proof)) (= 2 (count (:base proof))))
        nil
        (throw (ex-info "A live proof must produce a two-element tuple"
                        {:proof-cell id :proof proof}))))
    {:experiment/live-relation
     {:facts (into #{} (map (comp :base val)) live) :proofs proofs}}))

(defn view [network id]
  (let [v (net/network-cell-strongest network id)]
    (if (and (map? v) (contains? v :experiment/live-relation))
      (:experiment/live-relation v)
      (throw (ex-info "Expected a projected proof relation" {:id id :value v})))))

(defn- relation-message? [_emitter update network]
  (and (message/message? update)
       (or (relation? (message/message-value update))
           (relation? (net/network-cell-content network (message/message-id update))))))

(defn- apply-relation-message [_emitter update network]
  (let [id (message/message-id update)
        old (net/network-env-lookup network id)]
    (if (cell/cell? old)
      (let [content (merge-content (:content old) (message/message-value update))
            current (strongest content network)
            updated (net/assoc-net-cell network id (cell/cell (:name old) content current))
            tasks (if (merge/cell-updated? current (:strongest old) updated)
                    (tq/enqueue-all tq/empty-queue
                                    (graph/node-output-ids
                                     (graph/get-node (net/net-graph updated) id)))
                    tq/empty-queue)]
        [tasks updated])
      (throw (ex-info "Proof relation requires a declared cell" {:id id})))))

(def apply-patch
  (combinator/branch relation-message? apply-relation-message runner/apply-patch))

(defn evaluate-patches [evaluated network {:keys [success fail]}]
  (try
    (if (runner/evaluated-activation? evaluated)
      (let [{:keys [effects messages]}
            (patch/normalize-activation-return (:activation-result evaluated))]
        (loop [remaining (seq (concat effects messages)) tasks tq/empty-queue current network]
          (if (nil? remaining)
            (success tasks current)
            (let [[more updated] (apply-patch (:emitter evaluated) (first remaining) current)]
              (recur (next remaining) (tq/merge-queues tasks more) updated)))))
      (fail (ex-info "Expected an evaluated activation" {:value evaluated})))
    (catch Throwable error (fail error))))
