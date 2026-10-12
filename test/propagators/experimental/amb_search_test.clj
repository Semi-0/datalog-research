(ns propagators.experimental.amb-search-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [examples.lain.amb.builder :as builder]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(deftest normal-runner-finds-pythagorean-triple
  (let [run (builder/run (builder/build 5 builder/pythagorean?))
        network (get-in run [:result :network])
        replay (nb/run-propagators network (:tasks run))]
    (is (= :completed (get-in run [:result :status])))
    (is (= [3 4 5] (:solution run)))
    (is (seq (observation/cell-base network (get-in run [:cells :knowledge]))))
    (is (= (net/net-env network) (net/net-env replay)))
    (is (= (net/net-graph network) (net/net-graph replay)))
    (is (every? #(value/nothing? (net/network-cell-strongest (:network run) %))
                (get-in run [:cells :candidates])))))

(deftest exhaustion-is-explicit-not-quiescence-alone
  (let [run (builder/run (builder/build 4 builder/pythagorean?))
        network (get-in run [:result :network])]
    (is (= :completed (get-in run [:result :status])))
    (is (value/unusable? (net/network-cell-strongest network (get-in run [:cells :output]))))
    (is (= #{{:literals #{} :context #{}}}
           (observation/cell-base network (get-in run [:cells :knowledge]))))
    (is (= :unsatisfiable (:action
                           (observation/cell-base network (get-in run [:cells :repairs])))))))

(deftest different-task-orders-never-publish-an-invalid-triple
  (doseq [order [identity reverse #(concat (drop 2 %) (take 2 %))]]
    (let [built (builder/build 5 builder/pythagorean?)
          observed (atom [])
          watch (fn [advance]
                  (fn [state continuations]
                    (advance state
                             (assoc continuations :continue
                                    (fn [next-state]
                                      (let [network (:network next-state)
                                            output (get-in built [:cells :output])]
                                        (if (contains? (net/net-env network) output)
                                          (let [v (net/network-cell-strongest network output)]
                                            (if (value/unusable? v)
                                              nil
                                              (swap! observed conj (observation/base v))))
                                          nil))
                                      ((:continue continuations) next-state))))))
          result (binding [runner/*advance-transform* watch]
                   (runner/run-network (order (:tasks built)) (:network built)))]
      (is (= :completed (:status result)) (some-> (:error result) ex-message))
      (is (= [3 4 5] (observation/cell-base (:network result) (get-in built [:cells :output]))))
      (is (seq @observed))
      (is (every? #(and (vector? %) (= 3 (count %))
                       (true? (apply builder/pythagorean? %))) @observed)))))

(deftest larger-domain-and-independent-networks
  (let [large (builder/run (builder/build 20 builder/pythagorean?))
        small (builder/run (builder/build 5 builder/pythagorean?))
        oracle #{[3 4 5] [5 12 13] [6 8 10] [8 15 17] [9 12 15] [12 16 20]}]
    (is (= :completed (get-in large [:result :status])))
    (is (contains? oracle (:solution large)))
    (is (= [3 4 5] (:solution small)))
    (is (empty? (set/intersection (set (get-in large [:cells :candidates]))
                                 (set (get-in small [:cells :candidates])))))))
