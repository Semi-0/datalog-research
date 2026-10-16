(ns graph.lain-editor.graph-layout-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.graph-layout :as layout]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.runtime :as runtime]))

(def request
  {:pane "a" :generation "one" :parent nil
   :nodes [{:id "x" :label "X" :size [80 22]} {:id "y" :label "Y" :size [80 22]}]
   :edges [["x" "y"]] :moves []})

(deftest vijual-stream-retains-work-and-closes-owned-flows
  (let [registry (atom {})]
    (try
      (let [initial-result (layout/compute! registry request)
            entry (get @registry "a")
            second (layout/compute! registry request)
            moved (layout/compute! registry (assoc request :moves [{:id "x" :position [50 60]}]))
            latest (layout/compute! registry request)]
        (is (identical? (:input entry) (:input (get @registry "a"))))
        (is (< (:revision initial-result) (:revision second) (:revision moved) (:revision latest)))
        (is (= 0 (get-in second [:metrics :distance-sources-recomputed])))
        (is (= [50 60] (:position (first (filter #(= "x" (:id %)) (:nodes latest))))))
        (is (every? #(every? (fn [n] (Double/isFinite (double n))) (:position %)) (:nodes latest)))
        (let [before (:nodes (get @registry "a"))]
          (is (thrown? Exception (layout/compute! registry (assoc-in request [:nodes 0 :size] [-1 22]))))
          (is (= before (:nodes (get @registry "a")))))
        (let [replacement (layout/compute! registry (assoc request :generation "two"))]
          (is (seq (:nodes replacement)))
          (is (not (identical? (:input entry) (:input (get @registry "a")))))))
      (layout/close-pane! registry "a")
      (is (empty? @registry))
      (finally (layout/close! registry)))))

(deftest topology-transactions-remove-incident-edges
  (let [registry (atom {})]
    (try
      (layout/compute! registry request)
      (let [next-request (assoc request
                                :nodes [{:id "x" :label "renamed X" :size [100 22]}
                                        {:id "z" :label "Z" :size [50 22]}]
                                :edges [["x" "z"]])
            result (layout/compute! registry next-request)]
        (is (= #{"x" "z"} (set (map :id (:nodes result)))))
        (is (= #{["x" "z"]} (:edges (get @registry "a"))))
        (is (pos? (get-in result [:metrics :distance-sources-recomputed])))
        (is (some #(and (= -1 (second %)) (= "y" (:id (first %)))) (:deltas result)))
        (is (thrown? Exception (layout/compute! registry (assoc next-request :edges [["x" "missing"]]))))
        (is (= #{["x" "z"]} (:edges (get @registry "a")))))
      (finally (layout/close! registry)))))

(deftest runtime-boundaries-retire-flows-only-after-success
  (let [editors (atom {}) layouts (atom {})]
    (try
      (let [generation (:generation (server/command! editors layouts {:op "run" :pane "a" :source "(define result 1)"}))
            command (assoc request :op "layout" :generation generation)]
        (server/command! editors layouts command)
        (let [input (:input (get @layouts "a"))]
          (is (thrown? Exception (server/command! editors layouts {:op "run" :pane "a" :source "("})))
          (is (identical? input (:input (get @layouts "a")))))
        (server/command! editors layouts {:op "run" :pane "a" :source "(define result 2)"})
        (is (empty? @layouts))
        (is (thrown? Exception (server/command! editors layouts command)))
        (server/command! editors layouts (assoc command :generation (:generation (get @editors "a"))))
        (server/command! editors layouts {:op "close" :pane "a"})
        (is (empty? @layouts))
        (is (empty? @editors)))
      (finally (layout/close! layouts) (runtime/close! editors)))))

(deftest startup-frames-are-real-ordered-vijual-states
  (let [registry (atom {}) baseline (atom {})]
    (try
      (let [animated (layout/compute! registry (assoc request :animate true))
            direct (layout/compute! baseline request)
            frames (:frames animated) final-nodes (:nodes animated)]
        (is (= 0 (:iteration (first frames))))
        (is (<= 2 (count frames) 16))
        (is (apply <= (map :iteration frames)))
        (is (= (:iterationsUsed animated) (:iteration (last frames))))
        (is (<= (:iterationsUsed animated) 300))
        (is (= final-nodes (:nodes (last frames))))
        (is (= (mapv :position (:nodes direct)) (mapv :position final-nodes)))
        (is (not= (mapv :position (:nodes (first frames))) (mapv :position final-nodes)))
        (is (every? #(= #{"x" "y"} (set (map :id (:nodes %)))) frames))
        (is (= 0 (get-in (layout/compute! registry request) [:metrics :distance-sources-recomputed]))))
      (finally (layout/close! registry) (layout/close! baseline)))))

(deftest animated-batches-preserve-a-longer-solve-and-zero-work-cases
  (let [registry (atom {}) baseline (atom {})
        request (assoc request
                       :nodes (mapv (fn [i] {:id (str i) :label (str i) :size [60 24]}) (range 8))
                       :edges (mapv (fn [i] [(str i) (str (mod (inc i) 8))]) (range 8)))]
    (try
      (let [animated (layout/compute! registry (assoc request :animate true))
            direct (layout/compute! baseline request)]
        (is (> (count (:frames animated)) 2))
        (is (<= (:iterationsUsed animated) 300))
        (is (= (mapv :position (:nodes direct)) (mapv :position (:nodes animated))))
        (is (thrown? Exception (layout/compute! registry (assoc request :animate "yes")))))
      (doseq [nodes [[] [{:id "only" :size [28 24]}]]]
        (let [result (layout/compute! registry (assoc request :generation (str (count nodes)) :nodes nodes :edges [] :animate true))]
          (is (= 0 (:iterationsUsed result)))
          (is (= nodes (mapv #(dissoc % :position) (:nodes result))))))
      (finally (layout/close! registry) (layout/close! baseline)))))

(deftest pane-flow-applies-balanced-preferences-without-changing-node-sizes
  (let [registry (atom {})
        diamond (assoc request
                       :nodes (mapv (fn [id] {:id id :size [60 24]}) ["a" "b" "c" "d"])
                       :edges [["a" "b"] ["a" "c"] ["b" "d"] ["c" "d"]])]
    (try
      (let [result (layout/compute! registry diamond)]
        (is (pos? (get-in result [:metrics :preference-edge-evaluations])))
        (is (pos? (get-in result [:metrics :conflict-checks])))
        (is (= 0 (get-in result [:metrics :edge-crossings])))
        (is (= 0 (get-in result [:metrics :node-overlaps])))
        (is (= (mapv :size (:nodes diamond)) (mapv :size (:nodes result))))
        (is (<= (get-in result [:metrics :iterations]) 300)))
      (finally (layout/close! registry)))))

(deftest initial-layout-uses-directed-layers-before-stress-relaxation
  (let [registry (atom {})
        directed (assoc request :nodes [{:id "z" :size [44 32]} {:id "a" :size [44 32]}
                                       {:id "m" :size [44 32]}]
                                :edges [["z" "a"] ["a" "m"]] :animate true)]
    (try
      (let [result (layout/compute! registry directed)
            seeds (into {} (map (juxt :id :position)) (:nodes (first (:frames result))))]
        (is (< (first (seeds "z")) (first (seeds "a")) (first (seeds "m"))))
        (is (= 1 (count (set (map second (vals seeds))))))
        (is (= 0 (get-in result [:metrics :edge-crossings])))
        (is (= 0 (get-in result [:metrics :edge-node-overlaps]))))
      (finally (layout/close! registry)))))
