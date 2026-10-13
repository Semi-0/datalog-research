(ns examples.lain.amb.checkpoint-search
  "Matched DFS with TTMS withdrawal or immutable quiescent checkpoints.
  The counters are observation only; branch state remains explicit immutable data."
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-cache :as cache]
            [propagators.runner :as runner]))

(defn settle
  "Run the ordinary continuation-constructed runner; evaluator errors are errors."
  [{:keys [network tasks] :as state}]
  (let [result (runner/run-network tasks network)]
    (case (:status result)
      :completed (assoc state :network (:network result) :tasks tq/empty-queue)
      :failed (throw (ex-info "Checkpoint branch evaluation failed" {} (:error result)))
      (throw (ex-info "Unknown runner result" {:status (:status result)})))))

(defn tell
  "Publish through existing TTMS source stamping and normal cell evaluation.
  Retraction carries premise state even though its base is nothing."
  [{:keys [network tasks] :as state} source base status]
  (let [emitted (publication/source-message network source base status)
        payload (collection/content (message/message-value emitted))
        [new-tasks updated] (core/eval-cell source (assoc emitted :value payload) network)]
    (settle (assoc state :network updated :tasks (tq/merge-queues tasks new-tasks)))))

(defn- outcome [question state]
  (let [network (:network state)
        certificate (observation/cell-base network (get-in question [:cells :failure]))]
    (case (:status certificate)
      :satisfied
      (let [solution (observation/cell-base network (get-in question [:cells :output]))]
        (if (vector? solution)
          {:status :solution :state state :solution solution}
          (throw (ex-info "Satisfied branch has no published tuple" {}))))
      :failed {:status :exhausted :state state :solution nil}
      :pending (throw (ex-info "Concrete candidate is unresolved, not a dead end" {}))
      (throw (ex-info "Unknown constraint certificate" {:certificate certificate})))))

(defn- restore [context checkpoint current source]
  (case (:mode context)
    :checkpoint
    (do (swap! (:metrics context) update :restores inc)
        checkpoint)
    :withdrawal
    (do (swap! (:metrics context) update :withdrawals inc)
        (tell current source value/nothing :retracted))
    (throw (ex-info "Unknown restoration mode" {:mode (:mode context)}))))

(declare explore)

(defn- alternatives [context checkpoint depth]
  (let [source (nth (get-in context [:question :sources]) depth)]
    (loop [remaining (seq (get-in context [:question :domain])) current checkpoint]
      (if (nil? remaining)
        {:status :exhausted :state current :solution nil}
        (let [_ (swap! (:metrics context) update :decisions inc)
              child (tell current source (first remaining) :active)
              result (explore context child (inc depth))]
          (case (:status result)
            :solution result
            :exhausted (recur (next remaining)
                              (restore context checkpoint (:state result) source))
            (throw (ex-info "Unknown search result" {:status (:status result)}))))))))

(defn- explore [context state depth]
  (swap! (:metrics context) update :max-depth max depth)
  (if (= depth (count (get-in context [:question :sources])))
    (do (swap! (:metrics context) update :leaves inc)
        (outcome (:question context) state))
    (alternatives context state depth)))

(defn counter-transform
  "Count actual activations identically for checkpoint, withdrawal, and AMB."
  [metrics]
  (fn [advance]
    (fn [state continuations]
      (if (tq/queue-empty? (:tasks state))
        nil
        (swap! metrics (fn [counts]
                         (-> counts
                             (update :activations (fnil inc 0))
                             (update :peak-env (fnil max 0)
                                     (count (net/net-env (:network state))))))))
      (advance state continuations))))

(defn run [question mode]
  (if (contains? #{:checkpoint :withdrawal} mode)
    (let [metrics (atom {:activations 0 :decisions 0 :leaves 0
                        :withdrawals 0 :restores 0 :max-depth 0})
          started (System/nanoTime)
          result (binding [cache/*cache* nil
                           runner/*advance-transform* (counter-transform metrics)]
                   (explore {:question question :mode mode :metrics metrics}
                            (settle (select-keys question [:network :tasks])) 0))]
      (assoc result :mode mode :metrics @metrics
             :run-ms (/ (- (System/nanoTime) started) 1e6)))
    (throw (ex-info "Unknown restoration mode" {:mode mode}))))
