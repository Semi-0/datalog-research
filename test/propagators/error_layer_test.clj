(ns propagators.error-layer-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.error-layer :as errors]
            [propagators.cells.merge :as merge]
            [propagators.cells.merge-failure :as failure]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.support-collection :as support]
            [propagators.network :as net]
            [propagators.ids :as ids]))

(defn- problem [label]
  (failure/contradiction
   (value/contradiction-with-provenance #{label})
   17 (ex-info (name label) {:cause label})))

(deftest error-is-an-explicit-readable-layer
  (let [result (problem :sensor)
        records (errors/errors-of result)
        record (first records)]
    (is (value/contradiction? result))
    (is (value/unusable? result))
    (is (datum/layer-present? result :error))
    (is (= records (datum/layer-value result :error)))
    (is (= :cell/merge (:operation record)))
    (is (= "clojure.lang.ExceptionInfo" (:exception/class record)))
    (is (= "sensor" (:exception/message record)))
    (is (= {:cause :sensor} (:exception/data record)))
    (is (= (conj records :sensor) (value/contradiction-provenance result)))
    (is (= result (value/add-contradiction-provenance result #{:sensor})))
    (is (not (datum/layer-present? value/contradiction :error)))
    (is (= #{} (errors/errors-of value/contradiction)))))

(deftest error-merge-laws-and-other-information-domains
  (let [a (problem :a) b (problem :b) c (problem :c)
        join #(merge/cell-merge %1 %2 net/empty-net)
        values [a b c value/nothing value/contradiction
                (value/contradiction-with-provenance #{:ordinary})
                (support/content {:base 7 :support #{}})
                (membership/content {:base 8 :support #{} :membership #{}})]]
    (doseq [error [a b c] other values]
      (is (= (join error other) (join other error)))
      (is (= (join error other) (join (join error other) error)))
      (is (= (join error other) (join (join error other) other))))
    (is (= (join (join a b) c) (join a (join b c))))
    (is (= 3 (count (errors/errors-of (join (join a b) c)))))
    (is (= #{:a :b :c}
           (into #{} (filter keyword?)
                 (value/contradiction-provenance (join (join a b) c)))))))

(deftest failed-merge-is-annotated-at-the-public-boundary
  (with-redefs [merge/built-in-cell-merge
                (fn [& _] (throw (ex-info "bad handler" {:test true})))]
    (let [left (support/content {:base 7 :support #{}})
          right (support/content {:base 8 :support #{}})
          result (merge/cell-merge left right net/empty-net)]
      (is (value/contradiction? result))
      (is (= 1 (count (datum/layer-value result :error))))
      (is (= result (merge/strongest-value result net/empty-net))))))

(deftest error-contradictions-remain-revisable-within-ttms
  (let [source (ids/new-node-id)
        state (fn [epoch] #{{:source source :timestamp epoch :premises-status :active}})
        bad (support/content {:base (problem :sensor) :support (state 1)})
        good (support/content {:base 42 :support (state 2)})
        first-result (merge/strongest-value bad net/empty-net)
        repaired (merge/strongest-value (merge/cell-merge bad good net/empty-net) net/empty-net)]
    (is (= (problem :sensor) (datum/layer-value first-result :base)))
    (is (= 42 (datum/layer-value repaired :base)))))
