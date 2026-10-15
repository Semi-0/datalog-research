(ns graph.vijual.session-test
  (:require [clojure.test :refer [deftest is]]
            [graph.vijual :as v]
            [graph.vijual.layout.nodes :as nodes]
            [graph.vijual.layout.stress :as stress]))

(defn fixture [n]
  (mapv (fn [i] {:id i :position [(* 100.0 (Math/cos i)) (* 100.0 (Math/sin i))]
                 :size [(+ 20 (mod i 5)) 20] :label (str i)}) (range n)))

(defn path-edges [n] (mapv vector (range (dec n)) (range 1 n)))

(defn reference [state iterations]
  (let [items (:normalized state) ids (mapv :id items)
        radii (into {} (map (fn [{:keys [id size]}]
                             [id (* 0.5 (apply #(Math/hypot %1 %2) size))]) items))
        opts (:opts state)
        pairs (mapv (fn [{:keys [a b distance] :as pair}]
                      (assoc pair :distance (max distance (+ (radii a) (radii b) (:gap opts)))))
                    (stress/stress-pairs ids (:edges state)
                                         {:stress-node-spacing (:edge-length opts)
                                          :stress-disconnected-weight (:disconnected-weight opts)}))]
    (loop [positions (into {} (map (juxt :id :position) items)) remaining iterations]
      (if (zero? remaining)
        positions
        (let [next (stress/stress-majorization-step ids pairs positions)]
          (recur (into {} (map (fn [id]
                                 [id (if (contains? (:pinned-ids state) id)
                                       (positions id)
                                       (mapv #(* 0.5 (+ %1 %2)) (positions id) (next id)))]) ids))
                 (dec remaining)))))))

(defn assert-positions [expected actual]
  (is (= (set (keys expected)) (set (map :id (:normalized actual)))))
  (doseq [{:keys [id position]} (:normalized actual)
          [a b] (map vector (expected id) position)]
    ;; Mixed sparse/dense accumulation may differ by floating-point rounding.
    (is (<= (Math/abs (double (- a b))) (+ 1.0e-8 (* 1.0e-10 (Math/abs (double a))))))))

(defn assert-distances [state]
  (is (= (stress/graph-distance-maps (mapv :id (:normalized state)) (:edges state))
         (get-in state [:topology :paths :rows]))))

(deftest public-sessions-and-numeric-parity
  (let [input (fixture 25) edges (path-edges 25)
        state (v/create-stress-layout input edges)]
    (is (= input (:nodes state)))
    (is (= 25 (get-in state [:metrics :distance-sources-recomputed])))
    (doseq [iterations [0 1 10 100]]
      (assert-positions (reference state iterations)
                        (v/advance-stress-layout state {:iterations iterations})))
    (let [batch (nodes/advance-layout state {:iterations 10})
          chunks (nth (iterate #(nodes/advance-layout % {:iterations 1}) state) 10)]
      (assert-positions (into {} (map (juxt :id :position) (:normalized batch))) chunks))
    (let [quiet (v/update-stress-layout state {})
          metadata (nodes/update-layout state {:upsert-nodes [(assoc (first input) :label "new")]})]
      (is (= [] (:deltas quiet)))
      (is (identical? (:topology state) (:topology quiet)))
      (is (identical? (:numeric state) (:numeric quiet)))
      (is (identical? (:numeric state) (:numeric metadata)))
      (is (= 0 (get-in metadata [:metrics :distance-sources-recomputed])))
      (is (= 2 (count (:deltas metadata)))))))

(deftest sparse-edits-use-incident-pairs
  (let [n 20
        input (mapv (fn [i] {:id i :position [(* 80 i) 0] :size [0 0]}) (range n))
        prepared (nodes/create-layout input (path-edges n) {:pinned-ids (set (range 1 n))})
        settled (nodes/advance-layout prepared)
        edited (nodes/update-layout settled {:upsert-nodes [(assoc (first input) :position [5 15])]})
        result (nodes/advance-layout edited {:iterations 1})]
    (is (= 1 (get-in settled [:metrics :iterations])))
    (is (= 0 (get-in edited [:metrics :distance-sources-recomputed])))
    (is (= 1 (get-in result [:metrics :sparse-sweeps])))
    (is (= (* 2 (dec n)) (get-in result [:metrics :pair-evaluations])))
    (assert-positions (reference edited 1) result)
    (let [resized (nodes/update-layout result
                                        {:upsert-nodes [(assoc (second (:nodes result)) :size [500 500])]})]
      (is (= 0 (get-in resized [:metrics :distance-sources-recomputed])))
      (assert-positions (reference resized 5) (nodes/advance-layout resized {:iterations 5})))
    (is (= (mapv :position (rest input)) (mapv :position (rest (:nodes result)))))))

(deftest snapshots-and-pins
  (let [state (nodes/advance-layout (nodes/create-layout (fixture 12) (path-edges 12))
                                   {:iterations 3})
        old-nodes (:nodes state)
        old-xs (vec (get-in state [:numeric :geometry :xs]))
        old-basis (vec (get-in state [:numeric :basis :xs]))
        old-sums (vec (get-in state [:numeric :sums :sx]))
        pinned (nodes/update-layout state {:pin-ids #{0}})
        result (nodes/advance-layout pinned {:iterations 8})]
    (is (= (:position (first old-nodes)) (:position (first (:nodes result)))))
    (assert-positions (reference pinned 8) result)
    (nodes/advance-layout state {:iterations 13})
    (is (= old-nodes (:nodes state)))
    (is (= old-xs (vec (get-in state [:numeric :geometry :xs]))))
    (is (= old-basis (vec (get-in state [:numeric :basis :xs]))))
    (is (= old-sums (vec (get-in state [:numeric :sums :sx]))))
    (let [all (nodes/update-layout result {:pin-ids (set (range 12))})
          frozen (nodes/advance-layout all)]
      (is (= (:nodes all) (:nodes frozen)))
      (is (= 0 (get-in frozen [:metrics :pair-evaluations]))))
    (let [free (nodes/update-layout result {:unpin-ids #{0}})]
      (assert-positions (reference free 3) (nodes/advance-layout free {:iterations 3}))))
  (let [state (nodes/create-layout (fixture 10) (path-edges 10))
        early (nodes/advance-layout state {:iterations 100 :tolerance 1.0e6})]
    (is (= 1 (get-in early [:metrics :iterations])))))

(deftest topology-splits-merges-and-node-lifecycle
  (let [initial (nodes/create-layout (fixture 6) [[0 1] [1 2] [3 4]])
        split (nodes/update-layout initial {:remove-edges [[0 1]]})
        merged (nodes/update-layout split {:add-edges [[2 3]]})
        added (nodes/update-layout merged {:upsert-nodes [{:id 6 :position [400 200] :size [50 20]}]})
        removed (nodes/update-layout added {:remove-node-ids #{3}})]
    (is (= 3 (get-in split [:metrics :distance-sources-recomputed])))
    (is (= 4 (get-in merged [:metrics :distance-sources-recomputed])))
    (is (= 1 (get-in added [:metrics :distance-sources-recomputed])))
    (is (= 3 (get-in removed [:metrics :distance-sources-recomputed])))
    (is (identical? (get-in initial [:topology :paths :rows 3])
                    (get-in split [:topology :paths :rows 3])))
    (is (= [0 1 2 4 5 6] (mapv :id (:nodes removed))))
    (is (not-any? #(some #{3} %) (:edges removed)))
    (doseq [state [initial split merged added removed]]
      (assert-distances state)
      (assert-positions (reference state 4) (nodes/advance-layout state {:iterations 4})))
    (let [same (nodes/update-layout initial {:add-edges [[1 0] [0 0]]})]
      (is (identical? (:topology initial) (:topology same)))
      (is (= 0 (get-in same [:metrics :distance-sources-recomputed]))))))

(deftest diameter-changes-disconnected-targets
  (let [state (nodes/create-layout (fixture 6) [[0 1] [1 2] [3 4]])
        edited (nodes/update-layout state {:remove-edges [[1 2]]})]
    (is (= 2 (get-in state [:topology :paths :diameter])))
    (is (= 1 (get-in edited [:topology :paths :diameter])))
    ;; Pair [0 5] is offset 4 in the packed upper triangle.
    (is (= 320.0 (aget ^doubles (get-in state [:topology :packed :ds]) 4)))
    (is (= 240.0 (aget ^doubles (get-in edited [:topology :packed :ds]) 4)))
    (assert-positions (reference edited 8) (nodes/advance-layout edited {:iterations 8}))))

(deftest invalid-transactions-preserve-state
  (let [state (nodes/create-layout (fixture 3) (path-edges 3))]
    (doseq [changes [nil {:surprise true} {:upsert-nodes 4} {:add-edges [4]}
                     {:remove-node-ids #{99}} {:add-edges [[0 99]]}
                     {:pin-ids #{99}} {:pin-ids #{0} :unpin-ids #{0}}
                     {:upsert-nodes [(first (:nodes state))] :remove-node-ids #{0}}
                     {:add-edges [[0 2]] :remove-edges [[2 0]]}
                     {:upsert-nodes [{:id 0 :position [Double/NaN 0] :size [0 0]}]}]]
      (is (thrown? clojure.lang.ExceptionInfo (nodes/update-layout state changes))))
    (doseq [opts [{:iterations -1} {:tolerance -1} {:tolerance Double/NaN} {:unknown 1}]]
      (is (thrown? clojure.lang.ExceptionInfo (nodes/advance-layout state opts))))
    (is (= (fixture 3) (:nodes state)))
    (assert-positions (reference state 2) (nodes/advance-layout state {:iterations 2}))))

(deftest mixed-edit-sequence-matches-fresh-calculation
  (loop [state (nodes/advance-layout (nodes/create-layout (fixture 8) (path-edges 8))
                                     {:iterations 3})
         iteration 0]
    (when (< iteration 35)
      (let [first-node (first (:nodes state))
            changes (case (mod iteration 7)
                      0 {:upsert-nodes [(assoc first-node :label (str "edit-" iteration))]}
                      1 {:upsert-nodes [(assoc first-node :size [(+ 30 iteration) 10])]}
                      2 {:upsert-nodes [(assoc first-node :position [(+ 10 iteration) -20])]}
                      3 (if (contains? (:pinned-ids state) 0) {:unpin-ids #{0}} {:pin-ids #{0}})
                      4 (if (some #{[0 4]} (:edges state))
                          {:remove-edges [[0 4]]} {:add-edges [[0 4]]})
                      5 {:upsert-nodes [{:id (+ 100 iteration) :position [210 15] :size [10 10]}]
                         :add-edges [[0 (+ 100 iteration)]]}
                      6 {:remove-node-ids #{(+ 99 iteration)}}
                      (throw (ex-info "Invalid test operation" {:iteration iteration})))
            before (:nodes state)
            edited (nodes/update-layout state changes)
            result (nodes/advance-layout edited {:iterations 3})]
        (assert-distances edited)
        (assert-positions (reference edited 3) result)
        (is (= before (:nodes state)))
        (recur result (inc iteration))))))

(defrecord CustomNode [key center dimensions content])

(deftest custom-structs-and-heterogeneous-ids
  (let [ids [false :keyword "string" [:vector 3] {:map 4}]
        input (mapv (fn [id i] (->CustomNode id [(* 70 i) (* 10 i)] [10 20] (str i))) ids (range))
        opts {:id-fn :key :position-fn :center :size-fn :dimensions
              :with-position #(assoc %1 :center %2)}
        state (nodes/create-layout input (mapv vector ids (rest ids)) opts)
        edited (nodes/update-layout state {:upsert-nodes [(assoc (first input) :center [20 30])]
                                           :pin-ids #{false}})
        result (nodes/advance-layout edited {:iterations 3})
        split (nodes/update-layout result {:remove-edges [[false :keyword]]})]
    (is (= 5 (get-in split [:metrics :distance-sources-recomputed])))
    (is (= [20 30] (:center (first (:nodes result)))))
    (is (every? #(instance? CustomNode %) (:nodes result)))
    (is (= (mapv :content input) (mapv :content (:nodes result))))
    (assert-distances split)))
