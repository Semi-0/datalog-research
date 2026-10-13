(ns examples.lain.amb.checkpoint-enumeration
  "Add one answer/resume observer to the existing first-answer solver.
  Original activations are not replaced. Solver state and answers live in cells."
  (:require [examples.lain.amb.enumeration-data :as data]
            [examples.lain.amb.range-search :as ranges]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.ids :as ids]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(defn resume-frontier [frontier]
  (if (and (= :solution (:status frontier)) (vector? (:solution frontier)))
    (-> frontier (dissoc :solution :accepted)
        (assoc :status (if (seq (:pending frontier)) :running :exhausted)))
    (throw (ex-info "Resume needs a completed solution frontier" {}))))

(defn p:collect-and-resume [frontier answers]
  (prop/construct-propagator
   ::collect-and-resume
   (publication/lift
    (prop/concrete-propagator
     (fn [_ _ network]
       (let [current (observation/cell-base network frontier)]
         (case (:status current)
           :solution
           [(publication/snapshot-message network answers
                                          (conj (data/retained-set network answers) (:solution current)))
            (publication/snapshot-message network frontier (resume-frontier current))]
           :running []
           :exhausted []
           (throw (ex-info "Unknown enumeration frontier" {:status (:status current)})))))))
   [frontier] [answers frontier]))

(defn extend-all-answers [built]
  (let [answers (ids/new-node-id)
        seeded (data/seed-snapshot (:network built) answers #{})
        [id extended] ((p:collect-and-resume (:frontier built) answers) seeded)]
    (assoc built :network extended :tasks (conj (:tasks built) id)
           :answers answers :enumerator id)))

(defn template [question]
  (update (ranges/template question) :built extend-all-answers))

(defn answer [built network]
  (let [frontier (observation/cell-base network (:frontier built))]
    (case (:status frontier)
      :exhausted {:status :completed :answers (vec (sort (data/retained-set network (:answers built))))
                  :frontier frontier}
      :solution (throw (ex-info "Enumeration stopped before resuming a solution" {}))
      :running (throw (ex-info "Enumeration quiesced before exhaustion" {}))
      (throw (ex-info "Unknown enumeration status" {:status (:status frontier)})))))

(defn evaluate [built state]
  (let [result (runner/run-network (:tasks state) (:network state))]
    (case (:status result)
      :completed (assoc (answer built (:network result)) :network (:network result))
      :failed (throw (:error result))
      (throw (ex-info "Unknown enumeration runtime result" {:status (:status result)})))))

(defn run [template]
  (evaluate (:built template) (select-keys (:built template) [:network :tasks])))

(defn run-partitions
  ([template workers executor] (run-partitions template workers executor evaluate))
  ([{:keys [question built] :as template} workers executor execute]
   (let [parts (ranges/partition-ranges (:domain question) workers)
         states (ranges/fork-states template parts)
         results (driver/dispatch executor states #(execute built %))]
     (if (every? #(= :completed (:status %)) results)
       {:status :completed :answers (vec (sort (into #{} (mapcat :answers) results)))
        :branches results :ranges parts}
       (throw (ex-info "Not every enumeration worker completed" {}))))))
