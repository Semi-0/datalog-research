(ns propagators.prototype-2.world-registry-test
  "WorldRegistry: lazy mono children, hibernate/activate without parent copy."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.world :as world]
            [propagators.prototype-2.world-registry :as reg]))

(deftest spawn-shares-parent-env-until-touch
  (testing "lazy spawn: child env-cell identical to parent; touch extends only child"
    (let [w0 (world/world-from-forms '(define a 10) '(define b 5))
          r0 (reg/register (reg/empty-registry (:network w0)) w0)
          r1 (reg/spawn r0 (:world-id w0) '(replace-definition a 100))
          child-id (first (remove #{(:world-id w0)} (:active r1)))
          parent (reg/get-world r1 (:world-id w0))
          child (reg/get-world r1 child-id)]
      (is (= (:env-cell parent) (:env-cell child))
          "no eager child frames — shared spine")
      (is (seq (:pending-rewrites child)))
      (let [r2 (reg/touch r1 child-id)
            parent' (reg/get-world r2 (:world-id w0))
            child' (reg/get-world r2 child-id)]
        (is (= (:env-cell parent) (:env-cell parent')) "parent env untouched")
        (is (not= (:env-cell parent') (:env-cell child')) "child tip extended")
        (is (nil? (:pending-rewrites child')))
        (let [[va r3] (reg/observe r2 child-id 'a)
              [vb _] (reg/observe r3 child-id 'b)
              [va0 _] (reg/observe r3 (:world-id w0) 'a)]
          (is (= 100 va))
          (is (= 5 vb) "lookup falls through to parent binding b")
          (is (= 10 va0) "parent still sees a=10"))))))

(deftest hibernate-activate-relinks-parent
  (testing "hibernate drops live handle; activate shares parent env; observe materializes"
    (let [w0 (world/world-from-forms '(define a 10) '(define b 7))
          r0 (reg/register (reg/empty-registry (:network w0)) w0)
          r1 (reg/spawn r0 (:world-id w0) '(replace-definition a 100))
          child-id (first (remove #{(:world-id w0)} (:active r1)))
          r-hib (reg/hibernate r1 child-id)]
      (is (reg/hibernated? r-hib child-id))
      (is (not (reg/live? r-hib child-id)))
      (is (reg/live? r-hib (:world-id w0)))
      (let [r-awake (reg/activate r-hib child-id)
            child (reg/get-world r-awake child-id)
            parent (reg/get-world r-awake (:world-id w0))]
        (is (reg/live? r-awake child-id))
        (is (= (:env-cell parent) (:env-cell child))
            "activate does not rebuild from empty — shares parent")
        (is (seq (:pending-rewrites child)))
        (let [[va r2] (reg/observe r-awake child-id 'a)
              [vb _] (reg/observe r2 child-id 'b)]
          (is (= 100 va))
          (is (= 7 vb) "parent binding b survives activate"))))))

(deftest lazy-revive-direct-api
  (testing "world/revive {:lazy? true} + observe materializes"
    (let [w0 (world/world-from-forms '(define a 1))
          w1 (world/revive w0 '(replace-definition a 9) {:lazy? true})]
      (is (= (:env-cell w0) (:env-cell w1)))
      (let [[v w1'] (world/observe w1 'a)]
        (is (= 9 v))
        (is (not= (:env-cell w0) (:env-cell w1')))
        (let [[v0] (world/observe w0 'a)]
          (is (= 1 v0)))))))
