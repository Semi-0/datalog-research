(ns differential-dataflow.graph-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as a]
            [differential-dataflow.graph.interface :as g]
            [differential-dataflow.multiset :as ms]
            [differential-dataflow.stream-ops :as s]
            [differential-dataflow.versioned-core :as vc]
            [differential-dataflow.versioned-graph :as vg]))

(defn- drain-close!
  "Close `in-ch` after optional puts; read one batch from `out-ch` (blocking)."
  [out-ch in-ch]
  (a/close! in-ch)
  (a/<!! out-ch))

(deftest graph-map-through-channel
  (let [in (a/chan 10)
        out ((g/map inc) in)]
    (a/>!! in [[1 2] [3 4]])
    (is (= [[2 2] [4 4]] (a/<!! out)))
    (is (nil? (drain-close! out in)))))

(deftest graph-filter-through-channel
  (let [in (a/chan 10)
        out ((g/filter (fn [d] (even? d))) in)]
    (a/>!! in [[2 1] [3 1] [4 1]])
    (is (= [[2 1] [4 1]] (a/<!! out)))
    (is (nil? (drain-close! out in)))))

(deftest graph-negate-through-channel
  (let [in (a/chan 10)
        out (g/negate in)]
    (a/>!! in [[1 2] [3 -1]])
    (is (= [[1 -2] [3 1]] (a/<!! out)))
    (is (nil? (drain-close! out in)))))

