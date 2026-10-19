(ns experiments.amb-search.constraint-benchmark
  (:require [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.checkpoint-benchmark :as measurement]
            [examples.lain.amb.experiment :as topology]
            [examples.lain.amb.search-budget :as budget]
            [experiments.amb-search.constraint-fixtures :as fixtures]
            [experiments.amb-search.indexed-benchmark :as benchmark]
            [propagators.runner :as runner]))

(def limits {:milliseconds 45000 :activations 1000000})

(defn prepare [kind]
  (case kind
    :opaque-5 {:built (builder/build 5 builder/pythagorean?) :choices 3 :domain 5
               :answers (set (oracle/exhaustive 5))}
    :decomposed-5 {:built (fixtures/decomposed-pythagorean 5) :choices 3 :domain 5
                   :answers (set (oracle/exhaustive 5))}
    :queens-4 {:built (fixtures/queens 4) :choices 4 :domain 4
               :answers (set (fixtures/queens-oracle 4))}
    :queens-5 {:built (fixtures/queens 5) :choices 5 :domain 5
               :answers (set (fixtures/queens-oracle 5))}))

(defn sample [fixture mode]
  (let [scope (budget/scope limits)
        activate runner/evaluate-propagator]
    (try
      (let [row (with-redefs [runner/evaluate-propagator
                             (fn [id network handlers]
                               (let [n (swap! (:counts scope) inc)]
                                 (when (and (zero? (bit-and n 127))
                                            (or (> n (:activations limits))
                                                (> (:elapsed-ms (budget/usage scope)) (:milliseconds limits))))
                                   (throw (ex-info "Constraint benchmark budget exhausted"
                                                   {:type ::budget/exhausted :usage (budget/usage scope)}))))
                               (activate id network handlers))]
                  (benchmark/sample (:built fixture) mode))]
        (assert (or (contains? (:answers fixture) (:solution row))
                    (and (empty? (:answers fixture)) (nil? (:solution row))))
                (str "Constraint answer differs from exhaustive oracle: " (:solution row)))
        row)
      (catch Throwable error
        (if (budget/exhausted? error)
          {:status :bounded :usage (budget/usage scope)}
          (throw error))))))

(defn compare-case [kind repeats]
  (let [fixture (prepare kind)
        warmups (into {} (map (fn [mode] [mode (sample fixture mode)]) [:ordinary :indexed]))
        complete? (every? #(= :completed (:status %)) (vals warmups))]
    (if-not complete?
      {:fixture kind :status :bounded :warmups (into {} (map (fn [[k v]] [k (dissoc v :signature)])) warmups)}
      (let [signature (get-in warmups [:ordinary :signature])
            _ (assert (= signature (get-in warmups [:indexed :signature])))
            rows (reduce (fn [rows i]
                           (reduce (fn [rows mode]
                                     (let [row (sample fixture mode)]
                                       (assert (= :completed (:status row)) "Measured sample reached its budget")
                                       (assert (= signature (:signature row)) "Indexed search changed its path or learning")
                                       (binding [*out* *err*]
                                         (prn {:fixture kind :mode mode :ms (:run-ms row) :metrics (:metrics row)}))
                                       (update rows mode (fnil conj []) (dissoc row :signature))))
                                   rows (if (even? i) [:ordinary :indexed] [:indexed :ordinary])))
                         {} (range repeats))]
        {:fixture kind :status :completed :choices (:choices fixture) :domain (:domain fixture)
         :possible-combinations (long (Math/pow (:domain fixture) (:choices fixture)))
         :initial-topology (topology/topology (get-in fixture [:built :network]))
         :solution (:solution signature)
         :modes (into {} (map (fn [[mode rows]] [mode (measurement/summarize rows)])) rows)}))))

(defn -main [& args]
  (let [repeats (if (seq args) (Long/parseLong (first args)) 3)
        selected (if (next args) (mapv keyword (rest args)) [:opaque-5 :decomposed-5 :queens-4])]
    (try
      (assert (pos? repeats))
      (prn {:date "2026-10-19" :java (System/getProperty "java.version") :cache :disabled
            :warmups 1 :samples repeats :limits limits
            :cases (mapv #(compare-case % repeats) selected)})
      (finally (shutdown-agents)))))
