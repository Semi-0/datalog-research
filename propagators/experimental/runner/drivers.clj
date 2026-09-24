(ns propagators.experimental.runner.drivers
  "Execution drivers for a continuation-based iterator."
  (:import [java.util.concurrent Executor]))

(defn trampoline-runner
  "Drive a synchronously completing iterator without growing the stack."
  [advance]
  (fn [initial-state]
    (letfn [(bounce [state]
              (fn []
                (try
                  (advance state
                           {:done (fn [network]
                                    {:status :completed
                                     :network network})
                            :fail (fn [error]
                                    {:status :failed
                                     :error error})
                            :continue bounce})
                  (catch Throwable error
                    {:status :failed
                     :error error}))))]
      (trampoline bounce initial-state))))

(defn executor-scheduler
  "Return a scheduler that submits continuation thunks to `executor`."
  [^Executor executor]
  (fn [thunk]
    (.execute executor
              (reify Runnable
                (run [_]
                  (thunk))))
    :scheduled))

(defn scheduler-runner
  "Drive an immediately or eventually completing iterator through `schedule`."
  [schedule advance]
  (fn [initial-state {:keys [done fail]}]
    (let [terminal? (atom false)
          finish! (fn [handler value]
                    (if (compare-and-set! terminal? false true)
                      (handler value)
                      nil))]
      (letfn [(dispatch [state]
              (try
                (if @terminal?
                  nil
                  (schedule
                   (fn []
                     (try
                       (advance state
                                {:done #(finish! done %)
                                 :fail #(finish! fail %)
                                 :continue dispatch})
                       (catch Throwable error
                         (finish! fail error))))))
                (catch Throwable error
                  (finish! fail error))))]
        (dispatch initial-state)))))
