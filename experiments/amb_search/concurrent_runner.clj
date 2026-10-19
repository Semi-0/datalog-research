(ns experiments.amb-search.concurrent-runner
  "Pure, synchronous experiment: schedule independent activations without retries.
  The caller supplies complete read/write sets; unknown activations run alone.
  No production runner, evaluator or scheduling policy is changed."
  (:require [clojure.set :as set]
            [examples.lain.amb.worker-driver :as workers]
            [propagators.helpers.task-queue :as queue]
            [propagators.runner :as runner]))

(defn- activate [network id]
  (runner/evaluate-propagator
   id network
   {:success (fn [evaluated returned]
               (if (= network returned)
                 {:evaluated evaluated}
                 (throw (ex-info "Activation changed its snapshot" {:propagator id}))))
    :fail (fn [error] {:error error})}))

(defn- apply-result [network result]
  (if-let [error (:error result)]
    (throw error)
    (runner/evaluate-patches (:evaluated result) network
                             {:success (fn [tasks updated] {:tasks tasks :network updated})
                              :fail (fn [error] (throw error))})))

(defn access [reads writes]
  ;; Outputs are read as well: source publication consults their previous state.
  {:reads (into (set reads) writes) :writes (set writes)})

(defn- independent? [left right]
  (and (empty? (set/intersection (:writes left) (:reads right)))
       (empty? (set/intersection (:reads left) (:writes right)))))

(defn- first-tasks [network tasks width accesses]
  (loop [remaining tasks ids [] footprint (access [] [])]
    (if (or (= width (count ids)) (queue/queue-empty? remaining))
      ids
      (let [[id next-tasks] (queue/pop-task remaining)]
        (if-let [next-access (accesses network id)]
          (if (independent? footprint next-access)
            (recur next-tasks (conj ids id) (merge-with set/union footprint next-access))
            ids)
          (if (empty? ids) [id] ids))))))

(defn- commit-result [state [expected result]]
  (let [{:keys [network tasks]} state
        [id remaining] (queue/pop-task tasks)]
    (when-not (= expected id)
      (throw (ex-info "Worker admission changed FIFO order" {:expected expected :actual id})))
    (let [applied (apply-result network result)]
      (-> state
          (assoc :network (:network applied)
                 :tasks (queue/merge-queues remaining (:tasks applied)))
          (update-in [:metrics :commits] inc)))))

(defn run
  "Pool ownership belongs to the caller. Unknown activations run exclusively.
  Pending tasks remain queued until commit, preserving normal deduplication.
  An access plan is a promise of complete reads, writes and absence of effects.
  Only submit pure activations. There is no speculation, retry or reevaluation."
  ([executor width built] (run executor width built (fn [_ _] nil)))
  ([executor width built accesses]
  (when-not (pos-int? width)
    (throw (ex-info "Positive worker batch width required" {:width width})))
  (let [threads (atom #{})]
    (loop [state {:network (:network built) :tasks (queue/into-queue (:tasks built))
                  :metrics {:commits 0 :activation-dispatches 0 :batches 0
                            :parallel-batches 0 :max-batch 0}}]
      (if (queue/queue-empty? (:tasks state))
        (assoc state :status :completed :threads @threads)
        (let [snapshot (:network state)
              ids (first-tasks snapshot (:tasks state) width accesses)
              ;; Exclusive work has no parallelism to gain from a thread handoff.
              active-pool (when (> (count ids) 1) executor)
              results (workers/dispatch active-pool ids
                                        (fn [id]
                                          (swap! threads conj (.getName (Thread/currentThread)))
                                          (activate snapshot id)))
              next-state (reduce commit-result state (map vector ids results))]
          (recur (-> next-state
                     (update-in [:metrics :activation-dispatches] + (count ids))
                     (update-in [:metrics :batches] inc)
                     (update-in [:metrics :parallel-batches] + (if active-pool 1 0))
                     (update-in [:metrics :max-batch] max (count ids))))))))))
