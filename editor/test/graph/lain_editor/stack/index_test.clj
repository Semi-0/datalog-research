(ns graph.lain-editor.stack.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.stack.index :as index]
            [graph.lain-editor.experiments.baseline :as baseline]))

(def revision baseline/specification-revision)

(defn outcome [id status dependencies]
  {:constraint/id id :specification/revision revision
   :status status :required? true :dependencies dependencies})

(deftest explicit-membership-and-support-withdrawal-are-incremental
  (let [initial (index/create revision [(outcome :shape :satisfied [:premise/v1])]
                              false [{:evidence/id :fixture/shape
                                      :status :supported
                                      :dependencies [:premise/v1]}])
        added (index/apply-deltas
               initial
               {:outcome-updates [{:op :upsert
                                    :outcome (outcome :references :pending [:premise/v2])}]
                :membership-complete? true})
        withdrawn (index/invalidate-premise added :premise/v1)]
    (is (= [:shape :references] (mapv :constraint/id (index/outcomes added))))
    (is (true? (:membership-complete? added)))
    (is (= [:shape] (:invalidated-outcomes withdrawn)))
    (is (= [:fixture/shape] (:invalidated-evidence withdrawn)))
    (is (= :pending (get-in withdrawn [:index :outcomes :shape :status])))
    (is (= :support-withdrawn
           (get-in withdrawn [:index :outcomes :shape :reason])))
    (is (= :invalidated
           (get-in withdrawn [:index :evidence :fixture/shape :status])))))

(deftest withdrawal-keeps-an-inactive-obligation-visible
  (let [initial (index/create revision [(outcome :shape :satisfied [])] true [])
        next (index/apply-deltas
              initial {:outcome-updates [{:op :withdraw
                                          :constraint-id :shape
                                          :reason :fixture-removed}]})]
    (is (= :withdrawn (get-in next [:outcomes :shape :status])))
    (is (false? (get-in next [:outcomes :shape :required?])))
    (is (= [:shape] (index/changed-outcomes initial next)))
    (testing "unknown membership cannot be silently withdrawn"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown constraint"
                            (index/apply-deltas
                             initial {:outcome-updates [{:op :withdraw
                                                        :constraint-id :missing}]}))))))
