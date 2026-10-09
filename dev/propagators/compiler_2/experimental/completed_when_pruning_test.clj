(ns propagators.compiler-2.experimental.completed-when-pruning-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.experimental.completed-when-pruning :as prune]
            [propagators.compiler-2.experimental.constructor-receipts :as receipts]
            [propagators.core :as core]
            [propagators.gur.flat :as flat]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.cells.value :as value]))

(defn fixture [available?]
  (let [condition (ids/new-node-id) answer (ids/new-node-id)
        key [:pruning-test condition]
        captured (receipts/capture
                  #(let [n (reduce nb/ensure-cell net/empty-net [condition answer])
                         n (if available? (nb/seed-cell n condition true) n)
                         effect (flat/when-effect key condition
                                                 (fn [] [(message answer 9)]))]
                     (second (core/eval-activation-result effect n))))
        prop-id (flat/stable-node-id [:when key :prop])
        settled (nb/run-propagators (:result captured) [prop-id])]
    {:net settled :receipts (:receipts captured)
     :prop-id prop-id :condition condition :answer answer :key key}))

(deftest completed-control-is-retired
  (let [{:keys [net receipts prop-id answer]} (fixture true)
        result (prune/optimize net receipts)]
    (is (= #{prop-id} (:removed result)))
    (is (= 9 (net/network-cell-strongest (:net result) answer)))
    (is (= (:dict net) (:dict (:net result))))
    (is (nil? (get-in result [:net :env prop-id])))
    (is (= (dissoc (:env net) prop-id) (:env (:net result))))))

(deftest waiting-control-and-late-activation-are-preserved
  (let [{:keys [net receipts prop-id condition answer]} (fixture false)
        result (prune/optimize net receipts)
        fired (nb/run-propagators (nb/seed-cell (:net result) condition true) [prop-id])]
    (is (empty? (:removed result)))
    (is (identical? (get-in net [:env prop-id :activate])
                    (get-in result [:net :env prop-id :activate])))
    (is (= 9 (net/network-cell-strongest fired answer)))))

(deftest unknown-and-stale-receipts-do-not-authorize-pruning
  (let [{:keys [net receipts]} (fixture true)]
    (is (empty? (:removed (prune/optimize net []))))
    (is (empty? (:removed (prune/optimize net
                         (mapv #(assoc % :installed-activation (fn [& _] [])) receipts)))))))

(deftest persistent-markers-make-completed-control-inert
  (let [{:keys [net prop-id condition]} (fixture true)
        activate (get-in net [:env prop-id :activate])]
    (doseq [v [true false value/nothing value/contradiction]]
      (is (empty? (activate [] [] (nb/seed-cell net condition v)))))))

(deftest repeated-pruning-is-idempotent
  (let [{:keys [net receipts]} (fixture true)
        first-result (prune/optimize net receipts)
        second-result (prune/optimize (:net first-result) receipts)]
    (is (empty? (:removed second-result)))
    (is (= (:net first-result) (:net second-result)))))
