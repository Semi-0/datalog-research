(ns propagators.network-protocol-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.closure :as closure]
            [propagators.datastructures.reducer-subnet :as reducer]
            [propagators.network :as net]
            [propagators.network-protocol :as np]))

(deftest primitive-network-projects-to-itself
  (is (np/network? net/empty-net))
  (is (np/primitive-network? net/empty-net))
  (is (= net/empty-net (np/network-view net/empty-net))))

(deftest named-network-projects-as-primitive-net
  (let [n (net/assoc-net-dict-entry net/empty-net :x :id)]
    (is (np/network? n))
    (is (np/primitive-network? n))
    (is (= n (np/network-view n)))))

(deftest structural-extension-projects-to-primitive-net
  (let [s (assoc net/empty-net :out-ids #{:out})
        k (assoc net/empty-net :updated* (atom #{}) :out-ids #{:out})]
    (is (np/network? s))
    (is (np/network? k))
    (is (np/primitive-network? s))
    (is (np/primitive-network? k))
    (is (= net/empty-net (np/network-view s)))
    (is (= net/empty-net (np/network-view k)))))

(deftest closure-projects-to-closure-net
  (let [queued-net (net/net-with-io
                    net/empty-net
                    {:queue [[:prop :p]]
                     :queued-props #{:p}
                     :inbox []
                     :outbox []})
        c (closure/closure identity queued-net)]
    (is (np/network? c))
    (is (= net/empty-net (np/network-view c)))))

(deftest non-networks-return-false-and-nil
  (is (not (np/network? nil)))
  (is (nil? (np/network-view nil)))
  (is (not (np/network? 42)))
  (is (nil? (np/network-view 42)))
  (is (not (np/network? "not a network")))
  (is (nil? (np/network-view "not a network"))))

(deftest reducer-subnet-is-not-v1-network
  (let [r (reducer/reducer-subnet net/empty-net net/empty-net 0)]
    (is (not (np/network? r)))
    (is (nil? (np/network-view r)))))
