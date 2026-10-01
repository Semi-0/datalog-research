(ns propagators.dataflow-projection-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.dataflow-projection :as projection]
            [propagators.compiler-2.runtime.topology-effects :as topology]
            [propagators.gur.flat :as gur]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.semantic-trace :as trace]))

(deftest declared-return-aliases
  (is (= {:r :out}
         (projection/return-aliases [{:result :r :outputs [:out]}
                                    {:result :multi :outputs [:a :b]}
                                    {:result :same :outputs [:same]}])))
  (is (= :c (projection/canonical {:a :b :b :c} :a)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cyclic"
        (projection/canonical {:a :b :b :a} :a)))
  (is (= [[:out :out] [:input :out]]
         (projection/rename-edges {:return :out}
                                 [[:return :out] [:out :out] [:input :return]]))))

(deftest semantic-components-and-explicit-seeds
  (let [edges [[:a :p] [:p :b] [:other :q] [:q :end]]]
    (is (= [[:a :p] [:p :b]] (projection/select-edges edges #{:a})))
    (is (= edges (projection/select-edges edges #{:a :other})))
    (is (= edges (projection/select-edges edges nil)))
    (is (= [] (projection/select-edges edges #{:unknown}))))
  (let [graph (trace/graph-union {:nodes {:a 'a} :dataflow/seeds #{:a}})]
    (is (= #{:a} (:dataflow/seeds (trace/graph-union graph graph)))))
  (is (= #{:a :b} (:dataflow/seeds
                    (trace/graph-union {:dataflow/seeds #{:a}} {:dataflow/seeds #{:b}}))))
  (is (not (contains? (trace/graph-union {:nodes {}}) :dataflow/seeds))))

(deftest observation-metadata-does-not-export-runtime-markers
  (let [id (ids/new-node-id)
        compiled (net/assoc-net-dict-entry net/empty-net gur/name-bindings-key
                   {topology/topology-result-scope {:result id}
                    :runtime/marker {:executed id}})
        diff (topology/network-diff net/empty-net compiled [])]
    (is (= [(gur/bind-name topology/topology-result-scope :result id)] (:effects diff)))
    (is (= #{} (topology/topology-result-ids net/empty-net)))
    (is (= #{id} (topology/topology-result-ids compiled)))
    (is (empty? (:effects (topology/network-diff compiled compiled []))))))