(deftest graph-concat-zips-and-appends
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (g/concat a-ch b-ch)]
    (a/>!! a-ch [[1 1] [2 1]])
    (a/>!! b-ch [[10 1]])
    (is (= [[1 1] [2 1] [10 1]] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest graph-join-one-step
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (g/join a-ch b-ch 32)]
    (a/>!! a-ch [[[:k 1] 1]])
    (a/>!! b-ch [[[:k 2] 1]])
    (is (= [[[:k [1 2]] 1]] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest graph-count-first-batch
  (let [c (a/chan 10)
        out ((g/count) c)]
    (a/>!! c [[[:x :a] 2] [[:x :b] 1]])
    (is (= [[[:x 3] 1]] (a/<!! out)))
    (is (nil? (drain-close! out c)))))

(deftest graph-count-second-batch-emits-delta
  (let [c (a/chan 10)
        out ((g/count) c)]
    (a/>!! c [[[:x :a] 2]])
    (is (= [[[:x 2] 1]] (a/<!! out)))
    (a/>!! c [[[:x :b] 1]])
    (let [second-batch (a/<!! out)]
      (is (= #{[[:x 2] -1] [[:x 3] 1]} (set second-batch))))
    (is (nil? (drain-close! out c)))))

(deftest graph-reduce-consolidated-bag
  ;; `f` returns multiset rows per key; first step emits full bag vs empty prior output.
  (let [c (a/chan 10)
        out ((g/reduce (fn [vals] (ms/consolidate vals))) c)]
    (a/>!! c [[[:p :q] 1] [[:p :r] 1]])
    (is (= (sort-by (fn [[[_ v] _]] v) [[[:p :q] 1] [[:p :r] 1]])
           (sort-by (fn [[[_ v] _]] v) (a/<!! out))))
    (is (nil? (drain-close! out c)))))

(deftest graph-reduce-pipes-to-negate
  (let [c (a/chan 10)
        out (g/negate ((g/reduce (fn [vals] (ms/consolidate vals))) c))]
    (a/>!! c [[[:p :q] 1] [[:p :r] 1]])
    (is (= #{[[:p :q] -1] [[:p :r] -1]} (set (a/<!! out))))
    (is (nil? (drain-close! out c)))))

(deftest graph-reduce-explicit-buf-still-curried
  (let [c (a/chan 10)
        out ((g/reduce (fn [vals] (ms/consolidate vals)) 32) c)]
    (a/>!! c [[[:p :q] 1]])
    (is (= [[[:p :q] 1]] (a/<!! out)))
    (is (nil? (drain-close! out c)))))

(deftest graph-count-explicit-buf-still-curried
  (let [c (a/chan 10)
        out ((g/count 32) c)]
    (a/>!! c [[[:x :a] 1]])
    (is (= [[[:x 1] 1]] (a/<!! out)))
    (is (nil? (drain-close! out c)))))

(defn- poll!! [ch]
  (a/alt!!
    ch ([v] v)
    (a/timeout 50) ::none))

(deftest versioned-core-stateful-dispatches-by-message-kind
  (let [in (a/chan 10)
        out ((vc/stateful
              nil
              {:data (fn [state v rows] [state [[:d v rows]]])
               :frontier (fn [state F] [state [[:f F]]])
               :else (fn [state _msg] [state [:unknown]])}
              10)
             in)]
    (a/>!! in [:data 1 [:x]])
    (is (= [:d 1 [:x]] (a/<!! out)))
    (a/>!! in [:frontier #{1}])
    (is (= [:f #{1}] (a/<!! out)))
    (a/>!! in [:other])
    (is (= :unknown (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest versioned-core-emit-frontier-only-on-advance
  (let [[frontier messages] (vc/emit-frontier nil #{1})
        [frontier' messages'] (vc/emit-frontier frontier #{1})]
    (is (= #{1} frontier))
    (is (= [[:frontier #{1}]] messages))
    (is (= #{1} frontier'))
    (is (= [] messages'))))

(deftest versioned-core-binary-waits-for-both-frontiers
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (vc/binary a-ch b-ch {}
                       {:data (fn [state _side v rows]
                                [state [(vc/data v rows)]])}
                       10)]
    (a/>!! a-ch [:frontier #{2}])
    (is (= ::none (poll!! out)))
    (a/>!! b-ch [:frontier #{1}])
    (is (= [:frontier #{2}] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest versioned-map-transforms-data-and-forwards-frontier
  (let [in (a/chan 10)
        out ((vg/map inc) in)]
    (a/>!! in [:data 0 [[1 2] [3 4]]])
    (is (= [:data 0 [[2 2] [4 4]]] (a/<!! out)))
    (a/>!! in [:frontier #{1}])
    (is (= [:frontier #{1}] (a/<!! out)))
    (is (nil? (drain-close! out in)))))

(deftest versioned-concat-meets-input-frontiers
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (vg/concat a-ch b-ch 10)]
    (a/>!! a-ch [:frontier #{2}])
    (is (= ::none (poll!! out)))
    (a/>!! b-ch [:frontier #{1}])
    (is (= [:frontier #{2}] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest versioned-join-uses-version-lub
  (let [a-ch (a/chan 10)
        b-ch (a/chan 10)
        out (vg/join a-ch b-ch 10)]
    (a/>!! a-ch [:data 0 [[[:k :left] 2]]])
    (is (= ::none (poll!! out)))
    (a/>!! b-ch [:data 2 [[[:k :right] 3]]])
    (is (= [:data 2 [[[:k [:left :right]] 6]]] (a/<!! out)))
    (a/close! a-ch)
    (a/close! b-ch)
    (is (nil? (a/<!! out)))))

(deftest versioned-count-waits-until-frontier-closes-version
  (let [in (a/chan 10)
        out ((vg/count) in)]
    (a/>!! in [:data 0 [[[:x :a] 2] [[:x :b] 1]]])
    (is (= ::none (poll!! out)))
    (a/>!! in [:frontier #{1}])
    (is (= [:data 0 [[[:x 3] 1]]] (a/<!! out)))
    (is (= [:frontier #{1}] (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest versioned-count-emits-zero-after-retraction
  (let [in (a/chan 10)
        out ((vg/count) in)]
    (a/>!! in [:data 0 [[[:x :a] 2]]])
    (a/>!! in [:frontier #{1}])
    (is (= [:data 0 [[[:x 2] 1]]] (a/<!! out)))
    (is (= [:frontier #{1}] (a/<!! out)))
    (a/>!! in [:data 1 [[[:x :a] -2]]])
    (a/>!! in [:frontier #{2}])
    (is (= #{[[:x 2] -1] [[:x 0] 1]}
           (set (nth (a/<!! out) 2))))
    (is (= [:frontier #{2}] (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest versioned-ingress-enters-nested-scope
  (let [in (a/chan 10)
        out ((vg/ingress) in)]
    (a/>!! in [:data 0 [[[:x 1] 2]]])
    (is (= [:data [0 0] [[[:x 1] 2]]] (a/<!! out)))
    (is (= [:data [0 1] [[[:x 1] -2]]] (a/<!! out)))
    (a/>!! in [:frontier #{1}])
    (is (= [:frontier #{[1 0]}] (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest versioned-egress-leaves-nested-scope
  (let [in (a/chan 10)
        out ((vg/egress) in)]
    (a/>!! in [:data [0 2] [[[:x 1] 1]]])
    (is (= [:data 0 [[[:x 1] 1]]] (a/<!! out)))
    (a/>!! in [:frontier #{[1 3]}])
    (is (= [:frontier #{1}] (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest versioned-feedback-advances-data-and-frontier
  (let [in (a/chan 10)
        out ((vg/feedback) in)]
    (a/>!! in [:data [0 0] [[[:x 1] 1]]])
    (is (= [:data [0 1] [[[:x 1] 1]]] (a/<!! out)))
    (a/>!! in [:frontier #{[0 1]}])
    (is (= [:frontier #{[0 2]}] (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(deftest versioned-feedback-closes-empty-iteration-frontier
  (let [in (a/chan 10)
        out ((vg/feedback) in)]
    (doseq [frontier [#{[0 0]} #{[0 1]} #{[0 2]} #{[0 3]}]]
      (a/>!! in [:frontier frontier]))
    (is (= [:frontier #{[0 1]}] (a/<!! out)))
    (is (= [:frontier #{[0 2]}] (a/<!! out)))
    (is (= [:frontier #{[0 3]}] (a/<!! out)))
    (is (= [:frontier #{}] (a/<!! out)))
    (a/close! in)
    (is (nil? (a/<!! out)))))

(defn- first-iteration-only [in]
  ((s/scan-emit
    nil
    (fn [state [tag version _rows :as msg]]
      [state
       (case tag
         :data (if (zero? (peek version)) [msg] [])
         :frontier [msg]
         [])]))
   in))

(deftest versioned-iterate-emits-egressed-deltas
  (let [in (a/chan 10)
        out ((vg/iterate first-iteration-only) in)]
    (a/>!! in [:data 0 [[[:x 1] 1]]])
    (is (= [:data 0 [[[:x 1] 1]]] (a/<!! out)))))

(defn -main [& _]
  (let [{:keys [fail error pass]} (clojure.test/run-tests 'differential-dataflow.graph-test)]
    (println "graph-test:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error)) (System/exit 1))))
