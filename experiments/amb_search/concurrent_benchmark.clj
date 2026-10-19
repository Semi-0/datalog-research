(ns experiments.amb-search.concurrent-benchmark
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.checkpoint-benchmark :as measurement]
            [examples.lain.amb.worker-driver :as workers]
            [experiments.amb-search.concurrent-runner :as concurrent]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.network :as net]
            [propagators.network-cache :as cache]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(def modes [:ordinary :direct :pool-2 :pool-4])
(def ^:dynamic *activation-depth* 0)

(defn access-plan
  "Audited cell reads of this builder's choice/require activations only. The
  layered calculations and declaration-producing learner/strategy are barriers."
  [built]
  (let [known (into {} (filter (fn [[_ entry]]
                               (and (prop/prop? entry)
                                    (contains? #{::amb/amb ::amb/require} (prop/prop-name entry)))))
                    (net/net-env (:network built)))]
    (fn [network id]
      (when-let [original (get known id)]
        (let [current (net/network-lookup-propagator network id)
              node (get (net/net-graph network) id)
              inputs (:inputs node) outputs (:outputs node)]
          (when (and (= node (get (net/net-graph (:network built)) id))
                     (identical? (prop/prop-f original) (prop/prop-f current)))
            (case (prop/prop-name current)
              ::amb/amb
              (let [domain (first (disj inputs (get-in built [:cells :repairs])))
                    v (observation/cell-base network domain)]
                (when (or (vector? v) (value/unusable? v))
                  (concurrent/access inputs outputs)))
              ::amb/require
              (let [premises (datum/support-of
                              (net/network-cell-strongest network (first inputs)))]
                (concurrent/access (into inputs (map :source) premises) outputs)))))))))

(defn signature [built network]
  (let [read-cell #(observation/cell-base network %)
        output (read-cell (get-in built [:cells :output]))]
    {:solution (when (vector? output) output)
     :knowledge (read-cell (get-in built [:cells :knowledge]))
     :repair (read-cell (get-in built [:cells :repairs]))
     :choices (mapv read-cell (get-in built [:cells :controls]))}))

(defn sample [built mode pools]
  (let [accesses (access-plan built)
        started (System/nanoTime)
        count-state (atom {:total 0 :top 0})
        activate runner/evaluate-propagator
        result (binding [cache/*cache* nil]
                 ;; Count both the outer activations and nested propagation.
                 ;; This redefinition is confined to the isolated benchmark JVM.
                 (with-redefs [runner/evaluate-propagator
                               (fn [id network handlers]
                                 (swap! count-state
                                        (fn [counts]
                                          (cond-> (update counts :total inc)
                                            (zero? *activation-depth*) (update :top inc))))
                                 (binding [*activation-depth* (inc *activation-depth*)]
                                   (activate id network handlers)))]
                  (case mode
                   :ordinary
                     (let [execution (runner/run-network (:tasks built) (:network built))]
                       {:network (runner/completed-network execution)
                        :metrics {:commits (:top @count-state)
                                  :activation-dispatches (:top @count-state)
                                  :batches 0 :parallel-batches 0 :max-batch 1}
                        :threads #{(.getName (Thread/currentThread))}})
                   :direct (concurrent/run nil 1 built accesses)
                   :pool-2 (concurrent/run (get pools 2) 2 built accesses)
                   :pool-4 (concurrent/run (get pools 4) 4 built accesses))))
        finished (System/nanoTime)
        sig (signature built (:network result))]
    {:mode mode :status :completed :solution (:solution sig)
     :run-ms (/ (- finished started) 1e6)
     :signature sig
     :metrics (assoc (:metrics result) :total-activations (:total @count-state))
     :threads (:threads result)}))

(defn compare-bound [n repeats pools]
  (let [built (builder/build n builder/pythagorean?)
        reference (sample built :ordinary pools)
        _ (assert (= (:solution (oracle/chronological n)) (get-in reference [:signature :solution])))
        checked (fn [mode]
                  (let [row (sample built mode pools)]
                    (assert (= (:signature reference) (:signature row)) (str "FIFO parity failed: " mode))
                    (dissoc row :signature)))]
    (doseq [mode (rest modes)] (checked mode))
    (let [rows (reduce (fn [rows index]
                         (reduce (fn [rows mode]
                                   (let [row (checked mode)]
                                     (binding [*out* *err*]
                                       (prn (select-keys row [:mode :run-ms :metrics])))
                                     (update rows mode (fnil conj []) row)))
                                 rows (if (even? index) modes (reverse modes))))
                       {} (range repeats))]
      {:domain n :samples repeats :solution (get-in reference [:signature :solution])
       :modes (into {} (map (fn [[mode samples]] [mode (measurement/summarize samples)])) rows)})))

(defn -main [& args]
  (let [repeats (if (seq args) (Long/parseLong (first args)) 3)
        bound (if (next args) (Long/parseLong (second args)) 5)
        pools (into {} (map (fn [width] [width (workers/pool width)]) [2 4]))]
    (try
      (assert (pos? repeats))
      (prn (assoc (compare-bound bound repeats pools)
                  :date "2026-10-19" :java (System/getProperty "java.version")
                  :admission :conflict-free :reevaluations 0 :commit-order :fifo :cache :disabled))
      (finally (doseq [pool (vals pools)] (workers/close! pool))
               (shutdown-agents)))))
