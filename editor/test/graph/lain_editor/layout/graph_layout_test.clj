(ns graph.lain-editor.layout.graph-layout-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.layout.graph-layout :as layout]
            [graph.lain-editor.layout.node-clearance :as clearance]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.runtime.session :as runtime]))

(def request
  {:pane "a" :generation "one" :parent nil
   :nodes [{:id "x" :label "X" :size [80 22]} {:id "y" :label "Y" :size [80 22]}]
   :edges [["x" "y"]] :moves []})

(deftest node-spacing-increases-distances-without-changing-cards-or-topology
  (let [registry (atom {})
        separation (fn [result]
                     (let [[[ax ay] [bx by]] (map :position (:nodes result))]
                       (Math/hypot (- ax bx) (- ay by))))]
    (try
      (let [normal (layout/compute! registry request)
            roomy (layout/compute! registry (assoc request :spacing 2))
            repeated (layout/compute! registry (assoc request :spacing 2))
            restored (layout/compute! registry request)]
        (is (> (separation roomy) (* 1.5 (separation normal))))
        (is (= (mapv #(dissoc % :position) (:nodes normal))
               (mapv #(dissoc % :position) (:nodes roomy))))
        (is (not (apply #(clearance/conflict? %1 %2 56) (:nodes roomy))))
        (is (= #{["x" "y"]} (:edges (get @registry "a"))))
        (is (= 2 (:spacing roomy)))
        (is (pos? (:iterationsUsed roomy)))
        (is (= 0 (:iterationsUsed repeated)))
        (is (= (:nodes roomy) (:nodes repeated)))
        (is (< (separation restored) (separation roomy)))
        (is (= 1 (:spacing restored))))
      (finally (layout/close! registry)))))

(deftest spacing-validation-preserves-the-existing-flow
  (let [registry (atom {})]
    (try
      (layout/compute! registry request)
      (let [entry (get @registry "a")]
        (doseq [spacing [0 -1 5 "2" nil ##NaN ##Inf]]
          (is (thrown? Exception (layout/compute! registry (assoc request :spacing spacing)))))
        (is (thrown? Exception (layout/compute! registry
                                (-> request (assoc :spacing 2) (assoc-in [:nodes 0 :size] [-1 22])))))
        (is (identical? (:input entry) (:input (get @registry "a")))))
      (finally (layout/close! registry)))))

(deftest spacing-also-increases-separation-for-large-expanded-value-cards
  (let [registry (atom {})
        cards (update request :nodes #(mapv (fn [node] (assoc node :size [160 1000])) %))
        distance (fn [result]
                   (let [[[ax ay] [bx by]] (map :position (:nodes result))]
                     (Math/hypot (- ax bx) (- ay by))))]
    (try
      (let [normal (layout/compute! registry cards)
            wide (layout/compute! registry (assoc cards :spacing 2))]
        (is (> (distance wide) (* 1.5 (distance normal))))
        (is (= [[160 1000] [160 1000]] (mapv :size (:nodes wide)))))
      (finally (layout/close! registry)))))

(deftest time-budget-and-explicit-refinement-have-distinct-stop-reasons
  (let [registry (atom {}) ticks (atom -1)
        clock #(* 1000000 (swap! ticks inc))]
    (try
      (let [bounded (layout/compute! registry (assoc request :budgetMs 1 :animate true) clock)
            input (:input (get @registry "a"))
            untouched (layout/compute! registry (assoc request :budgetMs 10000))
            refined (layout/compute! registry (assoc request :refine true :budgetMs 1000) (constantly 0))]
        (is (= "budget" (:stopReason bounded)))
        (is (false? (:converged bounded)))
        (is (= 0 (:iterationsUsed bounded)))
        (is (= 1 (count (:frames bounded))))
        (is (= "unchanged" (:stopReason untouched)))
        (is (= 0 (:iterationsUsed untouched)) "A budget setting alone does not start work")
        (is (identical? input (:input (get @registry "a"))))
        (is (pos? (:iterationsUsed refined)))
        (is (= "converged" (:stopReason refined)))
        (is (true? (:converged refined)))
        (is (<= (get-in refined [:metrics :max-displacement]) 0.05))
        (is (= 0 (get-in refined [:metrics :distance-sources-recomputed])))
        (is (not (apply clearance/conflict? (:nodes refined)))))
      (finally (layout/close! registry)))))

(deftest refinement-budget-is-validated-before-touching-owned-state
  (let [registry (atom {})]
    (try
      (layout/compute! registry request)
      (let [before (get @registry "a")]
        (doseq [budget [0 -1 10001 1.5 "1000" nil]]
          (is (thrown? Exception (layout/compute! registry (assoc request :budgetMs budget)))))
        (is (thrown? Exception (layout/compute! registry (assoc request :refine "yes"))))
        (is (= before (get @registry "a"))))
      (finally (layout/close! registry)))))

(deftest convergence-can-continue-beyond-the-former-300-iteration-ceiling
  (let [registry (atom {}) ids (mapv str (range 100))
        chain (assoc request :nodes (mapv #(hash-map :id % :label % :size [80 22]) ids)
                     :edges (mapv vec (partition 2 1 ids)) :animate true)]
    (try
      ;; A non-advancing test clock isolates convergence from machine speed.
      (let [result (layout/compute! registry chain (constantly 0))]
        (is (> (:iterationsUsed result) 300))
        (is (= "converged" (:stopReason result)))
        (is (<= (count (:frames result)) 64))
        (is (= (:iterationsUsed result) (:iteration (last (:frames result)))))
        (is (= (:nodes result) (:nodes (last (:frames result))))))
      (finally (layout/close! registry)))))

(deftest refinement-starts-from-the-latest-released-drag-position
  (let [registry (atom {})]
    (try
      (layout/compute! registry request)
      (let [moved (layout/compute! registry (assoc request :moves [{:id "x" :position [500 400]}]))
            refined (layout/compute! registry (assoc request :refine true :animate true))
            first-frame (:nodes (first (:frames refined)))
            position #(->> % (filter (fn [node] (= "x" (:id node)))) first :position)]
        (is (= [500 400] (position (:nodes moved))))
        (is (= [500 400] (position first-frame)))
        (is (not= [500 400] (position (:nodes refined))))
        (is (pos? (:iterationsUsed refined))))
      (finally (layout/close! registry)))))

(deftest semantic-scopes-share-preview-detail-geometry-and-own-their-lifecycle
  (let [registry (atom {})
        root (assoc request :semanticScope "root")
        child (assoc request :semanticScope "child")]
    (try
      (let [preview (layout/compute! registry child)
            detail (layout/compute! registry child)]
        (is (= (:nodes preview) (:nodes detail)))
        (is (= 0 (get-in detail [:metrics :iterations])))
        (layout/compute! registry root)
        (layout/compute! registry (assoc child :pane "b"))
        (is (= 3 (count @registry)))
        (layout/close-pane! registry "a")
        (is (= #{["b" "one" "child"]} (set (keys @registry)))))
      (finally (layout/close! registry)))))

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
        (is (= 0 (get-in second [:metrics :iterations])))
        (is (= 0 (get-in second [:metrics :pair-evaluations])))
        (is (= 0 (get-in moved [:metrics :iterations])))
        (is (= 0 (get-in moved [:metrics :pair-evaluations])))
        (is (= (get-in (into {} (map (juxt :id identity)) (:nodes initial-result)) ["y" :position])
               (get-in (into {} (map (juxt :id identity)) (:nodes moved)) ["y" :position]))
            "A drag must not relax neighboring nodes")
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

(deftest unchanged-layout-inputs-do-not-relax-or-replay-stress
  (let [registry (atom {})]
    (try
      (let [initial (layout/compute! registry request)
            positions #(into {} (map (juxt :id :position)) (:nodes %))
            relabeled (assoc-in request [:nodes 0 :label] "renamed X")
            result (layout/compute! registry (assoc relabeled :animate true))
            reordered (layout/compute! registry (update relabeled :nodes #(vec (reverse %))))]
        (doseq [unchanged [result reordered]]
          (is (= (positions initial) (positions unchanged)))
          (is (= 0 (get-in unchanged [:metrics :iterations])))
          (is (= 0 (get-in unchanged [:metrics :pair-evaluations])))
          (is (= 0 (get-in unchanged [:metrics :distance-sources-recomputed])))
          (is (empty? (:frames unchanged))))
        (is (= "renamed X" (:label (first (:nodes result)))))
        (let [changed (layout/compute! registry (assoc relabeled :edges []))]
          (is (pos? (get-in changed [:metrics :iterations])))))
      (finally (layout/close! registry)))))

(deftest released-drag-positions-are-not-permanent-pins
  (let [registry (atom {})
        position #(get (into {} (map (juxt :id :position)) (:nodes %)) "x")]
    (try
      (layout/compute! registry request)
      (let [released (layout/compute! registry (assoc request :moves [{:id "x" :position [500 400]}]))
            grown (layout/compute! registry (assoc-in request [:nodes 0 :size] [340 240]))]
        (is (= [500 400] (position released)))
        (is (not= (position released) (position grown)))
        (is (pos? (get-in grown [:metrics :iterations])))
        (is (not (apply clearance/conflict? (:nodes grown)))))
      (finally (layout/close! registry)))))

(deftest size-changes-relax-without-recomputing-topology
  (let [registry (atom {})
        separation (fn [result]
                     (let [[[ax ay] [bx by]] (map :position (:nodes result))]
                       (Math/hypot (- ax bx) (- ay by))))]
    (try
      (let [initial (layout/compute! registry request)
            grown (assoc-in request [:nodes 0 :size] [240 180])
            result (layout/compute! registry grown)
            repeated (layout/compute! registry grown)
            shrunk (layout/compute! registry request)]
        (doseq [resized [result shrunk]]
          (is (pos? (get-in resized [:metrics :iterations])))
          (is (pos? (get-in resized [:metrics :pair-evaluations])))
          (is (= 0 (get-in resized [:metrics :distance-sources-recomputed]))))
        (is (= [240 180] (:size (first (:nodes result)))))
        (is (> (separation result) (separation initial)))
        (is (< (separation shrunk) (separation result)))
        (let [[a b] (:nodes result)
              [ax ay] (:position a) [bx by] (:position b)]
          (is (or (>= (abs (- ax bx)) (/ (+ (first (:size a)) (first (:size b))) 2))
                  (>= (abs (- ay by)) (/ (+ (second (:size a)) (second (:size b))) 2)))
              "The two-node growth example must no longer overlap"))
        (is (= (:nodes result) (:nodes repeated)))
        (is (= 0 (get-in repeated [:metrics :iterations])))
        (is (= 0 (get-in repeated [:metrics :pair-evaluations]))))
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

(deftest failed-runtime-close-does-not-retire-layout-state
  (let [editors (atom {"a" :runtime-pane})
        layouts (atom {"a" :layout-state})
        layout-closes (atom [])]
    (with-redefs [runtime/remove! (fn [_ _] (throw (ex-info "runtime close failed" {})))
                  layout/close-pane! (fn [_ id] (swap! layout-closes conj id))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime close failed"
                            (server/command! editors layouts {:op "close" :pane "a"})))
      (is (= {"a" :runtime-pane} @editors))
      (is (= {"a" :layout-state} @layouts))
      (is (empty? @layout-closes)))))

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
        (is (every? (fn [frame]
                      (let [nodes (:nodes frame)]
                        (not-any? true? (for [a (range (count nodes)) b (range (inc a) (count nodes))]
                                          (clearance/conflict? (nodes a) (nodes b))))))
                    (:frames animated)))
        (is (thrown? Exception (layout/compute! registry (assoc request :animate "yes")))))
      (doseq [nodes [[] [{:id "only" :size [28 24]}]]]
        (let [result (layout/compute! registry (assoc request :generation (str (count nodes)) :nodes nodes :edges [] :animate true))]
          (is (= 0 (:iterationsUsed result)))
          (is (= nodes (mapv #(dissoc % :position) (:nodes result))))))
      (finally (layout/close! registry) (layout/close! baseline)))))

(deftest pane-flow-uses-standard-stress-without-balanced-preferences
  (let [registry (atom {})
        diamond (assoc request
                       :nodes (mapv (fn [id] {:id id :size [60 24]}) ["a" "b" "c" "d"])
                       :edges [["a" "b"] ["a" "c"] ["b" "d"] ["c" "d"]])]
    (try
      (let [result (layout/compute! registry diamond)]
        (is (pos? (get-in result [:metrics :pair-evaluations])))
        (is (= 0 (get-in result [:metrics :preference-edge-evaluations])))
        (is (= 0 (get-in result [:metrics :conflict-checks])))
        (is (every? #(every? (fn [n] (Double/isFinite (double n))) (:position %)) (:nodes result)))
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
