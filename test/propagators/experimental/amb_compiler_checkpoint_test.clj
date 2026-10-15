(ns propagators.experimental.amb-compiler-checkpoint-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.checkpoint-search :as search]
            [examples.lain.amb.compiler-checkpoint :as compiled]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]
            [propagators.network-cache :as cache]))

(deftest matched-search-paths-including-exhaustion
  (doseq [[bound answer] [[4 nil] [5 [3 4 5]]]]
    (let [builder (search/run (question/build bound) :checkpoint)]
      (doseq [shape [:direct :application :arithmetic]]
        (testing (str bound " " shape)
          (let [built (compiled/build bound shape)
                original (:network built)
                result (search/run built :checkpoint)
                repeated (search/run built :checkpoint)
                path [:decisions :leaves :restores :max-depth]]
            (is (= (:status builder) (:status result)))
            (is (= answer (:solution result) (:solution repeated)))
            (is (= (select-keys (:metrics builder) path)
                   (select-keys (:metrics result) path)
                   (select-keys (:metrics repeated) path)))
            (is (identical? original (:network built)))
            (is (every? #(contains? (net/net-env original) %) (:sources built)))
            (is (= (:source built) (get compiled/sources shape)))
            (if (some? answer)
              (is (= answer (observation/cell-base (get-in result [:state :network])
                                                    (get-in built [:cells :output]))))
              (is (= :exhausted (:status result))))))))))

(deftest compiled-input-updates-survive-checkpoint-restoration
  (doseq [shape [:direct :application :arithmetic]]
    (let [built (compiled/build 5 shape)
          root (binding [cache/*cache* nil]
                 (search/settle (select-keys built [:network :tasks])))
          branch (fn [values]
                   (reduce (fn [state [source v]] (search/tell state source v :active))
                           root (map vector (:sources built) values)))
          failed (branch [1 1 1])
          accepted (branch [3 4 5])
          certificate #(observation/cell-base (:network %) (get-in built [:cells :failure]))]
      (is (= :failed (:status (certificate failed))))
      (is (= :satisfied (:status (certificate accepted))))
      (is (= [3 4 5] (observation/cell-base (:network accepted) (get-in built [:cells :output]))))
      (is (every? #(value/nothing? (observation/cell-base (:network root) %)) (:sources built)))
      (is (= :failed (:status (certificate failed)))))))

(deftest invalid-and-unresolved-questions-are-not-reported-as-exhausted
  (is (thrown? clojure.lang.ExceptionInfo (compiled/build 0 :direct)))
  (is (thrown? clojure.lang.ExceptionInfo (compiled/build 5 :unknown)))
  (doseq [shape [:direct :application]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unresolved"
                         (search/run (compiled/build 1 shape (fn [_ _ _] value/nothing))
                                     :checkpoint)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (search/run (compiled/build 1 shape
                                             (fn [_ _ _] (throw (ex-info "test failure" {}))))
                             :checkpoint)))))
