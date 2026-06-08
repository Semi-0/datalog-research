(ns propagators-behavior-algebra-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.datastructures.behavior-algebra :as hist]
            [propagators.datastructures.compound-object :as obj]))

(defn- record-map
  [record]
  (cond
    (hist/point-record? record)
    {:at (obj/slot-value record :at)
     :value (obj/slot-value record :value)}

    (hist/interval-record? record)
    {:from (obj/slot-value record :from)
     :to (obj/slot-value record :to)
     :value (obj/slot-value record :value)}))

(defn- records
  [history]
  (mapv record-map (hist/history-records history)))

(deftest history-union-is-idempotent
  (testing "duplicate temporal facts collapse without multiplicity"
    (let [history (hist/records->history
                   [(hist/point-record 6 :x)
                    (hist/point-record 6 :x)
                    (hist/interval-record 0 5 :nothing)])
          consolidated (hist/consolidate history)]
      (is (= [{:from 0 :to 5 :value :nothing}
              {:at 6 :value :x}]
             (records consolidated)))
      (is (= (records consolidated)
             (records (hist/history-union consolidated consolidated)))))))

(deftest history-negation-is-value-negation
  (testing "negation changes payload values and keeps temporal shape"
    (let [history (hist/records->history
                   [(hist/interval-record 0 6 2)
                    (hist/point-record 7 -3)
                    (hist/constant-record 8 5)])]
      (is (= [{:from 0 :to 6 :value -2}
              {:at 7 :value 3}
              {:from 8 :to hist/infinity :value -5}]
             (records (hist/history-negate-values history)))))))

(deftest history-addition-synchronizes-temporal-overlap
  (testing "intervals add only on overlap and points add only at equal ticks"
    (let [left (hist/records->history
                [(hist/interval-record 0 10 2)
                 (hist/point-record 12 4)])
          right (hist/records->history
                 [(hist/interval-record 5 12 7)
                  (hist/point-record 12 10)])]
      (is (= [{:from 5 :to 10 :value 9}
              {:at 12 :value 14}]
             (records (hist/history-add-values left right)))))))

(deftest history-point-interval-join-does-not-imply-continuation
  (testing "a point joins with an interval start but not with its half-open end"
    (let [points (hist/records->history
                  [(hist/point-record 6 1)
                   (hist/point-record 9 2)])
          interval (hist/records->history
                    [(hist/interval-record 6 9 10)])]
      (is (= [{:at 6 :value 11}]
             (records (hist/history-add-values points interval)))))))

(deftest history-join-supports-keyed-synchronization
  (testing "keyed joins match values by key and combine overlapping records"
    (let [left (hist/records->history
                [(hist/interval-record 0 10 {:k :a :v 2})
                 (hist/interval-record 0 10 {:k :b :v 20})])
          right (hist/records->history
                 [(hist/interval-record 5 8 {:k :a :v 7})
                  (hist/interval-record 5 8 {:k :c :v 70})])
          joined (hist/history-join
                  {:key-fn :k
                   :combine (fn [a b]
                              {:k (:k a)
                               :v (+ (:v a) (:v b))})}
                  left
                  right)]
      (is (= [{:from 5 :to 8 :value {:k :a :v 9}}]
             (records joined))))))

(deftest history-join-handles-explicit-open-intervals
  (testing "open intervals are explicit records, not inferred from points"
    (let [left (hist/records->history
                [(hist/constant-record 10 3)])
          right (hist/records->history
                 [(hist/interval-record 12 15 4)])]
      (is (= [{:from 12 :to 15 :value 7}]
             (records (hist/history-add-values left right)))))))

(deftest history-join-all-synchronizes-every-input
  (testing "n-ary point join requires every input at the same timestamp"
    (let [a (hist/records->history [(hist/point-record 6 1)
                                    (hist/point-record 7 10)])
          b (hist/records->history [(hist/point-record 6 2)])
          c (hist/records->history [(hist/point-record 6 3)
                                    (hist/point-record 8 30)])]
      (is (= [{:at 6 :value 6}]
             (records (hist/history-join-all + [a b c]))))))

  (testing "n-ary interval join emits the common overlap"
    (let [a (hist/records->history [(hist/interval-record 0 10 1)])
          b (hist/records->history [(hist/interval-record 4 12 2)])
          c (hist/records->history [(hist/interval-record 6 8 3)])]
      (is (= [{:from 6 :to 8 :value 6}]
             (records (hist/history-join-all + [a b c])))))))
