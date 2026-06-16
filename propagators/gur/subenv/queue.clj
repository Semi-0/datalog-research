(ns propagators.gur.subenv.queue
  "Queued child-network propagator runs for lexical sub-env frames."
  (:require [clojure.set :as set]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]))

(def child-queue-key [:gur/child-queue])

(defn task-ids
  [tasks]
  (cond
    (nil? tasks)
    []

    (ids/node-id? tasks)
    [tasks]

    (tq/task-queue? tasks)
    (loop [q tasks
           ids []]
      (if (tq/queue-empty? q)
        ids
        (let [[id q'] (tq/pop-task q)]
          (recur q' (conj ids id)))))

    (or (sequential? tasks) (set? tasks))
    (vec tasks)

    :else
    (throw (ex-info "unsupported child task collection" {:tasks tasks}))))

(defn prop-ids
  [& xs]
  (->> xs
       (mapcat task-ids)
       vec))

(defn run-props
  [n prop-ids]
  (let [run-tasks (requiring-resolve 'propagators.core/run-tasks)]
    (run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n)))

(defn- queue-state
  [q]
  {:scheduled (set (:scheduled q))
   :ran (set (:ran q))})

(defn queue-child-props
  [child-net tasks]
  (let [tokens (set (map (fn [prop-id] [prop-id (ids/new-node-id)])
                         (task-ids tasks)))]
    (if (empty? tokens)
      child-net
      (net/update-net-dict-entry
       child-net
       child-queue-key
       (fn [q]
         (update (queue-state q) :scheduled set/union tokens))))))

(defn pending-run-tokens
  [child-net]
  (let [{:keys [scheduled ran]} (queue-state
                                 (net/network-dict-entry child-net
                                                         child-queue-key))]
    (->> (set/difference scheduled ran)
         (sort-by pr-str)
         vec)))

(defn pending-prop-ids
  [child-net]
  (->> (pending-run-tokens child-net)
       (map first)
       distinct
       vec))

(defn- mark-child-run-tokens-ran
  [child-net tokens]
  (let [tokens (set tokens)]
    (if (empty? tokens)
      child-net
      (net/update-net-dict-entry
       child-net
       child-queue-key
       (fn [q]
         (let [q* (queue-state q)]
           (-> q*
               (update :scheduled set/union tokens)
               (update :ran set/union tokens))))))))

(defn mark-child-props-ran
  [child-net prop-ids]
  (let [prop-id-set (set prop-ids)
        tokens (->> (pending-run-tokens child-net)
                    (filter #(contains? prop-id-set (first %)))
                    set)]
    (mark-child-run-tokens-ran child-net tokens)))

(defn- all-scheduled-run-tokens
  [child-net]
  (->> (net/network-dict-entry child-net child-queue-key)
       queue-state
       :scheduled
       (sort-by pr-str)
       vec))

(defn clear-child-queue
  [child-net]
  (mark-child-run-tokens-ran child-net (all-scheduled-run-tokens child-net)))

(defn run-child-queue
  [child-net]
  (let [pending-tokens (pending-run-tokens child-net)
        pending-props (->> pending-tokens
                           (map first)
                           distinct
                           (sort-by pr-str)
                           vec)]
    (if (empty? pending-props)
      child-net
      (let [after (run-props child-net pending-props)]
        (mark-child-run-tokens-ran after pending-tokens)))))
