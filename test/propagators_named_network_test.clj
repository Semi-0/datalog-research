(ns propagators-named-network-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.bool4 :as bool4]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.named-network :as named]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- named-cell-net
  [named-values]
  (reduce
   (fn [n [k v]]
     (let [id (new-node-id)]
       (-> n
           (net/net-with-dict (assoc (net/net-dict-or-empty n) k id))
           (net/assoc-net-cell id (cell/cell v v)))))
   net/empty-net
   named-values))

(defn- named-prop-net
  [k id]
  (-> net/empty-net
      (net/net-with-dict {k id})
      (net/assoc-net-prop id (prop/prop (fn [_inputs _outputs _network] [])))))

(deftest named-network-interface-subsumption
  (testing "a subsumes b when a has every named key in b with stronger values"
    (let [a (named-cell-net [[:x bool4/contradiction] [:y true]])
          b (named-cell-net [[:x true]])]
      (is (= true (named/named-network->= a b)))
      (is (= false (named/named-network->= b a))))))

(deftest named-network-propagators-compare-by-id
  (testing "same named propagator must resolve to the same id"
    (let [id (new-node-id)
          a (named-prop-net :p id)
          b (named-prop-net :p id)
          c (named-prop-net :p (new-node-id))]
      (is (= true (named/named-network->= a b)))
      (is (= bool4/contradiction (named/named-network->= a c))))))

(deftest named-network-cell-merge-keeps-stronger-side
  (testing "incoming update replaces content when it subsumes the current content"
    (let [content (named-cell-net [[:x true]])
          update (named-cell-net [[:x bool4/contradiction]])
          merged (merge/cell-merge content update net/empty-net)]
      (is (= #{update} merged))
      (is (= update (merge/strongest-value merged net/empty-net)))))

  (testing "current content stays when it subsumes the incoming update"
    (let [content (named-cell-net [[:x bool4/contradiction]])
          update (named-cell-net [[:x true]])
          merged (merge/cell-merge content update net/empty-net)]
      (is (= #{content} merged))
      (is (= content (merge/strongest-value merged net/empty-net))))))

(deftest named-network-cell-merge-normalizes-into-evidence-set
  (testing "nothing plus one named network becomes a singleton evidence set"
    (let [update (named-cell-net [[:x true]])
          merged (merge/cell-merge value/nothing update net/empty-net)]
      (is (= #{update} merged))
      (is (= update (merge/strongest-value merged net/empty-net))))))

(deftest named-network-evidence-set-merge-maintains-antichain
  (testing "stronger update replaces weaker evidence"
    (let [weak (named-cell-net [[:x true]])
          strong (named-cell-net [[:x bool4/contradiction]])
          merged (merge/cell-merge #{weak} strong net/empty-net)]
      (is (= #{strong} merged))))

  (testing "weaker update is rejected by stronger evidence"
    (let [strong (named-cell-net [[:x bool4/contradiction]])
          weak (named-cell-net [[:x true]])
          merged (merge/cell-merge #{strong} weak net/empty-net)]
      (is (= #{strong} merged))))

  (testing "incomparable update is added to the evidence set"
    (let [a (named-cell-net [[:x true]])
          b (named-cell-net [[:y false]])
          merged (merge/cell-merge #{a} b net/empty-net)]
      (is (= #{a b} merged)))))

(deftest named-network-cell-merge-joins-incomparable-interfaces
  (testing "disjoint named commitments are kept by joining both networks"
    (let [content (named-cell-net [[:x true]])
          update (named-cell-net [[:y false]])
          merged (merge/cell-merge content update net/empty-net)
          strongest (merge/strongest-value merged net/empty-net)]
      (is (evidence/evidence-set? merged))
      (is (= #{content update} merged))
      (is (named/named-network? strongest))
      (is (= #{:x :y} (set (keys (net/net-dict-or-empty strongest))))))))

(deftest named-network-cell-merge-joins-incomparable-cell-values
  (testing "same named cell with incomparable Bool4 values keeps evidence and joins strongest"
    (let [content (named-cell-net [[:x true]])
          update (named-cell-net [[:x false]])
          merged (merge/cell-merge content update net/empty-net)
          strongest (merge/strongest-value merged net/empty-net)
          x-id (get (net/net-dict-or-empty strongest) :x)
          x-cell (get (net/net-env strongest) x-id)]
      (is (evidence/evidence-set? merged))
      (is (= #{content update} merged))
      (is (= #{:x} (set (keys (net/net-dict-or-empty strongest)))))
      (is (value/contradiction? (cell/cell-strongest x-cell))))))

(deftest named-network-cell-merge-contradicts-on-same-name-different-prop-id
  (testing "same named propagator with different ids contradicts in strongest, not content"
    (let [content (named-prop-net :p (new-node-id))
          update (named-prop-net :p (new-node-id))
          merged (merge/cell-merge content update net/empty-net)]
      (is (evidence/evidence-set? merged))
      (is (= #{content update} merged))
      (is (value/contradiction?
           (merge/strongest-value merged net/empty-net))))))
