(ns propagators.explain.fib-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [propagators.explain.facts :as facts]
            [propagators.explain.fib :as fib]))

(defn- frame-fact
  [evidence frame]
  (first (filter #(and (= :recursive-frame (:fact/type %))
                       (= frame (:frame %)))
                 evidence)))

(defn- final-result-fact
  [evidence]
  (first (filter #(= :final-result (:fact/type %)) evidence)))

(deftest accumulating-fib-extracts-evidence-backed-frame-facts
  (testing "fib(5) produces retained recursive frame facts"
    (let [{:keys [value frame-net net]} (fib/run-fib 5)
          evidence (facts/fib-evidence {:n 5
                                        :value value
                                        :frame-net frame-net
                                        :topology-net net})
          fib5 (frame-fact evidence [:fib 5])
          fib1 (frame-fact evidence [:fib 1])
          fib0 (frame-fact evidence [:fib 0])]
      (is (= 5 value))
      (is (= [:fib 5] (:frame fib5)))
      (is (some #{:expanded} (:statuses fib5)))
      (is (= [[:fib 4] [:fib 3]] (:children fib5)))
      (is (= :+ (:combine fib5)))
      (is (= 5 (:output fib5)))
      (is (= :done (:status fib1)))
      (is (= 1 (:output fib1)))
      (is (= :done (:status fib0)))
      (is (= 0 (:output fib0))))))

(deftest fib-evidence-includes-child-edges-and-final-result
  (testing "selected evidence has child links and final value"
    (let [{:keys [value frame-net net]} (fib/run-fib 5)
          evidence (facts/fib-evidence {:n 5
                                        :value value
                                        :frame-net frame-net
                                        :topology-net net})
          edge (first (filter #(and (= :recursive-child (:fact/type %))
                                    (= [:fib 5] (:from %))
                                    (= [:fib 4] (:to %)))
                              evidence))
          result (final-result-fact evidence)]
      (is (= [:fib 5] (:from edge)))
      (is (= [:fib 4] (:to edge)))
      (is (= :fib (:op result)))
      (is (= 5 (:input result)))
      (is (= 5 (:value result))))))

(deftest prompt-builder-includes-question-and-fact-ids
  (testing "prompt contract includes generic derived views, question, and concrete fact ids"
    (let [result (fib/explain-fib {:n 5
                                   :question "Why did fib expand?"
                                   :call-llm? false})
          prompt (:prompt result)]
      (is (str/includes? prompt "Use the derived views as already-parsed evidence."))
      (is (str/includes? prompt "Use raw facts only to audit citations."))
      (is (str/includes? prompt "Every concrete conclusion must cite fact ids."))
      (is (str/includes? prompt "Why did fib expand?"))
      (is (str/includes? prompt ":view/kind :frame-table"))
      (is (str/includes? prompt ":view/kind :expansion-chains"))
      (is (str/includes? prompt ":frame/fib-5"))
      (is (str/includes? prompt ":result/fib-5"))
      (is (nil? (:explanation result))))))

(deftest derived-views-generalize-recursive-evidence
  (testing "generic views summarize frames, roots, leaves, edges, and chains"
    (let [{:keys [value frame-net net]} (fib/run-fib 5)
          evidence (facts/fib-evidence {:n 5
                                        :value value
                                        :frame-net frame-net
                                        :topology-net net})
          views (facts/derived-views evidence)
          by-kind (into {} (map (juxt :view/kind identity) views))
          frame-table (get by-kind :frame-table)
          chains (get by-kind :expansion-chains)
          leaves (get by-kind :leaf-nodes)
          bottom-up (get by-kind :bottom-up-values)]
      (is (= [{:id :result/fib-5 :op :fib :input 5 :value 5}]
             (:rows (get by-kind :root-results))))
      (is (= :frame/fib-5 (-> frame-table :rows first :id)))
      (is (= [:frame/fib-4 :frame/fib-3]
             (-> frame-table :rows first :children)))
      (is (= #{:frame/fib-0 :frame/fib-1}
             (set (map :id (:rows leaves)))))
      (is (some #{[:frame/fib-5 :frame/fib-4 :frame/fib-3 :frame/fib-2 :frame/fib-1]}
                (:chains chains)))
      (is (= [:frame/fib-4 :frame/fib-3]
             (->> (:rows bottom-up)
                  (filter #(= :frame/fib-5 (:id %)))
                  first
                  :children))))))
