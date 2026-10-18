(ns propagators.semantic-trace.session-runner
  "Session-local composition of semantic capture merge with the standard runner."
  (:require [propagators.cells.merge :as merge]
            [propagators.combinator :as combinator]
            [propagators.network-patch :as patch]
            [propagators.helpers.task-queue :as queue]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.observation :as observation]))

(defn combined-capture-merge
  ([] (combined-capture-merge capture/capture-pair? capture/join))
  ([capture-pair? capture-join]
   (combinator/branch
    capture/publication-pair? capture/join-publications
    capture-pair? capture-join
    merge/cell-merge)))

(defn- retaining-patch-evaluator
  [sources evaluate-patches]
  (fn [evaluated network {:keys [success fail]}]
    (try
      (let [{:keys [messages effects]}
            (patch/normalize-activation-return
             (:activation-result evaluated))
            retention (filterv capture/retain-source-patch? effects)
            ordinary (remove capture/retain-source-patch? effects)
            evaluated (assoc evaluated :activation-result
                             {:messages messages :effects (vec ordinary)})]
        (evaluate-patches
         evaluated network
         {:success
          (fn [tasks next-network]
            (doseq [patch retention]
              (swap! sources assoc (:observation patch)
                     (cond-> (capture/retained-source patch)
                       (:publisher patch) (assoc :publisher (:publisher patch)))))
            (let [watches (when (some patch/network-declaration? ordinary)
                            (observation/refresh-declarations next-network))]
              (if (seq watches)
                (evaluate-patches
                 (assoc evaluated :activation-result watches) next-network
                 {:success (fn [new-tasks installed]
                             (success (queue/merge-queues tasks new-tasks) installed))
                  :fail fail})
                (success tasks next-network))))
          :fail fail}))
      (catch Throwable error
        (fail error)))))

(defn make-session-iterator
  ([] (make-session-iterator (atom {})
                             capture/capture-pair?
                             capture/join
                             runner/make-patch-evaluator))
  ([capture-pair? capture-join make-patch-evaluator]
   (make-session-iterator (atom {}) capture-pair? capture-join make-patch-evaluator))
  ([sources capture-pair? capture-join make-patch-evaluator]
   (constructor/network-iterator-constructor
    runner/fifo-task-policy
    runner/evaluate-propagator
    (retaining-patch-evaluator
     sources
     (make-patch-evaluator
      (combined-capture-merge capture-pair? capture-join))))))
