(ns examples.lain.amb.learning-checkpoint
  "Four ordinary control propagators: select, evaluate, learn, collect.
  Learned knowledge lives outside restored worlds; no runner changes or atoms."
  (:require [examples.lain.amb.checkpoint-enumeration :as enumeration]
            [examples.lain.amb.checkpoint-proofs :as proofs]
            [examples.lain.amb.enumeration-data :as data]
            [examples.lain.amb.network-search :as search]
            [examples.lain.amb.solver-strategy :as strategy]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn select-activation [cells]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [frontier (observation/cell-base network (:frontier cells))
           previous (observation/cell-base network (:job cells))]
       (case (:status frontier)
         :running
         (if-let [job (peek (:pending frontier))]
           (if (= (:id job) (:id previous))
             []
             [(publication/snapshot-message network (:job cells) job)])
           (throw (ex-info "Running checkpoint frontier is empty" {})))
         :solution []
         :exhausted []
         (throw (ex-info "Unknown checkpoint frontier" {:status (:status frontier)})))))))

(defn evaluate-activation [question cells]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [job (observation/cell-base network (:job cells))
           previous (observation/cell-base network (:evaluated cells))]
       (if (= (:id job) (:id previous))
         []
         (let [knowledge (observation/cell-base network (:knowledge cells))
               result (proofs/evaluate question job knowledge)]
           [(publication/snapshot-message network (:evaluated cells) result)]))))))

(defn learn-activation [question config cells]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [result (observation/cell-base network (:evaluated cells))
           previous (observation/cell-base network (:result cells))]
       (if (= (:id result) (:id previous))
         []
         (let [known (observation/cell-base network (:knowledge cells))
               learned (if (:learn? config) (proofs/learn question result known) known)
               messages (if (= known learned)
                          []
                          [(publication/snapshot-message network (:knowledge cells) learned)])]
           ;; Knowledge is applied before publishing the processed result. The
           ;; collector cannot dispatch the next job against stale knowledge.
           (conj messages (publication/snapshot-message network (:result cells)
                                                        (assoc result :nogood-count (count learned))))))))))

(defn account-result [question previous result]
  (let [next (search/update-frontier question previous result)]
    (if (= (:last-result previous) (:last-result next))
      previous
      (-> next
          (update :evaluations + (if (:pruned? result) 0 1))
          (update :pruned-jobs + (if (:pruned? result) 1 0))
          (update :early-failures + (if (and (= :failed (:status result))
                                           (< (:depth result) (count (:sources question)))) 1 0))
          (update :propagated-leaves + (if (and (not (:pruned? result))
                                               (= (:depth result) (count (:sources question)))) 1 0))
          (update :covered-assignments + (proofs/covered-assignments question result))
          (assoc :nogood-count (:nogood-count result))))))

(defn collect-activation [question cells]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [previous (observation/cell-base network (:frontier cells))
           result (observation/cell-base network (:result cells))
           next (account-result question previous result)]
       (if (= previous next)
         []
         [(publication/snapshot-message network (:frontier cells) next)])))))

(defn build [question config]
  (if (and (nil? (:kind question)) (= 3 (count (:sources question)))
           (boolean? (:learn? config)))
    (let [question (assoc question :required-result (proofs/required-result question))
          cells (zipmap [:frontier :job :evaluated :result :knowledge] (repeatedly ids/new-node-id))
          initial {:status :running :pending [{:id 0 :kind :root :depth 0
                                               :state (select-keys question [:network :tasks])}]
                   :next-id 1 :last-result nil :decisions 0 :leaves 0 :peak-frontier 1
                   :evaluations 0 :pruned-jobs 0 :early-failures 0 :propagated-leaves 0
                   :covered-assignments 0 :nogood-count 0}
          seeded (-> (nb/install-cells (vals cells))
                     (data/seed-snapshot (:frontier cells) initial)
                     (data/seed-snapshot (:knowledge cells) #{}))
          descriptors [[::select (select-activation cells) [(:frontier cells)] [(:job cells)]]
                       [::evaluate (evaluate-activation question cells) [(:job cells)] [(:evaluated cells)]]
                       [::learn (learn-activation question config cells)
                        [(:evaluated cells) (:knowledge cells)] [(:knowledge cells) (:result cells)]]
                       [::collect (collect-activation question cells)
                        [(:result cells) (:frontier cells)] [(:frontier cells)]]]
          built (reduce (fn [{:keys [network tasks]} [name activation inputs outputs]]
                          (let [[id installed] ((prop/construct-propagator
                                                 name (publication/lift activation) inputs outputs) network)]
                            {:network installed :tasks (conj tasks id)}))
                        {:network seeded :tasks []} descriptors)]
      (assoc built :frontier (:frontier cells) :cells cells :question question))
    (throw (ex-info "Learning checkpoints require a three-source TTMS question and explicit learning flag" {}))))

(defn run [question config]
  (let [built (enumeration/extend-all-answers (build question config))
        result (enumeration/evaluate built (select-keys built [:network :tasks]))
        knowledge (observation/cell-base (:network result) (get-in built [:cells :knowledge]))]
    (assoc result :nogoods knowledge)))

(defrecord LearningCheckpointSolver [learn?]
  strategy/ConstraintSolver
  (solve-question [_ question _context]
    (run question {:learn? learn?})))

(def early-solver (->LearningCheckpointSolver false))
(def learning-solver (->LearningCheckpointSolver true))

(def session-extension
  (extension/extension-bundle
   {:id ::solvers :bindings [['early-checkpoint-solver early-solver]
                            ['learning-checkpoint-solver learning-solver]] :effects []}))
