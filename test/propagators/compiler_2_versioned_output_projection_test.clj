(ns propagators.compiler-2-versioned-output-projection-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.operators.versioned-definition :as definition]
            [propagators.cells.value :as value]
            [propagators.datastructures.tms.distributed :as tms]))

(defn- input-projection [definition-id content]
  (#'definition/candidate-input-content definition-id content))

(deftest candidate-input-excludes-feedback-without-changing-outer-evidence
  (let [id [:block 'increment]
        self [:compiler-2/definition id 0]
        caller :caller/input
        slots {(tms/claim-slot-key :feedback)
               (tms/claim :feedback tms/distributed-proposition 5
                          [(tms/support self :returned-port :definition)])
               (tms/claim-slot-key :caller)
               (tms/claim :caller tms/distributed-proposition 4
                          [(tms/support caller :input :caller)])
               (tms/premise-slot-key self 0) (tms/premise-state self 0 true)
               (tms/premise-slot-key caller 0) (tms/premise-state caller 0 true)}
        outer (tms/indexed-distributed-content slots)
        projected (input-projection id outer)]
    (is (= #{(tms/claim-slot-key :caller) (tms/premise-slot-key caller 0)
             [:tms/latest-premise caller]}
           (set (keys (tms/distributed-slots projected)))))
    (is (= 4 (tms/distributed-base-value (tms/strongest-distributed-value projected))))
    (is (= 5 (tms/claim-value (get (tms/distributed-slots outer)
                                  (tms/claim-slot-key :feedback)))))
    (is (= self (tms/premise (get (tms/distributed-slots outer)
                                  (tms/premise-slot-key self 0)))))
    (let [withdrawn (tms/merge-distributed-content
                     projected (tms/distributed-premise-update caller 1 false))]
      (is (value/nothing? (tms/strongest-distributed-value
                          (input-projection id withdrawn))))
      (is (contains? (tms/distributed-slots (input-projection id withdrawn))
                     (tms/claim-slot-key :caller))))))

(deftest state-only-input-waits-and-other-definitions-remain-inputs
  (let [id [:block 'increment]
        other [:compiler-2/definition [:other-block 'source] 0]
        content (tms/distributed-input-update :input 4 other 0 :external-definition)]
    (is (value/nothing?
         (input-projection id (tms/distributed-premise-update :caller 0 true))))
    (is (= 4 (tms/distributed-base-value
              (tms/strongest-distributed-value (input-projection id content)))))
    (is (= 4 (input-projection id 4)))))

(deftest recursive-input-removes-only-self-support
  (let [id [:block 'walk]
        self [:compiler-2/definition id 1]
        source :fixture/source
        claim (tms/claim :recursive tms/distributed-proposition :next-state
                         [(tms/support self :walk-output :definition)
                          (tms/support source :fixture :input)])
        content (tms/indexed-distributed-content
                 {(tms/claim-slot-key :recursive) claim
                  (tms/premise-slot-key self 1)
                  (tms/premise-state self 1 true)
                  (tms/premise-slot-key source 0)
                  (tms/premise-state source 0 true)})
        projected (input-projection id content)
        projected-claim (get (tms/distributed-slots projected)
                             (tms/claim-slot-key :recursive))]
    (is (= :next-state
           (tms/distributed-base-value
            (tms/strongest-distributed-value projected))))
    (is (= #{source} (tms/supports projected-claim)))
    (is (not (contains? (tms/distributed-slots projected)
                        (tms/premise-slot-key self 1))))))
