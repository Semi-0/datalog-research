(ns examples.lain.amb.range-search
  "Range policy outside the execution driver. Same Net graph and activation IDs
  in every worker; branch values and frontiers differ. Never merge branch evidence."
  (:require [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.domain-refinement :as domain]
            [examples.lain.amb.network-search :as search]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.runner :as runner]))

(defn partition-ranges [values workers]
  (if (and (vector? values) (seq values) (= (count values) (count (distinct values)))
           (integer? workers) (pos? workers))
    (let [parts (min workers (count values)) width (quot (count values) parts)
          extra (rem (count values) parts)]
      (loop [index 0 start 0 result []]
        (if (= index parts)
          result
          (let [end (+ start width (if (< index extra) 1 0))]
            (recur (inc index) end (conj result (subvec values start end)))))))
    (throw (ex-info "Distinct nonempty vector and positive worker count required"
                    {:values values :workers workers}))))

(defn template [question]
  (case (:kind question)
    :domain {:question question :built (search/build question)}
    nil {:question question :built (search/build question)}
    (throw (ex-info "Unsupported experimental question" {:kind (:kind question)}))))

(defn- replace-frontier [built frontier]
  (let [id (:frontier built)
        emitted (publication/snapshot-message (:network built) id frontier)
        [fresh updated] (core/eval-cell id (update emitted :value collection/content) (:network built))]
    {:network updated :tasks (tq/merge-queues (tq/into-queue (:tasks built)) fresh)}))

(defn fork-states
  "Partition only the first source. Other ranges and constraints remain unchanged.
  Initial TTMS quiescence is shared, timed work; workers inherit that checkpoint."
  [{:keys [question built]} ranges]
  (let [base (select-keys question [:network :tasks])
        state (if (= :domain (:kind question)) base (checkpoint/settle base))
        source (first (:sources question))
        frontier (observation/cell-base (:network built) (:frontier built))]
    (mapv
     (fn [values]
       (let [jobs
             (if (= :domain (:kind question))
               (let [[fresh narrowed] (domain/apply-patch source
                                                        (message/message source (domain/domain values))
                                                        (:network state))]
                 [{:id 0 :kind :root :depth 0
                   :state {:network narrowed :tasks (tq/merge-queues (:tasks state) fresh)}}])
               (mapv (fn [id v] {:id id :kind :choice :source source :value v
                                 :depth 1 :state state}) (range) values))
             updated (assoc frontier :pending (vec (reverse jobs)) :next-id (count jobs))]
         (replace-frontier built updated))) ranges)))

(defn evaluate [built state]
  (let [result (runner/run-network (:tasks state) (:network state))]
    (case (:status result)
      :completed (assoc (search/answer built (:network result)) :network (:network result))
      :failed (throw (:error result))
      (throw (ex-info "Unknown worker execution result" {:status (:status result)})))))

(defn select-answer [results]
  (doseq [result results]
    (case (:status result)
      :solution (if (vector? (:solution result)) nil
                    (throw (ex-info "Worker solution must be a tuple" {})))
      :exhausted (if (nil? (:solution result)) nil
                     (throw (ex-info "Exhausted worker returned a solution" {})))
      (throw (ex-info "Unknown worker search status" {:status (:status result)}))))
  (if (seq results)
    (if-let [winner (first (sort-by :solution (filter #(= :solution (:status %)) results)))]
      (assoc (select-keys winner [:status :solution :network]) :branches results)
      {:status :exhausted :solution nil :branches results})
    (throw (ex-info "Range search needs at least one branch" {}))))

(defn run
  ([template workers executor] (run template workers executor evaluate))
  ([{:keys [question built] :as template} workers executor execute]
   (let [ranges (partition-ranges (:domain question) workers)
         states (fork-states template ranges)
         results (driver/dispatch executor states #(execute built %))]
     (assoc (select-answer results) :ranges ranges))))
