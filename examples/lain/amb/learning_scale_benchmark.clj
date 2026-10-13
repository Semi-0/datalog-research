(ns examples.lain.amb.learning-scale-benchmark
  "Larger compiled questions, seven unchanged solver protocols, explicit budgets."
  (:require [examples.lain.amb.checkpoint-benchmark :as measurement]
            [examples.lain.amb.knowledge :as knowledge]
            [examples.lain.amb.learning-checkpoint-benchmark :as experiment]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.search-budget :as budget]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.network-cache :as cache]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(def modes (into experiment/modes [:partitioned-learning :concurrent-learning]))
(def limits {:milliseconds 30000 :activations 1000000})

(defn chain [size]
  (if (and (integer? size) (> size 1))
    {:id :graph-chain :domain (vec (range 1 (inc size)))
     :edges (set (map vector (range 1 size) (range 2 (inc size))))}
    (throw (ex-info "Chain size must exceed one" {:size size}))))

(defn install [state installer]
  (let [[id network] (installer (:network state))]
    {:network network :tasks (conj (:tasks state) id)}))

(defn diamond [cells offset]
  (let [[input left right sum output] (subvec cells offset (+ offset 5))]
    [((prop/concrete-primitive-propagator ::inc inc) input left)
     ((prop/concrete-primitive-propagator ::double #(* 2 %)) input right)
     ((prop/concrete-primitive-propagator ::sum +) left right sum)
     ((prop/concrete-primitive-propagator ::inc inc) sum output)]))

(defn dataflow [stages]
  (if (and (integer? stages) (pos? stages))
    (let [size (inc (* 4 stages)) cells (vec (repeatedly size ids/new-node-id))
          initial {:network (nb/seed-cell (nb/install-cells cells) (first cells) 3) :tasks []}
          installed (reduce install initial (mapcat #(diamond cells (* 4 %)) (range stages)))
          completed (runner/run-network (:tasks installed) (:network installed))]
      (case (:status completed)
        :completed {:id :dataflow-diamonds :domain (vec (range 1 (inc size)))
                    :edges (knowledge/dataflow-edges (:network completed) cells)
                    :subject-cells cells :subject-network (:network completed)}
        :failed (throw (:error completed))
        (throw (ex-info "Unknown dataflow fixture status" {:status (:status completed)}))))
    (throw (ex-info "Positive dataflow stage count required" {:stages stages}))))

(defn fixture [id]
  (binding [cache/*cache* nil]
    (case id
      :suffix-8 (experiment/prepare-fixture (chain 8) :suffix)
      :suffix-12 (experiment/prepare-fixture (chain 12) :suffix)
      :dataflow-9 (experiment/prepare-fixture (dataflow 2) :conjunction)
      (throw (ex-info "Unknown scale fixture" {:id id})))))

(defn sample [fixture mode pool]
  (let [scope (budget/scope limits)]
    (try
      (binding [profiling/*advance-transform* (budget/transform scope)]
        (-> (experiment/sample fixture mode pool)
            (dissoc :answers :proof-examples)
            (assoc :answer-count (count (:expected fixture)) :budget-usage (budget/usage scope))))
      (catch Throwable error
        (if (budget/exhausted? error)
          {:status :incomplete :reason :budget-exhausted :budget-usage (budget/usage scope)}
          (throw error))))))

(defn measured [fixture mode pool]
  (let [row (sample fixture mode pool)]
    (binding [*out* *err*]
      (prn {:shape (:shape fixture) :size (count (:domain fixture)) :mode mode
            :status (:status row) :ms (:run-ms row) :search (:search row)
            :budget-usage (:budget-usage row)}))
    row))

(defn summary [rows]
  (if (every? #(= :completed (:status %)) rows)
    (assoc (measurement/summarize rows) :status :completed)
    {:status :incomplete :median-ms nil :samples rows}))

(defn compare-case [id pool repeats]
  (let [fixture (fixture id)
        warmups (into {} (map (fn [mode] [mode (measured fixture mode pool)]) modes))
        ;; A timed-out warmup remains visible, but is not repeated three times.
        viable (filterv #(= :completed (:status (get warmups %))) modes)
        rows (reduce
              (fn [rows index]
                (reduce (fn [rows mode]
                          (update rows mode (fnil conj []) (measured fixture mode pool)))
                        rows (if (even? index) viable (reverse viable))))
              {} (range repeats))]
    {:id id :shape (:shape fixture) :size (count (:domain fixture))
     :assignments (long (Math/pow (count (:domain fixture)) 3))
     :expected (:expected fixture) :edges (vec (sort (:edges fixture)))
     :source (get-in fixture [:compiled :source]) :setup (:setup fixture)
     :warmups warmups
     :modes (into {} (map (fn [mode]
                           [mode (if (contains? rows mode)
                                   (summary (get rows mode))
                                   (summary [(get warmups mode)]))]) modes))}))

(defn -main [& arguments]
  (let [repeats (if (seq arguments) (Long/parseLong (first arguments)) 3)
        selected (if (next arguments) (mapv keyword (rest arguments))
                     [:suffix-8 :suffix-12 :dataflow-9])]
    (if (and (pos? repeats) (seq selected))
      (let [pool (driver/pool 4)]
        (try
          (prn {:experiment :concurrent-learning-scale :date "2026-10-13"
                :samples repeats :warmups 1 :workers 4 :cache :disabled
                :learning :partition-local :limits limits :java (System/getProperty "java.version")
                :max-heap-bytes (.maxMemory (Runtime/getRuntime))
                :cases (mapv #(compare-case % pool repeats) selected)})
          (finally (driver/close! pool))))
      (throw (ex-info "Positive repeats and at least one fixture required" {})))))
