(ns propagators.semantic-trace.history-facts-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.datastructures.support-collection :as ttms]
            [propagators.datastructures.value-projection :as projection]
            [propagators.experimental.network-history :as history]
            [propagators.ids :as ids]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]))

(defn- premise [source revision status]
  {:source source :timestamp revision :premises-status status})

(defn- observed [observation source revision value]
  (capture/fragment
   {:observation observation
    :scopes {:root {:semantic-trace/graph true :nodes {:actor "actor"}
                    :node-kinds {:actor :cell} :edges []
                    :values {:actor (ttms/content
                                     {:base value :support #{(premise source revision :active)}})}}}}))

(defn- captured-value [captured]
  (projection/payload
   (facts/base-value
    (facts/current (capture/root-graph captured)
                   (facts/assertion-id (capture/observation-id captured) :root :node/value [:actor])))))

(deftest acquisition-reuse-compares-source-evidence-without-conflating-observations
  (let [source (ids/new-node-id)
        a (observed ::a source 0 7)
        b (observed ::b source 0 7)
        withdrawn (capture/update-scope a :root
                    #(facts/update-premise-state % #{(premise source 1 :retracted)}))]
    (is (capture/same-source? a b))
    (is (not (capture/equivalent? a b)))
    (is (not (capture/same-source? a (observed ::c source 1 7))))
    (is (not (capture/same-source? a (observed ::c source 0 9))))
    (is (not (capture/same-source? a withdrawn)))
    (is (= 7 (captured-value a)))))

(deftest replay-exposes-qualified-frame-facts-and-retains-frozen-observations
  (let [source (ids/new-node-id)
        before (observed ::before source 0 7)
        after (observed ::after source 1 9)
        frames (mapv (fn [index graph]
                       (history/frame {:sequence index :time (+ 1000 index) :elapsedMs index
                                       :graph graph
                                       :source-support #{(premise source index :active)}}))
                     [0 1] [before after])
        replay (history/replay frames)
        root (capture/root-graph replay)
        current (facts/update-premise-state root #{(premise source 2 :retracted)})]
    (is (capture/fragment? replay))
    (is (history/replay? replay))
    (is (= #{[0 ::before] [1 ::after]} (:history/frame (facts/relations root))))
    (is (= #{[0 1]} (:history/next (facts/relations root))))
    (is (= (:history/frame (facts/relations root)) (:history/frame (facts/relations current))))
    (is (identical? before (history/field (first (history/field replay :replay/frames)) :graph)))
    (is (= #{(premise source 0 :active)}
           (history/field (first (history/field replay :replay/frames)) :source-support)))
    (is (= 7 (captured-value before)))
    (is (= 9 (captured-value after)))
    (is (capture/equivalent? replay (history/replay frames)))))

(deftest bounded-history-contains-only-current-frame-membership
  (let [source (ids/new-node-id)
        recording (reduce (fn [previous index]
                            (history/next-bounded-recording
                             previous (observed [::frame index] source index index)
                             #{(premise source index :active)} (+ 1000 index) 3))
                          nil (range 12))
        frames (history/field recording :history/frames)
        replay (history/replay frames)]
    (is (= [9 10 11] (mapv :sequence frames)))
    (is (= #{[0 [::frame 9]] [1 [::frame 10]] [2 [::frame 11]]}
           (:history/frame (facts/relations (capture/root-graph replay)))))
    (is (= #{[0 1] [1 2]}
           (:history/next (facts/relations (capture/root-graph replay)))))
    (is (empty? (history/field (history/empty-replay) :replay/frames)))))

(deftest recorded-values-stay-opaque
  (let [opaque (delay (throw (ex-info "Must not force a captured value" {})))
        captured (observed ::opaque (ids/new-node-id) 0 opaque)
        replay (history/replay [(history/frame {:sequence 0 :time 0 :elapsedMs 0
                                                :graph captured :source-support #{}})])
        retained (history/field (first (history/field replay :replay/frames)) :graph)]
    (is (identical? captured retained))
    (is (identical? opaque (captured-value retained)))
    (is (not (realized? opaque)))))
