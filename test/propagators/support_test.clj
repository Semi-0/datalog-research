(ns propagators.support-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.datastructures.layered-value :as layered-value]
            [propagators.datastructures.support :as support]
            [propagators.ids :as ids]))

(def source-ids (zipmap [:A :B] (repeatedly 2 ids/new-node-id)))

(defn premise [source timestamp status]
  {:source (get source-ids source source)
   :timestamp timestamp :premises-status status})

(def a1 (premise :A 1 :active))
(def a2 (premise :A 2 :retracted))
(def a3 (premise :A 3 :active))
(def b1 (premise :B 1 :active))

(deftest exact-record-contract
  (is (support/entry? a1))
  (is (s/valid? ::support/premise a1))
  (doseq [source [:A nil {:uuid "not-a-uuid"}]]
    (let [invalid (assoc a1 :source source)]
      (is (not (support/entry? invalid)))
      (is (not (s/valid? ::support/premise invalid)))
      (is (thrown? clojure.lang.ExceptionInfo (support/join #{invalid})))))
  (is (not (support/entry? (assoc a1 :value 12))))
  (is (not (support/entry? (dissoc a1 :premises-status))))
  (is (not (support/entry? (assoc a1 :premises-status :unknown))))
  (is (support/support? #{}))
  (is (thrown? clojure.lang.ExceptionInfo (support/join [a1]))))

(deftest timestamp-aware-set-order
  (let [a2-active (premise :A 2 :active)
        b2 (premise :B 2 :active)]
    (doseq [[newer older expected]
            [[#{a2-active} #{a1} true]
             [#{a1 b1} #{b1} true]
             [#{a2-active b1} #{a1 b1} true]
             [#{a2-active} #{a1 b1} false]
             [#{a1 b2} #{a2-active b1} false]
             [#{a1} #{a1} false]
             [#{a2} #{a2-active} false]
             [#{a2} #{a1} true]
             [#{a2-active} #{a1 a2-active} true]
             [#{a1 a2-active} #{a1} false]
             [#{a2-active b1} #{a2} false]]]
      (is (= expected (support/dominates? newer older))
          (pr-str {:newer newer :older older})))
    (is (support/covers? #{a1} #{a1}))
    (is (not (support/covers? #{a2} #{a2-active})))
    (is (thrown? clojure.lang.ExceptionInfo (support/covers? #{a1} [a1])))))

(deftest finite-support-order-laws-including-retraction
  (let [entries [a1 (premise :A 1 :retracted) a2 b1
                 (premise :B 2 :active)]
        supports (reduce (fn [sets entry]
                           (into sets (map #(conj % entry) sets)))
                         [#{}] entries)
        covers (into {} (for [a supports b supports]
                         [[a b] (support/covers? a b)]))
        dominates (into {} (for [a supports b supports]
                            [[a b] (support/dominates? a b)]))]
    (is (every? #(covers [% %]) supports))
    (is (not-any? #(dominates [% %]) supports))
    (doseq [relation [covers dominates]]
      (is (empty? (for [a supports b supports c supports
                       :when (and (relation [a b]) (relation [b c])
                                  (not (relation [a c])))]
                   [a b c]))))))

(deftest current-support-join
  (is (= #{} (support/join)))
  (is (= #{a1 b1} (support/join #{a1} #{b1})))
  (is (= #{a2 b1} (support/join #{a1 b1} #{a2})))
  (is (= #{a2} (support/join #{a2} #{a1})))
  (is (= #{a3} (support/join #{a2} #{a3})))
  (let [conflict #{a2 (assoc a2 :premises-status :active)}]
    (is (= conflict (apply support/join (map hash-set conflict))))
    (is (support/unusable? conflict))))

(deftest join-laws
  (let [samples [#{} #{a1} #{a2} #{a3} #{b1} #{a1 b1}
                 #{a2 (assoc a2 :premises-status :active)}]]
    (doseq [a samples]
      (is (= a (support/join a a))))
    (doseq [a samples b samples]
      (is (= (support/join a b) (support/join b a))))
    (doseq [a samples b samples c samples]
      (is (= (support/join (support/join a b) c)
             (support/join a (support/join b c)))))))

(deftest compatibility-before-normalization
  (is (support/compatible? #{} #{a1} #{b1} #{a1}))
  (is (not (support/compatible? #{a1} #{b1} #{a3})))
  (is (support/compatible? (support/join #{a1} #{a3})))
  (is (not (support/unusable? #{a1 b1})))
  (is (support/unusable? #{a2 b1}))
  (is (not (support/unusable? #{}))))

(deftest computation-support-retains-incompatible-versions
  (is (= #{a1 a3} (support/combine #{a1} #{a3})))
  (is (support/unusable? (support/combine #{a1} #{a3})))
  (is (= #{a1 b1} (support/combine #{a1} #{a1 b1})))
  (is (= #{} (support/combine))))

(deftest readiness-is-not-representation-or-transport
  (doseq [wrap [identity obj/map-compound-object]]
    (testing "non-event layered values"
      (is (false? (value/unusable? (wrap {:base false :support #{a1}}))))
      (is (true? (value/unusable? (wrap {:base value/nothing :support #{a2}}))))
      (is (true? (value/unusable? (wrap {:base value/contradiction :support #{a1}}))))
      (is (true? (value/unusable? (wrap {:base 10 :support #{a2}}))))
      (is (false? (value/unusable? (wrap {:other 10})))))
    (let [datum (wrap {:base value/nothing :support #{a2}})]
      (is (layered-value/layer-present? datum :base))
      (is (= #{a2} (layered-value/support-of datum)))))
  (is (value/unusable? {:base {:base value/nothing}}))
  (is (value/unusable? {:base {:base 1 :support #{a1}} :support #{a3}}))
  (is (value/any-unusable-values? {:base {:base 1 :support #{a1}}}
                                  {:base 2 :support #{a3}}))
  (is (value/unusable? {:base 2 :support nil}))
  (is (not (value/any-unusable-values?)))
  (is (not (value/any-unusable-values? false true 42)))
  (is (value/any-unusable-values? 1 value/nothing))
  (is (not (value/any-unusable-values? {:base 1 :support #{a1}}
                                      {:base 2 :support #{b1}})))
  (is (value/any-unusable-values? {:base 1 :support #{a1}}
                                  {:base 2 :support #{b1}}
                                  {:base 3 :support #{a3}})))
