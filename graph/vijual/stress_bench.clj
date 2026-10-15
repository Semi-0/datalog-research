(ns graph.vijual.stress-bench
  "Repeatable exact-layout benchmark. Run with -J-Xmx1g -M -m graph.vijual.stress-bench.
  Reports warmed median/min/max latency and calling-thread allocation, not live heap."
  (:require [graph.vijual.layout.nodes :as nodes]
            [graph.vijual.layout.node-model :as model]
            [graph.vijual.layout.stress :as stress]))

(defn fixture [n]
  {:nodes (mapv (fn [i] {:id i
                         :position [(* 100.0 (Math/cos i)) (* 100.0 (Math/sin i))]
                         :size [(+ 20 (mod i 5)) 20]}) (range n))
   :edges (vec (concat (mapv vector (range (dec n)) (range 1 n))
                       (mapv vector (range (max 0 (- n 7))) (range 7 n))))})

(defn reference-pairs [items edges opts]
  (let [radii (into {} (map (fn [{:keys [id size]}]
                             [id (* 0.5 (apply #(Math/hypot %1 %2) size))]) items))]
    (mapv (fn [{:keys [a b distance] :as pair}]
            (assoc pair :distance (max distance (+ (radii a) (radii b) (:gap opts)))))
          (stress/stress-pairs (mapv :id items) edges
                               {:stress-node-spacing (:edge-length opts)
                                :stress-disconnected-weight (:disconnected-weight opts)}))))

(defn reference-layout [input edges iterations]
  ;; Retain the original map-based formula as an independent numerical oracle.
  ;; Benchmark fixtures have distinct centers and no pins.
  (let [opts (model/options {}) items (model/normalize input opts)
        ids (mapv :id items) pairs (reference-pairs items edges opts)]
    (model/validate-edges! ids edges)
    (loop [positions (into {} (map (juxt :id :position) items)) remaining iterations]
      (if (zero? remaining)
        (mapv (fn [node] (assoc node :position (positions (:id node)))) input)
        (let [next (stress/stress-majorization-step ids pairs positions)]
          (recur (into {} (map (fn [id]
                                 [id (mapv #(* 0.5 (+ %1 %2)) (positions id) (next id))]) ids))
                 (dec remaining)))))))

(defn- allocation-reader []
  (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
    (if (and (instance? com.sun.management.ThreadMXBean bean)
             (.isThreadAllocatedMemorySupported ^com.sun.management.ThreadMXBean bean))
      (do (.setThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean bean true)
          (fn [] (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean
                                           (.getId (Thread/currentThread)))))
      (constantly 0))))

(defn- measure [allocated f]
  (let [start (System/nanoTime) bytes (allocated) result (f)]
    {:ms (/ (- (System/nanoTime) start) 1.0e6)
     :allocated-mb (/ (- (allocated) bytes) 1048576.0)
     :work (:metrics result)}))

(defn- median [values] (nth (vec (sort values)) (quot (count values) 2)))

(defn- report [allocated n phase f]
  (let [samples (mapv (fn [_] (measure allocated f)) (range 3))]
    (prn {:nodes n :phase phase :median-ms (median (map :ms samples))
          :min-ms (apply min (map :ms samples)) :max-ms (apply max (map :ms samples))
          :allocated-mb (median (map :allocated-mb samples)) :work (:work (last samples))})
    (flush)))

(defn- quality [input edges result reference]
  (let [pairs (reference-pairs (model/normalize input nodes/default-options) edges nodes/default-options)
        actual (into {} (map (juxt :id :position) result))
        expected (into {} (map (juxt :id :position) reference))]
    {:max-coordinate-error
     (reduce max 0.0 (mapcat (fn [id] (map #(Math/abs (double (- %1 %2)))
                                         (actual id) (expected id))) (keys actual)))
     :reference-stress (stress/graph-distance-error pairs expected)
     :optimized-stress (stress/graph-distance-error pairs actual)}))

(defn- sparse-case [n]
  (let [input (mapv (fn [i] {:id i :position [(* 80 i) 0] :size [0 0]}) (range n))
        state (nodes/create-layout input (mapv vector (range (dec n)) (range 1 n))
                                    {:pinned-ids (set (range 1 n))})]
    (nodes/update-layout (nodes/advance-layout state)
                         {:upsert-nodes [(assoc (first input) :position [5 15])]})))

(defn- benchmark-size [allocated n iterations]
  (let [{:keys [nodes edges]} (fixture n)
        prepared (nodes/create-layout nodes edges)
        position-edit {:upsert-nodes [(assoc (first nodes) :position [25 15])]}
        edited (nodes/update-layout prepared position-edit)
        sparse (sparse-case n)
        component-edges (filterv (fn [[a b]] (= (quot a 25) (quot b 25))) edges)
        components (nodes/create-layout nodes component-edges)]
    (doseq [[phase f]
            [[:reference-batch #(reference-layout nodes edges iterations)]
             [:optimized-batch #(nodes/layout-nodes nodes edges {:iterations iterations})]
             [:prepare #(nodes/create-layout nodes edges)]
             [:prepared-relaxation #(nodes/advance-layout prepared {:iterations iterations})]
             [:no-op #(nodes/update-layout prepared {})]
             [:metadata-edit #(nodes/update-layout prepared
                                                   {:upsert-nodes [(assoc (first nodes) :label "changed")]})]
             [:position-edit #(nodes/update-layout prepared position-edit)]
             [:size-edit #(nodes/update-layout prepared
                                               {:upsert-nodes [(assoc (first nodes) :size [100 60])]})]
             [:connected-edge-edit #(nodes/update-layout prepared {:add-edges [[0 (dec n)]]})]
             [:component-edge-edit #(nodes/update-layout components {:add-edges [[0 (min 24 (dec n))]]})]
             [:isolated-node-add #(nodes/update-layout prepared
                                                       {:upsert-nodes [{:id n :position [400 200] :size [20 20]}]})]
             [:position-edit-and-relax #(nodes/advance-layout
                                         (nodes/update-layout prepared position-edit) {:iterations iterations})]
             [:sparse-pinned-relaxation #(nodes/advance-layout sparse {:iterations 1})]]]
      (report allocated n phase f))
    (prn (merge {:nodes n :phase :quality}
                (quality nodes edges (:nodes (nodes/advance-layout prepared {:iterations iterations}))
                         (reference-layout nodes edges iterations))))
    (prn (merge {:nodes n :phase :edited-quality}
                (quality (:nodes edited) edges
                         (:nodes (nodes/advance-layout edited {:iterations iterations}))
                         (reference-layout (:nodes edited) edges iterations))))
    (flush)))

(defn -main [& sizes]
  (let [sizes (if (seq sizes) (mapv parse-long sizes) [100 250 500 1000])
        allocated (allocation-reader)
        warmup (fixture 100) iterations 10]
    (model/require-valid! (every? #(and (integer? %) (<= 3 %)) sizes)
                          "Benchmark sizes must be integers >= 3" {:sizes sizes})
    (dotimes [_ 6]
      (reference-layout (:nodes warmup) (:edges warmup) iterations)
      (nodes/layout-nodes (:nodes warmup) (:edges warmup) {:iterations iterations}))
    (prn {:java (System/getProperty "java.version") :clojure (clojure-version)
          :shape :path-with-distance-7-chords :samples 3 :iterations iterations
          :warmup-calls-per-implementation 6 :allocation :calling-thread-cumulative-mb})
    (doseq [n sizes] (benchmark-size allocated n iterations))
    (shutdown-agents)))
