(ns propagators.event-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.datastructures.event :as event]
            [propagators.ids :as ids]))

(defn- content
  [& facts]
  (reduce event/merge-content value/nothing facts))

(deftest event-content-keeps-source-local-freshness
  (testing "newer facts dominate older facts for the same input/source"
    (let [events (content (event/active-event :slider :widget 1 10)
                          (event/active-event :slider :widget 2 20))]
      (is (= {[:slider :widget] 20}
             (event/active-values events)))))

  (testing "same source/timestamp conflicting values contradict"
    (is (value/contradiction?
         (content (event/active-event :slider :widget 1 10)
                  (event/active-event :slider :widget 1 11)))))

  (testing "different sources with different timestamps remain fresh"
    (let [events (content (event/active-event :left :keyboard 1 10)
                          (event/active-event :right :mouse 7 4))]
      (is (= {[:left :keyboard] 10
              [:right :mouse] 4}
             (event/active-values events)))))

  (testing "source retraction removes the source from strongest projection"
    (let [events (content (event/active-event :slider :widget 1 10)
                          (event/retraction-event :slider :widget 2))]
      (is (= {} (event/active-values events)))
      (is (value/nothing? (event/strongest-value events))))))

(deftest event-composite-timestamps-keep-derived-freshness
  (let [a-id (ids/->NodeId
              (java.util.UUID/fromString
               "019f380b-bb42-7287-a14e-066c48b2376e"))
        c-id (ids/->NodeId
              (java.util.UUID/fromString
               "019f380b-bb42-7287-a14e-066c48b2376f"))
        source "slider-panel-0"
        derived-source [:event/derived :sum #{[a-id source] [c-id source]}]
        older-evidence #{{:input-id a-id :source source :timestamp 3}
                         {:input-id c-id :source source :timestamp 3}}
        newer-evidence #{{:input-id a-id :source source :timestamp 4}
                         {:input-id c-id :source source :timestamp 4}}
        events (content
                (event/event-fact {:input-id :sum
                                   :source derived-source
                                   :timestamp older-evidence
                                   :value 13
                                   :evidence older-evidence})
                (event/event-fact {:input-id :sum
                                   :source derived-source
                                   :timestamp newer-evidence
                                   :value 14
                                   :evidence newer-evidence}))]
    (is (= {[:sum derived-source] 14}
           (event/active-values events)))))

(deftest event-composite-timestamps-ignore-stale-duplicate-evidence
  (let [source :widget
        derived-source [:event/derived :sum #{[:a source] [:b source] [:c source]}]
        active-3 #{{:input-id :a :source source :timestamp 3}
                   {:input-id :b :source source :timestamp 3}
                   {:input-id :c :source source :timestamp 3}}
        stale-retraction #{{:input-id :b :source source :timestamp 2}
                           {:input-id :a :source source :timestamp 4}
                           {:input-id :b :source source :timestamp 3}
                           {:input-id :c :source source :timestamp 3}}
        active-4 #{{:input-id :a :source source :timestamp 4}
                   {:input-id :b :source source :timestamp 4}
                   {:input-id :c :source source :timestamp 4}}
        events (content
                (event/event-fact {:input-id :sum
                                   :source derived-source
                                   :timestamp active-3
                                   :value 75
                                   :evidence active-3})
                (event/event-fact {:input-id :sum
                                   :source derived-source
                                   :timestamp stale-retraction
                                   :source-state event/retracted-state
                                   :evidence stale-retraction})
                (event/event-fact {:input-id :sum
                                   :source derived-source
                                   :timestamp active-4
                                   :value 76
                                   :evidence active-4}))]
    (is (= {[:sum derived-source] 76}
           (event/active-values events)))))

(deftest event-compatibility-is-source-aware
  (testing "same-source mismatched timestamps do not combine"
    (is (false?
         (event/compatible?
          [(content (event/active-event :a :widget 1 10))
           (content (event/active-event :b :widget 2 4))]))))

  (testing "same-source matched timestamps combine"
    (is (true?
         (event/compatible?
          [(content (event/active-event :a :widget 2 10))
           (content (event/active-event :b :widget 2 4))]))))

  (testing "different-source mismatched timestamps combine"
    (is (true?
         (event/compatible?
          [(content (event/active-event :a :keyboard 1 10))
           (content (event/active-event :b :mouse 7 4))]))))

  (testing "combined evidence preserves source timestamps"
      (is (= #{{:input-id :a :source :keyboard :timestamp 1}
	             {:input-id :b :source :mouse :timestamp 7}}
	           (event/combined-evidence
	            [(content (event/active-event :a :keyboard 1 10))
	             (content (event/active-event :b :mouse 7 4))])))))

(deftest event-lift-applies-scalar-functions-to-compatible-events
  (testing "same-source matched timestamps combine"
    (let [left (content (event/active-event :a :widget 2 10))
          right (content (event/active-event :b :widget 2 4))
          lifted (event/lift :sum + [left right] [nil nil])]
      (is (= {[:sum [:event/derived
                     :sum
                     #{[:a :widget] [:b :widget]}]]
              14}
             (event/active-values lifted)))
      (is (= #{{:input-id :a :source :widget :timestamp 2}
               {:input-id :b :source :widget :timestamp 2}}
             (event/evidence lifted)))))

  (testing "same-source mismatched timestamps retract derived output"
    (let [left (content (event/active-event :a :widget 1 10))
          right (content (event/active-event :b :widget 2 4))
          lifted (event/lift :sum + [left right] [nil nil])
          latest (first (event/latest-facts lifted))]
      (is (= {} (event/active-values lifted)))
      (is (= event/retracted-state (event/source-state latest)))))

  (testing "different-source mismatched timestamps combine"
    (let [left (content (event/active-event :a :keyboard 1 10))
          right (content (event/active-event :b :mouse 7 4))
          lifted (event/lift :sum + [left right] [nil nil])]
      (is (= [14] (vec (vals (event/active-values lifted)))))))

  (testing "constants combine with every event tuple"
    (let [events (content (event/active-event :a :widget 3 10))
          lifted (event/lift :plus-one + [events 1] [nil 1])]
      (is (= [11] (vec (vals (event/active-values lifted))))))))
