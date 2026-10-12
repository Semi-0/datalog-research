(ns examples.lain.amb.benchmark
  "Search-cost baseline only: host enumeration is not an equivalent runtime."
  (:require [examples.lain.amb.builder :as builder]
            [propagators.experimental.amb.observation :as observation]
            [propagators.propagator :as prop]
            [propagators.network :as net]))

(defn exhaustive [n]
  (vec (for [a (range 1 (inc n)) b (range a (inc n)) c (range b (inc n))
             :when (builder/pythagorean? a b c)] [a b c])))

(defn chronological [n]
  (let [tuples (for [a (range 1 (inc n)) b (range 1 (inc n)) c (range 1 (inc n))]
                [a b c])]
    (loop [remaining (seq tuples) tested 0]
      (if remaining
        (let [candidate (first remaining)]
          (if (builder/pythagorean? (first candidate) (second candidate) (nth candidate 2))
            {:solution candidate :tested (inc tested)}
            (recur (next remaining) (inc tested))))
        {:solution nil :tested tested}))))

(defn measurement [n]
  (let [built (builder/build n builder/pythagorean?)
        run (builder/run built)
        network (get-in run [:result :network])
        knowledge (observation/cell-base network (get-in run [:cells :knowledge]))
        records (mapv #(observation/cell-base network %) (get-in run [:cells :controls]))
        started (System/nanoTime)
        baseline (chronological n)]
    {:domain n :runtime-status (get-in run [:result :status]) :solution (:solution run)
     :run-ms (:run-ms run) :activations (get-in run [:profile :totals :calls])
     :repairs (reduce + (map #(quot (dec (:timestamp %)) 2) records))
     :retained-nogoods (count knowledge)
     :initial-propagators (count (filter prop/prop? (vals (net/net-env (:network built)))))
     :final-propagators (count (filter prop/prop? (vals (net/net-env network))))
     :chronological (assoc baseline :ms (/ (- (System/nanoTime) started) 1e6))}))

(defn -main [& _]
  ;; Warm the same operations without including their first load in reported runs.
  (measurement 5)
  (doseq [n [4 5 20]] (prn (measurement n))))
