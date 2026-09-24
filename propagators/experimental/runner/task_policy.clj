(ns propagators.experimental.runner.task-policy
  "Task-selection policies for the experimental continuation runner."
  (:require [propagators.helpers.task-queue :as tq]))

(def fifo-task-policy
  {:tasks-empty? tq/queue-empty?
   :take-task (fn [_network tasks]
                (tq/pop-task tasks))
   :add-tasks (fn [remaining new-tasks]
                (tq/merge-queues remaining (tq/into-queue new-tasks)))})

(defn queue-ids
  "Return FIFO ids using only the public task-queue operations."
  [tasks]
  (loop [remaining (tq/into-queue tasks)
         ids []]
    (if (tq/queue-empty? remaining)
      ids
      (let [[id next-tasks] (tq/pop-task remaining)]
        (recur next-tasks (conj ids id))))))

(defn round-tasks
  "Create ranked-round state. New tasks are admitted to the next round."
  [tasks]
  {:current-round (tq/into-queue tasks)
   :next-round tq/empty-queue})

(defn- active-round
  [{:keys [current-round next-round] :as tasks}]
  (if (tq/queue-empty? current-round)
    (assoc tasks
           :current-round next-round
           :next-round tq/empty-queue)
    tasks))

(defn- ranked-ids
  [score network tasks]
  (->> (queue-ids tasks)
       (map-indexed (fn [index id]
                      {:id id
                       :index index
                       :score (score network id)}))
       (sort-by (juxt (comp - :score) :index))
       (mapv :id)))

(defn ranked-round-robin-task-policy
  "Rank the current round by `score`; preserve FIFO ties and defer new work."
  [score]
  {:tasks-empty?
   (fn [{:keys [current-round next-round]}]
     (and (tq/queue-empty? current-round)
          (tq/queue-empty? next-round)))

   :take-task
   (fn [network tasks]
     (let [{:keys [current-round] :as active} (active-round tasks)
           [selected & rest-ids] (ranked-ids score network current-round)]
       [selected (assoc active
                        :current-round (tq/enqueue-all tq/empty-queue rest-ids))]))

   :add-tasks
   (fn [remaining new-tasks]
     (update remaining
             :next-round
             tq/merge-queues
             (tq/into-queue new-tasks)))})
