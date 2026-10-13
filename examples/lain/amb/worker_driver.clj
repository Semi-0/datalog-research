(ns examples.lain.amb.worker-driver
  "Execution only: independent immutable states, ordered results, caller-owned pool."
  (:import [java.util.concurrent Callable ExecutionException ExecutorService
            Executors TimeUnit]))

(defn pool [workers]
  (if (and (integer? workers) (pos? workers) (<= workers Integer/MAX_VALUE))
    (Executors/newFixedThreadPool (int workers))
    (throw (ex-info "Positive integer worker count required" {:workers workers}))))

(defn close! [^ExecutorService executor]
  (.shutdown executor)
  (if (.awaitTermination executor 30 TimeUnit/SECONDS)
    :closed
    (do
      (.shutdownNow executor)
      (if (.awaitTermination executor 5 TimeUnit/SECONDS)
        :closed
        (throw (ex-info "Worker pool did not terminate" {}))))))

(defn dispatch
  "nil executor runs the exact same states sequentially. Otherwise dispatch all
  states to workers and wait for all. No patch/state merging or search policy.
  Capture dynamic bindings separately for every task; propagate original errors."
  [^ExecutorService executor states execute]
  (if (nil? executor)
    (mapv execute states)
    (let [jobs (mapv (fn [state]
                       (let [run (bound-fn [] (execute state))]
                         (reify Callable (call [_] (run))))) states)]
      (try
        (let [futures (.invokeAll executor ^java.util.Collection jobs)]
          (mapv (fn [^java.util.concurrent.Future future]
                  (try (.get future)
                       (catch ExecutionException error
                         (throw (.getCause error))))) futures))
        (catch InterruptedException error
          (.interrupt (Thread/currentThread))
          (throw error))))))
