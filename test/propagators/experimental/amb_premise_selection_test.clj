(ns propagators.experimental.amb-premise-selection-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [propagators.application-dispatch :as dispatch]
            [examples.lain.amb.experiment :as experiment]
            [examples.lain.amb.premise-selection :as selection]
            [propagators.cells.value :as value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms :as tms]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.observation :as observation]
            [propagators.datastructures.layered-value :as datum]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

;; Characterize the unregistered path even when another test loads the extension.
(use-fixtures :once
  (fn [run]
    (let [method (get (methods dispatch/application-effects) propagators.network.Net)]
      (remove-method dispatch/application-effects propagators.network.Net)
      (try (run)
           (finally
             (if (some? method)
               (.addMethod dispatch/application-effects propagators.network.Net method)
               nil))))))

(defn- change-premise [network output epoch active?]
  (let [[tasks updated] (core/eval-cell output
                                       (message output (tms/distributed-premise-update
                                                        :definition/plus-ten epoch active?))
                                       network)]
    (nb/run-propagators updated tasks)))

(defn- list-slot-id [network id key]
  (let [owner (observation/base (net/network-cell-strongest network id))
        parents (obj/accessor-parent-ids owner key)]
    (if (= 1 (count parents))
      (first parents)
      (throw (ex-info "Expected one live list-slot source" {:key key :parents parents})))))

(deftest default-compiler-premise-closure-still-retracts-and-recovers
  (let [compiled (compiler/compile-source
                  "(let-cell [x out]
                     (premise-input 5 :input/x 0 x)
                     (define op (premise-closure (network (x) (+ x 10))
                                                 :definition/plus-ten 0))
                     (-> (op x) out) out)")
        run (runner/run-network (:props compiled) (:net compiled))
        network (:network run) output (:cell compiled)
        retracted (change-premise network output 1 false)
        restored (change-premise retracted output 2 true)
        base (fn [network]
               (-> (net/network-cell-strongest network output)
                   scope-source/unwrap tms/distributed-base-value))
        keys-of (fn [network]
                  (set (keys (tms/distributed-slots (net/network-cell-content network output)))))]
    (is (= :completed (:status run)))
    (is (= 15 (base network)))
    (is (value/nothing? (base retracted)))
    (is (= 15 (base restored)))
    (is (contains? (keys-of restored) (tms/premise-slot-key :input/x 0)))
    (doseq [epoch [0 1 2]]
      (is (contains? (keys-of restored) (tms/premise-slot-key :definition/plus-ten epoch))))
    (is (= 15 (base network)))))

(deftest amb-result-selection-rejects-first-procedure-and-keeps-branch-support
  (let [run (experiment/run (selection/source false :result 15))
        report (selection/report run)
        network (get-in run [:result :network])
        record (first (experiment/choice-records network))
        root (get-in run [:compiled :cell])
        snapshot (observation/list-snapshot network root)
        result-head (list-slot-id network (list-slot-id network root :cdr) :car)
        replay (nb/run-propagators network (get-in run [:compiled :props]))]
    (is (= :completed (:runtime-status report)))
    (is (= [2 15] (:solution report)))
    (is (:ready? report))
    (is (not (:exhausted? report)))
    (is (= {:index 1 :timestamp 3 :status :active} (first (:choices report))))
    (is (= 2 (nth (:domain record) (:index record))))
    ;; The choice source is an actual injection cell, not a synthetic ID.
    (is (contains? (net/net-env network) (:source record)))
    (is (= (net/net-env network) (net/net-env replay)))
    (is (= [2 15] (:items snapshot)))
    (is (= #{(contracts/premise record)}
           (datum/support-of (net/network-cell-strongest network (:source record)))))
    (is (= #{(contracts/premise record)}
           (datum/support-of (net/network-cell-strongest network result-head))))))

(deftest exact-fit-is-not-automatic-ranking-and-can-exhaust
  (let [first-fit (selection/report (experiment/run (selection/source false :result 6)))
        no-fit (selection/report (experiment/run (selection/source false :result 99)))]
    (is (= [1 6] (:solution first-fit)))
    (is (= 0 (get-in first-fit [:choices 0 :index])))
    (is (nil? (:solution no-fit)))
    (is (false? (:ready? no-fit)))
    (is (:exhausted? no-fit))))

(deftest premised-result-selection-is-a-characterized-interop-gap
  ;; This is failure characterization, not evidence of successful composition.
  (let [report (selection/report (experiment/run (selection/source true :result 15)))]
    (is (= :completed (:runtime-status report)))
    (is (nil? (:error report)))
    (is (nil? (:solution report)))
    (is (false? (:ready? report)))
    (is (:exhausted? report))))

(deftest supported-callable-selection-is-a-characterized-interop-gap
  (doseq [premised? [false true]]
    (let [report (selection/report (experiment/run (selection/source premised? :callable 15)))]
      (is (= :completed (:runtime-status report)))
      (is (nil? (:solution report)))
      (is (false? (:ready? report)))
      (is (:call-result-contradiction? report))
      (is (= 0 (get-in report [:choices 0 :index])))
      (is (not (:exhausted? report))))))
