(ns bench-tracer-facts
  "Observation-only tracer measurements; this is not an acceptance runner."
  (:require [graph.lain-editor.runtime.session :as runtime]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.operators.network-observation :as snapshot]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.semantic-trace.high-level :as high-level]
            [propagators.semantic-trace.tracer-modifiers :as modifiers]
            [propagators.stdlib.premise-state]
            [propagators.stdlib.source-publication])
  (:import [java.lang.management ManagementFactory]
           [com.sun.management ThreadMXBean]))

(defn- source-graph []
  {:semantic-trace/graph true
   :nodes (into {} (map (fn [n] [n (str "node-" n)])) (range 24))
   :node-kinds (zipmap (range 24) (repeat :cell))
   :values (zipmap (range 24) (range 24))
   :edges (mapv vector (range 23) (range 1 24))})

(defn- view [source]
  (high-level/derive-view
   (modifiers/apply-modifiers
    source [(modifiers/declaration :high-level)
            (modifiers/declaration :with-values)
            (modifiers/selection ["node-0" "node-12" "node-23"])])))

(defn- measure! [operation iterations run]
  (dotimes [_ 8] (run))
  (let [^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        thread (.getId (Thread/currentThread))
        allocation? (.isThreadAllocatedMemorySupported bean)
        _ (when allocation? (.setThreadAllocatedMemoryEnabled bean true))
        allocated (when allocation? (.getThreadAllocatedBytes bean thread))
        start (System/nanoTime)
        result (volatile! nil)]
    (dotimes [_ iterations] (vreset! result (run)))
    (let [elapsed (- (System/nanoTime) start)]
      {:operation operation :iterations iterations
       :nanoseconds-per-operation (quot elapsed iterations)
       :allocated-bytes-per-operation
       (when allocation? (quot (- (.getThreadAllocatedBytes bean thread) allocated) iterations))
       :result-assertions (count (facts/assertions @result))})))

(defn- used-heap-after-gc! []
  (dotimes [_ 2] (System/gc) (Thread/sleep 75))
  (.getUsed (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))))

(defn- retained-views! [source]
  (let [before (used-heap-after-gc!)
        held (mapv (fn [_] (view source)) (range 50))
        after (used-heap-after-gc!)
        result {:views (count held) :heap-delta-bytes (- after before)
                :note "Forced-GC heap delta; includes JVM noise, not exact retained size."}]
    (java.lang.ref.Reference/reachabilityFence held)
    result))

(defn- acquisition! [views]
  (let [calls (atom 0)
        freeze snapshot/frozen-snapshot
        start (System/nanoTime)
        prepared
        (with-redefs [snapshot/frozen-snapshot
                      (fn [& arguments] (swap! calls inc) (apply freeze arguments))]
          (runtime/prepare
           (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n"
                "(define input 3) (define result (+ input 1))\n"
                (apply str (for [i (range views)]
                             (str "(define view" i " ((tracer:roots modifier:with-values "
                                  "modifier:high-level) result))\n")))) []))
        session (:session prepared)]
    (try
      (let [state @session network (:program/net state)
            observations (mapv (fn [i]
                                 (let [id (env/resolve-binding-id
                                           network (:program/env state) (symbol (str "view" i)))]
                                   (capture/observation-id
                                    (data/payload (net/network-cell-strongest network id)))))
                               (range views))]
        (when (some nil? observations)
          (throw (ex-info "Benchmark view did not acquire an observation" {:views views})))
        {:views views :frozen-snapshot-calls @calls
         :distinct-observations (count (set observations))
         :elapsed-milliseconds (/ (- (System/nanoTime) start) 1e6)})
      (finally (runtime/close-session! session)))))

(defn -main [& _]
  (let [source (facts/from-graph :benchmark :root (source-graph) #{})
        source-state (fn [revision status]
                       (propagators.stdlib.premise-state/states-of
                        (propagators.stdlib.source-publication/datum
                         ::row revision true status)))
        active (source-state 0 :active)
        newer (source-state 1 :active)
        withdrawn (source-state 1 :retracted)
        old (facts/claim source :benchmark/status [:row] :old active #{})
        results
        {:benchmark :observation-only
         :source-nodes 24 :source-assertions (count (facts/assertions source))
         :acquisition [(acquisition! 1) (acquisition! 2)]
         :operations
         [(measure! :source-fact-construction 40
                    #(facts/from-graph :benchmark :root (source-graph) #{}))
          (measure! :view-construction 40 #(view source))
          (measure! :single-fact-update-and-view 40
                    #(view (facts/claim old :benchmark/status [:row] :new newer #{})))
          (measure! :withdrawal-and-view 40
                    #(view (facts/update-premise-state old withdrawn)))]
         :retained-memory (retained-views! source)}]
    (prn results)
    (shutdown-agents)))
