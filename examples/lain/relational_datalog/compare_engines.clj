(ns examples.lain.relational-datalog.compare-engines
  "Public-API comparisons only; no changes to any Datalog engine."
  (:require [clojure.set :as sets]
            [differential-leapfrog.core :as differential]
            [examples.lain.relational-datalog.benchmark :as benchmark]
            [examples.lain.relational-datalog.experiment :as experiment]
            [examples.lain.relational-datalog.semi-naive :as semi]
            [examples.lain.relational-datalog.transitive :as transitive]
            [leapfrog :as leapfrog]
            [leapfrog-pure :as pure]))

(def rules
  [{:head [:reach [:x :y]] :body [[:edge [:x :y]]]}
   {:head [:extension [:x :z]] :body [[:reach [:x :y]] [:edge [:y :z]]]}
   {:head [:reach [:x :z]] :body [[:extension [:x :z]]]}])

(def engines [:lain-naive :lain-semi-naive :leapfrog :leapfrog-pure :differential])

(def fixtures
  [{:name :chain :batches [#{[1 2] [2 3] [3 4]}]}
   {:name :diamond :batches [#{[1 2] [1 3] [2 4] [3 4]}]}
   {:name :cycle :batches [#{[1 2] [2 3] [3 1]}]}
   {:name :late-bridge :batches [#{[1 2]} #{[3 4]} #{[2 3]}]}])

(defn initial-state [engine]
  (case engine
    :lain-naive (experiment/initialize transitive/source {:bindings (semi/bindings :naive)})
    :lain-semi-naive (experiment/initialize transitive/source {:bindings (semi/bindings :semi-naive)})
    :leapfrog {}
    :leapfrog-pure {}
    :differential {:edb {} :idb {} :time 0 :history [] :rules rules :idb-preds #{:reach :extension}}
    (throw (ex-info "Unknown benchmark engine" {:engine engine}))))

(defn append-facts
  "Carry retained relations across batches using an engine's existing delta step."
  [step facts edges]
  (let [fresh (sets/difference edges (get facts :edge #{}))]
    (loop [facts (update facts :edge (fnil into #{}) fresh) delta {:edge fresh}]
      (if (every? empty? (vals delta))
        facts
        (let [derived (step facts delta rules)]
          (recur (merge-with into facts derived) derived))))))

(defn advance [engine state edges]
  (case engine
    :lain-naive (experiment/add-edges state edges)
    :lain-semi-naive (experiment/add-edges state edges)
    :leapfrog (append-facts leapfrog/semi-naive-step state edges)
    :leapfrog-pure (append-facts pure/semi-naive-step state edges)
    :differential
    (let [fresh (sets/difference edges (differential/support (differential/view state) :edge))]
      (differential/transact state {:edge (zipmap fresh (repeat 1))}))
    (throw (ex-info "Unknown benchmark engine" {:engine engine}))))

(defn read-facts [engine state]
  (case engine
    (:lain-naive :lain-semi-naive)
    (into {} (map (fn [key] [key (transitive/tuples state key)]) [:edge :reach :extension]))
    (:leapfrog :leapfrog-pure)
    (into {} (map (fn [key] [key (get state key #{})]) [:edge :reach :extension]))
    :differential
    (let [view (differential/view state)]
      (into {} (map (fn [key] [key (differential/support view key)]) [:edge :reach :extension])))
    (throw (ex-info "Unknown benchmark engine" {:engine engine}))))

(defn expected-facts [batches]
  (let [edges (reduce sets/union #{} batches)
        reach (transitive/closure edges)]
    {:edge edges :reach reach :extension (transitive/extensions reach edges)}))

(defn comparable? [engine fixture]
  ;; Recursive weighted derivation counts are not a Boolean cyclic fixed point.
  (not (and (= engine :differential) (= :cycle (:name fixture)))))

(defn repetitions [engine]
  (case engine
    (:leapfrog :leapfrog-pure) 100
    (:lain-naive :lain-semi-naive :differential) 1
    (throw (ex-info "Unknown benchmark engine" {:engine engine}))))

(defn sample [engine initialized fixture times]
  (let [started (System/nanoTime)
        state (loop [remaining times state nil]
                (if (zero? remaining)
                  state
                  (recur (dec remaining)
                         (reduce #(advance engine %1 %2) initialized (:batches fixture)))))
        elapsed (/ (- (System/nanoTime) started) (* times 1e6))
        actual (read-facts engine state)
        expected (expected-facts (:batches fixture))]
    (if (= expected actual)
      {:ms elapsed :counts (update-vals actual count)
       :nodes (if (contains? #{:lain-naive :lain-semi-naive} engine)
                (count (experiment/topology state))
                nil)}
      (throw (ex-info "Datalog engine disagrees with transitive specification"
                      {:engine engine :case (:name fixture) :expected expected :actual actual})))))

(defn compare-case [fixture initializations]
  (let [eligible (filterv #(comparable? % fixture) engines)
        _ (doseq [_ (range 2) engine eligible]
            (sample engine (get initializations engine) fixture (repetitions engine)))
        samples (reduce (fn [all index]
                          (let [order (if (even? index) eligible (reverse eligible))]
                            (reduce (fn [acc engine]
                                      (update acc engine conj
                                              (sample engine (get initializations engine)
                                                      fixture (repetitions engine))))
                                    all order)))
                        (zipmap eligible (repeat [])) (range 7))]
    {:case (:name fixture)
     :excluded (filterv #(not (comparable? % fixture)) engines)
     :results (mapv (fn [engine]
                      (let [rows (get samples engine)]
                        {:engine engine :samples (count rows) :repetitions (repetitions engine)
                         :median-ms (benchmark/median (map :ms rows))
                         :min-ms (apply min (map :ms rows)) :max-ms (apply max (map :ms rows))
                         :counts (mapv :counts rows) :nodes (mapv :nodes rows)}))
                    eligible)}))

(defn prepare-engines []
  (doseq [_ (range 2) engine engines] (initial-state engine))
  (let [samples (reduce (fn [all index]
                          (let [order (if (even? index) engines (reverse engines))]
                            (reduce (fn [acc engine]
                                      (let [started (System/nanoTime)
                                            state (initial-state engine)]
                                        (update acc engine conj
                                                {:state state :ms (/ (- (System/nanoTime) started) 1e6)})))
                                    all order)))
                        (zipmap engines (repeat [])) (range 7))]
    (update-vals samples (fn [rows] {:state (:state (last rows))
                                    :median-ms (benchmark/median (map :ms rows))}))))

(defn -main [& args]
  (if (empty? args)
    (try
      (let [prepared (prepare-engines)
            initializations (update-vals prepared :state)]
        (prn {:initialization-median-ms (update-vals prepared :median-ms)})
        (doseq [fixture fixtures]
          (prn (compare-case fixture initializations))))
      (finally (shutdown-agents)))
    (throw (ex-info "Comparison takes no arguments" {:args args}))))
