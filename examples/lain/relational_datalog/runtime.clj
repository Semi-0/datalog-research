(ns examples.lain.relational-datalog.runtime
  "Experiment-local relation policy and continuation runner. No global dispatch changes."
  (:require [clojure.set :as sets]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]))

(defn relation [facts]
  (if (and (set? facts) (every? vector? facts))
    {:experiment/facts facts}
    (throw (ex-info "Relation requires a set of tuple vectors" {:facts facts}))))

(def empty-relation (relation #{}))

(defn relation? [datum]
  (and (map? datum) (= #{:experiment/facts} (set (keys datum)))
       (set? (:experiment/facts datum))
       (every? vector? (:experiment/facts datum))))

(defn facts [datum]
  (if (relation? datum)
    (:experiment/facts datum)
    (throw (ex-info "Expected an experimental relation" {:value datum}))))

(defn merge-relation [content update]
  (cond
    (value/nothing? update) (if (value/nothing? content) empty-relation content)
    (value/nothing? content) (relation (facts update))
    :else (relation (sets/union (facts content) (facts update)))))

(defn strongest-relation [content]
  ;; Keep the tag through existing whole-datum forwarding and compound aliases.
  (relation (facts content)))

(defn- relation-message? [_emitter update network]
  (and (message/message? update)
       (or (relation? (message/message-value update))
           (relation? (net/network-cell-content network (message/message-id update))))))

(defn- apply-relation-message [_emitter update network]
  (let [id (message/message-id update)
        old (net/network-env-lookup network id)]
    (if (cell/cell? old)
      (let [content (merge-relation (cell/cell-content old) (message/message-value update))
            strongest (strongest-relation content)
            updated (net/assoc-net-cell network id (cell/cell (cell/cell-name old) content strongest))
            tasks (if (= strongest (cell/cell-strongest old))
                    tq/empty-queue
                    (tq/enqueue-all tq/empty-queue
                                    (graph/node-output-ids (graph/get-node (net/net-graph updated) id))))]
        [tasks updated])
      (throw (ex-info "Relation message requires a declared cell" {:id id})))))

(def apply-patch
  (combinator/branch relation-message? apply-relation-message runner/apply-patch))

(defn evaluate-patches [evaluated network {:keys [success fail]}]
  (try
    (if (runner/evaluated-activation? evaluated)
      (let [{:keys [effects messages]} (patch/normalize-activation-return (:activation-result evaluated))]
        (loop [remaining (seq (concat effects messages)) tasks tq/empty-queue current network]
          (if (nil? remaining)
            (success tasks current)
            (let [[new-tasks updated] (apply-patch (:emitter evaluated) (first remaining) current)]
              (recur (next remaining) (tq/merge-queues tasks new-tasks) updated)))))
      (fail (ex-info "Expected an evaluated activation" {:value evaluated})))
    (catch Throwable error (fail error))))

(defn completed [result]
  (if (= :completed (:status result))
    result
    (throw (ex-info "Experiment did not reach quiescence"
                    (select-keys result [:status :steps :ms]) (:error result)))))

(defn run
  "Return completion/failure, last completed immutable Net, and activation count."
  ([network tasks] (run network tasks {:max-steps 100000 :max-ms 30000}))
  ([network tasks {:keys [max-steps max-ms evaluate-propagator evaluate-patches]
                   :or {max-steps 100000 max-ms 30000
                        evaluate-propagator runner/evaluate-propagator
                        evaluate-patches evaluate-patches}}]
   (let [started (System/nanoTime)
         advance (constructor/network-iterator-constructor
                  runner/fifo-task-policy evaluate-propagator evaluate-patches)]
     (letfn [(bounce [{:keys [network tasks steps] :as state}]
               (fn []
                 (if (and (not (tq/queue-empty? tasks))
                          (or (>= steps max-steps)
                              (>= (- (System/nanoTime) started) (* max-ms 1000000))))
                   {:status :failed :network network :steps steps
                    :error (ex-info "Relation experiment budget reached" {:steps steps})}
                   (advance state
                            {:done (fn [final] {:status :completed :network final :steps steps})
                             :fail (fn [error] {:status :failed :network network :steps steps :error error})
                             :continue (fn [next-state] (bounce (update next-state :steps inc)))}))))]
       (assoc (trampoline bounce {:network network :tasks (tq/into-queue tasks) :steps 0})
              :ms (/ (- (System/nanoTime) started) 1e6))))))
