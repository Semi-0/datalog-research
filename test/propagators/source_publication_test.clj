(ns propagators.source-publication-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.stdlib.source-publication :as publication]))

(deftest external-source-identities-are-stable-and-port-qualified
  (let [source (ids/new-node-id)]
    (is (= source (publication/source-id source)))
    (is (= (publication/source-id [:widget :x])
           (publication/source-id [:widget :x])))
    (is (not= (publication/source-id [:widget :x])
              (publication/source-id [:widget :enabled])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity is required"
                          (publication/source-id nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported source status"
                          (publication/datum source 1 :value :unknown)))))

(deftest replacement-and-withdrawal-preserve-frozen-content
  (let [source (publication/source-id :clock)
        initial (publication/active source 1 :first)
        revised (merge/cell-merge initial (publication/active source 2 :second) net/empty-net)
        withdrawn (merge/cell-merge revised (publication/retracted source 3) net/empty-net)
        first-value (merge/strongest-value initial net/empty-net)
        current (merge/strongest-value revised net/empty-net)]
    (is (= :first (datum/layer-value first-value :base)))
    (is (= :second (datum/layer-value current :base)))
    (is (= #{{:source source :timestamp 2 :premises-status :active}}
           (datum/support-of current)))
    (is (value/nothing? (datum/layer-value (merge/strongest-value withdrawn net/empty-net) :base)))
    (is (= :first (datum/layer-value (merge/strongest-value initial net/empty-net) :base)))))

(deftest merge-registry-contains-only-retained-domains
  (is (= #{:default :named-network :closure :reducer-cell :reducer-subnet
           :distributed-tms :support-collection :membership-collection}
         (set (keys (methods merge/built-in-cell-merge))))))
