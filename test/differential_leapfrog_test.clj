(ns differential-leapfrog-test
  (:require [clojure.test :refer [deftest is testing]]
            [differential-leapfrog :as d]
            [differential-leapfrog.trie :as trie]
            [leapfrog :as lf]))

(defn- empty-system [rules idb-preds]
  {:edb {}
   :idb {}
   :time 0
   :history []
   :rules rules
   :idb-preds idb-preds})

(deftest weighted-trie-updates-and-support
  (let [t (-> (trie/empty-trie)
              (trie/add-tuple [1 :a] 2)
              (trie/add-tuple [1 :a] -1)
              (trie/add-tuple [2 :b] -3)
              (trie/add-tuple [3 :c] 1)
              (trie/add-tuple [3 :c] -1))]
    (is (= 1 (trie/weight-at t [1 :a])))
    (is (= -3 (trie/weight-at t [2 :b])))
    (is (= 0 (trie/weight-at t [3 :c])))
    (is (= #{[1 :a]} (set (trie/support-tuples t))))
    (is (= #{[1 :a] [2 :b]} (set (trie/nonzero-tuples t))))))

(deftest weighted-trie-adapts-to-leapfrog-relation
  (let [rel (trie/to-relation :r [:x :y]
                              (trie/from-weighted-rel {[1 2] 1
                                                        [2 3] -1
                                                        [:k 4] 2}))
        joined (lf/lftj [rel] [:x :y] :tuples)]
    (is (= #{[1 2] [:k 4]} (set joined)))))

(deftest weighted-lftj-multiplies-source-weights
  (let [body [[:a [:x :y]] [:b [:y :z]]]
        joined (d/weighted-lftj [{:a {[1 2] 2}}
                                  {:b {[2 3] -3}}]
                                 body)]
    (is (= [[{:x 1 :y 2 :z 3} -6]] joined))
    (is (= {:c {[1 3] -6}}
           (d/project-weighted {:head [:c [:x :z]] :body body} joined)))))

(deftest initial-paths-match-semi-naive-leapfrog
  (let [edges #{[1 2] [2 3] [3 4]}
        expected (:path (lf/semi-naive {:edge edges} lf/path-rules))
        system (d/transact (empty-system d/path-rules #{:path})
                           {:edge (zipmap edges (repeat 1))})]
    (is (= expected (d/support (d/view system) :path)))))

(deftest inserting-edge-emits-only-new-path-deltas
  (let [s1 (d/transact (empty-system d/path-rules #{:path})
                       {:edge {[1 2] 1 [2 3] 1 [3 4] 1}})
        s2 (d/transact s1 {:edge {[4 5] 1}})]
    (is (= {[4 5] 1 [3 5] 1 [2 5] 1 [1 5] 1}
           (get-in (last (:history s2)) [:delta :path])))))

(deftest retracting-edge-removes-unsupported-recursive-paths
  (let [s1 (d/transact (empty-system d/path-rules #{:path})
                       {:edge {[1 2] 1 [2 3] 1 [3 4] 1}})
        s2 (d/transact s1 {:edge {[2 3] -1}})]
    (is (= {[2 3] -1 [1 3] -1 [2 4] -1 [1 4] -1}
           (get-in (last (:history s2)) [:delta :path])))
    (is (= #{[1 2] [3 4]}
           (d/support (d/view s2) :path)))))

(deftest multiple-derivations-keep-visible-support
  (let [s1 (d/transact (empty-system d/path-rules #{:path})
                       {:edge {[1 2] 1 [1 3] 1 [2 4] 1 [3 4] 1}})
        s2 (d/transact s1 {:edge {[1 2] -1}})]
    (is (= 2 (get-in s1 [:idb :path [1 4]])))
    (is (= 1 (get-in s2 [:idb :path [1 4]])))
    (is (contains? (d/support (d/view s2) :path) [1 4]))))

(deftest same-transaction-body-changes-are-counted-once
  (let [rules [{:head [:ac [:x :z]]
                :body [[:ab [:x :y]]
                       [:bc [:y :z]]]}]
        system (d/transact (empty-system rules #{:ac})
                           {:ab {[1 2] 1}
                            :bc {[2 3] 1}})]
    (is (= {[1 3] 1} (get-in system [:idb :ac])))))

(deftest negative-delta-flows-through-positive-rule
  (let [rules [{:head [:ac [:x :z]]
                :body [[:ab [:x :y]]
                       [:bc [:y :z]]]}]
        s1 (d/transact (empty-system rules #{:ac})
                       {:ab {[1 2] 1}
                        :bc {[2 3] 1}})
        s2 (d/transact s1 {:bc {[2 3] -1}})]
    (is (= {[1 3] -1} (get-in (last (:history s2)) [:delta :ac])))
    (is (empty? (d/support (d/view s2) :ac)))))

(deftest negation-and-count-recompute-from-snapshot
  (let [base (empty-system d/small-rules #{:missing-edge-target :out-degree})
        s1 (d/transact base {:node {[1] 1 [2] 1}
                             :edge {[1 2] 1}})
        s2 (d/transact s1 {:edge {[2 1] 1}})
        s3 (d/transact s2 {:edge {[1 2] -1}})]
    (testing "initial snapshot"
      (is (= #{[2]} (d/support (d/view s1) :missing-edge-target)))
      (is (= #{[1 1]} (d/support (d/view s1) :out-degree))))
    (testing "insert fills the missing target and adds a count"
      (is (empty? (d/support (d/view s2) :missing-edge-target)))
      (is (= #{[1 1] [2 1]} (d/support (d/view s2) :out-degree)))
      (is (= {[2] -1} (get-in (last (:history s2)) [:delta :missing-edge-target]))))
    (testing "delete recomputes both views"
      (is (= #{[1]} (d/support (d/view s3) :missing-edge-target)))
      (is (= #{[2 1]} (d/support (d/view s3) :out-degree)))
      (is (= {[1 1] -1} (get-in (last (:history s3)) [:delta :out-degree]))))))

(deftest illegal-negation-or-aggregation-recursion-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"cycle through negation or aggregation"
       (d/transact (empty-system [{:head [:p [:x]]
                                   :body [{:kind :not :atom [:p [:x]]}]}]
                                 #{:p})
                   {:seed {[1] 1}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"cycle through negation or aggregation"
       (d/transact (empty-system [{:head [:p [:x :n]]
                                   :body [{:atom [:p [:x :m]]}]
                                   :aggregate {:op :count :group-var :x}}]
                                 #{:p})
                   {:seed {[1] 1}}))))

(defn -main [& _]
  (let [{:keys [fail error pass]} (clojure.test/run-tests 'differential-leapfrog-test)]
    (println "differential-leapfrog-test:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error)) (System/exit 1))))
