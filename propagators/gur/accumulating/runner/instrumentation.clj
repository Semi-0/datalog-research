(ns propagators.gur.accumulating.runner.instrumentation
  "Optional runtime instrumentation for the accumulating GUR runner.")

(def ^:dynamic *prop-run-observer*
  "Optional debug hook called with runner prop scheduling events.
  Events are maps with :event, :prop-id, and :net; :ran events also include
  :elapsed-ns. This is runtime instrumentation only, not recursive semantics."
  nil)

(def ^:dynamic *phase-observer*
  "Optional debug hook called with coarse runner phase timings."
  nil)

(def ^:dynamic *current-task-fact* nil)

(defn observe-prop-run!
  [event]
  (when-let [observer *prop-run-observer*]
    (observer (cond-> event
                *current-task-fact*
                (assoc :task-fact *current-task-fact*)))))

(defn timed-phase
  [phase f]
  (if *phase-observer*
    (let [started (System/nanoTime)
          result (f)]
      (*phase-observer* {:phase phase
                         :elapsed-ns (- (System/nanoTime) started)})
      result)
    (f)))
