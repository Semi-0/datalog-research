(ns examples.lain.amb.checkpoint-proofs
  "Observe only the required callable result. Choice literals are logical,
  while external contexts retain their actual premise versions."
  (:require [examples.lain.amb.checkpoint-search :as checkpoint]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.stdlib.premise-state :as state]))

(defn required-result [question]
  (if (:callable question)
    (let [matches (filter #(and (= (:callable question) (:operator-id %))
                               (= (:sources question) (:argument-ids %)))
                          (application/application-topologies (:network question)))]
      (if (= 1 (count matches))
        (:result-id (first matches))
        (throw (ex-info "Expected one declared required predicate application"
                        {:matches (count matches)}))))
    (get-in question [:cells :valid])))

(defn choice-index [question v]
  (let [index (.indexOf ^java.util.List (:domain question) v)]
    (if (neg? index)
      (throw (ex-info "Checkpoint source is outside its declared domain" {:value v}))
      index)))

(defn assignment [question network]
  (into #{}
        (keep (fn [source]
                (let [base (observation/cell-base network source)]
                  (if (value/unusable? base)
                    nil
                    [source (choice-index question base)]))))
        (:sources question)))

(defn proposed-assignment [question job]
  (let [assigned (assignment question (get-in job [:state :network]))]
    (case (:kind job)
      :root assigned
      :choice (conj assigned [(:source job) (choice-index question (:value job))])
      (throw (ex-info "Unknown checkpoint job" {:kind (:kind job)})))))

(defn choice-records [question network]
  (mapv (fn [[source index]]
          (let [current (net/network-cell-strongest network source)
                versions (filter #(= source (:source %)) (state/states-of current))]
            (if (and (= 1 (count versions)) (= :active (:premises-status (first versions))))
              (contracts/checked ::contracts/choice
                                 {:source source :domain (:domain question) :index index
                                  :timestamp (:timestamp (first versions)) :status :active
                                  :handled-request nil})
              (throw (ex-info "A checkpoint guess requires its actual active source version"
                              {:source source :versions versions})))))
        (sort-by (comp pr-str first) (assignment question network))))

(defn failed-certificate [network id]
  (let [current (net/network-cell-strongest network id)
        premises (datum/support-of current)]
    (if (and (not (value/unusable? current)) (false? (observation/base current))
             (observation/current-support? network premises))
      {:status :failed :support premises}
      nil)))

(defn classify [question job state]
  (let [network (:network state)
        early (failed-certificate network (:required-result question))
        gated (observation/cell-base network (get-in question [:cells :failure]))]
    (if early
      {:status :failed :certificate early}
      (case (:status gated)
        :failed
        (if (observation/current-support? network (:support gated))
          {:status :failed :certificate gated}
          (throw (ex-info "Checkpoint has a stale failure certificate" {})))
        :satisfied
        (let [answer (observation/cell-base network (get-in question [:cells :output]))]
          (if (and (= (:depth job) (count (:sources question))) (vector? answer))
            {:status :satisfied :solution answer}
            (throw (ex-info "A successful checkpoint must be a complete tuple" {}))))
        :pending
        (if (< (:depth job) (count (:sources question)))
          {:status :pending}
          (throw (ex-info "Fully assigned checkpoint remains unresolved" {})))
        (throw (ex-info "Unknown constraint certificate" {:certificate gated}))))))

(defn blocked? [question job knowledge]
  (let [network (get-in job [:state :network])
        sources (into #{} (map :source) (mapcat :context knowledge))
        context (observation/context network sources)]
    (learning/blocked? knowledge (proposed-assignment question job) context)))

(defn evaluate [question job knowledge]
  (if (and (seq knowledge) (blocked? question job knowledge))
    (merge (select-keys job [:id :kind :depth :state]) {:status :failed :pruned? true})
    (let [settled (case (:kind job)
                    :root (checkpoint/settle (:state job))
                    :choice (checkpoint/tell (:state job) (:source job) (:value job) :active)
                    (throw (ex-info "Unknown checkpoint job" {:kind (:kind job)})))]
      (merge (select-keys job [:id :kind :depth]) (classify question job settled)
             {:state settled :pruned? false}))))

(defn add-nogood [knowledge nogood]
  (contracts/checked ::contracts/nogood nogood)
  (if (some #(learning/subsumes? % nogood) knowledge)
    knowledge
    (conj (into #{} (remove #(learning/subsumes? nogood %)) knowledge) nogood)))

(defn learn [question result knowledge]
  (if (and (= :failed (:status result)) (not (:pruned? result)))
    (let [network (get-in result [:state :network])
          certificate (:certificate result)
          sources (set (:sources question))
          external (set (remove #(contains? sources (:source %)) (:support certificate)))
          context (observation/context network (set (map :source external)))
          nogood (learning/certificate->nogood certificate (choice-records question network) context)]
      (if nogood
        (add-nogood knowledge nogood)
        (throw (ex-info "Fresh failure explanation did not map to current choices" {}))))
    knowledge))

(defn covered-assignments [question result]
  (case (:status result)
    :pending 0
    :satisfied 1
    :failed (reduce * 1 (repeat (- (count (:sources question)) (:depth result))
                               (count (:domain question))))
    (throw (ex-info "Unknown checkpoint outcome" {:status (:status result)}))))
