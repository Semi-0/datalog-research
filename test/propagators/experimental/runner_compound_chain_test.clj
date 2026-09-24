(ns propagators.experimental.runner-compound-chain-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.experimental.runner.compound-chain :as chain]
            [propagators.experimental.runner.examples :as examples]
            [propagators.network :as net]))

(deftest compose-combinator-selects-one-ordered-handler
  (let [calls (atom [])
        dispatch (chain/compose-combinator
                  (fn [value _suffix] (= value :first))
                  (fn [_value suffix] (swap! calls conj [:first suffix]))
                  (fn [value _suffix] (= value :second))
                  (fn [_value suffix] (swap! calls conj [:second suffix]))
                  (fn [value suffix] (swap! calls conj [:fallback value suffix])))]
    (is (= [[:first 1]] (dispatch :first 1)))
    (is (= [[:first 1] [:second 2]] (dispatch :second 2)))
    (is (= [[:first 1] [:second 2] [:fallback :other 3]]
           (dispatch :other 3)))))

(deftest compose-combinator-requires-a-fallback
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires predicate/handler pairs"
       (chain/compose-combinator even? inc odd? dec))))

(deftest all-chain-variants-preserve-the-value
  (doseq [depth [1 3 10]
          variant [:vanilla :port :closure]]
    (testing (str (name variant) " depth " depth)
      (let [result (chain/run-chain variant depth 30)]
        (is (:ok result))
        (is (= 30 (:value result)))))))

(deftest port-chain-routes-one-patch-per-stage
  (let [result (chain/run-chain :port 10 :fresh)]
    (is (:ok result))
    (is (= 10 (get-in result [:metrics :port-patches])))
    (is (nil? (get-in result [:metrics :cell-patches])))))

(deftest unknown-port-is-an-explicit-failure
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"unknown experimental port"
       (chain/evaluate-patches [(chain/port-patch :missing 1)] net/empty-net))))

(defn- closure-values
  [network]
  (->> (vals (net/net-env network))
       (filter cell/cell?)
       (map cell/cell-strongest)
       (filter #(= ::chain/identity (::chain/closure-type %)))
       set))

(deftest atomic-closure-runs-inner-net-and-discards-its-state
  (let [prepared (chain/prepare-chain :closure 3)
        before (closure-values (:network prepared))
        result (chain/run-update prepared 41)
        after (closure-values (:network result))]
    (is (:ok result))
    (is (= 41 (:value result)))
    (is (= 3 (get-in result [:metrics :outer-activations])))
    (is (= 3 (get-in result [:metrics :inner-runs])))
    (is (= 3 (get-in result [:metrics :cell-patches])))
    (is (= before after))))

(deftest apply-closure-requires-the-specialized-evaluator
  (let [built (chain/build-experimental-chain :closure 1)
        result (examples/normal-runner
                {:network (:network built) :tasks (:tasks built)})]
    (is (= :failed (:status result)))
    (is (re-find #"requires the experimental evaluator"
                 (ex-message (:error result))))))

(deftest topology-cost-is-visible
  (let [port (chain/prepare-chain :port 3)
        closure (chain/prepare-chain :closure 3)]
    (is (= {:cells 7 :propagators 3 :dict-entries 3}
           (chain/topology-metrics port)))
    (is (= {:cells 7 :propagators 3 :dict-entries 3}
           (chain/topology-metrics closure)))))
