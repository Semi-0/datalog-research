(ns propagators.experimental.amb-learning-checkpoint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [examples.lain.amb.checkpoint-proofs :as proofs]
            [examples.lain.amb.checkpoint-question :as builder]
            [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.knowledge :as knowledge]
            [examples.lain.amb.learning-checkpoint :as hybrid]
            [examples.lain.amb.learning-checkpoint-benchmark :as experiment]
            [examples.lain.amb.solver-lain :as lain]
            [examples.lain.amb.solver-operator :as operator]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.observation :as observation]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-cache :as cache]
            [propagators.stdlib.premise-state :as state]))

(def tiny {:id :tiny-chain :domain [1 2 3] :edges #{[1 2] [2 3]}})

(deftest required-result-retains-a-smaller-sound-explanation
  (let [fixture (experiment/prepare-fixture tiny :prefix)
        question (experiment/question-of (get-in fixture [:state :network]))
        raw (proofs/required-result question)
        settled (-> (checkpoint/settle (select-keys question [:network :tasks]))
                    (checkpoint/tell (first (:sources question)) 3 :active)
                    (checkpoint/tell (second (:sources question)) 1 :active))
        certificate (proofs/failed-certificate (:network settled) raw)]
    (is (= :failed (:status certificate)))
    (is (= (set (take 2 (:sources question))) (set (map :source (:support certificate)))))
    (is (value/nothing? (observation/cell-base (:network settled) (last (:sources question)))))
    (is (= :pending (:status (observation/cell-base (:network settled) (get-in question [:cells :failure])))))
    (is (= :failed (:status (proofs/classify (assoc question :required-result raw) {:depth 2} settled))))))

(deftest learning-is-distinguished-from-early-refutation
  (doseq [shape [:prefix :suffix]]
    (let [fixture (experiment/prepare-fixture tiny shape)
          early (experiment/sample fixture :early-checkpoint nil)
          learned (experiment/sample fixture :learning-checkpoint nil)]
      (is (= (:expected fixture) (:answers early) (:answers learned)))
      (is (= 27 (get-in early [:search :covered-assignments])
                    (get-in learned [:search :covered-assignments])))
      (is (= {} (:nogood-widths early)))
      (is (= {2 6} (:nogood-widths learned)))
      (is (:sound-nogoods? learned))
      (case shape
        :prefix
        (do (is (= 0 (get-in learned [:search :pruned-jobs])))
            (is (= 6 (get-in learned [:search :early-failures])))
            (is (= (get-in early [:search :evaluations]) (get-in learned [:search :evaluations]))))
        :suffix
        (do (is (= 12 (get-in learned [:search :pruned-jobs])))
            (is (= 15 (get-in learned [:search :propagated-leaves])))
            (is (= 27 (get-in early [:search :propagated-leaves])))
            (is (< (get-in learned [:search :evaluations]) (get-in early [:search :evaluations]))))
        (throw (ex-info "Unknown shape in test" {}))))))

(deftest cached-prefix-avoids-network-evaluation-and-keeps-the-checkpoint
  (let [question (builder/build 2)
        initial (checkpoint/settle (select-keys question [:network :tasks]))
        parent (checkpoint/tell initial (first (:sources question)) 1 :active)
        [a b] (:sources question)
        nogood {:literals #{[a 0] [b 1]} :context #{}}
        job {:id 4 :kind :choice :source b :value 2 :depth 2 :state parent}
        result (with-redefs [checkpoint/tell (fn [& _] (throw (ex-info "Blocked job must not propagate" {})))]
                 (proofs/evaluate question job #{nogood}))]
    (is (:pruned? result))
    (is (= :failed (:status result)))
    (is (identical? parent (:state result)))
    (is (value/nothing? (observation/cell-base (:network parent) b)))))

(deftest external-context-version-and-retraction-guard-nogood-reuse
  (let [question (builder/build 2)
        external (ids/new-node-id)
        base (checkpoint/settle {:network (nb/ensure-cell (:network question) external) :tasks (:tasks question)})
        first-world (-> base (checkpoint/tell external 9 :active)
                        (checkpoint/tell (first (:sources question)) 1 :active))
        version (first (state/states-of (net/network-cell-strongest (:network first-world) external)))
        [a b] (:sources question)
        known #{{:literals #{[a 0] [b 1]} :context #{version}}}
        job {:id 2 :kind :choice :source b :value 2 :depth 2 :state first-world}
        updated (checkpoint/tell first-world external 10 :active)
        retracted (checkpoint/tell first-world external value/nothing :retracted)]
    (is (proofs/blocked? question job known))
    (is (not (proofs/blocked? question (assoc job :state updated) known)))
    (is (not (proofs/blocked? question (assoc job :state retracted) known)))))

(deftest all-five-strategies-swap-in-one-environment
  (let [fixture (experiment/prepare-fixture tiny :suffix)
        compiled (:compiled fixture) initial (get-in fixture [:state :network])
        shared (experiment/question-of initial) calls (atom []) pool (driver/pool 4)]
    (try
      (binding [cache/*cache* nil strategy/*executor* pool operator/*observe-solve* #(swap! calls conj %)]
        (let [final (reduce
                     (fn [network mode]
                       (let [result (experiment/select-and-run compiled network (get experiment/policies mode))]
                         (is (= (:expected fixture) (:answers result)))
                         (is (identical? shared (:question (peek @calls))))
                         (is (= (:graph initial) (:graph (:network result))))
                         (is (every? #(value/nothing? (net/network-cell-strongest (:network result) %))
                                     (:sources shared)))
                         (:network result))) initial experiment/modes)
              duplicate (experiment/select-and-run compiled final hybrid/learning-solver)]
          (is (= 5 (count @calls)))
          (is (= (:env final) (:env (:network duplicate))))))
      (finally (driver/close! pool)))))

(deftest false-intermediate-values-are-not-treated-as-global-failures
  (let [fixture (experiment/prepare-fixture tiny :prefix)
        compiled (experiment/compile-query (experiment/program "(or (reachable? facts a b) true)" [1 2 3])
                                           (knowledge/closure-oracle [1 2 3] (:edges tiny)))
        expected (vec (for [a [1 2 3] b [1 2 3] c [1 2 3]] [a b c]))
        fixture (assoc fixture :compiled compiled :state (lain/prepare compiled) :expected expected)
        result (experiment/sample fixture :learning-checkpoint nil)]
    (is (= expected (:answers result)))
    (is (= {} (:nogood-widths result)))
    (is (zero? (get-in result [:search :pruned-jobs])))))

(deftest constant-failure-and-unusable-results-are-distinct
  (let [fixture (experiment/prepare-fixture tiny :prefix)]
    (doseq [predicate ["false" ":bool4/nothing" ":bool4/contradiction"]]
      (let [compiled (experiment/compile-query (experiment/program predicate [1 2 3]) #{})
            fixture (assoc fixture :compiled compiled :state (lain/prepare compiled) :expected [])]
        (if (= "false" predicate)
          (let [result (experiment/sample fixture :learning-checkpoint nil)]
            (is (= [] (:answers result)))
            (is (= {0 1} (:nogood-widths result)))
            (is (= 27 (get-in result [:search :covered-assignments])))
            (is (zero? (get-in result [:search :propagated-leaves]))))
          (is (thrown? clojure.lang.ExceptionInfo (experiment/sample fixture :learning-checkpoint nil))))))))

(deftest learning-minimizes-only-compatible-contexts
  (let [a (ids/new-node-id) b (ids/new-node-id)
        small {:literals #{[a 0]} :context #{}}
        large {:literals #{[a 0] [b 1]} :context #{}}]
    (is (= #{small} (proofs/add-nogood #{large} small)))
    (is (= #{small} (proofs/add-nogood #{small} large)))
    (is (= #{small} (proofs/add-nogood #{small} small)))))

(deftest learning-policy-is-callable-as-a-literal-lain-binding
  (let [domain [1 2 3]
        reach (knowledge/closure-oracle domain (:edges tiny))
        source (str/replace (experiment/program (experiment/body :suffix) domain)
                            "(solve solver" "(solve learning-checkpoint-solver")
        compiled (experiment/compile-query source reach)
        settled (lain/prepare compiled)
        result (lain/run-state compiled settled)]
    (is (= :completed (:status result)))
    (is (= (experiment/answers :suffix domain (:edges tiny)) (:answers result)))))
