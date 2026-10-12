(ns propagators.experimental.runner.profiling
  "Opt-in synchronous runner timing; no scheduling or propagation policy changes."
  (:require [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]))

(def ^:dynamic *child-time* nil)

(defn profiler
  ([] (profiler #(System/nanoTime)))
  ([clock] {:clock clock :timings (atom {}) :steps (atom {})}))

(defn timed
  "Decorate a synchronous function. Nested measured calls are removed from self
  time; return values and exceptions are preserved. The label function receives
  the same arguments as the function. Async callbacks require a different timer."
  [profile label-fn f]
  (fn [& args]
    (let [label (apply label-fn args)
          clock (:clock profile)
          parent *child-time*
          children (volatile! 0)
          start (clock)]
      (try
        (binding [*child-time* children]
          (apply f args))
        (finally
          (let [elapsed (- (clock) start)]
            (if parent (vswap! parent + elapsed) nil)
            (swap! (:timings profile) update label
                   (fn [old]
                     (-> (or old {:calls 0 :total-ns 0 :self-ns 0 :max-ns 0})
                         (update :calls inc)
                         (update :total-ns + elapsed)
                         (update :self-ns + (- elapsed @children))
                         (update :max-ns max elapsed))))))))))

(defn phase [profile label f]
  (timed profile (constantly label) f))

(defn network-iterator
  "Use the existing FIFO policy and evaluators with opt-in timing and step
  counters. Measurements separate activation from patch evaluation by self time.
  No network snapshots are retained by the profiler."
  [profile]
  (let [activate (timed profile
                        (fn [id network _handlers]
                          [:activation (prop/prop-name
                                        (net/network-lookup-propagator network id))])
                        runner/evaluate-propagator)
        patches (phase profile :runner/patches runner/evaluate-patches)
        advance (constructor/network-iterator-constructor
                 runner/fifo-task-policy activate patches)]
    (fn [{:keys [network tasks] :as state} {:keys [done fail continue]}]
      (advance state
               {:done done :fail fail
                :continue
                (fn [{next-net :network next-tasks :tasks :as next-state}]
                  (swap! (:steps profile)
                         (fn [old]
                           (-> old
                               (update :transitions (fnil inc 0))
                               (update :node-additions (fnil + 0)
                                       (- (count (net/net-env next-net))
                                          (count (net/net-env network))))
                               (update :max-nodes (fnil max 0)
                                       (count (net/net-env next-net)))
                               (update :max-pending (fnil max 0)
                                       (count (:task-queue/q next-tasks))))))
                  (continue next-state))}))))

(defn report [profile]
  {:steps @(:steps profile)
   :timings
   (->> @(:timings profile)
        (map (fn [[label {:keys [calls total-ns self-ns max-ns]}]]
               {:label label :calls calls
                :total-ms (/ total-ns 1e6)
                :self-ms (/ self-ns 1e6)
                :max-ms (/ max-ns 1e6)}))
        (sort-by (comp - :self-ms))
        vec)})
