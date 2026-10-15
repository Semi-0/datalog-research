(ns graph.vijual.nodes-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.vijual :as vijual]
            [graph.vijual.layout.nodes :as nodes]
            [graph.vijual.layout.stress :as stress]))

(defn node [id position size]
  {:id id :position position :size size :payload {:label (str id)}})

(defn distance [nodes]
  (apply stress/euclidean-distance (map :position nodes)))

(defn close? [a b]
  (< (Math/abs (- a b)) 1.0e-6))

(deftest sized-nodes-use-continuous-coordinates
  (let [input [(node :a [10 20] [60 80]) (node :b [210 20] [60 80])]
        opts {:edge-length 1 :gap 12}
        result (vijual/layout-stress-nodes input [[:a :b]] opts)]
    (is (close? 112 (distance result)))
    (is (= (map #(dissoc % :position) input)
           (map #(dissoc % :position) result)))
    (is (close? 110 (/ (reduce + (map (comp first :position) result)) 2)))
    (is (= result (nodes/layout-nodes input [[:a :b]] opts)))
    (testing "larger edge target takes precedence over size clearance"
      (is (close? 250 (distance (nodes/layout-nodes input [[:a :b]]
                                                   {:edge-length 250})))))))

(defrecord Box [key origin dimensions content])

(deftest custom-struct-adapters
  (let [input [(->Box :a [0 0] [10 10] "left")
               (->Box :b [100 0] [10 10] "right")]
        result (nodes/layout-nodes input [[:a :b]]
                                   {:id-fn :key :position-fn :origin
                                    :size-fn :dimensions
                                    :with-position #(assoc %1 :origin %2)
                                    :edge-length 40})]
    (is (every? #(instance? Box %) result))
    (is (= ["left" "right"] (mapv :content result)))
    (is (close? 40 (apply stress/euclidean-distance (map :origin result))))))

(deftest degenerate-and-disconnected-inputs
  (is (= [] (nodes/layout-nodes [] [])))
  (let [one [(node :solo [-9 12] [0 0])]]
    (is (= one (nodes/layout-nodes one []))))
  (let [input [(node :a [0 0] [10 10]) (node :b [0 0] [20 20])]
        edges [[:a :b]]]
    (is (= input (nodes/layout-nodes input edges {:iterations 0})))
    (is (close? 80 (distance (nodes/layout-nodes input edges)))))
  (let [input [(node :a [0 0] [0 0]) (node :b [10 0] [0 0])]]
    (is (close? 240 (distance (nodes/layout-nodes input []))))
    (is (= input (nodes/layout-nodes input [] {:disconnected-weight 0})))
    (is (= input (nodes/layout-nodes input [[:a :a]]
                                    {:disconnected-weight 0})))))

(deftest coincident-triangle-spreads-in-two-dimensions
  (let [input (mapv #(node % [0 0] [10 10]) [:a :b :c])
        result (nodes/layout-nodes input [[:a :b] [:b :c] [:c :a]])
        points (mapv :position result)]
    (is (> (Math/abs (double (apply stress/cross-product points))) 1))
    (doseq [[a b] [[0 1] [1 2] [2 0]]]
      (is (close? 80 (stress/euclidean-distance (points a) (points b)))))))

(deftest relaxation-reduces-graph-stress
  (let [input [(node :a [0 0] [0 0]) (node :b [200 10] [0 0])
               (node :c [20 100] [0 0])]
        edges [[:a :b] [:b :c]]
        pairs (stress/stress-pairs [:a :b :c] edges {:stress-node-spacing 40})
        energy (fn [items]
                 (stress/graph-distance-error pairs
                                              (into {} (map (juxt :id :position) items))))
        result (nodes/layout-nodes input edges {:edge-length 40 :gap 0})]
    (is (< (energy result) (energy input)))
    (is (= result (nodes/layout-nodes input edges {:edge-length 40 :gap 0})))))

(deftest invalid-inputs-are-rejected
  (let [valid (node :a [0 0] [10 10])]
    (doseq [bad [(dissoc valid :id)
                 (assoc valid :position [Double/NaN 0])
                 (assoc valid :position [0])
                 (assoc valid :size [-1 10])
                 (assoc valid :size [0 Double/POSITIVE_INFINITY])]]
      (is (thrown? clojure.lang.ExceptionInfo (nodes/layout-nodes [bad] []))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (nodes/layout-nodes [valid valid] [])))
    (doseq [edges [[[:a :missing]] [[:a]] [[:a :a :a]]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (nodes/layout-nodes [valid] edges))))
    (doseq [opts [{:iterations -1} {:iterations 1.5} {:gap -1}
                  {:edge-length 0} {:disconnected-weight -0.1}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (nodes/layout-nodes [valid] [] opts))))))
