(ns propagators.semantic-trace.graph-union-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.graph-union :as union]))

(defn- settle [network effects]
  (let [[tasks next-network] (core/eval-activation-result effects network)]
    (nb/run-propagators next-network tasks)))

(defn- graph [label]
  (trace/graph-union {:nodes {label (name label)} :edges []}))

(defn- publish [network output producer graph]
  (settle network (union/publish network output producer graph #{} #{})))

(defn- result [network output]
  (union/graph-of (net/network-cell-strongest network output)))

(deftest explicit-union-retains-independent-contributors-and-replaces-refinement
  (let [output (ids/new-node-id)
        first (publish net/empty-net output :first (graph :a))
        combined (publish first output :second (graph :b))
        frozen (result combined output)
        refined (publish combined output :first (graph :c))]
    (is (= #{:a :b} (set (keys (:nodes frozen)))))
    (is (= #{:b :c} (set (keys (:nodes (result refined output))))))
    (is (= #{:a :b} (set (keys (:nodes frozen)))))
    (is (empty? (union/publish refined output :first (graph :c) #{} #{})))
    (is (seq (datum/support-of (net/network-cell-strongest refined output))))
    (is (not-any? (fn [[_ entry]]
                    (and (cell/cell? entry)
                         (value/contradiction? (cell/cell-strongest entry))))
                  (net/net-env refined)))))

(deftest withdrawal-removes-only-its-own-contribution
  (let [output (ids/new-node-id)
        both (-> net/empty-net
                 (publish output :first (graph :a))
                 (publish output :second (graph :b)))
        withdrawn (publish both output :first value/nothing)]
    (is (= #{:b} (set (keys (:nodes (result withdrawn output))))))
    (is (= #{:a :b} (set (keys (:nodes (result both output))))))))

(deftest refined-and-withdrawn-union-agrees-with-clean-reconstruction
  (let [output (ids/new-node-id)
        refined (-> net/empty-net
                    (publish output :first (graph :old))
                    (publish output :second (graph :removed))
                    (publish output :first (graph :current))
                    (publish output :second value/nothing))
        rebuilt (publish net/empty-net output :first (graph :current))]
    (is (= (result rebuilt output) (result refined output)))
    (is (not (value/contradiction?
              (net/network-cell-strongest refined output))))))

(deftest derived-publication-does-not-advance-an-unchanged-result
  (let [output (ids/new-node-id)
        graph (graph :a)
        network (settle net/empty-net
                        (concat [(propagators.network-patch/declare-cell output)]
                                (union/publish-derived
                                 (nb/install-cell net/empty-net output)
                                 output graph [])))]
    (is (= graph (result network output)))
    (is (empty? (union/publish-derived network output graph [])))))
