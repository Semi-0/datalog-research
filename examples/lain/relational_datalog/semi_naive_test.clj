(ns examples.lain.relational-datalog.semi-naive-test
  (:require [clojure.set :as sets]
            [clojure.test :as test :refer [deftest is]]
            [examples.lain.relational-datalog.benchmark :as benchmark]
            [examples.lain.relational-datalog.experiment :as experiment]
            [examples.lain.relational-datalog.runtime :as relation]
            [examples.lain.relational-datalog.semi-naive :as semi]
            [examples.lain.relational-datalog.transitive :as transitive]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]))

(def initial
  (delay (experiment/initialize transitive/source {:bindings (semi/bindings :semi-naive)})))

(deftest measurement-distinguishes-results-from-progress
  (let [counts (atom {:pairs 0 :callback-declarations 0})
        result (relation/relation #{[[1 2] [2 3]] [[1 2] [2 4]]})]
    (benchmark/record-evaluation!
     counts {:name :examples.lain.relational-datalog.semi-naive/product}
     {:activation-result [(message/message (ids/new-node-id) result)
                          (message/message (ids/new-node-id) (relation/relation #{[1 2]}))]})
    (is (= {:pairs 2 :callback-declarations 0} @counts))
    (benchmark/record-evaluation! counts {:name :unrelated} {:activation-result []})
    (is (= {:pairs 2 :callback-declarations 0} @counts))))

(deftest delta-pair-laws
  (let [subsets [#{} #{:a} #{:b} #{:a :b}]
        product (fn [left right] (set (for [a left b right] [a b])))]
    (doseq [old-left subsets old-right subsets left subsets right subsets
            :when (and (sets/subset? old-left left) (sets/subset? old-right right))]
      (let [delta (semi/delta-pairs old-left old-right left right)]
        (is (= (sets/difference (product left right) (product old-left old-right)) delta))
        (is (empty? (sets/intersection delta (product old-left old-right))))))
    (is (thrown? clojure.lang.ExceptionInfo (semi/delta-pairs #{:a} #{} #{} #{})))
    (is (thrown? clojure.lang.ExceptionInfo (semi/delta-pairs #{} #{:a} #{} #{})))))

(deftest simultaneous-input-growth-and-snapshot-fork
  (let [left (ids/new-node-id) right (ids/new-node-id) output (ids/new-node-id)
        [installed tasks _] ((operator/operator-install (semi/product-operator :semi-naive))
                              net/empty-net [left right] output)
        original {:network (:network (experiment/completed (relation/run installed tasks)))
                  :cell output}
        seeded (experiment/inject original [[left (relation/relation #{[1 2]})]
                                             [right (relation/relation #{[2 3]})]])
        updated (experiment/inject seeded [[left (relation/relation #{[3 4]})]
                                           [right (relation/relation #{[4 5]})]])
        forked (experiment/inject seeded [[right (relation/relation #{[9 10]})]])]
    (is (= #{[[1 2] [2 3]]} (relation/facts (net/network-cell-strongest (:network seeded) output))))
    (is (= #{[[1 2] [2 3]] [[1 2] [4 5]] [[3 4] [2 3]] [[3 4] [4 5]]}
           (relation/facts (net/network-cell-strongest (:network updated) output))))
    (is (= #{[[1 2] [2 3]] [[1 2] [9 10]]}
           (relation/facts (net/network-cell-strongest (:network forked) output))))
    (is (= #{[2 3]} (relation/facts (net/network-cell-strongest (:network seeded) right))))
    (is (= (experiment/topology seeded) (experiment/topology updated)))
    (is (= (experiment/topology seeded) (experiment/topology forked)))))

(deftest positive-clause-parity
  (transitive/verify @initial #{})
  (doseq [edges [#{[:a :b] [:b :c] [:c :d]}
                 #{[:a :b] [:a :c] [:b :d] [:c :d]}
                 #{[:a :b] [:b :c] [:c :a]}
                 #{[:a :b] [:x :y]}]]
    (transitive/verify (experiment/add-edges @initial edges) edges)))

(deftest each-candidate-is-generated-and-installed-once
  (doseq [edges [#{[:a :b] [:b :c] [:c :d]} #{[:a :b] [:b :c] [:c :a]}]]
    (let [counts (atom {:pairs 0 :callback-declarations 0})
          observed (assoc @initial :run-options {:evaluate-propagator (benchmark/measured-evaluator counts)})
          state (experiment/add-edges observed edges)
          before @counts
          publishers (filter #(= :examples.lain.relational-datalog.operators/publish (:name (val %)))
                             (net/net-env (:network state)))
          duplicate (experiment/add-edges state edges)]
      (is (= (* (count edges) (count (transitive/closure edges))) (:pairs before)))
      (is (= (count publishers) (:callback-declarations before)))
      (is (= before @counts))
      (is (zero? (:steps duplicate))))))

(deftest all-arrival-orders
  (doseq [edges [[[:a :b] [:b :c] [:c :d]] [[:a :b] [:c :d] [:b :c]]
                 [[:b :c] [:a :b] [:c :d]] [[:b :c] [:c :d] [:a :b]]
                 [[:c :d] [:a :b] [:b :c]] [[:c :d] [:b :c] [:a :b]]]]
    (transitive/verify (reduce #(experiment/add-edges %1 [%2]) @initial edges) edges)))

(deftest duplicate-and-operator-reactivation
  (let [edges #{[:a :b] [:b :c] [:c :d]}
        state (experiment/add-edges @initial edges)
        duplicate (experiment/add-edges state edges)
        rerun (experiment/completed (relation/run (:network state) []))
        operator-ids (keep (fn [[id entity]]
                             (if (or (= :examples.lain.relational-datalog.semi-naive/product (:name entity))
                                     (contains? #{[:examples.lain.relational-datalog.semi-naive/collection :map]
                                                  [:examples.lain.relational-datalog.semi-naive/collection :filter]}
                                                (:name entity)))
                               id
                               nil))
                           (net/net-env (:network state)))
        reactivated (experiment/completed (relation/run (:network state) operator-ids))]
    (is (seq operator-ids))
    (is (= (:network state) (:network duplicate)))
    (is (= (:network state) (:network rerun)))
    (is (= (:network state) (:network reactivated)))
    (is (zero? (:steps duplicate)))
    (is (zero? (:steps rerun)))))

(deftest lexical-capture-late-callback-and-pending-predicate
  (let [state (experiment/initialize
               "(let [offset 10]
                  (let-cell [callback gate input output db]
                    (-> (relation (tuple 1 2)) input)
                    (-> (empty-relation) output)
                    (when gate
                      (-> (network [row] (tuple (+ (at row 0) offset) (at row 1))) callback))
                    (-> (map callback input) output)
                    (p:slot :gate gate db)
                    (p:slot :result output db)
                    db))"
               {:bindings (semi/bindings :semi-naive)})
        after (experiment/inject state [[(experiment/slot state :gate) true]])]
    (is (= #{} (transitive/tuples state :result)))
    (is (= #{[11 2]} (transitive/tuples after :result))))
  (let [state (experiment/initialize
               "(filter (network [row] :bool4/nothing) (relation (tuple 1 2)))"
               {:bindings (semi/bindings :semi-naive)})]
    (is (= #{} (relation/facts (net/network-cell-strongest (:network state) (:cell state)))))))

(deftest malformed-inputs-fail
  (is (thrown? clojure.lang.ExceptionInfo (semi/bindings :unknown)))
  (is (thrown? clojure.lang.ExceptionInfo (semi/collection-operator :unknown)))
  (is (thrown? clojure.lang.ExceptionInfo (semi/product-messages :semi-naive net/empty-net
                                                                             {:inputs [] :outputs []})))
  (doseq [program ["(map 7 (relation (tuple 1 2)))"
                   "(map (network [row] 7) (relation (tuple 1 2)))"
                   "(filter (network [row] 7) (relation (tuple 1 2)))"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (experiment/initialize program {:bindings (semi/bindings :semi-naive)})))))

(deftest pending-callback-remains-live-without-reinstallation
  (let [state (experiment/initialize
               "(let-cell [gate result db]
                  (-> (filter (network [row] gate) (relation (tuple 1 2))) result)
                  (p:slot :gate gate db)
                  (p:slot :result result db)
                  db)"
               {:bindings (semi/bindings :semi-naive)})
        ready (experiment/inject state [[(experiment/slot state :gate) true]])]
    (is (= #{} (transitive/tuples state :result)))
    (is (= #{[1 2]} (transitive/tuples ready :result)))
    ;; Canonical applications register return ports when the result arrives.
    ;; That is expected growth, not another callback or publisher installation.
    (is (sets/subset? (experiment/topology state) (experiment/topology ready)))
    (let [publishers (fn [snapshot]
                       (into #{} (keep (fn [[id entity]]
                                         (when (= :examples.lain.relational-datalog.operators/publish
                                                  (:name entity)) id)))
                             (net/net-env (:network snapshot))))]
      (is (= (publishers state) (publishers ready))))))

(defn -main [& args]
  (if (empty? args)
    (let [result (test/run-tests 'examples.lain.relational-datalog.semi-naive-test)]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
    (throw (ex-info "This entry point only runs tests" {:args args}))))
