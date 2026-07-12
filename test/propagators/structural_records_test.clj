(ns propagators.structural-records-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :as snap]
            [propagators.closure :as closure]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(deftest predicates-accept-record-and-structural-map
  (testing "cell, snapshot, message, propagator predicates"
    (let [c (cell/cell 1 1)
          s (snap/snap :node c)
          m (message/message :node 42)
          p (prop/prop (fn [_ _ _] []))]
      (is (cell/cell? c))
      (is (cell/cell? {:content 1 :strongest 1}))
      (is (snap/snap? s))
      (is (snap/snap? {:id :node :cell c}))
      (is (message/message? m))
      (is (message/message? {:id :node :value 42}))
      (is (prop/prop? p))
      (is (prop/prop? {:activate (fn [_ _ _] [])})))))

(deftest ids-graph-and-network-structural-equivalence
  (testing "node-id, node, and net predicates accept equivalent maps"
    (let [node-id (ids/new-node-id)
          node (graph/node #{} #{})
          n (net/net {} {})]
      (is (ids/node-id? node-id))
      (is (ids/node-id? {:uuid (ids/unwrap-node-id node-id)}))
      (is (graph/node? node))
      (is (graph/node? {:inputs #{} :outputs #{}}))
      (is (net/net? n))
      (is (net/net? {:graph {} :env {} :dict net/empty-dict}))
      (is (net/network? n)))))

(deftest accessors-remain-stable
  (testing "constructor/accessor API remains unchanged"
    (let [node-id (ids/new-node-id)
          c (cell/cell :x :y)
          s (snap/snap node-id c)
          m (message/message node-id 9)
          p (prop/prop (fn [_ _ _] []))
          n (net/net {node-id (graph/node #{} #{})} {node-id c})]
      (is (= :x (cell/cell-content c)))
      (is (= :y (cell/cell-strongest c)))
      (is (= :cell/anonymous (cell/cell-name c)))
      (is (= node-id (snap/snap-id s)))
      (is (= c (snap/snap-cell s)))
      (is (= node-id (message/message-id m)))
      (is (= 9 (message/message-value m)))
      (is (fn? (prop/prop-f p)))
      (is (= :propagator/anonymous (prop/prop-name p)))
      (is (= {node-id c} (net/net-env n)))
      (is (= {node-id (graph/node #{} #{})} (net/net-graph n)))
      (is (= net/empty-dict (net/net-dict n))))))

(deftest named-runtime-entries-preserve-execution-identity
  (let [cell-id (ids/new-node-id)
        prop-id (ids/new-node-id)
        [_ with-cell] ((cell/construct-cell cell-id :test/input 1 1)
                       net/empty-net)
        [_ network] ((prop/construct-propagator prop-id
                                                :test/copy
                                                (fn [_ _ _] [])
                                                [cell-id]
                                                [])
                     with-cell)]
    (is (= :test/input
           (cell/cell-name (net/network-env-lookup network cell-id))))
    (is (= :test/copy
           (prop/prop-name (net/network-env-lookup network prop-id))))))
