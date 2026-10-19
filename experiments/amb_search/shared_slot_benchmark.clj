(ns experiments.amb-search.shared-slot-benchmark
  (:require [examples.lain.amb.checkpoint-benchmark :as measurement]
            [experiments.amb-search.constraint-benchmark :as constrained]
            [experiments.amb-search.shared-slot-application :as shared]))

(def modes [:ordinary :result-cell-slots :direct-layer-slots :shared-slots :indexed :indexed-shared-slots])

(defn sample [fixture mode]
  (case mode
    :ordinary (constrained/sample fixture :ordinary)
    :result-cell-slots (shared/with-result-cell-slots #(constrained/sample fixture :ordinary))
    :direct-layer-slots (shared/with-direct-layer-slots #(constrained/sample fixture :ordinary))
    :shared-slots (shared/with-shared-slots #(constrained/sample fixture :ordinary))
    :indexed (constrained/sample fixture :indexed)
    :indexed-shared-slots (shared/with-shared-slots #(constrained/sample fixture :indexed))))

(defn compare-case [kind repeats]
  (let [fixture (shared/audited-fixture #(constrained/prepare kind))
        warmups (into {} (map (fn [mode] [mode (sample fixture mode)]) modes))
        reference (get-in warmups [:ordinary :signature])]
    (doseq [[mode row] warmups]
      (assert (= :completed (:status row)) (str "Incomplete warmup: " mode))
      (assert (= reference (:signature row)) (str "Shared slot warmup parity failed: " mode)))
    (let [rows (reduce
                (fn [rows i]
                  (reduce (fn [rows mode]
                            (let [row (sample fixture mode)]
                              (assert (= :completed (:status row)))
                              (assert (= reference (:signature row)) (str "Shared slot parity failed: " mode))
                              (binding [*out* *err*]
                                (prn {:fixture kind :mode mode :ms (:run-ms row) :metrics (:metrics row)}))
                              (update rows mode (fnil conj []) (dissoc row :signature))))
                          rows (if (even? i) modes (reverse modes))))
                {} (range repeats))]
      {:fixture kind :solution (:solution reference)
       :modes (into {} (map (fn [[mode samples]] [mode (measurement/summarize samples)])) rows)})))

(defn -main [& args]
  (let [repeats (if (seq args) (Long/parseLong (first args)) 3)
        selected (if (next args) (mapv keyword (rest args)) [:opaque-5 :decomposed-5])]
    (try
      (assert (pos? repeats))
      (prn {:date "2026-10-19" :java (System/getProperty "java.version") :cache :disabled
            :warmups 1 :samples repeats :initialization :included
            :cases (mapv #(compare-case % repeats) selected)})
      (finally (shutdown-agents)))))
