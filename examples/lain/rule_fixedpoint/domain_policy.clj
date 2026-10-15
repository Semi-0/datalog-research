(ns examples.lain.rule-fixedpoint.domain-policy
  "Local finite-domain intersection over separate TTMS justifications."
  (:require [clojure.set :as sets]
            [examples.lain.amb.domain-refinement :as domain]
            [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.rule-fixedpoint.evidence :as evidence]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.runner :as runner]
            [propagators.stdlib.premise-state :as state]))

(def empty-content {::proofs {}})

(defn content [proofs] {::proofs proofs})

(defn merge-content [old update]
  (content (merge-with collection/merge-content (::proofs old) (::proofs update))))

(defn merge-base [old update]
  (cond
    (value/nothing? old) update
    (value/nothing? update) old
    (or (value/contradiction? old) (value/contradiction? update)) value/contradiction
    (and (domain/domain? old) (domain/domain? update)) (domain/intersect old update)
    (and (vector? old) (vector? update) (= 3 (count old) (count update))
         (every? set? (concat old update))) (mapv sets/intersection old update)
    :else (throw (ex-info "Expected finite domains or three contractor projections" {:old old :update update}))))

(defn strongest [content]
  (let [projected (map #(collection/strongest-value % merge-base) (vals (::proofs content)))
        active (remove value/unusable? projected)
        base (reduce merge-base value/nothing (map #(datum/layer-value % :base) active))
        base (if (and (domain/domain? base) (empty? (domain/candidates base))) value/contradiction base)
        evidence {:base base :support (apply support/combine (map datum/support-of active))
                  :premise-state (apply support/join (map state/states-of projected))}]
    (collection/strongest-value (collection/content evidence) merge-base)))

(defn- domain-message? [_ update network]
  (and (message/message? update)
       (or (and (map? (message/message-value update)) (contains? (message/message-value update) ::proofs))
           (let [content (net/network-cell-content network (message/message-id update))]
             (and (map? content) (contains? content ::proofs))))))

(defn- apply-domain [_ update network]
  (let [id (message/message-id update) old (net/network-env-lookup network id)
        content (merge-content (:content old) (message/message-value update))
        current (strongest content)
        updated (net/assoc-net-cell network id (cell/cell (:name old) content current))
        tasks (if (= current (:strongest old)) tq/empty-queue
                  (tq/enqueue-all tq/empty-queue
                                  (graph/node-output-ids (graph/get-node (net/net-graph updated) id))))]
    [tasks updated]))

(def apply-patch (combinator/branch domain-message? apply-domain relation/apply-patch))

(defn evaluate-patches [evaluated network {:keys [success fail]}]
  (try
    (if (runner/evaluated-activation? evaluated)
      (let [{:keys [effects messages]} (patch/normalize-activation-return (:activation-result evaluated))
            [tasks updated] (reduce (fn [[tasks current] update]
                                      (let [[fresh next-net] (apply-patch (:emitter evaluated) update current)]
                                        [(tq/merge-queues tasks fresh) next-net]))
                                    [tq/empty-queue network] (concat effects messages))]
        (success tasks updated))
      (fail (ex-info "Expected an evaluated domain activation" {})))
    (catch Throwable error (fail error))))

(defn state-updates [network port]
  (into {}
        (keep (fn [[id retained]]
                (let [current (evidence/source-states network (evidence/retained-sources retained))
                      old (apply support/join (:support/states retained) (map :support (:support/observations retained)))]
                  (if (= old current) nil [id (collection/content {:premise-state current})]))))
        (::proofs (net/network-cell-content network port))))
