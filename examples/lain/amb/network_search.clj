(ns examples.lain.amb.network-search
  "Checkpoint frontier, jobs, and branch results are ordinary cell values.
  Three propagators advance one branch decision at a time, not whole DFS."
  (:require [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.domain-refinement :as domain]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- classify [question job state]
  (if (= :domain (:kind question))
    (domain/inspect question state)
    (let [certificate (observation/cell-base (:network state) (get-in question [:cells :failure]))]
      (case (:status certificate)
        :satisfied {:status :satisfied
                    :solution (observation/cell-base (:network state) (get-in question [:cells :output]))}
        :failed {:status :failed}
        :pending (if (< (:depth job) (count (:sources question))) {:status :pending}
                     (throw (ex-info "Fully assigned branch is unresolved" {})))
        (throw (ex-info "Unknown constraint status" {:certificate certificate}))))))

(defn- options [question result]
  (if (= :domain (:kind question))
    (domain/choices question (:state result))
    {:source (nth (:sources question) (:depth result)) :values (:domain question)}))

(defn- expand [question frontier result]
  (let [{:keys [source values]} (options question result)
        start (:next-id frontier)
        jobs (mapv (fn [index v]
                     {:id (+ start index) :kind :choice :source source :value v
                      :depth (inc (:depth result)) :state (:state result)}) (range) values)]
    (-> frontier (update :pending into (reverse jobs))
        (update :next-id + (count jobs))
        (update :peak-frontier max (+ (count (:pending frontier)) (count jobs))))))

(defn update-frontier [question frontier result]
  (cond
    (not= :running (:status frontier)) frontier
    (= (:id result) (:last-result frontier)) frontier
    (not= (:id result) (:id (peek (:pending frontier)))) frontier
    :else
    (let [next (-> frontier (update :pending pop) (assoc :last-result (:id result))
                   (update :decisions + (if (= :choice (:kind result)) 1 0)))
          next (if (contains? #{:failed :satisfied} (:status result)) (update next :leaves inc) next)
          next (if (= :pending (:status result)) (expand question next result) next)]
      (case (:status result)
        :satisfied (assoc next :status :solution :solution (:solution result) :accepted (:state result))
        :failed (if (empty? (:pending next)) (assoc next :status :exhausted) next)
        :pending (if (empty? (:pending next))
                   (throw (ex-info "Pending branch produced no alternatives" {})) next)
        (throw (ex-info "Unknown branch result" {:result result}))))))

(defn- select-activation [frontier job]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [current (observation/cell-base network frontier)]
       (case (:status current)
         :running (if-let [next (peek (:pending current))]
                    [(publication/snapshot-message network job next)]
                    (throw (ex-info "Running frontier is empty" {})))
         :solution []
         :exhausted []
         (throw (ex-info "Unknown frontier status" {:status (:status current)})))))))

(defn- evaluate-activation [question job output]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [current (observation/cell-base network job)
           previous (observation/cell-base network output)]
       (if (= (:id current) (:id previous))
         []
         (let [state (case (:kind current)
                       :root (if (= :domain (:kind question))
                               (domain/settle (:state current)) (checkpoint/settle (:state current)))
                       :choice (if (= :domain (:kind question))
                                 (domain/tell (:state current) (:source current) (:value current))
                                 (checkpoint/tell (:state current) (:source current) (:value current) :active))
                       (throw (ex-info "Unknown job kind" {:job current})))
               outcome (classify question current state)
               result (merge (select-keys current [:id :kind :depth]) outcome {:state state})]
           [(publication/snapshot-message network output result)]))))))

(defn- collect-activation [question result frontier]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [previous (observation/cell-base network frontier)
           current (observation/cell-base network result)
           updated (update-frontier question previous current)]
       (if (= previous updated) []
           [(publication/snapshot-message network frontier updated)])))))

(defn build [question]
  (let [[frontier job result] (repeatedly 3 ids/new-node-id)
        initial {:status :running :pending [{:id 0 :kind :root :depth 0
                                            :state (select-keys question [:network :tasks])}]
                 :next-id 1 :last-result nil :decisions 0 :leaves 0 :peak-frontier 1}
        fresh (nb/install-cells [frontier job result])
        emitted (publication/snapshot-message fresh frontier initial)
        [_ seeded] (core/eval-cell frontier (assoc emitted :value (collection/content (:value emitted))) fresh)
        descriptors [[::select (select-activation frontier job) [frontier] [job]]
                     [::evaluate (evaluate-activation question job result) [job] [result]]
                     [::collect (collect-activation question result frontier) [result] [frontier]]]
        built (reduce (fn [{:keys [network tasks]} [name activate inputs outputs]]
                        (let [[id installed] ((prop/construct-propagator name
                                             (publication/lift activate) inputs outputs) network)]
                          {:network installed :tasks (conj tasks id)}))
                      {:network seeded :tasks []} descriptors)]
    (assoc built :frontier frontier :job job :branch-result result)))

(defn answer [built network]
  (let [frontier (observation/cell-base network (:frontier built))]
    (case (:status frontier)
      :solution {:status :solution :solution (:solution frontier) :frontier frontier}
      :exhausted {:status :exhausted :solution nil :frontier frontier}
      :running (throw (ex-info "Network quiesced with search still running" {}))
      (throw (ex-info "Unknown search outcome" {:frontier frontier})))))
