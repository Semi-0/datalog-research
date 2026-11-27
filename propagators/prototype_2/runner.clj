(ns propagators.prototype-2.runner
  "Continuation-constructed prototype-2 runner (core-shaped, Network.queue).

  Default evaluate is `evaluate/evaluate-propagator`. GC is optional via
  runner-constructor `with-done-transform` — not inside evaluate."
  (:require [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.gc :as gc]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.runner-constructor :as constructor]))

(def fifo-task-policy
  {:queue-empty? empty?
   :take-task
   (fn [network]
     (let [q (net/network-queue network)
           prop-id (peek q)]
       [prop-id (net/network-with-queue network (pop q))]))})

(defn network-iterator
  "FIFO iterator using the default evaluate-propagator."
  ([]
   (network-iterator ev/evaluate-propagator))
  ([evaluate-propagator]
   (constructor/network-iterator-constructor
    fifo-task-policy
    evaluate-propagator)))

(def ^:dynamic *iterator-factory*
  "Optional iterator constructor for a locally selected evaluation policy."
  network-iterator)

(defn run-network
  "Drain Network.queue until quiescence. Returns {:status :completed|:failed …}."
  ([network]
   (run-network network nil))
  ([network tasks]
   (let [network (if (seq tasks)
                   (net/enqueue-all network tasks)
                   network)
         last-network (atom network)
         advance (*iterator-factory*)]
     (letfn [(bounce [{:keys [network] :as state}]
               (reset! last-network network)
               (fn []
                 (try
                   (advance
                    state
                    {:done
                     (fn [final-network]
                       {:status :completed
                        :network final-network})

                     :fail
                     (fn [error]
                       {:status :failed
                        :error error
                        :network @last-network})

                     :continue bounce})
                   (catch Throwable error
                     {:status :failed
                      :error error
                      :network @last-network}))))]
       (trampoline bounce {:network network})))))

(defn completed-network
  "Return the completed network or throw the runner failure."
  [{:keys [status network error] :as execution}]
  (case status
    :completed network
    :failed (throw error)
    (throw (ex-info "unknown runner execution status"
                    {:execution execution}))))

(defn run-tasks
  "Scheduler: FIFO-drain to quiescence (no GC)."
  ([network]
   (run-tasks network nil))
  ([network tasks]
   (completed-network (run-network network tasks))))

(defn gc-iterator
  "Iterator that sweeps completed topology at equilibrium, keeping `retain`."
  ([retain]
   (gc-iterator retain ev/evaluate-propagator))
  ([retain evaluate-propagator]
   (constructor/with-done-transform
    (network-iterator evaluate-propagator)
    (fn [network]
      (gc/collect network retain)))))

(defn run-tasks-gc
  "Drain to quiescence, then GC completed cells/props not in `retain`."
  ([network retain]
   (run-tasks-gc network retain nil))
  ([network retain tasks]
   (binding [*iterator-factory* #(gc-iterator retain)]
     (run-tasks network tasks))))
